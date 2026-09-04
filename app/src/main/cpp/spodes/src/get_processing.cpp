/*
 * get_processing.cpp
 *
 *  Created on: 25 июл. 2025 г.
 *      Author: habarova
 */

#include "get_processing.h"

void GetProcessing::ProcessResponseByte (const std::vector<uint8_t> &response, uint8_t &value)
{
	if (response.at(16) == 0)
	{
		value = 0;
		return;
	}
	else if (response.at(16) != 3 and response.at(16) != 15 and response.at(16) != 17 and response.at(16) != 22)
		throw std::runtime_error("Unexpected value type");

	value = response.at(17);
}

void GetProcessing::ProcessResponseInt (const std::vector<uint8_t> &response, uint64_t &value, const unsigned long &result_size)
{
	if (response.at(16) == 0)
	{
		value = 0;
		return;
	}
	switch(response.at(16))
	{
	case 5: case 6: case 16: case 18: case 20: case 21:
		break;
	default:
		throw std::runtime_error("Unexpected value type");
	}

	for (int i = 0; i < result_size - 20; i++)
		value = (value << 8) | response.at(17 + i);
}

void GetProcessing::ProcessResponseFloat (const std::vector<uint8_t> &response, double &value, const unsigned long &result_size)
{
	if (response.at(16) == 0)
	{
		value = 0;
		return;
	}
	else if (response.at(16) != 23 and response.at(16) != 24)
		throw std::runtime_error("Unexpected value type");

	uint8_t bytes[8] = {}; // Массив для хранения 8 байт double
	for (int i = 0; i < result_size - 20; i++)
		bytes[i] = response.at(17 + i);

	memcpy(&value, bytes, result_size - 20);
}

void GetProcessing::ProcessResponseArrayByte (const std::vector<uint8_t> &response, uint8_t* result, uint8_t &array_size, uint8_t &value_type)
{
	if (response.at(16) != 1 and response.at(16) != 9 and response.at(16) != 4 and response.at(16) != 0)
		throw std::runtime_error("Unexpected value type");

	array_size = response.at(17);
	if (array_size == 0 or response.at(16) == 0)
	{
		value_type = 0;
		return;
	}

	value_type = (response.at(16) == 1) ? response.at(18) : response.at(16);
	switch(value_type)
	{
	case 3: case 4: case 9: case 15: case 17: case 22:
		break;
	default:
		throw std::runtime_error("Unexpected element type");
	}
	bool type_byte = (response.at(16) == 1) ? true : false;
	int response_index = 18 + type_byte;

	uint8_t length = array_size;
	if (response.at(16) == 4)
		length = array_size / 8 + (array_size % 8 != 0) ? 1 : 0;
	for (int i = 0; i < length; ++i)
	{
		result[i] = response.at(response_index + i);
		if (type_byte)
			response_index++;
	}
}

void GetProcessing::ProcessResponseArrayInt (const std::vector<uint8_t> &response, uint64_t* result, uint8_t &array_size, uint8_t &value_type)
{
	array_size = response.at(17);
	if (array_size == 0 or response.at(16) == 0)
	{
		value_type = 0;
		return;
	}
	else if (response.at(16) != 1)
		throw std::runtime_error("Unexpected value type");

	value_type = response.at(18);
	uint8_t bytes_count = 0;
	switch (value_type)
	{
	case 16: case 18:
		bytes_count = 2;
		break;
	case 5: case 6:
		bytes_count = 4;
		break;
	case 20: case 21:
		bytes_count = 8;
		break;
	default:
		throw std::runtime_error("Unexpected element type");
	}

	int value_index = 19;
	uint64_t value = 0;
	for (int i = 0; i < array_size; ++i)
	{
		for (int j = 0; j < bytes_count; ++j)
		{
			value = (value << 8) | response.at(value_index + j);
		}
		result[i] = value;
		value = 0;
		value_index += bytes_count + 1;
	}
}

