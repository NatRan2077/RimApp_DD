/*
 * struct_parsing.cpp
 *
 *  Created on: 18 июн. 2025 г.
 *      Author: habarova
 */

#include "struct_parsing.h"

void StructParsing::ReceiveDoubleLongUnsigned (const std::vector<uint8_t> &response, uint32_t &value, uint8_t &start_index)
{
	value = ((uint32_t)response.at(start_index) << 24) |
			((uint32_t)response.at(start_index + 1) << 16) |
			((uint32_t)response.at(start_index + 2) << 8) |
			(uint32_t)response.at(start_index + 3);
}

void StructParsing::ReceiveDateTime (const std::vector<uint8_t> &response, Structures::DateTime &date_time, uint8_t &start_index)
{
	uint8_t length = response.at(start_index);
	if (length != 4)
	{
		date_time.date = true;
		date_time.year = (static_cast<uint16_t>(response.at(start_index + 1)) << 8) | response.at(start_index + 2);
		date_time.month = response.at(start_index + 3);
		date_time.month_day = response.at(start_index + 4);
		date_time.week_day = response.at(start_index + 5);
		start_index += 5;
	}
	if (length != 5)
	{
		date_time.time = true;
		date_time.hour = response.at(start_index + 1);
		date_time.minute = response.at(start_index + 2);
		date_time.second = response.at(start_index + 3);
		date_time.hundredths = response.at(start_index + 4);
		start_index += 4;
	}
	if (length == 12)
	{
		date_time.deviation = (static_cast<uint16_t>(response.at(start_index + 1)) << 8) | response.at(start_index + 2);
		date_time.clock_status = response.at(start_index + 3);
		start_index += 3;
	}
}

void StructParsing::SendLongUnsigned (std::vector<uint8_t> &info_field, const uint16_t &value)
{
	info_field.push_back(static_cast<uint8_t>(value >> 8));
	info_field.push_back(static_cast<uint8_t>(value & 0xFF));
}

void StructParsing::SendDoubleLongUnsigned (std::vector<uint8_t> &info_field, const uint32_t &value)
{
	info_field.push_back(static_cast<uint8_t>((value >> 24) & 0xFF));
	info_field.push_back(static_cast<uint8_t>((value >> 16) & 0xFF));
	info_field.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
	info_field.push_back(static_cast<uint8_t>(value & 0xFF));
}

void StructParsing::SendDateTime (std::vector<uint8_t> &info_field, const Structures::DateTime &date_time)
{
	if (date_time.date)
	{
		info_field.push_back(static_cast<uint8_t>(date_time.year >> 8));
		info_field.push_back(static_cast<uint8_t>(date_time.year & 0xFF));
		info_field.insert(info_field.end(), {date_time.month,
											date_time.month_day,
											date_time.week_day});
	}
	if (date_time.time)
	{
		info_field.insert(info_field.end(), {date_time.hour,
											date_time.minute,
											date_time.second,
											date_time.hundredths});
	}
	if (date_time.date and date_time.time)
	{
		info_field.push_back(static_cast<uint8_t>(date_time.deviation >> 8));
		info_field.push_back(static_cast<uint8_t>(date_time.deviation & 0xFF));
		info_field.push_back(date_time.clock_status);
	}
}

void StructParsing::ReceiveScalerUnit (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ScalerUnit scaler_unit = {};
	scaler_unit.scaler = response.at(19);
	scaler_unit.unit = response.at(21);
	std::memcpy(structure, &scaler_unit, sizeof(scaler_unit));
}

void StructParsing::ReceiveValueDefinition (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ValueDefinition val_def = {};
	val_def.class_id = (static_cast<uint16_t>(response.at(19)) << 8) | response.at(20);
	for (int j = 0; j < 6; j++)
		val_def.logical_name[j] = response.at(23 + j);

	val_def.attribute_index = response.at(30);
	std::memcpy(structure, &val_def, sizeof(val_def));
}

void StructParsing::ReceiveScript (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ActionItem script;
	for (int j = 0; j < 6; j++)
		script.scipt_logical_name[j] = response.at(20 + j);

	script.script_selector = (static_cast<uint16_t>(response.at(27)) << 8) | response.at(28);
	std::memcpy(structure, &script, sizeof(script));
}

void StructParsing::ReceiveEmergencyProfile (const std::vector<uint8_t> &response, void* structure)
{
	Structures::EmergencyProfile emergency_profile;
	emergency_profile.emerg_profile_id = (static_cast<uint16_t>(response.at(19)) << 8) | response.at(20);
	uint8_t index = 22;
	ReceiveDateTime(response, emergency_profile.emerg_activation_time, index);
	index = 36;
	ReceiveDoubleLongUnsigned(response, emergency_profile.emerg_duration, index);
	std::memcpy(structure, &emergency_profile, sizeof(emergency_profile));
}

void StructParsing::ReceiveCaptureObjectDefinition (const std::vector<uint8_t> &response, void* structure)
{
	Structures::CaptureObjectDefinition capture_object_def;
	capture_object_def.class_id = (static_cast<uint16_t>(response.at(19)) << 8) | response.at(20);
	for(int j = 0; j < 6; j++)
		capture_object_def.logical_name[j] = response.at(23 + j);

	capture_object_def.attribute_index = (int8_t)response.at(30);
	capture_object_def.data_index = (static_cast<uint16_t>(response.at(32)) << 8) | response.at(33);
	std::memcpy(structure, &capture_object_def, sizeof(capture_object_def));
}

void StructParsing::ReceiveAssociatedPartnersType (const std::vector<uint8_t> &response, void* structure)
{
	Structures::AssociatedPartnersType associated_part_type;
	associated_part_type.client_SAP = (int8_t)response.at(19);
	associated_part_type.server_SAP = (static_cast<uint16_t>(response.at(21)) << 8) | response.at(22);
	std::memcpy(structure, &associated_part_type, sizeof(associated_part_type));
}

void StructParsing::ReceiveContextNameStructure (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ContextNameStructure context_name_struct;
	context_name_struct.joint_iso_ctt_element = response.at(19);
	context_name_struct.country_element = response.at(21);
	context_name_struct.country_name_element = (static_cast<uint16_t>(response.at(23)) << 8) | response.at(24);
	context_name_struct.identified_org_elem = response.at(26);
	context_name_struct.DLMS_UA_element = response.at(28);
	context_name_struct.application_context_element = response.at(30);
	context_name_struct.context_id_element = response.at(32);
	std::memcpy(structure, &context_name_struct, sizeof(context_name_struct));
}

void StructParsing::ReceivexDLMSContextType (const std::vector<uint8_t> &response, void* structure)
{
	Structures::xDLMSContextType dlms_context_type;
	for (int i = 0; i < 3; i++)
		dlms_context_type.conformance[i] = response.at(20 + i);

	dlms_context_type.max_receive_pdu_size = (static_cast<uint16_t>(response.at(24)) << 8) | response.at(25);
	dlms_context_type.max_send_pdu_size = (static_cast<uint16_t>(response.at(27)) << 8) | response.at(28);
	dlms_context_type.dlms_version_number = response.at(30);
	dlms_context_type.quality_of_service = (int8_t)response.at(32);
	dlms_context_type.cyphering_info_size = response.at(34);
	for (int i = 0; i < dlms_context_type.cyphering_info_size; i++)
		dlms_context_type.cyphering_info[i] = response.at(35 + i);
	std::memcpy(structure, &dlms_context_type, sizeof(dlms_context_type));
}

