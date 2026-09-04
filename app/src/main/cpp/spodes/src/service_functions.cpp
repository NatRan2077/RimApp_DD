/*
 * service_functions.cpp
 *
 *  Created on: 10 апр. 2025 г.
 *      Author: habarova
 */

#include "service_functions.h"

uint16_t ServiceFunctions::CalculateCRC (const std::vector<uint8_t> &data, const bool &hcs)
{
	uint16_t crc = 0xFFFF;
	uint16_t length = data.size();
	if (!hcs)
		length --;
	for (uint16_t i = 1; i < length; i++)
	{
		crc ^= data[i];
		for (int j = 0; j < 8; j++)
		{
			if (crc & 1)
				crc = (crc >> 1) ^ 0x8408; // отражённый полином
			else
				crc >>= 1;
		}
	}
	return ~crc; // XOR с 0xFFFF на выходе
}

uint8_t ServiceFunctions::AppendBit (const uint8_t &address, const bool &bit)
{
	uint8_t value = address;
	value <<= 1;
	value |= (bit ? 1 : 0);
	return value;
}

uint8_t ServiceFunctions::CalculateControlField (const uint8_t &N_S, const uint8_t &N_R, const bool &poll_final)
{
    return ((N_R & 0x07) << 5)         // N(R) в битах 7-5
         | (poll_final << 4) 		   // P/F в бите 4
         | ((N_S & 0x07) << 1)         // N(S) в битах 3-1
         | 0;                          // бит 0 всегда 0
}

bool ServiceFunctions::FormSNRMInfromationField(std::vector<uint8_t> &request, std::string &error_message,
												const uint16_t &max_length_transmit, const uint16_t &max_length_receive,
												const uint8_t &win_size_transmit, const uint8_t &win_size_receive)
{
	if (max_length_transmit < 64 or max_length_transmit > 2030 or
		max_length_receive < 128 or max_length_receive > 2030)
	{
		error_message = "SpodesClient::SetNormalResponceMode: Illegal value for information field length";
		return false;
	}
	if (win_size_transmit < 1 or win_size_transmit > 7 or
		win_size_receive < 1 or win_size_receive > 7)
	{
		error_message = "SpodesClient::SetNormalResponceMode: Illegal value for client window size";
		return false;
	}

	request.insert(request.end(), {0xE6, 0xE6, 00, 0x81, 0x80});
	if (max_length_transmit > 128 or max_length_receive > 128)
	{
		request[2] += 23;
		request.insert(request.end(), {0x14, 0x05, 0x02});
		request.push_back(max_length_transmit >> 8);
		request.push_back(max_length_transmit & 0xFF);
		request.insert(request.end(), {0x06, 0x02});
		request.push_back(max_length_receive >> 8);
		request.push_back(max_length_receive & 0xFF);
	}
	else
	{
		request[2] += 21;
		request.insert(request.end(), {0x12, 0x05, 0x01});
		request.push_back(max_length_transmit & 0xFF);
		request.insert(request.end(), {0x06, 0x01});
		request.push_back(max_length_receive & 0xFF);
	}
	request.insert(request.end(),
	{
		0x07, 0x04, 0, 0, 0, win_size_transmit,
		0x08, 0x04, 0, 0, 0, win_size_receive
	});
	return true;
}

