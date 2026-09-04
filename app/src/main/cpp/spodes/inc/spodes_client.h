/*
 * spodes_client.h
 *
 *  Created on: 10 апр. 2025 г.
 *      Author: habarova
 */

#ifndef INC_SPODES_CLIENT_H_
#define INC_SPODES_CLIENT_H_

#include "spodes_handler.h"
#include "get_processing.h"
#include "set_action_processing.h"

// [Android-патч] вместо serial_port::SerialPort (Windows/Linux COM-порт, недоступен
// на Android) используется собственный транспорт-интерфейс, реализованный поверх BLE.
// См. rim_transport/i_transport.h, rim_transport/ble_transport.h,
// SpodesClient_rasshifrovka.txt (раздел 6).
#include "i_transport.h"

class SpodesClient : public Structures
{
public:
	/**
	 * @brief Настройки для создания подключения по последовательному порту
	 */
	struct SerialPortParams
	{
		std::string port_name = "";
		int baudrate = 0;
		char parity = 'N';
		uint8_t stop_bits = 0;
		bool hardware_flow_control = false;
		unsigned long timeout_s = 0;
		unsigned long timeout_ms = 1000;
	};

	/**
	 * @brief Дополнительные настройки для запроса SNRM
	 */
	struct ConnectionParams
	{
		uint16_t max_info_length_transmit = 128;
		uint16_t max_info_length_receive = 128;
		uint8_t window_size_transmit = 7;
		uint8_t window_size_receive = 7;
	};

	struct RequestParams : ServiceFunctions::RequestParams {};
	struct SecurityParams: ServiceFunctions::SecurityParams {};
	struct OptionalParams: ServiceFunctions::OptionalParams {};
	struct ConnectionAddresses : ServiceFunctions::ConnectionAddresses {};

	/**
	 * @brief Вывод последней полученной ошибки
	 * @return - сообщение, в котором содержится причина некорректной работы последнего используемого метода
	 */
	const char* GetErrorMessage();

	/**
	 * @brief Вывод номера текущей обрабатываемой записи из журнала (буфера)
	 * @return - номер записи
	 */
	uint8_t GetCurrentElemNumber();

	/**
	 * @brief Вывод последних сообщений (запросов/ответов) в виде наборов байт. Первые два байта - длина одного сообщения, далее само сообщение
	 * @param[out] total_length - количество сообщений, размещенных в результирующем векторе байт
	 * @return - вектор, содержащий все последние сообщения
	 */
	std::vector<uint8_t> GetLastMessage(int &total_length);

	/**
	 * @brief Создание подключения по последовательному порту
	 * @param params - структура, содержащая пользовательские настройки подключения
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ConnectSerialPort(const SerialPortParams &params);

	/**
	 * [Android-патч] Подключение через BLE-транспорт (rim_transport::BleTransport)
	 * вместо последовательного порта. Байты между этим транспортом и реальным
	 * прибором доставляет JNI-мост (spodes_jni_bridge.cpp) через характеристики
	 * FFE9 (запись) / FFE4 (notify) сервиса FFE0 — см. core/ble/GattUuids.kt.
	 * @return - true в случае успешного открытия транспорта
	 */
	bool ConnectBleTransport();

	/** [Android-патч] Передать байты, полученные JNI-мостом по BLE-notify (FFE4). */
	void FeedIncomingBytes(const uint8_t* data, unsigned long num_bytes);

	/** [Android-патч] Забрать байты, которые нужно отправить в BLE-характеристику FFE9. */
	std::vector<uint8_t> PullOutgoingBytes();

	/**
	 * @brief Закрытие подключения по последовательному порту
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool Disconnect();

	/**
	 * @brief Запрос на установление режима нормального ответа 
	 * @param addr - логический и физический адреса сервера, идентификатор клиента
	 * @param params - дополнительные настройки (макс. размер информационного поля и макс. количество окон)
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetNormalResponseMode(const ConnectionAddresses &addr, const ConnectionParams* params);

	/**
	 * @brief Запрос на установление подключения по СПОДЭС (AARQ)
	 * @param security_level - тип соединения 
	 * @param params - параметры безопасности. При использовании шифрования/аутентификации установить is_ciph = true.
					   При нижайшем уровне передавать nullptr.
					   При низком заполнять password.
					   При высоком заполнять key.
	 * @param client_max_receive_pdu_size - максимальный размер получаемого пакета данных
	 * @param optional_params - дополнительные параметры для шифрования/аутентификации. Если не используется, то передавать nullptr
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool EstablishConnectionRequest(const uint8_t& security_level, const SecurityParams* params,
									const uint16_t &client_max_receive_pdu_size, const OptionalParams *optional_params);

	/**
	 * @brief Ответ от сервера при установлении подключения по СПОДЭС (AARE)
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool EstablishConnectionResponse();

	/**
	 * @brief Запрос на закрытие подключения по СПОДЭС (DISC)
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SendDisconnectRequest();

	/**
	 * @brief Ответ на кадр без счетчиков (UA)
	 * @return 0 - кадр был принят (UA)
	 *		   1 - возникла ошибка (см. последнее сообщение об ошибке)
	 *		   2 - устройство отключено, соединения нет (DM)
	 */
	uint8_t ReceiveUnnumberedAcknowledge();

