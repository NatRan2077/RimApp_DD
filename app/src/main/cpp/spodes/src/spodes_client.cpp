/*
 * spodes_client.cpp
 *
 *  Created on: 10 апр. 2025 г.
 *      Author: habarova
 */

#include "spodes_client.h"

// [Android-патч] реализация транспорта поверх BLE, используется ConnectBleTransport()/
// FeedIncomingBytes()/PullOutgoingBytes() ниже.
#include "ble_transport.h"

const char* SpodesClient::GetErrorMessage ()
{
	return error_message_.c_str();
}

uint8_t SpodesClient::GetCurrentElemNumber ()
{
	return element_number_;
}

std::vector<uint8_t> SpodesClient::GetLastMessage(int &total_length)
{
	std::vector<uint8_t> result = {};
	total_length = last_message_.size();
	for (const auto& msg : last_message_)
	{
		uint16_t size = static_cast<uint16_t>(msg.size());
		result.push_back(static_cast<uint8_t>((size >> 8) & 0xFF));  // Старший байт
		result.push_back(static_cast<uint8_t>(size & 0xFF));
		result.insert(result.end(), msg.begin(), msg.end());
	}

	last_message_.clear();
	return result;
}

bool SpodesClient::ConnectSerialPort(const SerialPortParams &params)
{
	// [Android-патч] Последовательный порт на Android недоступен (нет реализации
	// serial_port::SerialPort под этой платформой — см. SpodesClient_rasshifrovka.txt,
	// раздел 6). Метод оставлен ради совместимости сигнатуры с spodes_client_c_api.cpp,
	// но всегда возвращает false. Для Android используйте ConnectBleTransport().
	(void)params;
	error_message_ = "SpodesClient::ConnectSerialPort: not supported on this platform, "
					  "use ConnectBleTransport() instead";
	return false;
}

bool SpodesClient::ConnectBleTransport()
{
	// [Android-патч] новый метод: вместо COM-порта открываем BleTransport.
	// Реальный обмен байтами с прибором делает JNI-мост через
	// FeedIncomingBytes()/PullOutgoingBytes() — см. spodes_jni_bridge.cpp.
	transport_ = std::make_unique<rim_transport::BleTransport>();
	transport_->Open();
	return transport_->IsOpen();
}

void SpodesClient::FeedIncomingBytes(const uint8_t* data, unsigned long num_bytes)
{
	// [Android-патч]
	if (auto* ble = dynamic_cast<rim_transport::BleTransport*>(transport_.get()))
	{
		ble->PushIncoming(data, num_bytes);
	}
}

std::vector<uint8_t> SpodesClient::PullOutgoingBytes()
{
	// [Android-патч]
	if (auto* ble = dynamic_cast<rim_transport::BleTransport*>(transport_.get()))
	{
		return ble->PullOutgoing();
	}
	return {};
}

bool SpodesClient::Disconnect ()
{
	// [Android-патч] serial_port_ → transport_ (переименование, см. spodes_client.h)
	transport_->Close();
	if (transport_->IsOpen())
	{
		error_message_ = "SpodesClient::Disconnect: Can't close connection to transport";
		return false;
	}
	return true;
}

bool SpodesClient::SetNormalResponseMode (const ConnectionAddresses &addr, const ConnectionParams* params)
{
	ServiceFunctions serv_funcs;
	std::vector<uint8_t> request = {0x7E, 0xA0, 0x08};

	if (addr.source_address != 16 and addr.source_address != 32 and addr.source_address != 48)
	{
		error_message_ = "SpodesClient::SetNormalResponseMode: Illegal client address value";
		return false;
	}
	connection_addr_.source_address = serv_funcs.AppendBit(addr.source_address, 1);
	connection_addr_.logical_address = serv_funcs.AppendBit(addr.logical_address, 0);
	connection_addr_.physical_address = serv_funcs.AppendBit(addr.physical_address, 1);
	request.insert(request.end(),
	{
	    connection_addr_.logical_address,
	    connection_addr_.physical_address,
	    connection_addr_.source_address,
	    0x93, 0, 0
	});
	if (params != nullptr)
	{
		connection_params_ = *params;
		bool result = serv_funcs.FormSNRMInfromationField(request, error_message_,
														params->max_info_length_transmit, params->max_info_length_receive,
														params->window_size_transmit, params->window_size_receive);
		if (!result)
			return false;
	}

	uint16_t hcs = serv_funcs.CalculateCRC(std::vector<uint8_t>(request.begin(), request.begin() + 7), true);
	request[7] = hcs & 0xFF;		  // младший байт
	request[8] = (hcs >> 8) & 0xFF; // старший байт
	if (params != nullptr)
	{
		uint16_t fcs = serv_funcs.CalculateCRC(std::vector<uint8_t>(request.begin(), request.end() - 2), false);
		request.push_back(fcs & 0xFF);
		request.push_back((fcs >> 8) & 0xFF);
	}
	request.push_back(0x7E);

	if (!SendRequest(request))
		return false;
	return true;
}

