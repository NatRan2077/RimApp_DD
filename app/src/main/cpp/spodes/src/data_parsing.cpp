/*
 * data_parsing.cpp
 *
 *  Created on: 22 июл. 2025 г.
 *      Author: habarova
 */

#include "data_parsing.h"

int16_t DataParsing::GetInt16(uint8_t* value)
{
	int16_t result = 0;
	for (int i = 0; i < 2; i++)
		result = (result << 8) | value[i];
	return result;
}

uint16_t DataParsing::GetUint16(uint8_t* value)
{
	uint16_t result = 0;
	for (int i = 0; i < 2; i++)
		result = (result << 8) | value[i];
	return result;
}

int32_t DataParsing::GetInt32(uint8_t* value)
{
	int32_t result = 0;
	for (int i = 0; i < 4; i++)
		result = (result << 8) | value[i];
	return result;
}

uint32_t DataParsing::GetUint32(uint8_t* value)
{
	uint32_t result = 0;
	for (int i = 0; i < 4; i++)
		result = (result << 8) | value[i];
	return result;
}

int64_t DataParsing::GetInt64(uint8_t* value)
{
	int64_t result = 0;
	for (int i = 0; i < 8; i++)
		result = (result << 8) | value[i];
	return result;
}

uint64_t DataParsing::GetUint64(uint8_t* value)
{
	uint64_t result = 0;
	for (int i = 0; i < 8; i++)
		result = (result << 8) | value[i];
	return result;
}

float DataParsing::GetFloat(uint8_t* value)
{
	float result = 0;
	uint8_t bytes[4] = {};
	for (int i = 0; i < 4; i++)
		bytes[i] = value[i];

	memcpy(&result, bytes, 4);
	return result;
}

double DataParsing::GetDouble(uint8_t* value)
{
	double result = 0;
	uint8_t bytes[8] = {};
	for (int i = 0; i < 8; i++)
		bytes[i] = value[i];

	memcpy(&result, bytes, 8);
	return result;
}

Structures::DateTime DataParsing::GetDateTime(uint8_t* value, const uint8_t &length)
{
	Structures::DateTime date_time;
	uint8_t start_index = 0;
	if (length != 4)
	{
		date_time.date = true;
		date_time.year = (static_cast<uint16_t>(value[start_index]) << 8) | value[start_index + 1];
		date_time.month = value[start_index + 2];
		date_time.month_day = value[start_index + 3];
		date_time.week_day = value[start_index + 4];
		start_index += 5;
	}
	if (length != 5)
	{
		date_time.time = true;
		date_time.hour = value[start_index];
		date_time.minute = value[start_index + 1];
		date_time.second = value[start_index + 2];
		date_time.hundredths = value[start_index + 3];
		start_index += 4;
	}
	if (length == 12)
	{
		date_time.deviation = (static_cast<uint16_t>(value[start_index]) << 8) | value[start_index + 1];
		date_time.clock_status = value[start_index + 2];
	}
	return date_time;
}


