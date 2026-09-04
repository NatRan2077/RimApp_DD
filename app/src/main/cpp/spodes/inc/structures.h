/*
 * structures.h
 *
 *  Created on: 4 июн. 2025 г.
 *      Author: habarova
 */

#ifndef INC_STRUCTURES_H_
#define INC_STRUCTURES_H_

#include <cstdint>

class Structures
{
public:

	struct DateTime
	{
		bool date = false;
		bool time = false;
		uint16_t year = 0;
		uint8_t month = 0;
		uint8_t month_day = 0;
		uint8_t week_day = 0;
		uint8_t hour = 0;
		uint8_t minute = 0;
		uint8_t second = 0;
		uint8_t hundredths = 0;
		uint16_t deviation = 0;
		uint8_t clock_status = 0;
	};

	struct ScalerUnit
	{
		int8_t scaler = 0;
		uint8_t unit = 0;
	};

	struct ObjectDefinition
	{
		uint16_t class_id = 0;
		char logical_name[6] = {};
	};

	struct RegisterActMask
	{
		char mask_name[32] = {};
		uint8_t mask_name_size = 0;
		uint8_t index_list[32] = {};
		uint8_t index_list_size = 0;
	};

	struct CaptureObjectDefinition
	{
		uint16_t class_id = 0;
		char logical_name[6] = {};
		int8_t attribute_index = 0;
		uint16_t data_index = 0;
	};

	struct AdjustingTime
	{
		DateTime preset_time;
		DateTime validity_interval_start;
		DateTime validity_interval_end;
	};

	struct ActionSpecification
	{
		uint8_t service_id = 0;
		uint16_t class_id = 0;
		char logical_name[6] = {};
		int8_t index = 0;
		//TODO расписать как пользоваться
		uint8_t parameter[32] = {};
		uint8_t parameter_size = 0;
		uint8_t parameter_type = 0;
	};

	struct Script
	{
		uint16_t script_id = 0;
		ActionSpecification actions[32] = {};
		uint8_t actions_size = 0;
	};

	struct ScheduleTableEntry
	{
		uint16_t index = 0;
		bool enable = 0;
		char script_logical_name[6] = {};
		uint16_t script_selector = {};
		DateTime switch_time;
		uint16_t validity_window = 0;
		uint8_t exec_weekdays[7] = {};
		uint8_t exec_weekdays_size = 0;
		uint8_t exec_specdays[4] = {};
		uint8_t exec_specdays_size = 0;
		DateTime begin_date;
		DateTime end_date;
	};

	struct SpecDayEntry
	{
		uint16_t index = 0;
		DateTime specialday_date;
		uint8_t day_id = 0;
	};

	struct EnableDisable
	{
		uint16_t first_index_a = 0;
		uint16_t last_index_a = 0;
		uint16_t first_index_b = 0;
		uint16_t last_index_b = 0;
	};

	struct DataDelete
	{
		uint16_t first_index = 0;
		uint16_t last_index = 0;
	};

	struct Season
	{
		char season_profile_name[32] = {};
		uint8_t season_profile_name_size = 0;
		DateTime season_start;
		char week_name[32] = {};
		uint8_t week_name_size = 0;
	};

	struct WeekProfile
	{
		char week_profile_name[32] = {};
		uint8_t week_profile_name_size = 0;
		uint8_t monday = 0;
		uint8_t tuesday = 0;
		uint8_t wednesday = 0;
		uint8_t thursday = 0;
		uint8_t friday = 0;
		uint8_t saturday = 0;
		uint8_t sunday = 0;
	};

	struct DayProfileAction
	{
		DateTime start_time;
		char script_logical_name[6] = {};
		uint16_t script_selector = 0;
	};

	struct DayProfile
	{
		uint8_t day_id;
		DayProfileAction day_schedule[32] = {};
		uint8_t day_schedule_size = 0;
	};

	struct AttributeAccessItem
	{
		int8_t atribute_id = 0;
		uint8_t access_mode = 0;
		uint8_t access_selectors_type = 0;
		int8_t access_selectors[100] = {};
		uint8_t access_selectors_size = 0;
	};

	struct MethodAccessItem
	{
		int8_t method_id = 0;
		uint8_t access_mode = 0;
	};

	struct AccessRight
	{
		AttributeAccessItem attr_access_item[100] = {};
		uint8_t attr_access_item_size = 0;
		MethodAccessItem method_access_item[100] = {};
		uint8_t method_access_item_size = 0;
	};

	struct ObjectList
	{
		uint16_t class_id;
		uint8_t version;
		char logical_name[6] = {};
		AccessRight access_rights;
	};

	struct AssociatedPartnersType
	{
		int8_t client_SAP = 0;
		uint16_t server_SAP = 0;
	};

	struct ContextNameStructure
	{
		uint8_t joint_iso_ctt_element = 0;
		uint8_t country_element = 0;
		uint16_t country_name_element = 0;
		uint8_t identified_org_elem = 0;
		uint8_t DLMS_UA_element = 0;
		uint8_t application_context_element = 0;
		uint8_t context_id_element = 0;
	};

	struct xDLMSContextType
	{
		uint8_t conformance[3] = {};
		uint16_t max_receive_pdu_size = 0;
		uint16_t max_send_pdu_size = 0;
		uint8_t dlms_version_number = 0;
		int8_t quality_of_service = 0;
		char cyphering_info[32] = {};
		uint8_t cyphering_info_size = 0;
	};

	struct ImageToActivateInfoElement
	{
		uint32_t image_to_activate_size = 0;
		char image_to_activate_identification[32] = {};
		uint8_t image_to_activate_id_size = 0;
		char image_to_activate_signature[32] = {};
		uint8_t image_to_activate_sig_size = 0;
	};