unsigned long SpodesClient::ReadResponse (std::vector<char> &answer)
{
	unsigned long result_size = transport_->ReadData(answer.data(), max_length_);
	std::vector<uint8_t> message(answer.begin(), answer.begin() + result_size);
	last_message_.push_back(message);

	++receive_sequence_number_;
	ServiceFunctions serv_funcs;
	serv_funcs.CheckRawResponse(result_size, answer);
	return result_size;
}

bool SpodesClient::SendRequest(std::vector<uint8_t> &request)
{
	const char* data = reinterpret_cast<const char*>(request.data());

	if (transport_->WriteData(data, request.size()) != request.size())
	{
		error_message_ = "SpodesClient::SendRequest: Wrong number of written bytes";
		return false;
	}
	last_message_.push_back(request);
	return true;
}

bool SpodesClient::SendDisconnectRequest ()
{
	std::vector<uint8_t> request = {0x7E, 0xA0, 0x08};
	request.insert(request.end(),
	{
		connection_addr_.logical_address,
		connection_addr_.physical_address,
		connection_addr_.source_address,
		0x53
	});
	std::vector<uint8_t> slice(request.begin(), request.begin() + 7);
	ServiceFunctions serv_funcs;
	uint16_t hcs = serv_funcs.CalculateCRC(slice, true);
	request.push_back(hcs & 0xFF);		  // младший байт
	request.push_back((hcs >> 8) & 0xFF); // старший байт
	request.push_back(0x7E);

	if (!SendRequest(request))
		return false;
	return true;
}

