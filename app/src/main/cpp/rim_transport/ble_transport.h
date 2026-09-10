#ifndef RIM_TRANSPORT_BLE_TRANSPORT_H
#define RIM_TRANSPORT_BLE_TRANSPORT_H

#include <algorithm>
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

        // [Android-патч] НАЙДЕНО при разборе долгого подключения/обновления: раньше условие ожидания
        // было "rx_buffer_.size() >= num_bytes" — а вызывающий код (SpodesClient::ReadResponse)
        // всегда передаёт num_bytes = max_length_ (обычно 268 байт, см. ConnectBleTransport()).
        // Каждый РЕАЛЬНЫЙ ответ счётчика — это ОДИН HDLC-кадр "0x7E ... 0x7E", почти всегда КОРОЧЕ
        // 268 байт (голый S-frame RR — 9 байт, один сегмент основного буфера — ~137-270 байт). То
        // есть условие "size >= 268" почти НИКОГДА не выполнялось сразу — байты давно лежали в
        // буфере (за миллисекунды после BLE-нотификации), а ReadData всё равно спал до истечения
        // ПОЛНОГО таймаута (по умолчанию 3 секунды), и только потом отдавал уже накопленное. При
        // многосегментном ответе (7+ сегментов на одно чтение) это давало ~3 с НА КАЖДЫЙ сегмент —
        // самая вероятная причина того, что подключение ощущалось "долгим" (счёт может идти на
        // десятки секунд), а не долей секунды, как в реальном обмене пульт↔счётчик.
        //
        // [Android-патч v2] НАЙДЕН И ИСПРАВЛЕН баг в первой версии этого фикса — он искал конец кадра
        // наивно, как "первый байт 0x7E после начального". Разбор диагностики журналов (запрос
        // "Суточный журнал", 1.0.98.2.0.255) поймал редкий, но реальный случай: байт HCS/FCS кадра
        // (обычная часть контрольной суммы, НЕ экранируется в этом простом варианте HDLC) может
        // СЛУЧАЙНО равняться 0x7E. Прошлая версия принимала такой байт за закрывающий флаг, обрезала
        // кадр на 2 байта раньше времени, а "хвост" (последние 2 настоящих байта кадра) оставался в
        // rx_buffer_ и склеивался с НАЧАЛОМ следующего ответа при следующем PushIncoming() — это
        // сдвигало границы всех дальнейших кадров в этой BLE-сессии. Конкретно в том логе это привело
        // к цепочке: (1) обрезанный кадр "Суточный журнал" отдал в Kotlin-парсер испорченные байты —
        // настоящий data-access-result 0x0B подменился нулевым байтом из "хвоста"-заплатки в
        // GetResponseRaw(), парсер решил, что это успешный ответ со значением Null; (2) "хвост" (2
        // лишних байта) склеился с началом ответа на СЛЕДУЮЩИЙ запрос ("Профиль нагрузки №1"), и байт
        // на месте бита сегментации (см. GetResponseRaw() в spodes_client.cpp: "(first.at(1)>>3)&1")
        // случайно оказался 1 — клиент решил, что ответ сегментирован, и отправил ЛИШНИЙ RR-кадр,
        // которого счётчик не ожидал; (3) это по-настоящему сбило N(S)/N(R) между клиентом и
        // счётчиком до конца сессии — все ПОСЛЕДУЮЩИЕ запросы ("Профиль нагрузки №2/№3") получили либо
        // кадр-отказ (U-frame вместо I-frame), либо тот же самый "голый" RR-повтор (устройство считает
        // кадр с "неправильным" номером повтором уже обработанного и просто переотправляет старый ACK,
        // см. более раннюю историю диагностики).
        //
        // ИСПРАВЛЕНО: вместо поиска 0x7E внутри кадра читаем ДЕКЛАРИРУЕМУЮ ДЛИНУ из самого HDLC-
        // заголовка (2-й и 3-й байты кадра, сразу после начального 0x7E — "(len_hi<<8|len_lo) &
        // 0x07FF", подтверждено сверкой с реальными кадрами: это число равно КОЛИЧЕСТВУ ВСЕХ байт
        // между флагами, включая сами байты длины). Значит полный размер кадра всегда равен
        // declared_length + 2 (начальный и конечный флаг) — и это НАДЁЖНЕЕ поиска 0x7E, потому что не
        // зависит от случайного совпадения байта контрольной суммы. Закрывающий 0x7E всё равно
        // проверяем — но как ПОДТВЕРЖДЕНИЕ на вычисленном месте, а не как единственный критерий: если
        // его там нет (например, декларируемая длина оказалась мусором из рассинхронизированного
        // потока), просто не считаем кадр готовым и ждём/отдаём по старой логике накопленного буфера.
        unsigned long ReadData(char* data, unsigned long num_bytes) override {
            std::unique_lock<std::mutex> lock(rx_mutex_);

            // Возвращает полный размер кадра (включая оба 0x7E), если из уже накопленных байт видно
            // валидный HDLC-заголовок этого протокола (0x7E, затем длина с верхним полубайтом 0xA) —
            // иначе -1 (кадр ещё не начат/не распознан).
            auto declared_frame_len = [this]() -> long {
                if (rx_buffer_.size() < 3 || rx_buffer_.front() != 0x7E) return -1;
                unsigned int len_hi = rx_buffer_[1];
                unsigned int len_lo = rx_buffer_[2];
                if ((len_hi & 0xF0u) != 0xA0u) return -1; // не похоже на заголовок этого протокола
                unsigned int declared = ((len_hi << 8) | len_lo) & 0x07FFu;
                return static_cast<long>(declared) + 2; // + начальный и конечный флаги
            };

            // true, только если в буфере УЖЕ есть достаточно байт на весь кадр по декларируемой длине
            // И на вычисленном месте действительно стоит закрывающий 0x7E (подтверждение, а не догадка).
            auto have_complete_frame = [this, &declared_frame_len]() -> bool {
                long total = declared_frame_len();
                if (total < 0 || rx_buffer_.size() < static_cast<size_t>(total)) return false;
                return rx_buffer_[static_cast<size_t>(total) - 1] == 0x7E;
            };

            cv_.wait_for(lock, std::chrono::milliseconds(read_timeout_ms_),
                         [this, num_bytes, &have_complete_frame] {
                             return rx_buffer_.size() >= num_bytes
                                    || have_complete_frame()
                                    || !is_open_;
                         });

            long total = declared_frame_len();
            unsigned long frame_len = (total > 0
                                       && rx_buffer_.size() >= static_cast<size_t>(total)
                                       && rx_buffer_[static_cast<size_t>(total) - 1] == 0x7E)
                                      ? static_cast<unsigned long>(total)
                                      : 0;
            unsigned long to_copy = frame_len > 0
                                    ? std::min<unsigned long>(num_bytes, frame_len)
                                    : std::min<unsigned long>(num_bytes, rx_buffer_.size());
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
