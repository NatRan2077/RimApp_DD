/*
 * data_parsing.h
 *
 *  Created on: 22 июл. 2025 г.
 *      Author: habarova
 */

#ifndef INC_DATA_PARSING_H_
#define INC_DATA_PARSING_H_

#include <iostream>

#include "structures.h"

/**
 * @brief Класс для преобразования набора байт в значение 
 */
class DataParsing
{
public:
	int16_t GetInt16(uint8_t* value);
	uint16_t GetUint16(uint8_t* value);
	int32_t GetInt32(uint8_t* value);
	uint32_t GetUint32(uint8_t* value);
	int64_t GetInt64(uint8_t* value);
	uint64_t GetUint64(uint8_t* value);
	float GetFloat(uint8_t* value);
	double GetDouble(uint8_t* value);
	Structures::DateTime GetDateTime(uint8_t* value, const uint8_t &length);
};



#endif /* INC_DATA_PARSING_H_ */