bool SpodesClient::SendReadyToReceive(const int8_t &block_number)
{
	std::vector<uint8_t> request = {0x7E, 0, 0};
	request.insert(request.end(),
	{
		connection_addr_.logical_address,
		connection_addr_.physical_address,
		connection_addr_.source_address,
	});
	ServiceFunctions serv_funcs;
	if (block_number > -1)
	{
		request.push_back(serv_funcs.CalculateControlField(send_sequence_number_, receive_sequence_number_, 1));
		request.insert(request.end(), {0, 0, 0xE6, 0xE6, 0, 0xC0, 0x02, 0xC1, 0, 0, 0, (uint8_t)block_number});
	}
	else
		request.push_back( ((receive_sequence_number_ & 0x07) << 5) | (1 << 4) | ((0 & 0x07) << 1) | 1);

	request.insert(request.end(), {0, 0, 0x7E});

	uint16_t request_size = request.size() - 2;
	uint16_t value = request_size & 0x07FF;
	value |= 0xA000;
	request[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
	request[2] = static_cast<uint8_t>(value & 0xFF);

	if (block_number > -1)
	{
		uint16_t hcs = serv_funcs.CalculateCRC(std::vector<uint8_t>(request.begin(), request.begin() + 7), true);
		request[7] = hcs & 0xFF;		  // младший байт
		request[8] = (hcs >> 8) & 0xFF; // старший байт
	}

	uint16_t fcs = serv_funcs.CalculateCRC(std::vector<uint8_t>(request.begin(), request.end() - 2), false);
	request[request_size - 1] = fcs & 0xFF;
	request[request_size] = (fcs >> 8) & 0xFF;

	if (!SendRequest(request))
		return false;
	if (block_number > -1)
		send_sequence_number_ ++;
	return true;
}

uint8_t SpodesClient::ReceiveUnnumberedAcknowledge ()
{
	unsigned long max_length = 12 + connection_params_.max_info_length_receive;
	std::vector<char> answer(max_length, 0);
	unsigned long result_size = 0;
	try
	{
		result_size = transport_->ReadData(answer.data(), max_length);
		std::vector<uint8_t> response(result_size, 0);
		for (int i = 0; i < result_size; i++)
			response[i] = static_cast<uint8_t>(answer[i]);
		last_message_.push_back(response);

		if (result_size == 0)
			throw std::runtime_error("Couldn't read response from server");

		if (response.at(6) == 0x1F)
		{
			error_message_ = "SpodesClient: Received DM message. Server is already disconnected";
			return 2;
		}
		else if (response.at(6) != 0x73)
			throw std::runtime_error("Unexpected response format");

		if (result_size > 30)
		{
			if (response[11] == 0x12)
			{
				connection_params_.max_info_length_transmit = response[14];
				connection_params_.max_info_length_receive = response[17];
				connection_params_.window_size_transmit = response[23];
				connection_params_.window_size_receive = response[29];
			}
			else
			{
				connection_params_.max_info_length_transmit = (static_cast<uint16_t>(response[14]) << 8) | response[15];
				connection_params_.max_info_length_receive = (static_cast<uint16_t>(response[18]) << 8) | response[19];
				connection_params_.window_size_transmit = response[25];
				connection_params_.window_size_receive = response[31];
			}
			max_length_ = 12 + connection_params_.max_info_length_receive;
		}
		else
			max_length_ =  12 + 256;
		return 1;
	}
	catch (const std::exception &re)
	{
		error_message_ = "SpodesClient::ReceiveUnnumberedAcknowledge: " + (std::string)re.what();
		return 0;
	}
}

bool SpodesClient::EstablishConnectionRequest (const uint8_t& security_level, const SecurityParams* params,
												const uint16_t &client_max_receive_pdu_size, const OptionalParams *optional_params)
{
	if (params != nullptr)
		security_params_ = *params;
	if (optional_params != nullptr)
		optional_params_ = *optional_params;

	std::vector<uint8_t> request(9, 0);
	SpodesHandler spodes_handler;
	try
	{
		spodes_handler.FormConnectionRequest(request, security_level, connection_addr_, security_params_, optional_params_);
	}
	catch (const std::runtime_error &re)
	{
		error_message_ = "SpodesClient::EstablishConnectionRequest: " + (std::string)re.what();
		return false;
	}
	spodes_handler.AddUserInformation(request, security_level, init_vec_, sec_control_byte_, security_params_.has_ciphering,
									client_max_receive_pdu_size, optional_params_);

	request[13] = request.size() - 14;

	ServiceFunctions serv_funcs;
	uint8_t control_field = serv_funcs.CalculateControlField(send_sequence_number_, receive_sequence_number_, 1);
	ServiceFunctions::HDLCParams hdlc_params {0, connection_addr_.source_address, connection_addr_.logical_address,
												connection_addr_.physical_address, control_field};
	serv_funcs.FormHDLCHeader(request, hdlc_params);

	if (!SendRequest(request))
		return false;
	send_sequence_number_ ++;
	return true;
}

bool SpodesClient::EstablishConnectionResponse ()
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		unsigned long result_size = ReadResponse(answer);

		std::vector<uint8_t> response(result_size, 0);
		for (int i = 0; i < result_size; i++)
			response[i] = static_cast<uint8_t>(answer[i]);

		uint8_t ciphered_answer[16] = {};
		SpodesHandler spodes_handler;
		bool is_hls = spodes_handler.ProcessUserInformation(response, respond_ap_title_, ciphered_answer, security_params_);

		if (is_hls)
		{
			RequestParams req_params;
			req_params.attribute_id = 1;
			req_params.class_id = 15;
			req_params.instance_id = "0.0.40.0.0.255";
			char info_field[16];
			std::memcpy(info_field, ciphered_answer, sizeof(ciphered_answer));
			if (!ActionRequestString(req_params, info_field, 16))
				return false;
			if (!ReceiveConfirmationResponse(true))
				return false;
	}
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::EstablishConnectionResponse: " + (std::string)ex.what();
		return false;
	}
	return true;
}


void SpodesClient::ReadFrame (std::vector<uint8_t> &response, unsigned long &result_size)
{
	while (true)
	{
		if (!SendReadyToReceive(-1))
			throw std::runtime_error(error_message_);

		std::vector<char> next_answer(max_length_, 0);
		unsigned long next_size = ReadResponse(next_answer);
		std::vector<uint8_t> next_block(next_size, 0);
		for (int i = 0; i < next_size; i++)
			next_block[i] = static_cast<uint8_t>(next_answer[i]);

		response.insert(response.end() - 3, next_block.begin() + 9, next_block.end() - 3);
		result_size += next_block.size() - 12;

		if (!((next_block.at(1) >> 3) & 1))
			break;
	}
}