	//bool SendUnnumberedInformation();

	/**
	 * @brief Запрос на чтение значений нескольких атрибутов. Запрашивать значения с одинаковым типом данных
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса) 
	 * @param param_count - количество параметров
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool MultipleGetRequest(const RequestParams* params, const uint8_t &param_count);

	/**
	 * @brief Запрос на чтение значения атрибута объекта (сервис GET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param selective_access - параметры фильтра. Если фильтр не используется, передавать nullptr
	 * @param selector - номер фильтра. Если фильтр не используется, передавать 0
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetRequest(const RequestParams &params, void* selective_access, const uint8_t &selector);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] value - значение атрибута
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseByte(uint8_t &value);
	bool GetResponseInt(uint64_t &value);
	bool GetResponseFloat(double &value);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] result - значение атрибута (строка)
	 * @param[out] str_size - размер строки
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseString(char* result, uint8_t &str_size);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] date_time - значение атрибута
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseDateTime(DateTime &date_time);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] structure - значение атрибута
	 * @param[out] struct_id - номер структуры
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseStructure(void* structure, const uint8_t &struct_id);

	/**
	 * @brief Ответ на запрос данных (атрибут представляет собой массив или запрашивалось несколько атрибутов)
	 * @param[out] result - массив значений
	 * @param[out] array_size - размер массива
	 * @param value_type - тип данных
	 * @param is_list - флаг запроса нескольких атрибутов
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseArrayByte(uint8_t* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list);
	bool GetResponseArrayInt(uint64_t* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list);
	bool GetResponseArrayFloat(double* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] result - массив значений
	 * @param[out] array_size - размер массива
	 * @param[out] element_size - размер одного элемента (строки)
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseArrayString(char** result, uint8_t &array_size, uint8_t &element_size);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] result - массив значений
	 * @param[out] array_size - размер массива
	 * @param[out] element_size - размер элемента
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseArrayBitString(uint8_t** result, uint8_t &array_size, uint8_t &element_size);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] date_time - массив значений
	 * @param[out] array_size - размер массива
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseArrayDateTime(DateTime* date_time, uint8_t &array_size);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] structure_array - массив значений (структур)
	 * @param[out] struct_id - номер массива структур
	 * @param[out] array_size - размер массива
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseArrayStructure(void** structure_array, const uint8_t &struct_id, uint8_t &array_size);

	/**
	 * @brief Ответ на запрос значения атрибута объекта (сервис GET)
	 * @param[out] values - массив значений (не преобразованных)
	 * @param[out] types - массив типов значений
	 * @param[out] lengths - массив длин значений
	 * @param[out] array_size - количество записей
	 * @param[out] elem_count - размер записи
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool GetResponseBuffer(uint8_t (*values)[300], uint8_t (*types)[300], uint8_t (*lengths)[300], int &array_size, uint8_t &elem_count);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value - устанавливаемое значение
	 * @param value_type - тип данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestByte(const RequestParams &params, const uint8_t &value, const uint8_t &value_type);
	bool SetRequestInt(const RequestParams &params, const uint64_t &value, const uint8_t &value_type);
	bool SetRequestFloat(const RequestParams &params, const double &value, const uint8_t &value_type);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value - устанавливаемое значение
	 * @param value_size - размер строки
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestString(const RequestParams &params, const char* value, const uint8_t &value_size);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param date_time - устанавливаемое значение
	 * @param date_format_available - флаг использования 25-27 типов данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestDateTime(const RequestParams &params, const DateTime &date_time, const bool &date_format_available);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param structure - устанавливаемые значения (структура)
	 * @param struct_id - номер структуры
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestStructure(const RequestParams &params, void* structure, const uint8_t &struct_id);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value_array - массив значений
	 * @param array_size - размер массива
	 * @param value_type - тип данных (если значение объекта не массив, а строка)
	 * @param element_type - тип данных у элемента массива 
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestArrayByte(const RequestParams &params, const uint8_t* value_array,
							const uint8_t &array_size, const uint8_t &value_type, const uint8_t &element_type);
	bool SetRequestArrayInt(const RequestParams &params, const uint64_t* value_array,
							const uint8_t &array_size, const uint8_t &element_type);
	bool SetRequestArrayFloat(const RequestParams &params, const double* value_array,
								const uint8_t &array_size, const uint8_t &element_type);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value_array - массив значений (строк)
	 * @param array_size - размер массива
	 * @param element_size - размер строки
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestArrayString(const RequestParams &params, const char** value_array, const uint8_t &array_size, const uint8_t &element_size);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value_array - массив значений (бит-строк)
	 * @param array_size - размер массива
	 * @param element_size - размер элемента массива (бит-строки)
	 * @param elem_type - тип данных элемента
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestArrayBitString(const RequestParams &params, const uint8_t** value_array, const uint8_t &array_size,
								const uint8_t &element_size, const uint8_t &elem_type);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param date_time - массив значений (структуры дата-время)
	 * @param array_size - размер масива
	 * @param date_format_available - флаг использования 25-27 типов данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestArrayDateTime(const RequestParams &params, const DateTime* date_time, const uint8_t &array_size, const bool &date_format_available);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис SET)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param structure_array - массив значений (структур)
	 * @param struct_id - номер массива структур
	 * @param array_size - размер массива (кол-во структур)
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SetRequestArrayStructure(const RequestParams &params, void** structure_array, const uint8_t &struct_id, uint8_t &array_size);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value - устанавливаемое значение
	 * @param value_type - тип данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ActionRequestByte(const RequestParams &params, const uint8_t &value, const uint8_t &value_type);
	bool ActionRequestInt(const RequestParams &params, const uint64_t &value, const uint8_t &value_type);
	bool ActionRequestFloat(const RequestParams &params, const double &value, const uint8_t &value_type);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value - устанавливаемое значение
	 * @param value_size - размер строки
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ActionRequestString(const RequestParams &params, const char* value, const uint8_t &value_size);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param date_time - устанавливаемое значение
	 * @param date_format_available - флаг использования 25-27 типов данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ActionRequestDateTime(const RequestParams &params, const DateTime &date_time, const bool &date_format_available);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param structure - устанавливаемые значения (структура)
	 * @param struct_id - номер структуры 
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ActionRequestStructure(const RequestParams &params, void* structure, const uint8_t &struct_id);

	/**
	 * @brief Запрос на запись значения объекта атрибута (сервис ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param structure_array - массив значений (структур)
	 * @param struct_id - номер массива структур
	 * @param array_size - размер массива (кол-во структур) 
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ActionRequestArrayStructure(const RequestParams &params, void** structure_array, uint8_t &struct_id, uint8_t &array_size);

	/**
	 * @brief Подтверждение выполнения запроса на запись
	 * @param is_action - флаг сервиса ACTION
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ReceiveConfirmationResponse(const bool &is_action);

private:
	ConnectionAddresses connection_addr_;
	ConnectionParams connection_params_;
	SecurityParams security_params_;
	OptionalParams optional_params_;

	// объект подключения
	// [Android-патч] было: std::unique_ptr<serial_port::SerialPort> serial_port_;
	std::unique_ptr<rim_transport::ITransport> transport_;

	// последнее сообщение об ошибке
	std::string error_message_;

	// кол-во принятых кадров
	uint8_t receive_sequence_number_ = 0;

	// кол-во отправленных кадров
	uint8_t send_sequence_number_ = 0;
	
	// параметры, используемые при шифровании/аутентификации
	uint8_t sec_control_byte_ = 30;
	uint8_t respond_ap_title_[8] = {};
	std::vector<uint8_t> init_vec_ = {};

	// максимальная длина одного кадра
	unsigned long max_length_ = 0;

	// номер обрабатываемого элемента (для буфера)
	uint8_t element_number_ = 0;

	// последние принятые/отправленные кадры
	std::vector<std::vector<uint8_t>> last_message_ = {};

	/**
	 * @brief Отправление запрос
	 * @param request - содержимое запроса
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SendRequest(std::vector<uint8_t> &request);

	/**
	 * @brief Чтение ответа
	 * @param[out] answer - содержимое ответа
	 * @return - количество принятых байт
	 */
	unsigned long ReadResponse(std::vector<char> &answer);