void StructParsing::ReceiveSendDestinationAndMethod (const std::vector<uint8_t> &response, void* structure)
{
	Structures::SendDestinationAndMethod send_dest_method;
	send_dest_method.transport_service = response.at(19);
	send_dest_method.destination_size = response.at(21);
	for (int i = 0; i < send_dest_method.destination_size; i++)
		send_dest_method.destination[i] = response.at(22 + i);

	send_dest_method.message = response.at(23 + send_dest_method.destination_size);
	std::memcpy(structure, &send_dest_method, sizeof(send_dest_method));
}

void StructParsing::ReceiveRepetitionDelay (const std::vector<uint8_t> &response, void* structure)
{
	Structures::RepetitionDelay rep_delay;
	rep_delay.repetition_delay_min = (static_cast<uint16_t>(response.at(19)) << 8) | response.at(20);
	rep_delay.repetition_delay_exponent = (static_cast<uint16_t>(response.at(22)) << 8) | response.at(23);
	rep_delay.repetition_delay_max = (static_cast<uint16_t>(response.at(25)) << 8) | response.at(26);
	std::memcpy(structure, &rep_delay, sizeof(rep_delay));
}

void StructParsing::ReceiveConfirmationParameters (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ConfirmationParameters confirm_param;
	uint8_t index = 19;
	ReceiveDateTime(response, confirm_param.confirm_start_date, index);
	index = 33;
	ReceiveDoubleLongUnsigned(response, confirm_param.confirm_interval, index);
	std::memcpy(structure, &confirm_param, sizeof(confirm_param));
}

void StructParsing::ReceiveActionSet (const std::vector<uint8_t> &response, void* structure)
{
	Structures::ActionSet action;
	for (int i = 0; i < 6; i++)
		action.action_up.scipt_logical_name[i] = response.at(22 + i);
	action.action_up.script_selector = (static_cast<uint16_t>(response.at(29)) << 8) | response.at(30);

	for (int i = 0; i < 6; i++)
		action.action_down.scipt_logical_name[i] = response.at(35 + i);
	action.action_down.script_selector = (static_cast<uint16_t>(response.at(42)) << 8) | response.at(43);

	std::memcpy(structure, &action, sizeof(action));
}

void StructParsing::ReceiveObjectDefinition (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ObjectDefinition> object_def(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; i++)
	{
		object_def[i].class_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		for (int j = 0; j < 6; j++)
			object_def[i].logical_name[j] = response.at(index + 4 + j);
		index += 13;
	}
	auto dst = static_cast<Structures::ObjectDefinition*>(*structure_array);
	std::memcpy(dst, object_def.data(), array_size * sizeof(object_def[0]));
}

void StructParsing::ReceiveRegisterActMask (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::RegisterActMask> register_act_mask(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; i++)
	{
		register_act_mask[i].mask_name_size = response.at(index);
		index++;
		for (int j = 0; j < register_act_mask[i].mask_name_size; j++)
			register_act_mask[i].mask_name[j] = response.at(index + j);

		index += register_act_mask[i].mask_name_size + 1;
		register_act_mask[i].index_list_size = response.at(index);
		index += 2;
		for(int j = 0; j < register_act_mask[i].index_list_size; j++)
			register_act_mask[i].index_list[j] = response.at(index + j * 2);

		index += register_act_mask[i].index_list_size * 2 + 2;
	}
	auto dst = static_cast<Structures::RegisterActMask*>(*structure_array);
	std::memcpy(dst, register_act_mask.data(), array_size * sizeof(register_act_mask[0]));
}

void StructParsing::ReceiveArrayScript (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::Script> script(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; i++)
	{
		script[i].script_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;
		script[i].actions_size = response.at(index);
		index += 4;
		// Parse array_size number of ActionSpecification entries
		for(int k = 0; k < script[i].actions_size; k++)
		{
			script[i].actions[k].service_id = response.at(index);
			index += 2;
			script[i].actions[k].class_id = (response.at(index) << 8) | response.at(index + 1);
			index += 4;
			for(int j = 0; j < 6; j++)
				script[i].actions[k].logical_name[j] = response.at(index + j);

			index += 7;
			script[i].actions[k].index = (int8_t)response.at(index);
			index++;
			script[i].actions[k].parameter_type = response.at(index);
			switch(script[i].actions[k].parameter_type)
			{
			case 0:
				script[i].actions[k].parameter_size = 0;
				break;
			case 3:
			case 15:
			case 17:
			case 22:
				script[i].actions[k].parameter_size = 1;
				break;
			case 16:
			case 18:
				script[i].actions[k].parameter_size = 2;
				break;
			case 4:
			case 9:
			{
				index++;
				script[i].actions[k].parameter_size = response.at(index);
				break;
			}
			case 5:
			case 6:
			case 23:
				script[i].actions[k].parameter_size = 4;
				break;
			case 20:
			case 21:
			case 24:
				script[i].actions[k].parameter_size = 8;
				break;
			}
			index++;

			for(int j = 0; j < script[i].actions[k].parameter_size; j++)
				script[i].actions[k].parameter[j] = response.at(index + j);

			index += script[i].actions[k].parameter_size + 3;
		}
	}
	auto dst = static_cast<Structures::Script*>(*structure_array);
	std::memcpy(dst, script.data(), array_size * sizeof(script[0]));
}

void StructParsing::ReceiveScheduleTableEntry (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ScheduleTableEntry> schedule_table_entry(array_size);
	uint8_t index = 21;

	for (int i = 0; i < array_size; ++i)
	{
		schedule_table_entry[i].index = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;

		schedule_table_entry[i].enable = (response.at(index) != 0);
		index += 3;

		for (int j = 0; j < 6; j++)
			schedule_table_entry[i].script_logical_name[j] = response.at(index + j);

		index += 7;
		schedule_table_entry[i].script_selector = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;

		ReceiveDateTime(response, schedule_table_entry[i].switch_time, index);
		index += 2;

		schedule_table_entry[i].validity_window = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;

		uint8_t length = response.at(index++);
		schedule_table_entry[i].exec_weekdays_size = length / 8 + (length % 8 != 0) ? 1 : 0;
		for (int j = 0; j < schedule_table_entry[i].exec_weekdays_size; j++)
			schedule_table_entry[i].exec_weekdays[j] = response.at(index + j);

		index += schedule_table_entry[i].exec_weekdays_size + 1;
		length = response.at(index++);
		schedule_table_entry[i].exec_specdays_size = length / 8 + (length % 8 != 0) ? 1 : 0;
		for (int j = 0; j < schedule_table_entry[i].exec_specdays_size; j++)
			schedule_table_entry[i].exec_specdays[j] = response.at(index + j);

		index += schedule_table_entry[i].exec_specdays_size + 1;
		ReceiveDateTime(response, schedule_table_entry[i].begin_date, index);
		index += 2;

		ReceiveDateTime(response, schedule_table_entry[i].end_date, index);
		index += 4;
	}
	auto dst = static_cast<Structures::ScheduleTableEntry*>(*structure_array);
	std::memcpy(dst, schedule_table_entry.data(), array_size * sizeof(schedule_table_entry[0]));
}

