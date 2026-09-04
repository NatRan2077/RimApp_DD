/*
 * service_functions.h
 *
 *  Created on: 10 апр. 2025 г.
 *      Author: habarova
 */

#ifndef INC_SERVICE_FUNCTIONS_H_
#define INC_SERVICE_FUNCTIONS_H_

#include <iomanip>
#include <iostream>
#include <vector>
#include <thread>
#include <random>

#include "struct_parsing.h"
#include "plusaes.hpp"
#include "errors.h"

class ServiceFunctions
{
private:
public:

	/**
	 * @brief Параметры HDLC кадра
	 */
	struct HDLCParams
	{
		uint8_t segmentation_bit = 0; 
		uint8_t source_address = 0;	  
		uint8_t logical_address = 0;   
		uint8_t physical_address = 0;  
		uint8_t control_field = 0;	   
	};

	/**
	 * @brief Параметры LLC подуровня HDLC кадра
	 */
	struct LLCParams
	{
		uint8_t service_id = 0;
		uint8_t service_type = 0;
		bool service_class = 0;
		bool priority = 0;
	};

	/**
	 * @brief Параметры запроса
	 */
	struct RequestParams
	{
		uint16_t class_id = 0;		  // номер класса
		std::string instance_id = ""; // obis-код
		uint8_t attribute_id = 0;	  // номер атрибута
		bool retry = false;			  // флаг повторного запроса
	};

	/**
	 * @brief Параметры безопасности
	 */
	struct SecurityParams
	{
		std::string password = "";
		std::string key = "";
		bool has_ciphering = false;
	};

	/**
	 * @brief Параметры шифрования/аутентификации/шифрования + аутентификации
	 */
	struct OptionalParams
	{
		uint8_t calling_ap_title[8] = {};
		uint32_t calling_ae_invocation_id = 0;
		bool auth = false;
		bool ciph = false;
		uint8_t auth_key[16] = {};
		uint8_t ciph_key[16] = {};
		uint8_t sys_title[8] = {};
		uint8_t invoke_counter[4] = {};
	};

	/**
	 * @brief Адреса соединения
	 */
	struct ConnectionAddresses
	{
		uint8_t source_address = 0;
		uint8_t logical_address = 0;
		uint8_t physical_address = 0;
	};

	/**
	 * @brief Расчет контрольной суммы
	 * @param data - содержимое запроса
	 * @param hcs - флаг HCS/FCS
	 * @return контрольная сумма
	 */
	uint16_t CalculateCRC (const std::vector<uint8_t> &data, const bool &hcs);

	/**
	 * @brief Формирование части кадра до LLC подуровня
	 * @param request - содержимое запроса
	 * @param params - параметры HDLC кадра
	 */
	void FormHDLCHeader (std::vector<uint8_t> &request, const HDLCParams &params);

	/**
	 * @brief Добавление бита в конец
	 * @param address - адрес
	 * @param bit - добавляемый бит
	 * @return преобразованный адрес
	 */
	uint8_t AppendBit (const uint8_t &address, const bool &bit);

	/**
	 * @brief Вычисление контрольного поля
	 * @param N_S - кол-во отправленных пакетов
	 * @param N_R - кол-во принятых пакетов
	 * @param poll_final - бит окончания приема/передачи
	 * @return значение контрольного поля
	 */
	uint8_t CalculateControlField (const uint8_t &N_S, const uint8_t &N_R, const bool &poll_final);

	/**
	 * @brief Добавление в запрос SNRM дополнительных настроек (опционально)
	 * @param request - содержимое запроса
	 * @param error_message - сообщение об ошибке
	 * @param max_length_transmit - максимальный размер кадра (передаваемый)
	 * @param max_length_receive - максимальный размер кадра (принимаемый)
	 * @param win_size_transmit - кол-во отправляемых окон
	 * @param win_size_receive - кол-во принимаемых окон
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool FormSNRMInfromationField(std::vector<uint8_t> &request, std::string &error_message,
									const uint16_t &max_length_transmit, const uint16_t &max_length_receive,
									const uint8_t &win_size_transmit, const uint8_t &win_size_receive);

	/**
	 * @brief Формирование LLC заголовка
	 * @param request - содержимое запроса
	 * @param llc_params - параметры LLC подуровня
	 */
	void FormLLCHeader(std::vector<uint8_t> &request, const LLCParams &llc_params);