	/**
	 * @brief Отправление запроса на продолжение чтения данных (для длинных ответов)
	 * @param block_number - номер последнего принятого блока данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool SendReadyToReceive(const int8_t &block_number);

	/**
	 * @brief Получение запроса на продолжение записи данных (для длинных запросов)
	 * @param block_number - номер последнего отправленного блока данных
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ReceiveBlockConfirmation(const uint8_t &block_number);

	/**
	 * @brief Добавление HDLC заголовка запроса
	 * @param request - содержимое запроса
	 */
	void AddHDLCHeader(std::vector<uint8_t> &request);

	/**
	 * @brief Вспомогательный метод для чтения и обработки ответов (сервис GET)
	 * @param answer - полученные данные
	 * @param response - преобразованные к удобному виду данные
	 * @param result_size - размер полученного ответа
	 * @param is_list - флаг использования режима "список" (был запрос на чтение значений нескольких атрибутов)
	 */
	void GetResponseGeneral(std::vector<char> &answer, std::vector<uint8_t> &response, unsigned long &result_size, const bool &is_list);

	/**
	 * @brief Вспомогательный метод для формирования запросов на запись (сервисы SET и ACTION)
	 * @param llc_params - параметры LLC уровня
	 * @param req_params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param info_field - информационная часть сообщения
	 * @param is_action - флаг использования сервиса ACTION
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool WriteRequestGeneral(const ServiceFunctions::LLCParams &llc_params, const RequestParams &req_params,
							const std::vector<uint8_t> &info_field, const bool &is_action);

	/**
	 * @brief Чтение блоков данных (при длинном ответе)
	 * @param response - содержимое ответа
	 * @param result_size - размер ответа
	 */
	void ReadBlocks(std::vector<uint8_t> &response, unsigned long &result_size);