void StructParsing::ReceiveSpecDayEntry (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::SpecDayEntry> spec_day_entry(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		spec_day_entry[i].index = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;
		ReceiveDateTime(response, spec_day_entry[i].specialday_date, index);
		spec_day_entry[i].day_id = response.at(index + 2);
		index += 5;
	}
	auto dst = static_cast<Structures::SpecDayEntry*>(*structure_array);
	std::memcpy(dst, spec_day_entry.data(), array_size * sizeof(spec_day_entry[0]));
}

void StructParsing::ReceiveSeason (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::Season> season(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		season[i].season_profile_name_size = response.at(index++);
		for (int j = 0; j < season[i].season_profile_name_size; j++)
			season[i].season_profile_name[j] = response.at(index + j);

		index += season[i].season_profile_name_size + 1;
		ReceiveDateTime(response, season[i].season_start, index);
		index += 2;
		season[i].week_name_size = response.at(index++);
		for (int j = 0; j < season[i].week_name_size; j++)
			season[i].week_name[j] = response.at(index + j);

		index += season[i].week_name_size + 3;
	}
	auto dst = static_cast<Structures::Season*>(*structure_array);
	std::memcpy(dst, season.data(), array_size * sizeof(season[0]));
}

void StructParsing::ReceiveWeekProfile (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::WeekProfile> week_profile(array_size);
	uint8_t index = 21;

	for (int i = 0; i < array_size; ++i)
	{
		week_profile[i].week_profile_name_size = response.at(index++);
		for (int j = 0; j < week_profile[i].week_profile_name_size; j++)
			week_profile[i].week_profile_name[j] = response.at(index + j);

		index += week_profile[i].week_profile_name_size + 1;
		week_profile[i].monday = response.at(index);
		index += 2;
		week_profile[i].tuesday = response.at(index);
		index += 2;
		week_profile[i].wednesday = response.at(index);
		index += 2;
		week_profile[i].thursday = response.at(index);
		index += 2;
		week_profile[i].friday = response.at(index);
		index += 2;
		week_profile[i].saturday = response.at(index);
		index += 2;
		week_profile[i].sunday = response.at(index);
		index += 4;
	}

	auto dst = static_cast<Structures::WeekProfile*>(*structure_array);
	std::memcpy(dst, week_profile.data(), array_size * sizeof(week_profile[0]));

}

void StructParsing::ReceiveDayProfile (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::DayProfile> day_profile(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		day_profile[i].day_id = response.at(index);
		index += 2;
		day_profile[i].day_schedule_size = response.at(index++);
		for(int k = 0; k < day_profile[i].day_schedule_size; k++)
		{
			index += 3;
			ReceiveDateTime(response, day_profile[i].day_schedule[k].start_time, index);
			index += 3;
			for(int j = 0; j < 6; j++)
				day_profile[i].day_schedule[k].script_logical_name[j] = response.at(index + j);

			index += 7;
			day_profile[i].day_schedule[k].script_selector = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
			index += 2;
		}
		index += 3;
	}
	auto dst = static_cast<Structures::DayProfile*>(*structure_array);
	std::memcpy(dst, day_profile.data(), array_size * sizeof(day_profile[0]));
}

void StructParsing::ReceiveArrayActionSet (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ActionSet> action_set(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		index += 3;
		for(int j = 0; j < 6; j++)
			action_set[i].action_up.scipt_logical_name[j] = response.at(index + j);

		index += 7;
		action_set[i].action_up.script_selector = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 6;
		for(int j = 0; j < 6; j++)
			action_set[i].action_down.scipt_logical_name[j] = response.at(index + j);

		index += 7;
		action_set[i].action_down.script_selector = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 5;
	}
	auto dst = static_cast<Structures::ActionSet*>(*structure_array);
	std::memcpy(dst, action_set.data(), array_size * sizeof(action_set[0]));
}

void StructParsing::ReceiveExecutionTimeDate (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ExecutionTimeDate> execution_time_date(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		ReceiveDateTime(response, execution_time_date[i].time, index);
		index += 2;
		ReceiveDateTime(response, execution_time_date[i].date, index);
		index += 4;
	}
	auto dst = static_cast<Structures::ExecutionTimeDate*>(*structure_array);
	std::memcpy(dst, execution_time_date.data(), array_size * sizeof(execution_time_date[0]));
}

void StructParsing::ReceiveImageToActivateInfoElement (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ImageToActivateInfoElement> image_to_activate_info_elem(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		ReceiveDoubleLongUnsigned(response, image_to_activate_info_elem[i].image_to_activate_size, index);
		index += 5;
		image_to_activate_info_elem[i].image_to_activate_id_size = response.at(index++);
		for(int j = 0; j < image_to_activate_info_elem[i].image_to_activate_id_size; j++)
			image_to_activate_info_elem[i].image_to_activate_identification[j] = response.at(index + j);

		index += image_to_activate_info_elem[i].image_to_activate_id_size + 1;
		image_to_activate_info_elem[i].image_to_activate_sig_size = response.at(index++);

		for(int j = 0; j < image_to_activate_info_elem[i].image_to_activate_sig_size; j++)
			image_to_activate_info_elem[i].image_to_activate_signature[j] = response.at(index + j);

		index += image_to_activate_info_elem[i].image_to_activate_sig_size + 3;
	}
	auto dst = static_cast<Structures::ImageToActivateInfoElement*>(*structure_array);
	std::memcpy(dst, image_to_activate_info_elem.data(), array_size * sizeof(image_to_activate_info_elem[0]));
}

void StructParsing::ReceiveActionItem (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ActionItem> action_item(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		index++;
		for(int j = 0; j < 6; j++)
			action_item[i].scipt_logical_name[j] = response.at(index + j);

		index += 7;
		action_item[i].script_selector = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 5;
	}
	auto dst = static_cast<Structures::ActionItem*>(*structure_array);
	std::memcpy(dst, action_item.data(), array_size * sizeof(action_item[0]));
}

void StructParsing::ReceiveArrayCaptureObjectDefinition (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::CaptureObjectDefinition> capture_object_def(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		capture_object_def[i].class_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 4;
		for(int j = 0; j < 6; j++)
			capture_object_def[i].logical_name[j] = response.at(index + j);

		index += 7;
		capture_object_def[i].attribute_index = (int8_t)response.at(index);
		index += 2;
		capture_object_def[i].data_index = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 5;
	}
	auto dst = static_cast<Structures::CaptureObjectDefinition*>(*structure_array);
	std::memcpy(dst, capture_object_def.data(), array_size * sizeof(capture_object_def[0]));
}

