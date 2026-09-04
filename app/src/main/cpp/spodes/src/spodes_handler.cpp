/*
 * spodes_handler.cpp
 *
 *  Created on: 22 июл. 2025 г.
 *      Author: habarova
 */

#include "spodes_handler.h"

void SpodesHandler::FormConnectionRequest (std::vector<uint8_t> &request, const uint8_t &security_level,
										const ServiceFunctions::ConnectionAddresses &connection_addr,
										const ServiceFunctions::SecurityParams &params,
										const ServiceFunctions::OptionalParams &optional_params)
{
	if (security_level > 2)
		throw std::runtime_error("Illegal security level value");

	if ((connection_addr.source_address == 0x61 and security_level < 2) or
		(connection_addr.source_address == 0x41 and security_level == 0))
		throw std::runtime_error("Security level doesn't match with connection type");

	if (security_level == 1 and params.password == "")
		throw std::runtime_error("The password is not specified");

	if (security_level == 2 and params.key.size() != 16)
		throw std::runtime_error("Illegal key length");

	request.insert(request.end(), {0xE6, 0xE6, 0, 0x60, 0});
	request.insert(request.end(), {0xA1, 0x09, 0x06, 0x07, 0x60, 0x85, 0x74, 0x05, 0x08, 0x01});
	if (params.has_ciphering)
	{
		request.push_back(3);
		request.insert(request.end(), {0xA6, 0x0A, 0x04, 0x08});
		request.insert(request.end(), optional_params.calling_ap_title, optional_params.calling_ap_title + 8);
	}
	else
		request.push_back(1);

	ServiceFunctions serv_funcs;
	switch (security_level)
	{
	case 1: case 2:
	{
		serv_funcs.AddSecurityField(request, params, optional_params, security_level);
		break;
	}
	default:
		break;
	}
}

void SpodesHandler::AddUserInformation (std::vector<uint8_t> &request, const uint8_t &security_level, std::vector<uint8_t> &init_vec,
										uint8_t &sec_control_byte, const bool &has_ciphering, const uint16_t &client_max_receive_pdu_size,
										const ServiceFunctions::OptionalParams &optional_params)
{
	request.push_back(0xBE);
	std::vector<uint8_t> user_info = {0x01, 0, 0, 0, 0x06, 0x5F, 0x1F, 0x04, 0, 0x62, 0x1E};
	if (security_level == 0)
		user_info.push_back(0x1F);
	else if (security_level == 1)
		user_info.push_back(0xDD);
	else
		user_info.push_back(0xDD);
	user_info.push_back(client_max_receive_pdu_size >> 8);
	user_info.push_back(client_max_receive_pdu_size & 0xFF);

	std::vector<uint8_t> ciphered_apdu = {};
	if (has_ciphering)
	{
		if (optional_params.auth and !optional_params.ciph)
			sec_control_byte = 10;
		else if (!optional_params.auth and optional_params.ciph)
			sec_control_byte = 20;
		init_vec.insert(init_vec.begin(), optional_params.sys_title, optional_params.sys_title + 8);
		init_vec.insert(init_vec.end(), optional_params.invoke_counter, optional_params.invoke_counter + 4);
		if (!optional_params.auth and optional_params.ciph)
			init_vec.insert(init_vec.end(), {0, 0, 0, 2});

		ServiceFunctions serv_funcs;
		serv_funcs.CipherRequest(user_info, ciphered_apdu, 21, optional_params, sec_control_byte, init_vec);
		request.insert(request.end(), {(uint8_t)(ciphered_apdu.size() + 4), 4, (uint8_t)(ciphered_apdu.size() + 2)});
		request.insert(request.end(), ciphered_apdu.begin(), ciphered_apdu.end());
	}
	else
	{
		request.insert(request.end(), {(uint8_t)(user_info.size() + 2), 4, (uint8_t)user_info.size()});
		request.insert(request.end(), user_info.begin(), user_info.end());
	}
}