	/**
	 * @brief Чтение кадров одного пакета данных
	 * @param response - содержимое ответа
	 * @param result_size - размер ответа
	 */
	void ReadFrame(std::vector<uint8_t> &response, unsigned long &result_size);

	/**
	 * @brief Запись блоков (при длинном запросе)
	 * @param llc_params - параметры LLC уровня
	 * @param req_params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param info_field - информационная часть сообщения
	 * @param is_action - флаг использования сервиса ACTION
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool WriteBlocks(ServiceFunctions::LLCParams &llc_params, const RequestParams &req_params, const std::vector<uint8_t> &info_field, const bool &is_action);

	/**
	 * @brief Шифрование (если используется), формирование запроса на запись и отправление сообщения
	 * @param request - содержимое запроса
	 * @param is_action - флаг использования сервиса ACTION 
	 * @param is_last - флаг последнего блока
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool CipherAndSendFrame(std::vector<uint8_t> &request, const bool &is_action, const bool &is_last);

	/**
	 * @brief Вспомогательные методы для записи значения атрибута объекта (сервисы GET и ACTION)
	 * @param params - параметры запроса (номер класса, obis-код, номер атрибута, флаг повторного запроса)
	 * @param value - устанавливаемые значения
	 * @param value_type - тип данных
	 * @param is_action - флаг использования сервиса ACTION
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool WriteRequestByte(const RequestParams &params, const uint8_t &value, const uint8_t &value_type, const bool &is_action);
	bool WriteRequestInt(const RequestParams &params, const uint64_t &value, const uint8_t &value_type, const bool &is_action);
	bool WriteRequestFloat(const RequestParams &params, const double &value, const uint8_t &value_type, const bool &is_action);
	bool WriteRequestString(const RequestParams &params, const char* value, const uint8_t &value_size, const bool &is_action);
	bool WriteRequestDateTime(const RequestParams &params, const DateTime &date_time, const bool &date_format, const bool &is_action);
	bool WriteRequestStructure(const RequestParams &params, void* structure, const uint8_t &struct_id, const bool &is_action);
	bool WriteRequestArrayStructure(const RequestParams &params, void** structure_array, const uint8_t &struct_id,
									uint8_t &array_size, const bool &is_action);

	using IoException = std::runtime_error;
};



#endif /* INC_SPODES_CLIENT_H_ */