void StructParsing::ReceiveObjectList (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ObjectList> object_list(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		object_list[i].class_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 3;
		object_list[i].version = response.at(index);
		index += 3;
		for(int j = 0; j < 6; j++)
			object_list[i].logical_name[j] = response.at(index + j);

		index += 9;
		object_list[i].access_rights.attr_access_item_size = response.at(index++);
		for (int j = 0; j < object_list[i].access_rights.attr_access_item_size; j++)
		{
			index += 3;
			object_list[i].access_rights.attr_access_item[j].atribute_id = (int8_t)response.at(index);
			index += 2;
			object_list[i].access_rights.attr_access_item[j].access_mode = response.at(index++);
			if (response.at(index) == 1)
			{
				index++;
				object_list[i].access_rights.attr_access_item[j].access_selectors_size = response.at(index);
				for (int k = 0; k < response.at(index); k++)
				{
					index += 2;
					object_list[i].access_rights.attr_access_item[j].access_selectors[k] = (int8_t)response.at(index);
				}
			}
			index++;
		}
		index++;
		object_list[i].access_rights.method_access_item_size = response.at(index++);
		for (int j = 0; j < object_list[i].access_rights.method_access_item_size; j++)
		{
			index += 3;
			object_list[i].access_rights.method_access_item[j].method_id = (int8_t)response.at(index);
			index += 2;
			object_list[i].access_rights.method_access_item[j].access_mode = response.at(index);
			index++;
		}
		index += 3;
	}
	auto dst = static_cast<Structures::ObjectList*>(*structure_array);
	std::memcpy(dst, object_list.data(), array_size * sizeof(object_list[0]));
}

void StructParsing::ReceivePushObjectDefinition (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::PushObjectDefinition> push_obj_def(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		push_obj_def[i].class_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 4;
		for (int j = 0; j < 6; j++)
			push_obj_def[i].logical_name[j] = response.at(index + j);

		index += 7;
		push_obj_def[i].attribute_index = (int8_t)response.at(index);
		index += 2;
		push_obj_def[i].data_index = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
		index += 5;
		push_obj_def[i].restriction_elem.restriction_type = response.at(index);
		if (push_obj_def[i].restriction_elem.restriction_type == 0)
			index += 3;
		else if (push_obj_def[i].restriction_elem.restriction_type == 1)
		{
			index += 4;
			ReceiveDateTime(response, push_obj_def[i].restriction_elem.restriction_by_date.from_date, index);
			index += 2;
			ReceiveDateTime(response, push_obj_def[i].restriction_elem.restriction_by_date.to_date, index);
			index += 2;
		}
		else if (push_obj_def[i].restriction_elem.restriction_type == 2)
		{
			index += 4;
			ReceiveDoubleLongUnsigned(response, push_obj_def[i].restriction_elem.restriction_by_entry.from_entry, index);
			index += 5;
			ReceiveDoubleLongUnsigned(response, push_obj_def[i].restriction_elem.restriction_by_entry.to_entry, index);
			index += 5;
		}

		push_obj_def[i].column_elem_size = response.at(index);
		index++;
		for (int j = 0; j < push_obj_def[i].column_elem_size; j++)
		{
			index += 3;
			push_obj_def[i].column_elem[j].class_id = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
			index += 4;
			for (int k = 0; k < 6; k++)
				push_obj_def[i].column_elem[j].logical_name[k] = response.at(index + k);

			index += 7;
			push_obj_def[i].column_elem[j].attribute_index = (int8_t)response.at(index);
			index += 2;
			push_obj_def[i].column_elem[j].data_index = (static_cast<uint16_t>(response.at(index)) << 8) | response.at(index + 1);
			index += 2;
		}
		index += 3;
	}
	auto dst = static_cast<Structures::PushObjectDefinition*>(*structure_array);
	std::memcpy(dst, push_obj_def.data(), array_size * sizeof(push_obj_def[0]));
}

void StructParsing::ReceiveWindowElement (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::WindowElement> win_elem(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		ReceiveDateTime(response, win_elem[i].start_time, index);
		index += 2;
		ReceiveDateTime(response, win_elem[i].end_time, index);
		index += 4;
	}
	auto dst = static_cast<Structures::WindowElement*>(*structure_array);
	std::memcpy(dst, win_elem.data(), array_size * sizeof(win_elem[0]));
}

void StructParsing::ReceiveProtectionParametersElement (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::ProtectionParametersElement> protect_params(array_size);
	uint8_t index = 21;
	for (int i = 0; i < array_size; ++i)
	{
		protect_params[i].protect_type = response.at(index);
		index += 4;
		protect_params[i].protect_options.transaction_id_size = response.at(index++);
		for (int j = 0; j < protect_params[i].protect_options.transaction_id_size; j++)
			protect_params[i].protect_options.transaction_id[j] = response.at(index + j);

		index += protect_params[i].protect_options.transaction_id_size + 1;
		protect_params[i].protect_options.origin_sys_title_size = response.at(index++);
		for (int j = 0; j < protect_params[i].protect_options.origin_sys_title_size; j++)
			protect_params[i].protect_options.origin_sys_title[j] = response.at(index + j);

		index += protect_params[i].protect_options.origin_sys_title_size + 1;
		protect_params[i].protect_options.recipient_sys_title_size = response.at(index++);
		for (int j = 0; j < protect_params[i].protect_options.recipient_sys_title_size; j++)
			protect_params[i].protect_options.recipient_sys_title[j] = response.at(index + j);

		index += protect_params[i].protect_options.recipient_sys_title_size + 1;
		protect_params[i].protect_options.other_info_size = response.at(index++);
		for (int j = 0; j < protect_params[i].protect_options.other_info_size; j++)
			protect_params[i].protect_options.other_info[j] = response.at(index + j);

		index += protect_params[i].protect_options.other_info_size;
		protect_params[i].protect_options.key_info_elem.key_info_type = response.at(index);
		if (protect_params[i].protect_options.key_info_elem.key_info_type == 0)
		{
			protect_params[i].protect_options.key_info_elem.identified_key_info = response.at(index + 2);
			index += 6;
		}
		else if (protect_params[i].protect_options.key_info_elem.key_info_type == 1)
		{
			index += 4;
			protect_params[i].protect_options.key_info_elem.wrapped_key_info.kek_id = response.at(index);
			index += 2;
			protect_params[i].protect_options.key_info_elem.wrapped_key_info.key_ciphered_data_size = response.at(index++);
			for (int j = 0; j < protect_params[i].protect_options.key_info_elem.wrapped_key_info.key_ciphered_data_size; j++)
				protect_params[i].protect_options.key_info_elem.wrapped_key_info.key_ciphered_data[j] = response.at(index + j);

			index += protect_params[i].protect_options.key_info_elem.wrapped_key_info.key_ciphered_data_size + 3;
		}
		else if (protect_params[i].protect_options.key_info_elem.key_info_type == 2)
		{
			index += 4;
			protect_params[i].protect_options.key_info_elem.agreed_key_info.key_param_size = response.at(index++);
			for (int j = 0; j < protect_params[i].protect_options.key_info_elem.agreed_key_info.key_param_size; j++)
				protect_params[i].protect_options.key_info_elem.agreed_key_info.key_param[j] = response.at(index + j);

			index += protect_params[i].protect_options.key_info_elem.agreed_key_info.key_param_size + 1;
			protect_params[i].protect_options.key_info_elem.agreed_key_info.key_ciphered_data_size = response.at(index++);
			for (int j = 0; j < protect_params[i].protect_options.key_info_elem.agreed_key_info.key_ciphered_data_size; j++)
				protect_params[i].protect_options.key_info_elem.agreed_key_info.key_ciphered_data[j] = response.at(index + j);

			index += protect_params[i].protect_options.key_info_elem.agreed_key_info.key_ciphered_data_size + 3;
		}
	}
	auto dst = static_cast<Structures::ProtectionParametersElement*>(*structure_array);
	std::memcpy(dst, protect_params.data(), array_size * sizeof(protect_params[0]));
}

