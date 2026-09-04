#ifndef RIM_TRANSPORT_BLE_TRANSPORT_H
#define RIM_TRANSPORT_BLE_TRANSPORT_H

#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <vector>

#include "i_transport.h"

namespace rim_transport {

/**
 * Транспорт поверх BLE: SpodesClient пишет байты через WriteData — они
 * складываются в очередь исходящих, которую JNI-мост (spodes_jni_bridge.cpp,
 * функция nativePullOutgoing) забирает и отправляет в характеристику FFE9.
 * Байты, пришедшие по notify от FFE4, JNI кладёт сюда через PushIncoming —
 * их и возвращает ReadData, когда SpodesClient их запрашивает.
 *
 * Чтение блокирующее с таймаутом (по умолчанию 3 с) — этого обычно достаточно
 * для одного BLE-обмена по протоколу СПОДЭС; при необходимости таймаут можно
 * увеличить конструктором.
 */
class BleTransport : public ITransport {
public:
    explicit BleTransport(unsigned long read_timeout_ms = 3000)
        : read_timeout_ms_(read_timeout_ms) {}

    void Open() override { is_open_ = true; }

    void Close() override {
        is_open_ = false;
        cv_.notify_all();
    }

    bool IsOpen() override { return is_open_; }

    unsigned long ReadData(char* data, unsigned long num_bytes) override {
        std::unique_lock<std::mutex> lock(rx_mutex_);
        cv_.wait_for(lock, std::chrono::milliseconds(read_timeout_ms_),
                     [this, num_bytes] { return rx_buffer_.size() >= num_bytes || !is_open_; });

        unsigned long to_copy = std::min<unsigned long>(num_bytes, rx_buffer_.size());
        for (unsigned long i = 0; i < to_copy; ++i) {
            data[i] = static_cast<char>(rx_buffer_.front());
            rx_buffer_.pop_front();
        }
        return to_copy;
    }

    unsigned long WriteData(const char* data, unsigned long num_bytes) override {
        std::lock_guard<std::mutex> lock(tx_mutex_);
        tx_buffer_.insert(tx_buffer_.end(), data, data + num_bytes);
        return num_bytes;
    }

    // ---- вызывается из JNI-моста, а не из SpodesClient ----

    /// Кладёт байты, пришедшие по BLE-notify (FFE4), в буфер на чтение.
    void PushIncoming(const uint8_t* data, unsigned long num_bytes) {
        {
            std::lock_guard<std::mutex> lock(rx_mutex_);
            rx_buffer_.insert(rx_buffer_.end(), data, data + num_bytes);
        }
        cv_.notify_all();
    }

    /// Забирает накопленные исходящие байты (для отправки в FFE9) и очищает буфер.
    std::vector<uint8_t> PullOutgoing() {
        std::lock_guard<std::mutex> lock(tx_mutex_);
        std::vector<uint8_t> result(tx_buffer_.begin(), tx_buffer_.end());
        tx_buffer_.clear();
        return result;
    }

private:
    bool is_open_ = false;
    unsigned long read_timeout_ms_;

    std::mutex rx_mutex_;
    std::condition_variable cv_;
    std::deque<uint8_t> rx_buffer_;

    std::mutex tx_mutex_;
    std::deque<uint8_t> tx_buffer_;
};

}  // namespace rim_transport

#endif  // RIM_TRANSPORT_BLE_TRANSPORT_H