bool SpodesHandler::ProcessUserInformation (std::vector<uint8_t> &response, uint8_t* respond_ap_title, uint8_t* ciphered_answer,
											ServiceFunctions::SecurityParams &security_params)
{
	uint8_t info_size = response.at(13);
	uint8_t tag_length = 0;
	bool is_hls = false;
	std::string error_msg = "";

	for (int i = 14; i < info_size + 10; i++)
	{
		tag_length = response.at(i + 1);
		switch(response.at(i))
		{
		case 0xA2:
		{
			if (response.at(i + tag_length + 1) == 1)
				error_msg = "Rejected permanent. ";
			else if (response.at(i + tag_length + 1) == 2)
				error_msg = "Rejected transient. ";
			break;
		}
		case 0xA3:
		{
			if (response.at(i + tag_length + 1) > 1 and response.at(i + tag_length + 1) < 14)
			{
				error_msg += Errors::kResultSourceDiagnostic_.at(response.at(i + tag_length + 1));
				throw std::runtime_error(error_msg);
			}
			break;
		}
		case 0xA4:
		{
			for (int j = 0; j < 8; j++)
				respond_ap_title[j] = response.at(i + j + 4);
			break;
		}
		case 0xAA:
		{
			is_hls = true;
			uint8_t server_challenge[16] = {};
			std::copy(response.begin() + i + 4, response.begin() + i + 20, server_challenge);
			uint8_t key[16] = {};
			std::memcpy(key, security_params.key.data(), 16);
			plusaes::encrypt_ecb(server_challenge, 16, key, 16, ciphered_answer, 16, false);
			break;
		}
		/*case 0xBE:
		{
			if (security_params_.has_ciphering)
			{
			//	plusaes::decrypt_gcm(data, data_size, aadata, aadata_size, key, key_size, iv, iv_size, tag, tag_size)
			}
			if (tag_length < 16 and response[i + 4] == 14)
			{
				error_message_ = "Error: " + Errors::kConfirmedServiceErrors_.at(response[i + 5]);
				if (Errors::kServiceErrors_.find(response[i + 6]) != Errors::kServiceErrors_.end())
					error_message_ += ", " + Errors::kServiceErrors_.at(response[i + 6])->at(response[i + 7]);
				else
					error_message_ + ", unknown error";
				return false;
			}
			break;
		}*/
		}
		i += tag_length + 1;
	}
	return is_hls;
}

void SpodesHandler::AddSetStructure (std::vector<uint8_t> &info_field, void* structure, const uint8_t &struct_id)
{
	StructParsing sp;

	switch(struct_id)
	{
	case 1:
		sp.SendScalerUnit(info_field, structure);
		break;
	case 2:
		sp.SendValueDefinition(info_field, structure);
		break;
	case 3:
		sp.SendScript(info_field, structure);
		break;
	case 4:
		sp.SendEmergencyProfile(info_field, structure);
		break;
	case 5:
		sp.SendCaptureObjectDefinition(info_field, structure);
		break;
	case 6:
		sp.SendAssociatedPartnersType(info_field, structure);
		break;
	case 7:
		sp.SendContextNameStructure(info_field, structure);
		break;
	case 8:
		sp.SendxDLMSContextType(info_field, structure);
		break;
	case 9:
		sp.SendDestinationAndMethod(info_field, structure);
		break;
	case 10:
		sp.SendRepetitionDelay(info_field, structure);
		break;
	case 11:
		sp.SendConfirmationParameters(info_field, structure);
		break;
	case 12:
		sp.SendActionSet(info_field, structure);
		break;
	default:
		throw std::runtime_error("Unexpected structure id");
	}
}