void StructParsing::ReceiveCertificateInfo (const std::vector<uint8_t> &response, void** structure_array, const uint8_t &array_size)
{
	std::vector<Structures::CertificateInfo> cert_info(array_size);
	uint8_t index = 21;

	for (int i = 0; i < array_size; ++i)
	{
		cert_info[i].cert_entity = response.at(index);
		index += 2;
		cert_info[i].cert_type = response.at(index);
		index += 2;

		cert_info[i].serial_number_size = response.at(index++);
		for (int j = 0; j < cert_info[i].serial_number_size; j++)
			cert_info[i].serial_number[j] = response.at(index + j);
		index += cert_info[i].serial_number_size + 1;

		cert_info[i].issuer_size = response.at(index++);
		for (int j = 0; j < cert_info[i].issuer_size; j++)
			cert_info[i].issuer[j] = response.at(index + j);
		index += cert_info[i].issuer_size + 1;

		cert_info[i].subject_size = response.at(index++);
		for (int j = 0; j < cert_info[i].subject_size; j++)
			cert_info[i].subject[j] = response.at(index + j);
		index += cert_info[i].subject_size + 1;

		cert_info[i].subject_alt_name_size = response.at(index++);
		for (int j = 0; j < cert_info[i].subject_alt_name_size; j++)
			cert_info[i].subject_alt_name[j] = response.at(index + j);
		index += cert_info[i].subject_alt_name_size + 3;
	}

	auto dst = static_cast<Structures::CertificateInfo*>(*structure_array);
	std::memcpy(dst, cert_info.data(), array_size * sizeof(cert_info[0]));
}

void StructParsing::SendScalerUnit (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ScalerUnit* scalar_unit = static_cast<Structures::ScalerUnit*>(structure);
	info_field.insert(info_field.end(), {2, 2, 15, (uint8_t)scalar_unit->scaler, 22, scalar_unit->unit});
}

void StructParsing::SendValueDefinition (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ValueDefinition* val_def = static_cast<Structures::ValueDefinition*>(structure);
	info_field.insert(info_field.end(), {2, 3});
	info_field.push_back(18);
	SendLongUnsigned(info_field, val_def->class_id);
	info_field.insert(info_field.end(), {9, 6});
	for (int i = 0; i < 6; i++)
		info_field.push_back((uint8_t)val_def->logical_name[i]);
	info_field.insert(info_field.end(), {15, (uint8_t)val_def->attribute_index});
}

void StructParsing::SendScript (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ActionItem* script = static_cast<Structures::ActionItem*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, 6});
	for (int i = 0; i < 6; i++)
		info_field.push_back((uint8_t)script->scipt_logical_name[i]);
	info_field.push_back(18);
	SendLongUnsigned(info_field, script->script_selector);
}

void StructParsing::SendEmergencyProfile (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::EmergencyProfile* emerg_profile = static_cast<Structures::EmergencyProfile*>(structure);
	info_field.insert(info_field.end(), {2, 3});
	info_field.push_back(18);
	SendLongUnsigned(info_field, emerg_profile->emerg_profile_id);
	info_field.insert(info_field.end(), {9, 12});
	SendDateTime(info_field, emerg_profile->emerg_activation_time);
	info_field.push_back(6);
	SendDoubleLongUnsigned(info_field, emerg_profile->emerg_duration);
}

void StructParsing::SendCaptureObjectDefinition (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::CaptureObjectDefinition* cap_obj_def = static_cast<Structures::CaptureObjectDefinition*>(structure);
	info_field.insert(info_field.end(), {2, 4});
	info_field.push_back(18);
	SendLongUnsigned(info_field, cap_obj_def->class_id);
	info_field.insert(info_field.end(), {9, 6});
	for (int j = 0; j < 6; j++)
		info_field.push_back((uint8_t)cap_obj_def->logical_name[j]);
	info_field.insert(info_field.end(), {15, (uint8_t)cap_obj_def->attribute_index});
	info_field.push_back(18);
	SendLongUnsigned(info_field, cap_obj_def->data_index);
}

void StructParsing::SendAssociatedPartnersType (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::AssociatedPartnersType* associate_part_type = static_cast<Structures::AssociatedPartnersType*>(structure);
	info_field.insert(info_field.end(), {2, 2, 15, (uint8_t)associate_part_type->client_SAP});
	info_field.push_back(18);
	SendLongUnsigned(info_field, associate_part_type->server_SAP);
}

void StructParsing::SendContextNameStructure (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ContextNameStructure* context_name = static_cast<Structures::ContextNameStructure*>(structure);
	info_field.insert(info_field.end(), {2, 7,
										17, context_name->joint_iso_ctt_element,
										17, context_name->country_element});
	info_field.push_back(18);
	SendLongUnsigned(info_field, context_name->country_name_element);
	info_field.insert(info_field.end(), {17, context_name->identified_org_elem,
										17, context_name->DLMS_UA_element,
										17, context_name->application_context_element,
										17, context_name->context_id_element});
}

void StructParsing::SendxDLMSContextType (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::xDLMSContextType* dlms_context = static_cast<Structures::xDLMSContextType*>(structure);
	info_field.insert(info_field.end(), {2, 6, 4, 24});
	for (int i = 0; i < 3; i++)
		info_field.push_back(dlms_context->conformance[i]);
	info_field.push_back(18);
	SendLongUnsigned(info_field, dlms_context->max_receive_pdu_size);
	info_field.push_back(18);
	SendLongUnsigned(info_field, dlms_context->max_send_pdu_size);
	info_field.insert(info_field.end(), {17, dlms_context->dlms_version_number,
										15, (uint8_t)dlms_context->quality_of_service,
										9, dlms_context->cyphering_info_size});
	for (int i = 0; i < dlms_context->cyphering_info_size; i++)
		info_field.push_back((uint8_t)dlms_context->cyphering_info[i]);
}

void StructParsing::SendDestinationAndMethod (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::SendDestinationAndMethod* dest_method = static_cast<Structures::SendDestinationAndMethod*>(structure);
	info_field.insert(info_field.end(), {2, 3,
										22, dest_method->transport_service,
										9, dest_method->destination_size});
	for (int i = 0; i < dest_method->destination_size; i++)
		info_field.push_back(dest_method->destination[i]);
	info_field.insert(info_field.end(), {22, dest_method->message});
}

void StructParsing::SendRepetitionDelay (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::RepetitionDelay* rep_delay = static_cast<Structures::RepetitionDelay*>(structure);
	info_field.insert(info_field.end(), {2, 3});
	info_field.push_back(18);
	SendLongUnsigned(info_field, rep_delay->repetition_delay_min);
	info_field.push_back(18);
	SendLongUnsigned(info_field, rep_delay->repetition_delay_exponent);
	info_field.push_back(18);
	SendLongUnsigned(info_field, rep_delay->repetition_delay_max);
}