void ServiceFunctions::FormHDLCHeader (std::vector<uint8_t> &request, const HDLCParams &params)
{
	request.insert(request.end(), {0, 0, 0});
	request[0] = 0x7E;

	uint16_t request_size = request.size() - 2;
	uint16_t value = request_size & 0x07FF;
	value |= (static_cast<uint16_t>(params.segmentation_bit & 0x01) << 11);
	value |= 0xA000;
	request[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
	request[2] = static_cast<uint8_t>(value & 0xFF);

	request[3] = params.logical_address;
	request[4] = params.physical_address;
	request[5] = params.source_address;
	request[6] = params.control_field;

	std::vector<uint8_t> slice(request.begin(), request.begin() + 7);
	uint16_t hcs = CalculateCRC(slice, true);
	request[7] = hcs & 0xFF;		  // младший байт
	request[8] = (hcs >> 8) & 0xFF; // старший байт
	uint16_t fcs = CalculateCRC(std::vector<uint8_t>(request.begin(), request.end() - 2), false);
	request[request_size - 1] = fcs & 0xFF;
	request[request_size] = (fcs >> 8) & 0xFF;
	request[request_size + 1] = 0x7E;
}

void ServiceFunctions::FormLLCHeader(std::vector<uint8_t> &request, const LLCParams &llc_params)
{
	request.insert(request.end(), {0xE6, 0xE6, 0, llc_params.service_id, llc_params.service_type});
	uint8_t invoke_id_and_priority = 1;
	if (llc_params.service_class)
		invoke_id_and_priority |= 1 << 6;
	if (llc_params.priority)
		invoke_id_and_priority |= 1 << 7;
	request.push_back(invoke_id_and_priority);
}

bool ServiceFunctions::AddAddress(std::vector<uint8_t> &request, const RequestParams &req_params, std::string &error_message_)
{
	request.push_back((req_params.class_id >> 8) & 0xFF);
	request.push_back(req_params.class_id & 0xFF);
	try
	{
		std::vector<uint8_t> obis_code = ParseString(req_params.instance_id);
		request.insert(request.end(), obis_code.begin(), obis_code.end());
	}
	catch(std::invalid_argument &ia)
	{
		error_message_ = "ServiceFunctions::AddAddress: " + (std::string)ia.what();
		return false;
	}
	request.push_back(req_params.attribute_id);
	return true;
}

std::vector<uint8_t> ServiceFunctions::ParseString(const std::string& instance_id)
{
    std::vector<uint8_t> result;
    const char* start = instance_id.c_str();  // Получаем C-строку

    while (true)
    {
        // Ищем конец текущего токена
        const char* end = start;
        while (*end && *end != '.')
            ++end;

        // Парсим текущий токен
        const size_t value_length = end - start;
        if (value_length == 0 || value_length > 3)
            throw std::invalid_argument("Invalid token length");

        // Парсинг числа
        uint16_t value = 0;
        for (const char* number = start; number != end; ++number)
        {
            if (*number < '0' || *number > '9')
                throw std::invalid_argument("Invalid character");

            value = value * 10 + (*number - '0');
            if (value > 255)
                throw std::out_of_range("Value exceeds 255");
        }

        result.push_back(static_cast<uint8_t>(value));

        // Переход к следующему токену
        if (*end == '\0') break;
        start = end + 1;
    }

    return result;
}

void ServiceFunctions::AddSecurityField (std::vector <uint8_t> &request, const SecurityParams &params, const OptionalParams &optional_params,
										const uint8_t &security_level)
{
	request.insert(request.end(), {0x8A, 0x02, 0x07, 0x80});
	request.insert(request.end(), {0x8B, 0x07, 0x60, 0x85, 0x74, 0x05, 0x08, 0x02});
	uint8_t security_mechanism = (params.has_ciphering) ? 5 : security_level;
	request.push_back(security_mechanism);
	if (security_level == 1 )
	{
		request.push_back(0xAC);
		request.push_back(params.password.size() + 2);
		request.push_back(0x80);
		request.push_back(params.password.size());
		request.insert(request.end(), params.password.begin(), params.password.end());
	}
	else
	{
		request.insert(request.end(), {0xAC, 0x12, 0x80, 0x10});

		std::random_device rd;
		std::mt19937 gen(rd());
		std::uniform_int_distribution<int> dist(0x00, 0xFF); // Диапазон байт
		uint8_t key[16] = {};
		std::memcpy(key, params.key.data(), 16);
		uint8_t random_message[16] = {};
		for (int i = 0; i < 16; i++)
		{
			random_message[i] = static_cast<uint8_t>(dist(gen));
			request.push_back(random_message[i]);
		}
	}
}

void ServiceFunctions::CheckRawResponse (const unsigned long &result_size, const std::vector<char> &answer)
{
	if (result_size == 0)
		throw std::runtime_error("Couldn't read response from server");

	if ((uint8_t)answer[6] == 0x1F)
		throw std::runtime_error("Received DM message. Server is already disconnected");

	if ((uint8_t)answer[6] == 0x97)
	{
		std::string error_msg = "Received frame reject response (FRMR). Error: ";
		if (Errors::kFrameRejectMessage_.find((uint8_t)answer[9]) != Errors::kFrameRejectMessage_.end())
			error_msg += Errors::kFrameRejectMessage_.at((uint8_t)answer[7]);
		else
			error_msg += "Unknown error";
		throw std::runtime_error(error_msg);
	}
}

void ServiceFunctions::CheckGetResponse (const uint8_t &result_byte)
{
	if (result_byte > 0)
	{
		if (Errors::kDataAccessResult_.find(result_byte) != Errors::kDataAccessResult_.end())
			throw std::runtime_error(Errors::kDataAccessResult_.at(result_byte));
		else
			throw std::runtime_error("Unknown error");
	}
}

/*std::string ServiceFunctions::ConvertDateTime (const std::vector<char> &info_field)
{
	std::vector<char> date_field(info_field.begin(), info_field.begin() + 5);
	std::string date = ConvertDate(date_field);
	std::vector<char> time_field(info_field.begin() + 5, info_field.begin() + 9);
	std::string time = ConvertTime(time_field);

	int deviation = static_cast<uint8_t>(info_field[9]) | static_cast<uint8_t>(info_field[10]);
	std::ostringstream date_time;
	if (deviation != 0x8000)
	{
		if (deviation < 0)
			date_time << "UTC -";
		else
			date_time << "UTC +";
		date_time << std::setw(2) << std::setfill('0') << deviation / 60;
		if (deviation % 60 != 0)
			date_time << ":" << std::setw(2) << std::setfill('0') << deviation % 60;
		date_time << " ";
	}
	int clock_status = static_cast<uint8_t>(info_field[11]);
	if (clock_status != 0xFF)
	{
		date_time << "Clock status: " << clock_status;
	}

	return date_time.str();
}

std::string ServiceFunctions::ConvertDate (const std::vector<char> &info_field)
{
	std::ostringstream date;
	int year = static_cast<uint8_t>(info_field[0] << 8) | static_cast<uint8_t>(info_field[1]);
	if (year == 0xFFFF)
		throw "Year is not defined";
	int month = static_cast<uint8_t>(info_field[2]);
	if (month == 0xFF)
		throw "Month is not defined";
	int day = static_cast<uint8_t>(info_field[3]);
	if (day == 0xFF)
		throw "Day of month is not defined";
	int week_day = static_cast<uint8_t>(info_field[4]);
	if (week_day == 0xFF)
		throw "Day of week is not defined";

	const char* week_day_names[] = {"", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
	date << week_day_names[week_day] << ", "
		 << std::setw(2) << std::setfill('0') << day << "."
		 << std::setw(2) << std::setfill('0') << month << "."
		 << std::setw(4) << std::setfill('0') << year << " ";

	return date.str();
}

std::string ServiceFunctions::ConvertTime (const std::vector<char> &info_field)
{
	std::ostringstream time;
	int hour = static_cast<uint8_t>(info_field[0]);;
	if (hour == 0xFF)
		throw "Hour is not defined";
	int minute = static_cast<uint8_t>(info_field[1]);;
	if (minute == 0xFF)
		throw "Minute is not defined";
	int second = static_cast<uint8_t>(info_field[2]);;
	if (second == 0xFF)
		throw "Second is not defined";
	int hundredths = static_cast<uint8_t>(info_field[3]);;
	if (hundredths == 0xFF)
		throw "Hundredths are not defined";
	time << std::setw(2) << std::setfill('0') << hour << ":"
		 << std::setw(2) << std::setfill('0') << minute << ":"
		 << std::setw(2) << std::setfill('0') << second << "."
		 << std::setw(2) << std::setfill('0') << hundredths;

	return time.str();
}*/

void ServiceFunctions::CipherRequest(const std::vector<uint8_t> &data, std::vector<uint8_t> &output, const uint8_t &func_tag, const OptionalParams &optional_params,
									const uint8_t &sec_control_byte, const std::vector<uint8_t> &init_vec)
{
	output.insert(output.begin(), {func_tag, 0, sec_control_byte});
	output.insert(output.end(), optional_params.invoke_counter, optional_params.invoke_counter + 4);

	if (optional_params.ciph)
	{
		std::vector<uint8_t> input = {};
		input.insert(input.begin(), data.begin(), data.end());

		if (optional_params.auth)
		{
			std::vector<uint8_t> associate_data = {sec_control_byte};
			associate_data.insert(associate_data.end(), optional_params.auth_key, optional_params.auth_key + 16);
			uint8_t tag[12] = {};
			plusaes::encrypt_gcm(input.data(), input.size(), associate_data.data(), associate_data.size(), optional_params.ciph_key, 16,
								init_vec.data(), init_vec.size(), tag, 12);
			output.insert(output.end(), input.begin(), input.end());
			output.insert(output.end(), tag, tag + 12);
		}
		else
		{
			plusaes::crypt_ctr(input.data(), input.size(), optional_params.ciph_key, 16, reinterpret_cast<const uint8_t (*)[16]>(init_vec.data()));
			output.insert(output.end(), input.begin(), input.end());
		}
	}
	else
	{
		std::vector<uint8_t> associate_data = {sec_control_byte};
		associate_data.insert(associate_data.end(), optional_params.auth_key, optional_params.auth_key + 16);
		uint8_t tag[12] = {};
		associate_data.insert(associate_data.end(), data.begin(), data.end());
		plusaes::encrypt_gcm(nullptr, 0, associate_data.data(), associate_data.size(), optional_params.ciph_key, 16,
							init_vec.data(), init_vec.size(), tag, 12);
		output.insert(output.end(), data.begin(), data.end());
		output.insert(output.end(), tag, tag + 12);
	}
	output[1] = output.size();
}

void ServiceFunctions::DecipherResponse(std::vector<uint8_t> &answer, const OptionalParams &optional_params, const uint8_t &sec_control_byte,
										const std::vector<uint8_t> &init_vec)
{
	std::vector<uint8_t> data = {};
	if (optional_params.auth)
		data.insert(data.begin(), answer.begin() + 19, answer.end() - 15);
	else
		data.insert(data.begin(), answer.begin() + 19, answer.end() - 3);


	if (optional_params.auth)
	{
		std::vector<uint8_t> aadata = {sec_control_byte};
		aadata.insert(aadata.end(), optional_params.auth_key, optional_params.auth_key + 16);
		std::vector<uint8_t> tag = {};
		tag.insert(tag.begin(), answer.end() - 14, answer.end() - 3);
		if (!optional_params.ciph)
		{
			std::vector<uint8_t> ciphered_tag = {};
			aadata.insert(aadata.end(), data.begin(), data.end());
			plusaes::encrypt_gcm(nullptr, 0, aadata.data(), aadata.size(), optional_params.ciph_key, 16,
								init_vec.data(), init_vec.size(), ciphered_tag.data(), 30);
			for (int i = 0; i < 12; i++)
			{
				if (ciphered_tag[i] != tag[i])
					throw std::runtime_error("Authentication integrity violation");
			}
		}
		else
		{
			plusaes::decrypt_gcm(data.data(), data.size(), aadata.data(), aadata.size(), optional_params.ciph_key, 16,
								init_vec.data(), init_vec.size(), tag.data(), 12);
		}
	}
	else
		plusaes::crypt_ctr(data.data(), data.size(), optional_params.ciph_key, 16, reinterpret_cast<const uint8_t (*)[16]>(init_vec.data()));

	answer.erase(answer.begin() + 12, answer.end() - 3);
	answer.insert(answer.begin() + 12, data.begin(), data.end());
}

bool ServiceFunctions::AddSelectiveAccess (std::vector<uint8_t> &request, const uint16_t &class_id, const uint8_t &selector, void* selective_access)
{
	switch(class_id)
	{
	case 7:
	{
		switch(selector)
		{
		case 1:
			AddFirstProfileSelector(request, selective_access);
			break;
		case 2:
			AddSecondProfileSelector(request, selective_access);
			break;
		default:
			return false;
		}
		break;
	}
	case 15:
	{
		StructParsing sp;
		switch(selector)
		{
		case 1:
			request.insert(request.end(), {1, 0});
			break;
		case 2:
		{
			request.insert(request.end(), {2, 1});
			Structures::ClassList* class_list = static_cast<Structures::ClassList*>(selective_access);
			request.push_back(class_list->list_size);
			for (int i = 0; i < class_list->list_size; i++)
			{
				request.push_back(18);
				sp.SendLongUnsigned(request, class_list->class_id[i]);
			}
			break;
		}
		case 3:
			AddThirdObjectSelector(request, selective_access);
			break;
		case 4:
		{
			request.push_back(4);
			sp.SendObjectDefinition(request, selective_access);
			break;
		}
		default:
			return false;
		}
		break;
	}
	default:
		return false;
	}
	return true;
}

void ServiceFunctions::AddFirstProfileSelector (std::vector<uint8_t> &request, void* selective_access)
{
	request.insert(request.end(), {1, 2, 4});
	Structures::RangeDescriptor* range_desc = static_cast<Structures::RangeDescriptor*>(selective_access);
	StructParsing sp;
	sp.SendCaptureObjectDefinition(request, selective_access);

	request.push_back(range_desc->value_type);
	bool include_length = false;
	switch(range_desc->value_type)
	{
	case 9: case 25: case 26: case 27:
		include_length = true;
		break;
	default:
		break;
	}
	if (include_length)
		request.push_back(range_desc->value_length);
	for (int i = 0; i < range_desc->value_length; i++)
		request.push_back(range_desc->from_value[i]);

	request.push_back(range_desc->value_type);
	if (include_length)
		request.push_back(range_desc->value_length);
	for (int i = 0; i < range_desc->value_length; i++)
		request.push_back(range_desc->to_value[i]);

	request.insert(request.end(), {1, range_desc->array_size});
	if (range_desc->array_size > 0)
	{
		for (int i = 0; i < range_desc->array_size; i++)
		{
			request.insert(request.end(), {2, 4, 18});
			sp.SendLongUnsigned(request, range_desc->selected_values[i].class_id);
			request.insert(request.end(), {9, 6});
			for (int j = 0; j < 6; j++)
				request.push_back((uint8_t)range_desc->selected_values[i].logical_name[j]);
			request.insert(request.end(), {15, (uint8_t)range_desc->selected_values[i].attribute_index});
			request.push_back(18);
			sp.SendLongUnsigned(request, range_desc->selected_values[i].data_index);
		}
	}
}

void ServiceFunctions::AddSecondProfileSelector(std::vector<uint8_t> &request, void* selective_access)
{
	StructParsing sp;
	request.insert(request.end(), {2, 2, 4, 6});
	Structures::EntryDescriptor* entry_desc = static_cast<Structures::EntryDescriptor*>(selective_access);
	sp.SendDoubleLongUnsigned(request, entry_desc->from_entry);
	request.push_back(6);
	sp.SendDoubleLongUnsigned(request, entry_desc->to_entry);
	request.push_back(18);
	sp.SendLongUnsigned(request, entry_desc->from_selected_value);
	request.push_back(18);
	sp.SendLongUnsigned(request, entry_desc->to_selected_value);
}

void ServiceFunctions::AddThirdObjectSelector (std::vector<uint8_t> &request, void* selective_access)
{
	StructParsing sp;
	request.insert(request.end(), {3, 1});
	Structures::ObjectIdList* obj_id_list = static_cast<Structures::ObjectIdList*>(selective_access);
	request.push_back(obj_id_list->list_size);
	for (int i = 0; i < obj_id_list->list_size; i++)
	{
		request.insert(request.end(), {2, 2, 18});
		sp.SendLongUnsigned(request, obj_id_list->object_id[i].class_id);
		request.insert(request.end(), {9, 6});
		for (int j = 0; j < 6; j++)
			request.push_back((uint8_t)obj_id_list->object_id[i].logical_name[j]);
	}
}

void ServiceFunctions::ReadList (std::vector<uint8_t> &response, const uint8_t &value_size)
{
	uint8_t index = 16;
	uint8_t elem_count = response.at(index++);
	for (int i = 0; i < elem_count; i++)
	{
		//TODO проверить как возвращается если ошибка
		CheckGetResponse(response.at(index));

		response.erase(response.begin() + index);
		index += value_size;
	}
	response.insert(response.begin() + 15, 1);
}

void ServiceFunctions::CheckConfirmationResponse (const std::vector<uint8_t> &response, const bool &is_action)
{
	if (is_action)
	{
		//TODO посмотреть пример
		if (response.at(15) != 0)
		{
			if (Errors::kActionResult_.find(response.at(15)) != Errors::kActionResult_.end())
				throw std::runtime_error(Errors::kActionResult_.at(response.at(15)));
			else
				throw std::runtime_error("Unknown error");
		}
		else if (response.at(16) != 0 and response.at(17) != 0)
		{
			if (Errors::kDataAccessResult_.find(response.at(16)) != Errors::kDataAccessResult_.end())
				throw std::runtime_error(Errors::kDataAccessResult_.at(response.at(16)));
			else
				throw std::runtime_error("Unknown error");
		}
	}
	else
	{
		if (response[15] != 0)
		{
			if (Errors::kDataAccessResult_.find(response.at(15)) != Errors::kDataAccessResult_.end())
				throw std::runtime_error(Errors::kDataAccessResult_.at(response.at(15)));
			else
				throw std::runtime_error("Unknown error");
		}
	}
}