void SpodesClient::ReadBlocks (std::vector<uint8_t> &response, unsigned long &result_size)
{
	ServiceFunctions serv_funcs;
	uint16_t length = response.at(21);
	if (response.at(21) > 127)
		length = (static_cast<uint16_t>(response.at(22)) << 8) | response.at(23);

	response.erase(response.begin() + 15, response.begin() + 20);
	response.erase(response.begin() + 16, response.begin() + ((response.at(16) > 127) ? 19 : 17));
	uint8_t block_num = 0;

	while (true)
	{
		if (!SendReadyToReceive(block_num))
			throw std::runtime_error(error_message_);
		std::vector<char> next_answer(max_length_, 0);
		unsigned long next_size = ReadResponse(next_answer);
		std::vector<uint8_t> next_block(next_size, 0);
		for (int i = 0; i < next_size; i++)
			next_block[i] = static_cast<uint8_t>(next_answer[i]);
		if ((next_block.at(1) >> 3) & 1)
			ReadFrame(next_block, next_size);

		if (security_params_.has_ciphering)
			serv_funcs.DecipherResponse(next_block, optional_params_, sec_control_byte_, init_vec_);

		block_num = (int8_t)next_block.at(19);
		serv_funcs.CheckGetResponse((next_block.at(20) > 0) ? next_block.at(21) : 0);

		uint16_t info_size = (next_block.at(21) > 127) ? (static_cast<uint16_t>(next_block.at(22)) << 8) | next_block.at(23) : next_block.at(21);
		uint8_t info_index = (next_block.at(21) > 127) ? 24 : 22;
		response.insert(response.end() - 3, next_block.begin() + info_index, next_block.begin() + info_index + info_size);
		result_size += info_size;

		if (next_block.at(15) == 1)
			break;
	}
}

void SpodesClient::AddHDLCHeader (std::vector<uint8_t> &request)
{
	ServiceFunctions serv_funcs;
	if (security_params_.has_ciphering)
	{
		std::vector<uint8_t> data(request.begin() + 12, request.end());
		request.erase(request.begin() + 12, request.end());
		std::vector<uint8_t> ciphered_apdu = {};
		serv_funcs.CipherRequest(data, ciphered_apdu, 200, optional_params_, sec_control_byte_, init_vec_);
		request.insert(request.end(), ciphered_apdu.begin(), ciphered_apdu.end());
	}

	uint8_t control_field = serv_funcs.CalculateControlField(send_sequence_number_, receive_sequence_number_, 1);
	ServiceFunctions::HDLCParams hdlc_params {0, connection_addr_.source_address, connection_addr_.logical_address,
												connection_addr_.physical_address, control_field};
	serv_funcs.FormHDLCHeader(request, hdlc_params);
}

bool SpodesClient::MultipleGetRequest (const RequestParams* params, const uint8_t &param_count)
{
	if (params[0].retry)
		send_sequence_number_--;
	std::vector<uint8_t> request(9, 0);
	ServiceFunctions serv_funcs;
	ServiceFunctions::LLCParams llc_params {192, 3, true, true};
	serv_funcs.FormLLCHeader(request, llc_params);
	request.push_back(param_count);
	for (int i = 0; i < param_count; i++)
	{
		if (!serv_funcs.AddAddress(request, params[i], error_message_))
			return false;
	}
	request.push_back(0);
	AddHDLCHeader(request);

	if (!SendRequest(request))
		return false;
	send_sequence_number_++;
	return true;
}

bool SpodesClient::GetRequest (const RequestParams &params, void* selective_access, const uint8_t &selector)
{
	if (params.retry)
		send_sequence_number_--;
	std::vector<uint8_t> request(9, 0);
	ServiceFunctions serv_funcs;
	ServiceFunctions::LLCParams llc_params {192, 1, true, true};
	serv_funcs.FormLLCHeader(request, llc_params);
	if (!serv_funcs.AddAddress(request, params, error_message_))
		return false;

	if (selective_access != nullptr and selector > 0)
	{
		if (params.attribute_id != 2)
			return false;
		request.push_back(1);
		if (!serv_funcs.AddSelectiveAccess(request, params.class_id, selector, selective_access))
		{
			error_message_ = "SpodesClient::AddSelectiveAccess: Wrong parameters for selective access";
			return false;
		}
	}
	else
		request.push_back(0);

	AddHDLCHeader(request);

	if (!SendRequest(request))
		return false;
	send_sequence_number_++;
	return true;
}