void StructParsing::SendConfirmationParameters (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ConfirmationParameters* confirm_params = static_cast<Structures::ConfirmationParameters*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, 12});
	SendDateTime(info_field, confirm_params->confirm_start_date);
	info_field.push_back(6);
	SendDoubleLongUnsigned(info_field, confirm_params->confirm_interval);
}

void StructParsing::SendActionSet (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ActionSet* action = static_cast<Structures::ActionSet*>(structure);
	info_field.insert(info_field.end(), {2, 2, 2, 2, 9, 6});
	for (int i = 0; i < 6; i++)
		info_field.push_back((uint8_t)action->action_up.scipt_logical_name[i]);
	info_field.push_back(18);
	SendLongUnsigned(info_field, action->action_up.script_selector);

	info_field.insert(info_field.end(), {2, 2, 9, 6});
	for (int i = 0; i < 6; i++)
		info_field.push_back((uint8_t)action->action_down.scipt_logical_name[i]);
	info_field.push_back(18);
	SendLongUnsigned(info_field, action->action_down.script_selector);
}

void StructParsing::SendObjectDefinition (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ObjectDefinition* obj_def = static_cast<Structures::ObjectDefinition*>(structure);
	info_field.insert(info_field.end(), {2, 2});
	info_field.push_back(18);
	SendLongUnsigned(info_field, obj_def->class_id);

	info_field.insert(info_field.end(), {9, 6});
	for (int j = 0; j < 6; j++)
		info_field.push_back((uint8_t)obj_def->logical_name[j]);
}

void StructParsing::SendRegisterActMask (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::RegisterActMask* reg_act_mask = static_cast<Structures::RegisterActMask*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, reg_act_mask->mask_name_size});
	for (int j = 0; j < reg_act_mask->mask_name_size; j++)
		info_field.push_back((uint8_t)reg_act_mask->mask_name[j]);

	info_field.insert(info_field.end(), {1, reg_act_mask->index_list_size});
	for (int j = 0; j < reg_act_mask->index_list_size; j++)
		info_field.insert(info_field.end(), {17, reg_act_mask->index_list[j]});
}

void StructParsing::SendArrayScript (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::Script* script = static_cast<Structures::Script*>(structure);
	info_field.insert(info_field.end(), {2, 2});
	info_field.push_back(18);
	SendLongUnsigned(info_field, script->script_id);

	info_field.insert(info_field.end(), {1, script->actions_size});
	for (int j = 0; j < script->actions_size; j++)
	{
		info_field.insert(info_field.end(), {2, 5, 22});
		info_field.push_back(script->actions[j].service_id);
		info_field.push_back(18);
		SendLongUnsigned(info_field, script->actions[j].class_id);

		info_field.insert(info_field.end(), {9, 6});
		for (int k = 0; k < 6; k++)
			info_field.push_back((uint8_t)script->actions[j].logical_name[k]);

		info_field.insert(info_field.end(), {15, (uint8_t)script->actions[j].index});

		info_field.push_back(script->actions[j].parameter_type);
		for (int k = 0; k < script->actions[j].parameter_size; k++)
			info_field.push_back(script->actions[j].parameter[k]);
	}
}

void StructParsing::SendScheduleTableEntry (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ScheduleTableEntry* schedule_table = static_cast<Structures::ScheduleTableEntry*>(structure);
	info_field.insert(info_field.end(), {2, 10});
	info_field.push_back(18);
	SendLongUnsigned(info_field, schedule_table->index);
	info_field.insert(info_field.end(), {3, static_cast<uint8_t>((schedule_table->enable) ? 1 : 0)});
	info_field.insert(info_field.end(), {9, 6});
	for (int j = 0; j < 6; j ++)
		info_field.push_back((uint8_t)schedule_table->script_logical_name[j]);

	info_field.push_back(18);
	SendLongUnsigned(info_field, schedule_table->script_selector);
	info_field.insert(info_field.end(), {9, 4});
	SendDateTime(info_field, schedule_table->switch_time);
	info_field.push_back(18);
	SendLongUnsigned(info_field, schedule_table->validity_window);

	info_field.insert(info_field.end(), {4, schedule_table->exec_weekdays_size});
	uint8_t length = schedule_table->exec_weekdays_size / 8 + (schedule_table->exec_weekdays_size % 8 != 0) ? 1 : 0;
	for (int j = 0; j < length; j++)
		info_field.push_back(schedule_table->exec_weekdays[j]);

	info_field.insert(info_field.end(), {4, schedule_table->exec_specdays_size});
	length = schedule_table->exec_specdays_size / 8 + (schedule_table->exec_specdays_size % 8 != 0) ? 1 : 0;
	for (int j = 0; j < length; j++)
		info_field.insert(info_field.end(), {4, schedule_table->exec_specdays[j]});

	info_field.insert(info_field.end(), {9, 5});
	SendDateTime(info_field, schedule_table->begin_date);
	info_field.insert(info_field.end(), {9, 5});
	SendDateTime(info_field, schedule_table->end_date);
}

void StructParsing::SendSpecDayEntry (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::SpecDayEntry* spec_day = static_cast<Structures::SpecDayEntry*>(structure);
	info_field.insert(info_field.end(), {2, 3});
	info_field.push_back(18);
	SendLongUnsigned(info_field, spec_day->index);
	info_field.insert(info_field.end(), {9, 5});
	SendDateTime(info_field, spec_day->specialday_date);
	info_field.insert(info_field.end(), {17, spec_day->day_id});
}

void StructParsing::SendSeason (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::Season* season = static_cast<Structures::Season*>(structure);
	info_field.insert(info_field.end(), {2, 3,
										9, season->season_profile_name_size});
	for (int j = 0; j < season->season_profile_name_size; j++)
		info_field.push_back((uint8_t)season->season_profile_name[j]);

	info_field.insert(info_field.end(), {9, 12});
	SendDateTime(info_field, season->season_start);

	info_field.insert(info_field.end(), {9, season->week_name_size});
	for (int j = 0; j < season->week_name_size; j++)
		info_field.push_back((uint8_t)season->week_name[j]);
}

void StructParsing::SendWeekProfile (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::WeekProfile* week_profile = static_cast<Structures::WeekProfile*>(structure);
	info_field.insert(info_field.end(), {2, 8,
										9, week_profile->week_profile_name_size});
	for (int j = 0; j < week_profile->week_profile_name_size; j++)
		info_field.push_back((uint8_t)week_profile->week_profile_name[j]);

	info_field.insert(info_field.end(), {17, week_profile->monday,
										17, week_profile->tuesday,
										17, week_profile->wednesday,
										17, week_profile->thursday,
										17, week_profile->friday,
										17, week_profile->saturday,
										17, week_profile->sunday});
}

void StructParsing::SendDayProfile (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::DayProfile* day_profile = static_cast<Structures::DayProfile*>(structure);
	info_field.insert(info_field.end(), {2, 3,
										17, day_profile->day_id,
										1, day_profile->day_schedule_size});
	for (int j = 0; j < day_profile->day_schedule_size; j++)
	{
		info_field.insert(info_field.end(), {2, 3, 9, 4});
		SendDateTime(info_field, day_profile->day_schedule[j].start_time);

		info_field.insert(info_field.end(), {9, 6});
		for (int k = 0; k < 6; k++)
			info_field.push_back((uint8_t)day_profile->day_schedule[j].script_logical_name[k]);

		info_field.push_back(18);
		SendLongUnsigned(info_field, day_profile->day_schedule[j].script_selector);
	}
}