	struct Image
	{
		char image_id[32] = {};
		uint8_t image_id_size = 0;
		uint32_t image_size = 0;
	};

	struct RestrictionByEntry
	{
		uint32_t from_entry = 0;
		uint32_t to_entry = 0;
	};

	struct RestrictionByDate
	{
		DateTime from_date;
		DateTime to_date;
	};

	struct RestrictionElement
	{
		uint8_t restriction_type = 0;
		RestrictionByDate restriction_by_date;
		RestrictionByEntry restriction_by_entry;
	};

	struct PushObjectDefinition
	{
		uint16_t class_id = 0;
		char logical_name[6] = {};
		int8_t attribute_index = 0;
		uint16_t data_index;
		RestrictionElement restriction_elem;
		CaptureObjectDefinition column_elem[32] = {};
		uint8_t column_elem_size = 0;
	};

	struct SendDestinationAndMethod
	{
		uint8_t transport_service = 0;
		char destination[32] = {};
		uint8_t destination_size = 0;
		uint8_t message = 0;
	};

	struct WindowElement
	{
		DateTime start_time;
		DateTime end_time;
	};

	struct AgreedKeyInfoOptions
	{
		char key_param[32] = {};
		uint8_t key_param_size = 0;
		char key_ciphered_data[32] = {};
		uint8_t key_ciphered_data_size = 0;
	};

	struct WrappedKeyInfoOptions
	{
		uint8_t kek_id = 0;
		char key_ciphered_data[32] = {};
		uint8_t key_ciphered_data_size = 0;
	};

	struct KeyInfoElement
	{
		uint8_t key_info_type = 0;
		uint8_t identified_key_info = 0;
		WrappedKeyInfoOptions wrapped_key_info;
		AgreedKeyInfoOptions agreed_key_info;
	};

	struct ProtectionOptions
	{
		char transaction_id[32] = {};
		uint8_t transaction_id_size = 0;
		char origin_sys_title[32] = {};
		uint8_t origin_sys_title_size = 0;
		char recipient_sys_title[32] = {};
		uint8_t recipient_sys_title_size = 0;
		char other_info[32] = {};
		uint8_t other_info_size = 0;
		KeyInfoElement key_info_elem;
	};

	struct ProtectionParametersElement
	{
		uint8_t protect_type = 0;
		ProtectionOptions protect_options;
	};

	struct RequestAction
	{
		uint8_t request_actor = 0;
		uint8_t request_act_list[8] = {};
		uint8_t request_act_list_size = 0;
	};

	struct CertificateInfo
	{
		uint8_t cert_entity = 0;
		uint8_t cert_type = 0;
		char serial_number[32] = {};
		uint8_t serial_number_size = 0;
		char issuer[32] = {};
		uint8_t issuer_size = 0;
		char subject[32] = {};
		uint8_t subject_size = 0;
		char subject_alt_name[32] = {};
		uint8_t subject_alt_name_size = 0;
	};

	struct Key
	{
		uint8_t key_id = 0;
		char key_wrapped[32] = {};
		uint8_t key_wrapped_size = 0;
	};

	struct CertificateIdentificationBySerial
	{
		char serial_num[32] = {};
		uint8_t serial_num_size = 0;
		char issuer[32] = {};
		uint8_t issuer_size = 0;
	};

	struct CertificateIdentificationByEntity
	{
		uint8_t cert_entity = 0;
		uint8_t cert_type = 0;
		char sys_title[32] = {};
		uint8_t sys_title_size = 0;
	};

	struct CertificateIdentification
	{
		uint8_t cert_id_type = 0;
		CertificateIdentificationBySerial serial;
		CertificateIdentificationByEntity entity;
	};

	struct ValueDefinition
	{
		uint16_t class_id = 0;
		char logical_name[6] = {};
		int8_t attribute_index = 0;
	};

	struct EmergencyProfile
	{
		uint16_t emerg_profile_id = 0;
		DateTime emerg_activation_time;
		uint32_t emerg_duration = 0;
	};

	struct ActionItem
	{
		char scipt_logical_name[6] = {};
		uint16_t script_selector = 0;
	};

	struct ActionSet
	{
		ActionItem action_up;
		ActionItem action_down;
	};

	struct ExecutionTimeDate
	{
		DateTime time;
		DateTime date;
	};

	struct RepetitionDelay
	{
		uint16_t repetition_delay_min = 0;
		uint16_t repetition_delay_exponent = 0;
		uint16_t repetition_delay_max = 0;
	};

	struct ConfirmationParameters
	{
		DateTime confirm_start_date;
		uint32_t confirm_interval = 0;
	};

	//SELECTIVE ACCESS
	struct RangeDescriptor
	{
		CaptureObjectDefinition restricting_object;
		uint8_t value_type = 0;
		uint8_t value_length = 0;
		uint8_t from_value[32] = {};
		uint8_t to_value[32] = {};
		uint8_t array_size = 0;
		CaptureObjectDefinition selected_values[32] = {};
	};

	struct EntryDescriptor
	{
		uint32_t from_entry = 0;
		uint32_t to_entry = 0;
		uint16_t from_selected_value = 0;
		uint16_t to_selected_value = 0;
	};

	struct ClassList
	{
		uint8_t list_size = 0;
		uint16_t class_id[32] = {};
	};

	struct ObjectIdList
	{
		uint8_t list_size = 0;
		ObjectDefinition object_id[32] = {};
	};
};

#endif /* INC_STRUCTURES_H_ */