void SpodesHandler::AddActionStructure (std::vector<uint8_t> &info_field, void* structure, const uint8_t &struct_id)
{
	StructParsing sp;

	switch (struct_id)
	{
	case 13:
		sp.SendAdjustingTime(info_field, structure);
		break;
	case 14:
		sp.SendEnableDisable(info_field, structure);
		break;
	case 15:
		sp.SendDataDelete(info_field, structure);
		break;
	case 16:
		sp.SendImageTransfer(info_field, structure);
		break;
	case 17:
		sp.SendImageBlock(info_field, structure);
		break;
	case 18:
		sp.SendCertificateIdentification(info_field, structure);
		break;
	case 19:
		sp.SendRequestAction(info_field, structure);
		break;
	case 21:
		sp.SendObjectDefinition(info_field, structure);
		break;
	case 22:
		sp.SendRegisterActMask(info_field, structure);
		break;
	case 24:
		sp.SendScheduleTableEntry(info_field, structure);
		break;
	case 25:
		sp.SendSpecDayEntry(info_field, structure);
		break;
	case 34:
		sp.SendObjectList(info_field, structure);
		break;
	default:
		throw std::runtime_error("Unexpected structure id");
	}
}

void SpodesHandler::AddSetArrayStructure (std::vector<uint8_t> &info_field, void** structure_array, const uint8_t &struct_id, uint8_t &array_size)
{
	StructParsing sp;
	for (int i = 0; i < array_size; i++)
	{
		switch(struct_id)
		{
		case 21:
			sp.SendObjectDefinition(info_field, structure_array[i]);
			break;
		case 22:
			sp.SendRegisterActMask(info_field, structure_array[i]);
			break;
		case 23:
			sp.SendArrayScript(info_field, structure_array[i]);
			break;
		case 24:
			sp.SendScheduleTableEntry(info_field, structure_array[i]);
			break;
		case 25:
			sp.SendSpecDayEntry(info_field, structure_array[i]);
			break;
		case 26:
			sp.SendSeason(info_field, structure_array[i]);
			break;
		case 27:
			sp.SendWeekProfile(info_field, structure_array[i]);
			break;
		case 28:
			sp.SendDayProfile(info_field, structure_array[i]);
			break;
		case 29:
			sp.SendActionSet(info_field, structure_array[i]);
			break;
		case 30:
			sp.SendExecutionTimeDate(info_field, structure_array[i]);
			break;
		case 31:
			sp.SendImageToActivateInfoElement(info_field, structure_array[i]);
			break;
		case 32:
			sp.SendScript(info_field, structure_array[i]);
			break;
		case 33:
			sp.SendCaptureObjectDefinition(info_field, structure_array[i]);
			break;
		case 34:
			sp.SendObjectList(info_field, structure_array[i]);
			break;
		case 35:
			sp.SendPushObjectDefinition(info_field, structure_array[i]);
			break;
		case 36:
			sp.SendWindowElement(info_field, structure_array[i]);
			break;
		case 37:
			sp.SendProtectionParametersElement(info_field, structure_array[i]);
			break;
		case 38:
			sp.SendCertificateInfo(info_field, structure_array[i]);
			break;
		default:
			throw std::runtime_error("Unexpected structure id");
		}
	}
}

void SpodesHandler::AddActionArrayStructure (std::vector<uint8_t> &info_field, void** structure_array, const uint8_t &struct_id, uint8_t &array_size)
{
	StructParsing sp;
	for (int i = 0; i < array_size; i++)
	{
		switch (struct_id)
		{
		case 1:
			sp.SendKey(info_field, structure_array[i]);
			break;
		default:
			throw std::runtime_error("Unexpected structure id");
		}
	}
}

