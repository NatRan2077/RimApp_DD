#ifndef RIM_TRANSPORT_I_TRANSPORT_H
#define RIM_TRANSPORT_I_TRANSPORT_H

// Собственная (не вендорская) абстракция транспорта — замена serial_port::SerialPort,
// которая в оригинальной библиотеке SpodesClient реализована только для Windows/Linux
// COM-портов (см. SpodesClient_rasshifrovka.txt, раздел 6). Для Android данные идут
// через BLE, поэтому SpodesClient обращается к транспорту через этот интерфейс, а
// не напрямую к serial_port::SerialPort.
//
// ВНИМАНИЕ: этот файл и BleTransport (см. ble_transport.h) — НАШ код, не часть
// оригинальной библиотеки SpodesClient. Изменения в самой библиотеке (spodes_client.h/.cpp,
// struct_parsing.h) сведены к минимуму и явно помечены комментарием "// [Android-патч]".

namespace rim_transport {

class ITransport {
public:
    virtual ~ITransport() = default;

    virtual void Open() = 0;
    virtual void Close() = 0;
    virtual bool IsOpen() = 0;

    /// Блокирующее чтение (с внутренним таймаутом реализации) — семантика совместима
    /// с исходным serial_port::Interface::ReadData, которым пользовался spodes_client.cpp.
    virtual unsigned long ReadData(char* data, unsigned long num_bytes) = 0;
    virtual unsigned long WriteData(const char* data, unsigned long num_bytes) = 0;
};

}  // namespace rim_transport

#endif  // RIM_TRANSPORT_I_TRANSPORT_H