	/**
	 * @brief Добавления адреса атрибута в запрос (класс, код, атрибут)
	 * @param request - содержимое запроса
	 * @param req_params - адрес атрибута
	 * @param error_message_ - сообщение об ошибке
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool AddAddress(std::vector<uint8_t> &request, const RequestParams &req_params, std::string &error_message_);

	/**
	 * @brief Преобразование строки в вектор байт
	 * @param instance_id - obis-код
	 * @return - вектор байт
	 */
	std::vector<uint8_t> ParseString(const std::string& instance_id);

	/**
	 * @brief Проверка ответа на ошибки
	 * @param result_size - размер ответа
	 * @param answer - содержимое ответа
	 */
	void CheckRawResponse(const unsigned long &result_size, const std::vector<char> &answer);

	/**
	 * @brief Проверка успешной передачи значения атрибута в ответе
	 * @param result_byte - байт, в котором содержится результат запроса
	 */
	void CheckGetResponse(const uint8_t &result_byte);

	/**
	 * @brief Добавление поля с информацией о типе соединения (уровне безопасности) в запрос AARQ
	 * @param request - содержимое запроса
	 * @param params - параметры безопасности
	 * @param optional_params - параметры шифрования/аутентификации 
	 * @param security_level - тип соединения
	 */
	void AddSecurityField(std::vector <uint8_t> &request, const SecurityParams &params, const OptionalParams &optional_params,
							const uint8_t &security_level);


	//std::string ConvertDateTime(const std::vector<char> &info_field);
	//std::string ConvertDate(const std::vector<char> &info_field);
	//std::string ConvertTime(const std::vector<char> &info_field);

	/**
	 * @brief Шифрование запроса
	 * @param data - содержимое запроса
	 * @param output - зашифрованная часть запроса
	 * @param func_tag - тег сервиса
	 * @param optional_params - параметры шифрования/аутентификации
	 * @param sec_control_byte - байт контроля безопасности
	 * @param init_vec - инициализирующий вектор
	 */
	void CipherRequest(const std::vector<uint8_t> &data, std::vector<uint8_t> &output, const uint8_t &func_tag, const OptionalParams &optional_params,
						const uint8_t &sec_control_byte, const std::vector<uint8_t> &init_vec);

	/**
	 * @brief Дешифрование ответа
	 * @param answer - содержимое ответа
	 * @param optional_params - параметры шифрования/аутентификации
	 * @param sec_control_byte - байт контроля безопасности
	 * @param init_vec - инициализирующий вектор
	 */
	void DecipherResponse(std::vector<uint8_t> &answer, const OptionalParams &optional_params, const uint8_t &sec_control_byte,
						const std::vector<uint8_t> &init_vec);

	/**
	 * @brief Добавление в запрос пользовательского фильтра
	 * @param request - содержимое запроса
	 * @param class_id - номер класса
	 * @param selector - номер фильтра
	 * @param selective_access - содержимое фильтра
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool AddSelectiveAccess(std::vector<uint8_t> &request, const uint16_t &class_id, const uint8_t &selector, void* selective_access);

	/**
	 * @brief Преобразование структры (фильтра) в вектор байт и добавление в запрос
	 * @param request - содержимое запроса
	 * @param selective_access - фильтр
	 */
	void AddFirstProfileSelector(std::vector<uint8_t> &request, void* selective_access);
	void AddSecondProfileSelector(std::vector<uint8_t> &request, void* selective_access);
	void AddThirdObjectSelector(std::vector<uint8_t> &request, void* selective_access);

	/**
	 * @brief Чтение списка значений атрибутов
	 * @param response - содержимое ответа
	 * @param value_size - размер значения атрибута
	 */
	void ReadList(std::vector<uint8_t> &response, const uint8_t &value_size);

	/**
	 * @brief Проверка на ошибки ответа-подтверждения записи
	 * @param response - содержимое ответа
	 * @param is_action - фильтр сервиса ACTION
	 */
	void CheckConfirmationResponse(const std::vector<uint8_t> &response, const bool &is_action);
};



#endif /* INC_SERVICE_FUNCTIONS_H_ */