void SpodesHandler::ReadGetStructure (std::vector<uint8_t> &response, void* structure, const uint8_t &struct_id)
{
	if (response.at(16) == 0)
		return;
	else if (response.at(16) != 2)
		throw std::runtime_error("Unexpected value type");
	StructParsing sp;
	switch(struct_id)
	{
	case 1:
		sp.ReceiveScalerUnit(response, structure);
		break;
	case 2:
		sp.ReceiveValueDefinition(response, structure);
		break;
	case 3:
		sp.ReceiveScript(response, structure);
		break;
	case 4:
		sp.ReceiveEmergencyProfile(response, structure);
		break;
	case 5:
		sp.ReceiveCaptureObjectDefinition(response, structure);
		break;
	case 6:
		sp.ReceiveAssociatedPartnersType(response, structure);
		break;
	case 7:
		sp.ReceiveContextNameStructure(response, structure);
		break;
	case 8:
		sp.ReceivexDLMSContextType(response, structure);
		break;
	case 9:
		sp.ReceiveSendDestinationAndMethod(response, structure);
		break;
	case 10:
		sp.ReceiveRepetitionDelay(response, structure);
		break;
	case 11:
		sp.ReceiveConfirmationParameters(response, structure);
		break;
	case 12:
		sp.ReceiveActionSet(response, structure);
		break;
	default:
		throw std::runtime_error("Unexpected structure id");
	}
}

void SpodesHandler::ReadGetArrayStructure (std::vector<uint8_t> &response, void** structure_array, const uint8_t &struct_id, uint8_t &array_size)
{
	array_size = response.at(17);
	if (array_size == 0 or response.at(16) == 0)
		return;

	else if (response.at(16) != 1)
		throw std::runtime_error("Unexpected value type");

	if (response.at(18) != 2)
		throw std::runtime_error("Unexpected element type");

	StructParsing sp;
	switch(struct_id)
	{
	case 21:
		sp.ReceiveObjectDefinition(response, structure_array, array_size);
		break;
	case 22:
		sp.ReceiveRegisterActMask(response, structure_array, array_size);
		break;
	case 23:
		sp.ReceiveArrayScript(response, structure_array, array_size);
		break;
	case 24:
		sp.ReceiveScheduleTableEntry(response, structure_array, array_size);
		break;
	case 25:
		sp.ReceiveSpecDayEntry(response, structure_array, array_size);
		break;
	case 26:
		sp.ReceiveSeason(response, structure_array, array_size);
		break;
	case 27:
		sp.ReceiveWeekProfile(response, structure_array, array_size);
		break;
	case 28:
		sp.ReceiveDayProfile(response, structure_array, array_size);
		break;
	case 29:
		sp.ReceiveArrayActionSet(response, structure_array, array_size);
		break;
	case 30:
		sp.ReceiveExecutionTimeDate(response, structure_array, array_size);
		break;
	case 31:
		sp.ReceiveImageToActivateInfoElement(response, structure_array, array_size);
		break;
	case 32:
		sp.ReceiveActionItem(response, structure_array, array_size);
		break;
	case 33:
		sp.ReceiveArrayCaptureObjectDefinition(response, structure_array, array_size);
		break;
	case 34:
		sp.ReceiveObjectList(response, structure_array, array_size);
		break;
	case 35:
		sp.ReceivePushObjectDefinition(response, structure_array, array_size);
		break;
	case 36:
		sp.ReceiveWindowElement(response, structure_array, array_size);
		break;
	case 37:
		sp.ReceiveProtectionParametersElement(response, structure_array, array_size);
		break;
	case 38:
		sp.ReceiveCertificateInfo(response, structure_array, array_size);
		break;
	default:
		throw std::runtime_error("Unexpected structure id");
	}
}

void SpodesHandler::SetStringSize (const uint8_t &value_type, uint8_t &element_size, const uint8_t &size_index)
{
	switch (value_type)
	{
		case 9:
			element_size = size_index;
			break;
		case 25:
			element_size = 12;
			break;
		case 26:
			element_size = 5;
			break;
		case 27:
			element_size = 4;
			break;
		case 0:
			element_size = 0;
			break;
		default:
			throw std::runtime_error("Unexpected element type");
	}
}