void SpodesClient::GetResponseGeneral (std::vector<char> &answer, std::vector<uint8_t> &response, unsigned long &result_size, const bool &is_list)
{
	result_size = ReadResponse(answer);
	response.resize(result_size);
	for (int i = 0; i < result_size; i++)
		response[i] = static_cast<uint8_t>(answer[i]);

	if ((response.at(1) >> 3) & 1)
		ReadFrame(response, result_size);
	ServiceFunctions serv_funcs;
	if (security_params_.has_ciphering)
		serv_funcs.DecipherResponse(response, optional_params_, sec_control_byte_, init_vec_);

	if (response.at(13) == 2)
	{
		ReadBlocks(response, result_size);
		if (is_list)
			serv_funcs.ReadList(response, 2);
	}
	else if (response.at(13) == 3 and is_list)
	{
		response.insert(response.begin() + 15, 0);
		serv_funcs.ReadList(response, 2);
	}

	serv_funcs.CheckGetResponse((response.at(15) > 0) ? response.at(16) : 0);
}

bool SpodesClient::GetResponseByte (uint8_t &value)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		GetProcessing get_proc;
		get_proc.ProcessResponseByte(response, value);
		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseByte: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseInt (uint64_t &value)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		GetProcessing get_proc;
		get_proc.ProcessResponseInt(response, value, result_size);
		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseInt: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseFloat (double &value)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		GetProcessing get_proc;
		get_proc.ProcessResponseFloat(response, value, result_size);
		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseFloat: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseString (char* result, uint8_t &str_size)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);

		uint8_t value_type = response.at(16);
		SpodesHandler spodes_handler;
		spodes_handler.SetStringSize(value_type, str_size, response.at(17));

		uint8_t str_index = (value_type == 9) ? 18 : 17;
		for (int i = 0; i < str_size; i++)
			result[i] = (char)response.at(str_index + i);
		result[str_size] = '\0';

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseString: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseDateTime (DateTime &date_time)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		if (response.at(16) == 0)
			return true;
		else if (response.at(16) != 9)
			throw std::runtime_error("Unexpected value type");

		//TODO если будет необходимость в 25-27 типах данных то для них обработку сделать
		uint8_t str_index = 17;
		StructParsing sp;
		sp.ReceiveDateTime(response, date_time, str_index);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseDateTime: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseStructure (void* structure, const uint8_t &struct_id)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		SpodesHandler spodes_handler;
		spodes_handler.ReadGetStructure(response, structure, struct_id);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseStructure: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayByte (uint8_t* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, is_list);
		GetProcessing get_proc;
		get_proc.ProcessResponseArrayByte(response, result, array_size, value_type);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayByte: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayInt (uint64_t* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, is_list);
		GetProcessing get_proc;
		get_proc.ProcessResponseArrayInt(response, result, array_size, value_type);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayInt: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayFloat (double* result, uint8_t &array_size, uint8_t &value_type, const bool &is_list)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, is_list);
		GetProcessing get_proc;
		get_proc.ProcessResponseArrayFloat(response, result, array_size, value_type);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayFloat: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayString(char** result, uint8_t &array_size, uint8_t &element_size)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		array_size = response.at(17);
		if (array_size == 0 or response.at(16) == 0)
		{
			element_size = 0;
			return true;
		}
		else if (response.at(16) != 1)
			throw std::runtime_error("Unexpected value type");

		uint8_t value_type = response.at(18);
		SpodesHandler spodes_handler;
		spodes_handler.SetStringSize(value_type, element_size, response.at(19));

		int base_index = (value_type == 9) ? 20 : 19;
		for (int i = 0; i < array_size; ++i)
		{
			int element_index = base_index + i * (element_size + ((value_type == 9) ? 2 : 1));
			std::memcpy(result[i], &response.at(element_index), element_size);
			result[i][element_size] = '\0';
		}

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayString: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayBitString (uint8_t** result, uint8_t &array_size, uint8_t &element_size)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		GetProcessing get_proc;
		get_proc.ProcessResponseArrayBitString(response, result, array_size, element_size);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayBitString: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayDateTime (DateTime* date_time, uint8_t &array_size)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		array_size = response.at(17);
		if (array_size == 0 or response.at(16) == 0)
			return true;
		else if (response.at(16) != 1)
			throw std::runtime_error("Unexpected value type");

		uint8_t str_index = 19;
		StructParsing sp;
		for (int i = 0; i < array_size; ++i)
		{
			sp.ReceiveDateTime(response, date_time[i], str_index);
			str_index += 2;
		}

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayDateTime: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseArrayStructure (void** structure_array, const uint8_t &struct_id, uint8_t &array_size)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		SpodesHandler spodes_handler;
		spodes_handler.ReadGetArrayStructure(response, structure_array, struct_id, array_size);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseArrayStructure: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::GetResponseBuffer (uint8_t (*values)[300], uint8_t (*types)[300], uint8_t (*lengths)[300], int &array_size, uint8_t &elem_count)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		GetResponseGeneral(answer, response, result_size, false);
		GetProcessing get_proc;
		get_proc.ProcessResponseBuffer(response, values, types, lengths, array_size, elem_count, element_number_);

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::GetResponseBuffer: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::ReceiveBlockConfirmation(const uint8_t &block_number)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		std::vector<uint8_t> response = {};
		unsigned long result_size = 0;
		result_size = ReadResponse(answer);
		response.resize(result_size);
		for (int i = 0; i < result_size; i++)
			response[i] = static_cast<uint8_t>(answer[i]);

		ServiceFunctions serv_funcs;
		if (security_params_.has_ciphering)
			serv_funcs.DecipherResponse(response, optional_params_, sec_control_byte_, init_vec_);

		if (response.at(18) != block_number)
			throw std::runtime_error("Server failed to read information block");

		return true;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::ReceiveBlockConfirmation: " + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::CipherAndSendFrame (std::vector<uint8_t> &request, const bool &is_action, const bool &is_last)
{
	ServiceFunctions serv_funcs;
	if (security_params_.has_ciphering)
	{
		std::vector<uint8_t> data(request.begin() + 12, request.end());
		request.erase(request.begin() + 12, request.end());
		std::vector<uint8_t> ciphered_apdu = {};
		uint8_t func_tag = (is_action) ? 203 : 201;
		serv_funcs.CipherRequest(data, ciphered_apdu, func_tag, optional_params_, sec_control_byte_, init_vec_);
		request.insert(request.end(), ciphered_apdu.begin(), ciphered_apdu.end());
	}

	uint8_t control_field = serv_funcs.CalculateControlField(send_sequence_number_, receive_sequence_number_, is_last);
	ServiceFunctions::HDLCParams hdlc_params {0, connection_addr_.source_address, connection_addr_.logical_address,
												connection_addr_.physical_address, control_field};
	serv_funcs.FormHDLCHeader(request, hdlc_params);

	if (!SendRequest(request))
		return false;
	++send_sequence_number_;
	return true;
}

