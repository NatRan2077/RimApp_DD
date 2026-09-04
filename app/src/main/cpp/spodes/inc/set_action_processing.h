/*
 * set_action_processing.h
 *
 *  Created on: 25 июл. 2025 г.
 *      Author: habarova
 */

#ifndef INC_SET_ACTION_PROCESSING_H_
#define INC_SET_ACTION_PROCESSING_H_

#include <iostream>
#include <vector>

#include "structures.h"

/**
 * @brief Вспомогательные методы для формирования запроса (сервисы GET и ACTION)
 */
class WriteProcessing
{
public:
	void ProcessRequestByte(std::vector <uint8_t> &info_field, const uint8_t &value, const uint8_t &value_type);
	void ProcessRequestInt(std::vector <uint8_t> &info_field, const uint64_t &value, const uint8_t &value_type);
	void ProcessRequestFloat(std::vector <uint8_t> &info_field, const double &value, const uint8_t &value_type);
	void ProcessRequestDateTime(std::vector <uint8_t> &info_field, const Structures::DateTime &date_time, const bool &date_format);
	void ProcessRequestArrayByte(std::vector <uint8_t> &info_field, const uint8_t* value_array, const uint8_t &array_size,
								const uint8_t &value_type, const uint8_t &element_type);
	void ProcessRequestArrayInt(std::vector <uint8_t> &info_field, const uint64_t* value_array, const uint8_t &array_size, const uint8_t &element_type);
	void ProcessRequestArrayFloat(std::vector <uint8_t> &info_field, const double* value_array,
								const uint8_t &array_size, const uint8_t &element_type);
	void ProcessRequestArrayString(std::vector <uint8_t> &info_field, const char** value_array,
								const uint8_t &array_size, const uint8_t &element_size);
	void ProcessRequestArrayDateTime(std::vector <uint8_t> &info_field, const Structures::DateTime* date_time,
									const uint8_t &array_size, const bool &date_format_available);
	void ProcessRequestArrayBitString(std::vector <uint8_t> &info_field, const uint8_t** value_array,
									const uint8_t &array_size, const uint8_t &element_size, const uint8_t &elem_type);

};



#endif /* INC_SET_ACTION_PROCESSING_H_ */