void StructParsing::SendExecutionTimeDate (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ExecutionTimeDate* time_date = static_cast<Structures::ExecutionTimeDate*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, 4});
	SendDateTime(info_field, time_date->time);
	info_field.insert(info_field.end(), {9, 5});
	SendDateTime(info_field, time_date->date);
}

void StructParsing::SendImageToActivateInfoElement (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ImageToActivateInfoElement* info_image = static_cast<Structures::ImageToActivateInfoElement*>(structure);
	info_field.insert(info_field.end(), {2, 3});
	info_field.push_back(6);
	SendDoubleLongUnsigned(info_field, info_image->image_to_activate_size);

	info_field.insert(info_field.end(), {9, info_image->image_to_activate_id_size});
	for (int j = 0; j < info_image->image_to_activate_id_size; j++)
		info_field.push_back((uint8_t)info_image->image_to_activate_identification[j]);

	info_field.insert(info_field.end(), {9, info_image->image_to_activate_sig_size});
	for (int j = 0; j < info_image->image_to_activate_sig_size; j++)
		info_field.push_back((uint8_t)info_image->image_to_activate_signature[j]);
}

void StructParsing::SendObjectList (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ObjectList* obj_list = static_cast<Structures::ObjectList*>(structure);
	info_field.insert(info_field.end(), {2, 4});
	info_field.push_back(18);
	SendLongUnsigned(info_field, obj_list->class_id);

	info_field.insert(info_field.end(), {17, obj_list->version});
	info_field.insert(info_field.end(), {9, 6});
	for (int j = 0; j < 6; j++)
		info_field.push_back((uint8_t)obj_list->logical_name[j]);

	info_field.insert(info_field.end(), {2, 2,
										1, obj_list->access_rights.attr_access_item_size});
	for (int j = 0; j < obj_list->access_rights.attr_access_item_size; j++)
	{
		info_field.insert(info_field.end(), {2, 3,
											15, (uint8_t)obj_list->access_rights.attr_access_item[j].atribute_id,
											22, obj_list->access_rights.attr_access_item[j].access_mode});
		if (obj_list->access_rights.attr_access_item[j].access_selectors_type == 1)
		{
			info_field.insert(info_field.end(), {1, obj_list->access_rights.attr_access_item[j].access_selectors_size});
			for (int k = 0; k < obj_list->access_rights.attr_access_item[j].access_selectors_size; k++)
				info_field.insert(info_field.end(), {15, (uint8_t)obj_list->access_rights.attr_access_item[j].access_selectors[k]});
		}
		else
			info_field.push_back(0);
	}

	info_field.insert(info_field.end(), {1, obj_list->access_rights.method_access_item_size});
	for (int j = 0; j < obj_list->access_rights.method_access_item_size; j++)
	{
		info_field.insert(info_field.end(), {2, 2,
											15, (uint8_t)obj_list->access_rights.method_access_item[j].method_id,
											22, obj_list->access_rights.method_access_item[j].access_mode});
	}
}

void StructParsing::SendPushObjectDefinition (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::PushObjectDefinition* push_obj_def = static_cast<Structures::PushObjectDefinition*>(structure);
	info_field.insert(info_field.end(), {2, 6});
	info_field.push_back(18);
	SendLongUnsigned(info_field, push_obj_def->class_id);

	info_field.insert(info_field.end(), {9, 6});
	for (int j = 0; j < 6; j++)
		info_field.push_back(push_obj_def->logical_name[j]);

	info_field.insert(info_field.end(), {15, (uint8_t)push_obj_def->attribute_index});
	info_field.push_back(18);
	SendLongUnsigned(info_field, push_obj_def->data_index);

	info_field.insert(info_field.end(), {2, 2,
										22, push_obj_def->restriction_elem.restriction_type});
	if (push_obj_def->restriction_elem.restriction_type == 0)
		info_field.push_back(0);
	else if (push_obj_def->restriction_elem.restriction_type == 1)
	{
		info_field.insert(info_field.end(), {2, 2, 9, 12});
		SendDateTime(info_field, push_obj_def->restriction_elem.restriction_by_date.from_date);
		info_field.insert(info_field.end(), {9, 12});
		SendDateTime(info_field, push_obj_def->restriction_elem.restriction_by_date.to_date);
	}
	else if (push_obj_def->restriction_elem.restriction_type == 2)
	{
		info_field.insert(info_field.end(), {2, 2, 6});
		SendDoubleLongUnsigned(info_field, push_obj_def->restriction_elem.restriction_by_entry.from_entry);
		info_field.push_back(6);
		SendDoubleLongUnsigned(info_field, push_obj_def->restriction_elem.restriction_by_entry.to_entry);
	}
	info_field.insert(info_field.end(), {1, push_obj_def->column_elem_size});
	for (int j = 0; j < push_obj_def->column_elem_size; j++)
	{
		info_field.insert(info_field.end(), {2, 4});
		info_field.push_back(18);
		SendLongUnsigned(info_field, push_obj_def->column_elem[j].class_id);

		info_field.insert(info_field.end(), {9, 6});
		for (int k = 0; k < 6; k++)
			info_field.push_back((uint8_t)push_obj_def->column_elem[j].logical_name[k]);

		info_field.insert(info_field.end(), {15, (uint8_t)push_obj_def->column_elem[j].attribute_index});
		info_field.push_back(18);
		SendLongUnsigned(info_field, push_obj_def->column_elem[j].data_index);
	}
}

void StructParsing::SendWindowElement (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::WindowElement* win_elem = static_cast<Structures::WindowElement*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, 12});
	SendDateTime(info_field, win_elem->start_time);
	info_field.insert(info_field.end(), {9, 12});
	SendDateTime(info_field, win_elem->end_time);
}