void GetProcessing::ProcessResponseArrayFloat (const std::vector<uint8_t> &response, double* result, uint8_t &array_size, uint8_t &value_type)
{
	array_size = response.at(17);
	if (array_size == 0 or response.at(16) == 0)
	{
		value_type = 0;
		return;
	}
	else if (response.at(16) != 1)
		throw std::runtime_error("Unexpected value type");

	value_type = response.at(18);
	int bytes_count = 0;
	switch(value_type)
	{
	case 23:
		bytes_count = 4;
		break;
	case 24:
		bytes_count = 8;
		break;
	default:
		throw std::runtime_error("Unexpected element type");
	}

	int value_index = 19;
	double value = 0;
	for (int i = 0; i < array_size; ++i)
	{
		uint8_t bytes[8] = {};
		for (int j = 0; j < bytes_count; ++j)
		{
			bytes[j] = response.at(value_index + j);
		}
		memcpy(&value, bytes, bytes_count);
		value = 0;
		value_index += bytes_count + 1;
	}
}

void GetProcessing::ProcessResponseBuffer (const std::vector<uint8_t> &response, uint8_t (*values)[300], uint8_t (*types)[300], uint8_t (*lengths)[300],
										int &array_size, uint8_t &elem_count, uint8_t &element_number)
{
	array_size = 0;
	if (response.at(17) == 0 or response.at(16) == 0)
	{
		elem_count = 0;
		return;
	}
	else if (response.at(16) != 1)
		throw std::runtime_error("Unexpected value type");

	int main_array_size = 1;
	int resp_index = 20;
	elem_count = response.at(19);
	if (response.at(18) == 1)
	{
		main_array_size = response.at(17);
		array_size = response.at(19);
		elem_count = response.at(21);
		resp_index = 22;
	}
	else if (response.at(18) != 2)
		throw std::runtime_error("Unexpected element type");

	for (int k = 0; k < main_array_size; ++k)
	{
		array_size += response.at(resp_index - 3);
		for (int i = 0; i < array_size; ++i)
		{
			element_number++;
			int elem_index = 0;
			for (int j = 0; j < elem_count; ++j)
			{
				uint8_t type = response.at(resp_index++);
				types[i][j] = type;
				uint8_t len = 0;

				switch (type)
				{
				case 0:
					len = 0;
					break;
				case 3: case 15: case 17: case 22:
					len = 1;
					break;
				case 4: case 9:
					len = response.at(resp_index++);
					break;
				case 5: case 6: case 23:
					len = 4;
					break;
				case 16: case 18:
					len = 2;
					break;
				case 20: case 21: case 24:
					len = 8;
					break;
				default:
					throw std::runtime_error("Unexpected element type");
				}

				lengths[i][j] = len;

				for (int n = 0; n < len; n++)
					values[i][elem_index + n] = response.at(resp_index + n);

				elem_index += len;
				resp_index += len;
			}
			resp_index += 2;
		}
		resp_index += 2;
	}
}

void GetProcessing::ProcessResponseArrayBitString (const std::vector<uint8_t> &response, uint8_t** result, uint8_t &array_size, uint8_t &element_size)
{
	array_size = response.at(17);
	if (array_size == 0 or response.at(16) == 0)
	{
		element_size = 0;
		return;
	}
	else if (response.at(16) != 1)
		throw std::runtime_error("Unexpected value type");

	if (response.at(18) != 4 and response.at(18) != 9)
		throw std::runtime_error("Unexpected element_type");

	element_size = response.at(19);
	uint8_t length = element_size;
	if (response.at(16) == 4)
		length = element_size / 8 + (element_size % 8 != 0) ? 1 : 0;

	for (int i = 0; i < array_size; ++i)
	{
		for (int j = 0; j < length; ++j)
		{
			uint8_t next_elem = i * (length + 2);
			result[i][j] = response.at(19 + next_elem + j);
		}
	}
}