bool SpodesClient::WriteBlocks (ServiceFunctions::LLCParams &llc_params, const RequestParams &req_params,
								const std::vector<uint8_t> &info_field, const bool &is_action)
{
	uint16_t max_transmit = connection_params_.max_info_length_transmit;
	uint16_t max_req_length = (max_transmit > 127) ? max_transmit - 26 : max_transmit - 24;
	uint8_t last_block = 0;
	uint8_t block_number = 1;
	bool is_last = false;
	bool first_block = true;
	size_t start_index = 0;

	ServiceFunctions serv_funcs;
	while (!is_last)
	{
		std::vector<uint8_t> request(9, 0);
		serv_funcs.FormLLCHeader(request, llc_params);
		if (first_block)
		{
			if (!serv_funcs.AddAddress(request, req_params, error_message_))
				return false;
			if (is_action)
				request.push_back(1);
			else
				request.push_back(0);
		}
		request.insert(request.end(), {last_block, 0, 0, 0, block_number});

		size_t end_index = std::min(start_index + max_req_length, info_field.size());
		is_last = end_index >= info_field.size();
		uint16_t req_length = end_index - start_index;

		if (req_length > 127)
			request.insert(request.end(), {0x82, static_cast<uint8_t>(req_length >> 8), static_cast<uint8_t>(req_length & 0xFF)});
		else
			request.push_back(static_cast<uint8_t>(req_length & 0xFF));

		request.insert(request.end(), info_field.begin() + start_index, info_field.begin() + end_index);

		if(!CipherAndSendFrame(request, is_action, is_last))
			return false;
		if (!is_last)
		{
			if (!ReceiveBlockConfirmation(block_number))
				return false;
		}

		++last_block;
		++block_number;
		if (first_block)
		{
			llc_params.service_type = is_action ? 6 : 3;
			max_req_length += 10;
			first_block = false;
		}
		start_index += max_req_length;
	}
	return true;
}