void StructParsing::SendProtectionParametersElement (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::ProtectionParametersElement* protect_param = static_cast<Structures::ProtectionParametersElement*>(structure);
	info_field.insert(info_field.end(), {2, 2,
										22, protect_param->protect_type,
										2, 5, 9, protect_param->protect_options.transaction_id_size});
	for (int j = 0; j < protect_param->protect_options.transaction_id_size; j++)
		info_field.push_back(protect_param->protect_options.transaction_id[j]);

	info_field.insert(info_field.end(), {9, protect_param->protect_options.origin_sys_title_size});
	for (int j = 0; j < protect_param->protect_options.origin_sys_title_size; j++)
		info_field.push_back((uint8_t)protect_param->protect_options.origin_sys_title[j]);

	info_field.insert(info_field.end(), {9, protect_param->protect_options.recipient_sys_title_size});
	for (int j = 0; j < protect_param->protect_options.recipient_sys_title_size; j++)
		info_field.push_back((uint8_t)protect_param->protect_options.recipient_sys_title[j]);

	info_field.insert(info_field.end(), {9, protect_param->protect_options.other_info_size});
	for (int j = 0; j < protect_param->protect_options.other_info_size; j++)
		info_field.push_back((uint8_t)protect_param->protect_options.other_info[j]);

	info_field.insert(info_field.end(), {2, 2,
										22, protect_param->protect_options.key_info_elem.key_info_type});

	if (protect_param->protect_options.key_info_elem.key_info_type == 0)
		info_field.insert(info_field.end(), {22, protect_param->protect_options.key_info_elem.identified_key_info});
	else if (protect_param->protect_options.key_info_elem.key_info_type == 1)
	{
		info_field.insert(info_field.end(), {2, 2,
											22, protect_param->protect_options.key_info_elem.wrapped_key_info.kek_id,
											9, protect_param->protect_options.key_info_elem.wrapped_key_info.key_ciphered_data_size});
		for (int j = 0; j < protect_param->protect_options.key_info_elem.wrapped_key_info.key_ciphered_data_size; j++)
			info_field.push_back((uint8_t)protect_param->protect_options.key_info_elem.wrapped_key_info.key_ciphered_data[j]);
	}
	else if (protect_param->protect_options.key_info_elem.key_info_type == 2)
	{
		info_field.insert(info_field.end(), {2, 2,
											9, protect_param->protect_options.key_info_elem.agreed_key_info.key_param_size});
		for (int j = 0; j < protect_param->protect_options.key_info_elem.agreed_key_info.key_param_size; j++)
			info_field.push_back((uint8_t)protect_param->protect_options.key_info_elem.agreed_key_info.key_param[j]);

		info_field.insert(info_field.end(), {9, protect_param->protect_options.key_info_elem.agreed_key_info.key_ciphered_data_size});
		for (int j = 0; j < protect_param->protect_options.key_info_elem.agreed_key_info.key_ciphered_data_size; j++)
			info_field.push_back((uint8_t)protect_param->protect_options.key_info_elem.agreed_key_info.key_ciphered_data[j]);
	}
}

void StructParsing::SendCertificateInfo (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::CertificateInfo* cert_info = static_cast<Structures::CertificateInfo*>(structure);
	info_field.insert(info_field.end(), {2, 6,
										22, cert_info->cert_entity,
										22, cert_info->cert_type,
										9, cert_info->serial_number_size});
	for (int j = 0; j < cert_info->serial_number_size; j++)
		info_field.push_back((uint8_t)cert_info->serial_number[j]);

	info_field.insert(info_field.end(), {9, cert_info->issuer_size});
	for (int j = 0; j < cert_info->issuer_size; j++)
		info_field.push_back((uint8_t)cert_info->issuer[j]);

	info_field.insert(info_field.end(), {9, cert_info->subject_size});
	for (int j = 0; j < cert_info->subject_size; j++)
		info_field.push_back((uint8_t)cert_info->subject[j]);

	info_field.insert(info_field.end(), {9, cert_info->subject_alt_name_size});
	for (int j = 0; j < cert_info->subject_alt_name_size; j++)
		info_field.push_back((uint8_t)cert_info->subject_alt_name[j]);
}

void StructParsing::SendAdjustingTime (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::AdjustingTime* adjust_time = static_cast<Structures::AdjustingTime*>(structure);
	info_field.insert(info_field.end(), {2, 3, 9, 12});
	SendDateTime(info_field, adjust_time->preset_time);
	info_field.insert(info_field.end(), {9, 12});
	SendDateTime(info_field, adjust_time->validity_interval_start);
	info_field.insert(info_field.end(), {9, 12});
	SendDateTime(info_field, adjust_time->validity_interval_end);
}

void StructParsing::SendEnableDisable (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::EnableDisable* enable = static_cast<Structures::EnableDisable*>(structure);
	info_field.insert(info_field.end(), {2, 4});
	info_field.push_back(18);
	SendLongUnsigned(info_field, enable->first_index_a);
	info_field.push_back(18);
	SendLongUnsigned(info_field, enable->last_index_a);
	info_field.push_back(18);
	SendLongUnsigned(info_field, enable->first_index_b);
	info_field.push_back(18);
	SendLongUnsigned(info_field, enable->last_index_b);
}

void StructParsing::SendDataDelete (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::DataDelete* data_delete = static_cast<Structures::DataDelete*>(structure);
	info_field.insert(info_field.end(), {2, 2});
	info_field.push_back(18);
	SendLongUnsigned(info_field, data_delete->first_index);
	info_field.push_back(18);
	SendLongUnsigned(info_field, data_delete->last_index);
}

void StructParsing::SendImageTransfer (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::Image* image = static_cast<Structures::Image*>(structure);
	info_field.insert(info_field.end(), {2, 2, 9, image->image_id_size});
	for (int j = 0; j < image->image_id_size; j++)
		info_field.push_back((uint8_t)image->image_id[j]);

	info_field.push_back(6);
	SendDoubleLongUnsigned(info_field, image->image_size);
}

void StructParsing::SendImageBlock (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::Image* image = static_cast<Structures::Image*>(structure);
	info_field.insert(info_field.end(), {2, 2});
	info_field.push_back(6);
	SendDoubleLongUnsigned(info_field, image->image_size);

	info_field.insert(info_field.end(), {9, image->image_id_size});
	for (int j = 0; j < image->image_id_size; j++)
		info_field.push_back((uint8_t)image->image_id[j]);
}

void StructParsing::SendCertificateIdentification (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::CertificateIdentification* cert_id = static_cast<Structures::CertificateIdentification*>(structure);
	info_field.insert(info_field.end(), {2, 2,
										22, cert_id->cert_id_type});
	if (cert_id->cert_id_type == 0)
	{
		info_field.insert(info_field.end(), {22, cert_id->entity.cert_entity,
											22, cert_id->entity.cert_type,
											9, cert_id->entity.sys_title_size});
		for (int j = 0; j < cert_id->entity.sys_title_size; j++)
			info_field.push_back(cert_id->entity.sys_title[j]);
	}
	else if (cert_id->cert_id_type == 1)
	{
		info_field.insert(info_field.end(), {9, cert_id->serial.serial_num_size});
		for (int j = 0; j < cert_id->serial.serial_num_size; j++)
			info_field.push_back(cert_id->serial.serial_num[j]);
		info_field.insert(info_field.end(), {9, cert_id->serial.issuer_size});
		for (int j = 0; j < cert_id->serial.issuer_size; j++)
			info_field.push_back((uint8_t)cert_id->serial.issuer[j]);
	}
}

void StructParsing::SendRequestAction (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::RequestAction* req_act = static_cast<Structures::RequestAction*>(structure);
	info_field.insert(info_field.end(), {2, 2,
										17, req_act->request_actor,
										4, req_act->request_act_list_size});
	uint8_t length = req_act->request_act_list_size / 8 + (req_act->request_act_list_size % 8 != 0) ? 1 : 0;
	for (int j = 0; j < length; j++)
		info_field.push_back(req_act->request_act_list[j]);
}

void StructParsing::SendKey (std::vector<uint8_t> &info_field, void* structure)
{
	Structures::Key* key = static_cast<Structures::Key*>(structure);
	info_field.insert(info_field.end(), {2, 2,
										22, key->key_id,
										9, key->key_wrapped_size});
	for (int j = 0; j < key->key_wrapped_size; j++)
		info_field.push_back((uint8_t)key->key_wrapped[j]);
}
