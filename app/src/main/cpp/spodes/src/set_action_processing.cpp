/*
 * set_action_processing.cpp
 *
 *  Created on: 25 июл. 2025 г.
 *      Author: habarova
 */

#include "set_action_processing.h"

void WriteProcessing::ProcessRequestByte (std::vector <uint8_t> &info_field, const uint8_t &value, const uint8_t &value_type)
{
	info_field.push_back(value_type);
	switch (value_type)
	{
	case 3: case 15: case 17: case 22:
		info_field.push_back(value);
		break;
	default:
		throw std::runtime_error("Unexpected value type");
	}
}

void WriteProcessing::ProcessRequestInt (std::vector <uint8_t> &info_field, const uint64_t &value, const uint8_t &value_type)
{
	int bytes_count = 0;
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
		throw std::runtime_error("Unexpected value type");
	}
	info_field.push_back(value_type);
	for (int i = bytes_count - 1; i > 0; i--)
	{
		info_field.push_back(static_cast<uint8_t>((value >> (i * 8)) & 0xFF));
	}
	info_field.push_back(static_cast<uint8_t>(value & 0xFF));
}

void WriteProcessing::ProcessRequestFloat (std::vector <uint8_t> &info_field, const double &value, const uint8_t &value_type)
{
	info_field.push_back(value_type);
	switch (value_type)
	{
	case 23:
	{
		float value_temp = static_cast<float>(value);
		const uint8_t* byte_ptr = reinterpret_cast<const uint8_t*>(&value_temp);
		info_field.insert(info_field.end(), byte_ptr, byte_ptr + sizeof(float));
		break;
	}
	case 24:
	{
		const uint8_t* byte_ptr = reinterpret_cast<const uint8_t*>(&value);
		info_field.insert(info_field.end(), byte_ptr, byte_ptr + sizeof(double));
		break;
	}
	default:
		throw std::runtime_error("Unexpected value type");
	}
}

void WriteProcessing::ProcessRequestDateTime (std::vector <uint8_t> &info_field, const Structures::DateTime &date_time, const bool &date_format)
{
	uint8_t value_type = 0;
	if (date_format)
	{
		if (date_time.date and date_time.time)
			value_type = 25;
		else
			value_type = (date_time.date) ? 26 : 27;
	}
	else
		value_type = 9;
	info_field.push_back(value_type);
	info_field.push_back(0);

	if (date_time.date)
	{
		info_field.push_back((date_time.year >> 8) & 0xFF);
		info_field.push_back(date_time.year & 0xFF);
		info_field.insert(info_field.end(), {date_time.month, date_time.month_day, date_time.week_day});
	}

	if (date_time.time)
		info_field.insert(info_field.end(), {date_time.hour, date_time.minute, date_time.second, date_time.hundredths});

	if (date_time.date and date_time.time)
	{
		info_field.push_back((date_time.deviation >> 8) & 0xFF);
		info_field.push_back(date_time.deviation & 0xFF);
		info_field.push_back(date_time.clock_status);
	}

	if (value_type == 9)
		info_field[1] = info_field.size() - 2;
	else
		info_field.erase(info_field.begin() + 1);
}

void WriteProcessing::ProcessRequestArrayByte (std::vector <uint8_t> &info_field, const uint8_t* value_array, const uint8_t &array_size,
												const uint8_t &value_type, const uint8_t &element_type)
{
	info_field.push_back(value_type);
	info_field.push_back(array_size);
	bool element_active = false;
	switch(value_type)
	{
	case 1:
	{
		element_active = true;
		if (element_type != 3 and element_type != 15 and element_type != 17 and element_type != 22)
			throw std::runtime_error("Unexpected element type");
		break;
	}
	case 9: case 4:
		break;
	default:
		throw std::runtime_error("Unexpected value type");

	}
	uint8_t length = array_size;
	if (value_type == 4)
		length = array_size / 8 + (array_size % 8 != 0) ? 1 : 0;

	for (int i = 0; i < length; ++i)
	{
		if (element_active)
			info_field.push_back(element_type);
		else
			info_field.push_back(value_array[i]);
	}
}

