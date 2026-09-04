/*
 * struct_parsing.h
 *
 *  Created on: 18 июн. 2025 г.
 *      Author: habarova
 */

#ifndef INC_STRUCT_PARSING_H_
#define INC_STRUCT_PARSING_H_
#include <vector>
#include <cstring>

// [Android-патч] Оригинальный #include "serial_port.h" убран — заголовок нигде
// в этом файле не используется (только тянул зависимость на Windows/Linux COM-порт,
// которой нет на Android). См. rim_transport/ble_transport.h — новый транспорт.
#include "structures.h"

class StructParsing
{
private:
	/**
	 * @brief Преобразование 4 байт в число
	 * @param response - содержимое ответа
	 * @param value - значение
	 * @param start_index - начальный индекс числа в ответе
	 */
	void ReceiveDoubleLongUnsigned(const std::vector<uint8_t> &response, uint32_t &value, uint8_t &start_index);

	/**
	 * @brief Добавление структуры "дата-время" в вектор байт (запрос)
	 * @param info_field - содержимое запроса
	 * @param date_time - структура "дата-время"
	 */
	void SendDateTime(std::vector<uint8_t> &info_field, const Structures::DateTime &date_time);
public:
	/**
	 * @brief Преобразование вектора байт в структуру "дата-время"
	 * @param response - содержимое ответа
	 * @param date_time - структура "дата-время"
	 * @param start_index - начальный индекс строки в ответе
	 */
	void ReceiveDateTime(const std::vector<uint8_t> &response, Structures::DateTime &date_time, uint8_t &start_index);

	/**
	 * @brief Добавление числа в вектор байт (запрос)
	 * @param info_field - содержимое запроса
	 * @param value - значение
	 */
	void SendLongUnsigned(std::vector<uint8_t> &info_field, const uint16_t &value);
	void SendDoubleLongUnsigned(std::vector<uint8_t> &info_field, const uint32_t &value);

	/**
	 * @brief Обработка вектора байт (ответа) и заполнение структуры
	 * @param response - содержимое ответа 
	 * @param structure - заполняемая структура
	 */
	void ReceiveScalerUnit(const std::vector<uint8_t> &response, void* structure);
	void ReceiveValueDefinition(const std::vector<uint8_t> &response, void* structure);
	void ReceiveScript(const std::vector<uint8_t> &response, void* structure);
	void ReceiveEmergencyProfile(const std::vector<uint8_t> &response, void* structure);
	void ReceiveCaptureObjectDefinition(const std::vector<uint8_t> &response, void* structure);
	void ReceiveAssociatedPartnersType(const std::vector<uint8_t> &response, void* structure);
	void ReceiveContextNameStructure(const std::vector<uint8_t> &response, void* structure);
	void ReceivexDLMSContextType(const std::vector<uint8_t> &response, void* structure);
	void ReceiveSendDestinationAndMethod(const std::vector<uint8_t> &response, void* structure);
	void ReceiveRepetitionDelay(const std::vector<uint8_t> &response, void* structure);
	void ReceiveConfirmationParameters(const std::vector<uint8_t> &response, void* structure);
	void ReceiveActionSet(const std::vector<uint8_t> &response, void* structure);

	/**
	 * @brief Обработка вектора байт (ответа) и заполнение массива структур
	 * @param response - содержимое ответа
	 * @param structure_array - заполняемый массив структур
	 * @param array_size - размер массива
	 */
	void ReceiveObjectDefinition(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveArrayScript(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveRegisterActMask(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveScheduleTableEntry(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveSpecDayEntry(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveSeason(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveWeekProfile(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveDayProfile(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveArrayActionSet(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveExecutionTimeDate(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveImageToActivateInfoElement(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveActionItem(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveArrayCaptureObjectDefinition(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveObjectList(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceivePushObjectDefinition(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveWindowElement(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveProtectionParametersElement(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);
	void ReceiveCertificateInfo(const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size);

	/**
	 * @brief Формирование части запроса, содержащего значения структуры
	 * @param info_field - содержимое запроса
	 * @param structure - записываемая структура 
	 */
	void SendScalerUnit(std::vector<uint8_t> &info_field, void* structure);
	void SendValueDefinition(std::vector<uint8_t> &info_field, void* structure);
	void SendScript(std::vector<uint8_t> &info_field, void* structure);
	void SendEmergencyProfile(std::vector<uint8_t> &info_field, void* structure);
	void SendCaptureObjectDefinition(std::vector<uint8_t> &info_field, void* structure);
	void SendAssociatedPartnersType(std::vector<uint8_t> &info_field, void* structure);
	void SendContextNameStructure(std::vector<uint8_t> &info_field, void* structure);
	void SendxDLMSContextType(std::vector<uint8_t> &info_field, void* structure);
	void SendDestinationAndMethod(std::vector<uint8_t> &info_field, void* structure);
	void SendRepetitionDelay(std::vector<uint8_t> &info_field, void* structure);
	void SendConfirmationParameters(std::vector<uint8_t> &info_field, void* structure);
	void SendActionSet(std::vector<uint8_t> &info_field, void* structure);

	/**
	 * @brief Формирование части запроса, содержащего значения массива структур
	 * @param info_field - содержимое запроса
	 * @param structure - записываемый массив структур
	 */
	void SendObjectDefinition(std::vector<uint8_t> &info_field, void* structure);
	void SendRegisterActMask(std::vector<uint8_t> &info_field, void* structure);
	void SendArrayScript(std::vector<uint8_t> &info_field, void* structure);
	void SendScheduleTableEntry(std::vector<uint8_t> &info_field, void* structure);
	void SendSpecDayEntry(std::vector<uint8_t> &info_field, void* structure);
	void SendSeason(std::vector<uint8_t> &info_field, void* structure);
	void SendWeekProfile(std::vector<uint8_t> &info_field, void* structure);
	void SendDayProfile(std::vector<uint8_t> &info_field, void* structure);
	void SendExecutionTimeDate(std::vector<uint8_t> &info_field, void* structure);
	void SendImageToActivateInfoElement(std::vector<uint8_t> &info_field, void* structure);
	void SendObjectList(std::vector<uint8_t> &info_field, void* structure);
	void SendPushObjectDefinition(std::vector<uint8_t> &info_field, void* structure);
	void SendWindowElement(std::vector<uint8_t> &info_field, void* structure);
	void SendProtectionParametersElement(std::vector<uint8_t> &info_field, void* structure);
	void SendCertificateInfo(std::vector<uint8_t> &info_field, void* structure);
	void SendAdjustingTime(std::vector<uint8_t> &info_field, void* structure);
	void SendEnableDisable(std::vector<uint8_t> &info_field, void* structure);
	void SendDataDelete(std::vector<uint8_t> &info_field, void* structure);
	void SendImageTransfer(std::vector<uint8_t> &info_field, void* structure);
	void SendImageBlock(std::vector<uint8_t> &info_field, void* structure);
	void SendCertificateIdentification(std::vector<uint8_t> &info_field, void* structure);
	void SendRequestAction(std::vector<uint8_t> &info_field, void* structure);
	void SendKey(std::vector<uint8_t> &info_field, void* structure);
};


#endif /* INC_STRUCT_PARSING_H_ */