bool SpodesClient::WriteRequestGeneral (const ServiceFunctions::LLCParams &llc_params, const RequestParams &req_params,
										const std::vector<uint8_t> &info_field, const bool &is_action)
{
	if (req_params.retry)
		send_sequence_number_--;
	std::vector<uint8_t> request(9, 0);
	ServiceFunctions serv_funcs;
	serv_funcs.FormLLCHeader(request, llc_params);
	if (!serv_funcs.AddAddress(request, req_params, error_message_))
		return false;

	if (is_action)
		request.push_back(1);
	else
		request.push_back(0);
	request.insert(request.end(), info_field.begin(), info_field.end());

	if(!CipherAndSendFrame(request, is_action, true))
		return false;
	return true;
}

bool SpodesClient::WriteRequestByte (const RequestParams &params, const uint8_t &value, const uint8_t &value_type, const bool &is_action)
{
	std::vector <uint8_t> info_field = {};
	WriteProcessing write_proc;
	write_proc.ProcessRequestByte(info_field, value, value_type);
	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestByte (const RequestParams &params, const uint8_t &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestByte(params, value, value_type, false))
			return false;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestByte: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::WriteRequestInt (const RequestParams &params, const uint64_t &value, const uint8_t &value_type, const bool &is_action)
{
	std::vector <uint8_t> info_field = {};
	WriteProcessing write_proc;
	write_proc.ProcessRequestInt(info_field, value, value_type);

	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestInt (const RequestParams &params, const uint64_t &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestInt(params, value, value_type, false))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestInt: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::WriteRequestFloat (const RequestParams &params, const double &value, const uint8_t &value_type, const bool &is_action)
{
	std::vector <uint8_t> info_field = {};
	WriteProcessing write_proc;
	write_proc.ProcessRequestFloat(info_field, value, value_type);
	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestFloat (const RequestParams &params, const double &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestFloat(params, value, value_type, false))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestFloat: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::WriteRequestString (const RequestParams &params, const char* value, const uint8_t &value_size, const bool &is_action)
{
	std::vector<uint8_t> info_field = {};
	info_field.push_back(9);
	info_field.push_back(value_size);
	info_field.insert(info_field.end(), value, value + value_size);

	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestString (const RequestParams &params, const char* value, const uint8_t &value_size)
{
	try
	{
		if (!WriteRequestString(params, value, value_size, false))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestString: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::WriteRequestDateTime (const RequestParams &params, const DateTime &date_time, const bool &date_format, const bool &is_action)
{
	std::vector<uint8_t> info_field = {};
	WriteProcessing write_proc;
	write_proc.ProcessRequestDateTime(info_field, date_time, date_format);

	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestDateTime (const RequestParams &params, const DateTime &date_time, const bool &date_format_available)
{
	if (!WriteRequestDateTime(params, date_time, date_format_available, false))
		return false;

	return true;
}

bool SpodesClient::WriteRequestStructure (const RequestParams &params, void* structure, const uint8_t &struct_id, const bool &is_action)
{
	std::vector<uint8_t> info_field = {};
	SpodesHandler spodes_handler;
	if (is_action)
		spodes_handler.AddActionStructure(info_field, structure, struct_id);
	else
		spodes_handler.AddSetStructure(info_field, structure, struct_id);

	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (connection_params_.max_info_length_transmit - 16 < info_field.size())
	{
		llc_params.service_type = (is_action) ? 4 : 2;
		if (!WriteBlocks(llc_params, params, info_field, is_action))
			return false;
	}
	else if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestStructure (const RequestParams &params, void* structure, const uint8_t &struct_id)
{
	try
	{
		if (!WriteRequestStructure(params, structure, struct_id, false))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestStructure: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::SetRequestArrayByte (const RequestParams &params, const uint8_t* value_array,
										const uint8_t &array_size, const uint8_t &value_type, const uint8_t &element_type)
{
	try
	{
		std::vector <uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayByte(info_field, value_array, array_size, value_type, element_type);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayByte:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::SetRequestArrayInt (const RequestParams &params, const uint64_t* value_array,
										const uint8_t &array_size, const uint8_t &element_type)
{
	try
	{
		std::vector<uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayInt(info_field, value_array, array_size, element_type);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayInt:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::SetRequestArrayFloat (const RequestParams &params, const double* value_array,
										const uint8_t &array_size, const uint8_t &element_type)
{
	try
	{
		std::vector <uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayFloat(info_field, value_array, array_size, element_type);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayFloat:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::SetRequestArrayString (const RequestParams &params, const char** value_array,
										const uint8_t &array_size, const uint8_t &element_size)
{
	try
	{
		std::vector<uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayString(info_field, value_array, array_size, element_size);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayString:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::SetRequestArrayDateTime (const RequestParams &params, const DateTime* date_time, const uint8_t &array_size, const bool &date_format_available)
{
	try
	{
		std::vector<uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayDateTime(info_field, date_time, array_size, date_format_available);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayDateTime:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::SetRequestArrayBitString (const RequestParams &params, const uint8_t** value_array, const uint8_t &array_size,
											const uint8_t &element_size, const uint8_t &elem_type)
{
	try
	{
		std::vector<uint8_t> info_field = {};
		WriteProcessing write_proc;
		write_proc.ProcessRequestArrayBitString(info_field, value_array, array_size, element_size, elem_type);

		ServiceFunctions::LLCParams llc_params {193, 1, true, true};
		if (connection_params_.max_info_length_transmit - 16 < info_field.size())
		{
			llc_params.service_type = 2;
			if (!WriteBlocks(llc_params, params, info_field, false))
				return false;
		}
		else if (!WriteRequestGeneral(llc_params, params, info_field, false))
			return false;

		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayBitString:" + (std::string)ex.what();
		return false;
	}
}

bool SpodesClient::WriteRequestArrayStructure (const RequestParams &params, void** structure_array, const uint8_t &struct_id,
												uint8_t &array_size, const bool &is_action)
{
	std::vector<uint8_t> info_field = {};
	info_field.push_back(1);
	info_field.push_back(array_size);
	SpodesHandler spodes_handler;
	if (is_action)
		spodes_handler.AddActionArrayStructure(info_field, structure_array, struct_id, array_size);
	else
		spodes_handler.AddSetArrayStructure(info_field, structure_array, struct_id, array_size);

	ServiceFunctions::LLCParams llc_params {static_cast<uint8_t>(is_action ? 195 : 193), 1, true, true};
	if (connection_params_.max_info_length_transmit - 16 < info_field.size())
	{
		llc_params.service_type = (is_action) ? 4 : 2;
		if (!WriteBlocks(llc_params, params, info_field, is_action))
			return false;
	}
	else if (!WriteRequestGeneral(llc_params, params, info_field, is_action))
		return false;

	return true;
}

bool SpodesClient::SetRequestArrayStructure (const RequestParams &params, void** structure_array, const uint8_t &struct_id, uint8_t &array_size)
{
	try
	{
		if (!WriteRequestArrayStructure(params, structure_array, struct_id, array_size, false))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::SetRequestArrayStructure: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestByte (const RequestParams &params, const uint8_t &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestByte(params, value, value_type, true))
			return false;
	}
	catch (const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestByte: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestInt (const RequestParams &params, const uint64_t &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestInt(params, value, value_type, true))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestInt: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestFloat (const RequestParams &params, const double &value, const uint8_t &value_type)
{
	try
	{
		if (!WriteRequestFloat(params, value, value_type, true))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestFloat: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestString (const RequestParams &params, const char* value, const uint8_t &value_size)
{
	try
	{
		if (!WriteRequestString(params, value, value_size, true))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestString: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestDateTime (const RequestParams &params, const DateTime &date_time, const bool &date_format_available)
{
	if (!WriteRequestDateTime(params, date_time, date_format_available, true))
		return false;

	return true;
}

bool SpodesClient::ActionRequestStructure (const RequestParams &params, void* structure, const uint8_t &struct_id)
{
	try
	{
		if (!WriteRequestStructure(params, structure, struct_id, true))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestStructure: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ActionRequestArrayStructure(const RequestParams &params, void** structure_array, uint8_t &struct_id, uint8_t &array_size)
{
	try
	{
		if (!WriteRequestArrayStructure(params, structure_array, struct_id, array_size, true))
			return false;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ActionRequestArrayStructure: " + (std::string)ex.what();
		return false;
	}
	return true;
}

bool SpodesClient::ReceiveConfirmationResponse (const bool &is_action)
{
	std::vector<char> answer(max_length_, 0);
	try
	{
		unsigned long result_size = ReadResponse(answer);
		std::vector<uint8_t> response(result_size, 0);
		for (int i = 0; i < result_size; i++)
			response[i] = static_cast<uint8_t>(answer[i]);

		ServiceFunctions serv_funcs;
		if (security_params_.has_ciphering)
			serv_funcs.DecipherResponse(response, optional_params_, sec_control_byte_, init_vec_);

		serv_funcs.CheckConfirmationResponse(response, is_action);
		return true;
	}
	catch(const std::exception &ex)
	{
		error_message_ = "SpodesClient::ReceiveConfirmationResponse: " + (std::string)ex.what();
		return false;
	}
}


