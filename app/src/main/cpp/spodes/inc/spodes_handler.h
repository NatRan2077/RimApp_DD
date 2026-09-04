/*
 * spodes_handler.h
 *
 *  Created on: 22 июл. 2025 г.
 *      Author: habarova
 */

#ifndef INC_SPODES_HANDLER_H_
#define INC_SPODES_HANDLER_H_

#include "service_functions.h"

class SpodesHandler
{
public:
	/**
	 * @brief Вспомогательный метод для формирования AARQ запроса (создание подключения по СПОДЭС)
	 * @param request - содержимое запроса
	 * @param security_level - тип соединения
	 * @param connection_addr - логический и физический адреса сервера, идентификатор клиента
	 * @param params - параметры безопасности. При использовании шифрования/аутентификации установить is_ciph = true.
					   При нижайшем уровне передавать nullptr.
					   При низком заполнять password.
					   При высоком заполнять key.
	 * @param optional_params - дополнительные параметры для шифрования/аутентификации. Если не используется, то передавать nullptr
	 */
	void FormConnectionRequest(std::vector<uint8_t> &request, const uint8_t &security_level,
							const ServiceFunctions::ConnectionAddresses &connection_addr,
							const ServiceFunctions::SecurityParams &params,
							const ServiceFunctions::OptionalParams &optional_params);

	/**
	 * @brief Добавление в запрос AARQ поля "user information"
	 * @param request - содержимое запроса
	 * @param security_level - тип соединения
	 * @param init_vec - инициализирующий вектор
	 * @param sec_control_byte 
	 * @param has_ciphering - флаг шифрования
	 * @param client_max_receive_pdu_size - максимальный размер принимаемого пакета
	 * @param optional_params - дополнительные параметры для шифрования/аутентификации. Если не используется, то передавать nullptr
	 */
	void AddUserInformation(std::vector<uint8_t> &request, const uint8_t &security_level, std::vector<uint8_t> &init_vec,
							uint8_t &sec_control_byte, const bool &has_ciphering, const uint16_t &client_max_receive_pdu_size,
							const ServiceFunctions::OptionalParams &optional_params);

	/**
	 * @brief Обработка поля "user information" в ответе AARE
	 * @param response - содержимое ответа
	 * @param respond_ap_title 
	 * @param ciphered_answer - зашифрованная часть ответа
	 * @param security_params - параметры безопасности. При использовании шифрования/аутентификации установить is_ciph = true.
								При нижайшем уровне передавать nullptr.
								При низком заполнять password.
								При высоком заполнять key.
	 * @return - true в случае успешного выполнения метода, иначе false
	 */
	bool ProcessUserInformation(std::vector<uint8_t> &response, uint8_t* respond_ap_title, uint8_t* ciphered_answer,
								ServiceFunctions::SecurityParams &security_params);

	/**
	 * @brief Вспомогательный метод для обработки ответа, содержащего значение атрибута в виде структуры
	 * @param response - содержимое ответа
	 * @param structure - заполняемая структура
	 * @param struct_id - номер структуры
	 */
	void ReadGetStructure(std::vector<uint8_t> &response, void* structure, const uint8_t &struct_id);

	/**
	 * @brief Вспомогательный метод для обработки ответа, содержащего значение атрибута в виде массива структур
	 * @param response - содержимое ответа
	 * @param structure_array - заполняемый массив структур
	 * @param struct_id - номер структуры
	 * @param array_size - размер массива
	 */
	void ReadGetArrayStructure(std::vector<uint8_t> &response, void** structure_array, const uint8_t &struct_id, uint8_t &array_size);

	/**
	 * @brief Вспомогательные методы для формирования запроса, содержащего значение атрибута в виде структуры
	 * @param info_field - часть запроса, содержащая информационное поле (LLC подуровень)
	 * @param structure - записываемая структура
	 * @param struct_id - номер структуры
	 */
	void AddSetStructure(std::vector<uint8_t> &info_field, void* structure, const uint8_t &struct_id);
	void AddActionStructure(std::vector<uint8_t> &info_field, void* structure, const uint8_t &struct_id);

	/**
	 * @brief Вспомогательные методы для формирования запроса, содержащего значение атрибута в виде массива структур
	 * @param info_field - часть запроса, содержащая информационное поле (LLC подуровень)
	 * @param structure_array - записываемый массив структур
	 * @param struct_id - номер массива структур
	 * @param array_size - размер массива
	 */
	void AddSetArrayStructure(std::vector<uint8_t> &info_field, void** structure_array, const uint8_t &struct_id, uint8_t &array_size);
	void AddActionArrayStructure(std::vector<uint8_t> &info_field, void** structure_array, const uint8_t &struct_id, uint8_t &array_size);

	/**
	 * @brief Вспомогательный метод для расчета размера строки
	 * @param value_type - тип данных
	 * @param element_size - размер элемента
	 * @param size_index - размер строки
	 */
	void SetStringSize(const uint8_t &value_type, uint8_t &element_size, const uint8_t &size_index);
};

#endif /* INC_SPODES_HANDLER_H_ */