void WriteProcessing::ProcessRequestArrayInt (std::vector <uint8_t> &info_field, const uint64_t* value_array,
												const uint8_t &array_size, const uint8_t &element_type)
{
	int bytes_count = 0;
	switch (element_type)
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
	info_field.push_back(1);
	info_field.push_back(array_size);

	for (int i = 0; i < array_size; ++i)
	{
		info_field.push_back(element_type);
		for (int j = bytes_count - 1; j > 0; --j)
		{
			info_field.push_back(static_cast<uint8_t>((value_array[i] >> (j * 8)) & 0xFF));
		}
		info_field.push_back(static_cast<uint8_t>(value_array[i] & 0xFF));
	}
}

void WriteProcessing::ProcessRequestArrayFloat (std::vector <uint8_t> &info_field, const double* value_array,
												const uint8_t &array_size, const uint8_t &element_type)
{
	if (element_type != 23 and element_type != 24)
		throw std::runtime_error("Unexpected element type");

	info_field.push_back(1);
	info_field.push_back(array_size);

	for (int i = 0; i < array_size; ++i)
	{
		info_field.push_back(element_type);
		if (element_type == 23)
		{
			float value_temp = static_cast<float>(value_array[i]);
			const uint8_t* byte_ptr = reinterpret_cast<const uint8_t*>(&value_temp);
			info_field.insert(info_field.end(), byte_ptr, byte_ptr + sizeof(float));
		}
		else
		{
			const uint8_t* byte_ptr = reinterpret_cast<const uint8_t*>(&value_array[i]);
			info_field.insert(info_field.end(), byte_ptr, byte_ptr + sizeof(double));
		}
	}
}

void WriteProcessing::ProcessRequestArrayString (std::vector <uint8_t> &info_field, const char** value_array,
												const uint8_t &array_size, const uint8_t &element_size)
{
	info_field.push_back(1);
	info_field.push_back(array_size);
	for (int i = 0; i < array_size; ++i)
	{
		info_field.push_back(9);
		info_field.push_back(element_size);
		info_field.insert(info_field.end(), value_array[i], value_array[i] + element_size);
	}
}

void WriteProcessing::ProcessRequestArrayDateTime (std::vector <uint8_t> &info_field, const Structures::DateTime* date_time,
												const uint8_t &array_size, const bool &date_format_available)
{
	uint8_t value_type = 0;
	uint8_t value_length = 0;
	if (date_time[0].date and date_time[0].time)
	{
		value_type = (date_format_available) ? 25 : 9;
		value_length = 12;
	}
	else if (date_time[0].date)
	{
		value_type = (date_format_available) ? 26 : 9;
		value_length = 5;
	}
	else if (date_time[0].time)
	{
		value_type = (date_format_available) ? 27 : 9;
		value_length = 4;
	}
	else
		throw std::runtime_error("Date and time fields aren't used");

	info_field.push_back(1);
	info_field.push_back(array_size);

	for (int i = 0; i < array_size; ++i)
	{
		info_field.push_back(value_type);
		info_field.push_back(value_length);

		if (date_time[0].date)
		{
			info_field.push_back((date_time[i].year >> 8) & 0xFF);
			info_field.push_back(date_time[i].year & 0xFF);
			info_field.insert(info_field.end(), {date_time[i].month, date_time[i].month_day, date_time[i].week_day});
		}

		if (date_time[0].time)
			info_field.insert(info_field.end(), {date_time[i].hour, date_time[i].minute, date_time[i].second, date_time[i].hundredths});

		if (date_time[0].date and date_time[0].time)
		{
			info_field.push_back((date_time[i].deviation >> 8) & 0xFF);
			info_field.push_back(date_time[i].deviation & 0xFF);
			info_field.push_back(date_time[i].clock_status);
		}
	}
}

void WriteProcessing::ProcessRequestArrayBitString (std::vector <uint8_t> &info_field, const uint8_t** value_array,
													const uint8_t &array_size, const uint8_t &element_size, const uint8_t &elem_type)
{
	if (elem_type != 4 and elem_type != 9)
		throw std::runtime_error("Unexpected element type");

	info_field.push_back(1);
	info_field.push_back(array_size);
	uint8_t length = element_size;
	if (elem_type == 4)
		length = element_size / 8 + (element_size % 8 != 0) ? 1 : 0;
	for (int i = 0; i < array_size; ++i)
	{
		info_field.push_back(elem_type);
		info_field.push_back(element_size);
		info_field.insert(info_field.end(), value_array[i], value_array[i] + length);
	}
}

