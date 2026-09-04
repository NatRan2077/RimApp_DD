#include "spodes_client_c_api.h"
#include "spodes_client.h"

#include <vector>
#include <string>
#include <cstring>

#ifdef __cplusplus
extern "C" {
#endif



    typedef Structures::DateTime DateTime;

    SpodesClient* SpodesClient_Create(void) {
        return new SpodesClient();
    }

    void SpodesClient_Destroy(SpodesClient* client) {
        delete client;
    }

    const char* SpodesClient_GetErrorMessage(SpodesClient* client) {
        return client->GetErrorMessage();
    }

    uint8_t SpodesClient_GetCurrentElemNumber(SpodesClient* client) {
        return client->GetCurrentElemNumber();
    }

    bool SpodesClient_ConnectSerialPort(SpodesClient* client, const SerialPortParamsC* params) {
        SpodesClient::SerialPortParams cppParams;
        cppParams.port_name = params->port_name ? params->port_name : std::string();
        cppParams.baudrate = params->baudrate;
        cppParams.parity = params->parity;
        cppParams.stop_bits = params->stop_bits;
        cppParams.hardware_flow_control = params->hardware_flow_control;
        cppParams.timeout_s = params->timeout_s;
        cppParams.timeout_ms = params->timeout_ms;
        return client->ConnectSerialPort(cppParams);
    }

    bool SpodesClient_Disconnect(SpodesClient* client) {
        return client->Disconnect();
    }

    bool SpodesClient_SetNormalResponseMode(SpodesClient* client,
        const ConnectionAddressesC* addrC,
        const ConnectionParamsC* paramsC) {
        SpodesClient::ConnectionAddresses addr;
        addr.source_address = addrC->source_address;
        addr.logical_address = addrC->logical_address;
        addr.physical_address = addrC->physical_address;

        SpodesClient::ConnectionParams cppParams;
        SpodesClient::ConnectionParams* pParams = nullptr;
        if (paramsC) {
            cppParams.max_info_length_transmit = paramsC->max_info_length_transmit;
            cppParams.max_info_length_receive = paramsC->max_info_length_receive;
            cppParams.window_size_transmit = paramsC->window_size_transmit;
            cppParams.window_size_receive = paramsC->window_size_receive;
            pParams = &cppParams;
        }
        return client->SetNormalResponseMode(addr, pParams);
    }

    bool SpodesClient_EstablishConnectionRequest(SpodesClient* client,
        uint8_t security_level,
        const SecurityParamsC* secC,
        uint16_t client_max_receive_pdu_size,
        const OptionalParamsC* optC) {
        SpodesClient::SecurityParams secParams;
        SpodesClient::SecurityParams* pSec = nullptr;
        if (secC) {
            if (secC->password) secParams.password = secC->password;
            if (secC->key)      secParams.key = secC->key;
            secParams.has_ciphering = secC->has_ciphering;
            pSec = &secParams;
        }
        SpodesClient::OptionalParams optParams;
        SpodesClient::OptionalParams* pOpt = nullptr;
        if (optC) {
            std::memcpy(optParams.calling_ap_title, optC->calling_ap_title, sizeof(optParams.calling_ap_title));
            optParams.calling_ae_invocation_id = optC->calling_ae_invocation_id;
            optParams.auth = optC->auth;
            optParams.ciph = optC->ciph;
            std::memcpy(optParams.auth_key, optC->auth_key, sizeof(optParams.auth_key));
            std::memcpy(optParams.ciph_key, optC->ciph_key, sizeof(optParams.ciph_key));
            std::memcpy(optParams.sys_title, optC->sys_title, sizeof(optParams.sys_title));
            std::memcpy(optParams.invoke_counter, optC->invoke_counter, sizeof(optParams.invoke_counter));
            pOpt = &optParams;
        }
        return client->EstablishConnectionRequest(security_level,
            pSec,
            client_max_receive_pdu_size,
            pOpt);
    }

    bool SpodesClient_EstablishConnectionResponse(SpodesClient* client) {
        return client->EstablishConnectionResponse();
    }

    bool SpodesClient_SendDisconnectRequest(SpodesClient* client) {
        return client->SendDisconnectRequest();
    }

    uint8_t SpodesClient_ReceiveUnnumberedAcknowledge(SpodesClient* client) {
        return client->ReceiveUnnumberedAcknowledge();
    }

    bool SpodesClient_MultipleGetRequest(SpodesClient* client,
        const RequestParamsC* paramsC,
        uint8_t param_count) {
        std::vector<SpodesClient::RequestParams> vec;
        vec.reserve(param_count);
        for (uint8_t i = 0; i < param_count; ++i) {
            const RequestParamsC& c = paramsC[i];
            SpodesClient::RequestParams cpp;
            cpp.class_id = c.class_id;
            cpp.instance_id = c.instance_id ? c.instance_id : std::string();
            cpp.attribute_id = c.attribute_id;
            cpp.retry = c.retry;
            vec.push_back(cpp);
        }
        return client->MultipleGetRequest(vec.data(), param_count);
    }

    bool SpodesClient_GetRequest(SpodesClient* client,
        const RequestParamsC* paramsC,
        void* selective_access,
        uint8_t selector) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->GetRequest(cpp, selective_access, selector);
    }

    bool SpodesClient_GetResponseByte(SpodesClient* client, uint8_t* value) {
        return client->GetResponseByte(*value);
    }

    bool SpodesClient_GetResponseInt(SpodesClient* client, uint64_t* value) {
        return client->GetResponseInt(*value);
    }

    bool SpodesClient_GetResponseFloat(SpodesClient* client, double* value) {
        return client->GetResponseFloat(*value);
    }

    bool SpodesClient_GetResponseString(SpodesClient* client,
        char* result,
        uint8_t* str_size) {
        uint8_t size = *str_size;
        bool ok = client->GetResponseString(result, size);
        *str_size = size;
        return ok;
    }

    bool SpodesClient_GetResponseDateTime(SpodesClient* client, DateTimeC* dtC) {
        DateTime dt;
        bool ok = client->GetResponseDateTime(dt);
        if (ok) {
            dtC->date = dt.date;
            dtC->time = dt.time;
            dtC->year = dt.year;
            dtC->month = dt.month;
            dtC->month_day = dt.month_day;
            dtC->week_day = dt.week_day;
            dtC->hour = dt.hour;
            dtC->minute = dt.minute;
            dtC->second = dt.second;
            dtC->hundredths = dt.hundredths;
            dtC->deviation = dt.deviation;
            dtC->clock_status = dt.clock_status;
        }
        return ok;
    }

    bool SpodesClient_GetResponseStructure(SpodesClient* client,
        void* structure,
        uint8_t struct_id) {
        return client->GetResponseStructure(structure, struct_id);
    }

    bool SpodesClient_GetResponseArrayByte(SpodesClient* client,
        uint8_t* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list) {
        uint8_t as = *array_size;
        uint8_t vt = *value_type;
        bool ok = client->GetResponseArrayByte(result, as, vt, is_list);
        *array_size = as;
        *value_type = vt;
        return ok;
    }

    bool SpodesClient_GetResponseArrayInt(SpodesClient* client,
        uint64_t* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list) {
        uint8_t as = *array_size;
        uint8_t vt = *value_type;
        bool ok = client->GetResponseArrayInt(result, as, vt, is_list);
        *array_size = as;
        *value_type = vt;
        return ok;
    }

    bool SpodesClient_GetResponseArrayFloat(SpodesClient* client,
        double* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list) {
        uint8_t as = *array_size;
        uint8_t vt = *value_type;
        bool ok = client->GetResponseArrayFloat(result, as, vt, is_list);
        *array_size = as;
        *value_type = vt;
        return ok;
    }

    bool SpodesClient_GetResponseArrayString(SpodesClient* client,
        char** result,
        uint8_t* array_size,
        uint8_t* element_size) {
        uint8_t as = *array_size;
        uint8_t es = *element_size;
        bool ok = client->GetResponseArrayString(result, as, es);
        *array_size = as;
        *element_size = es;
        return ok;
    }

    bool SpodesClient_GetResponseArrayBitString(SpodesClient* client,
        uint8_t** result,
        uint8_t* array_size,
        uint8_t* element_size) {
        uint8_t as = *array_size;
        uint8_t es = *element_size;
        bool ok = client->GetResponseArrayBitString(result, as, es);
        *array_size = as;
        *element_size = es;
        return ok;
    }

    bool SpodesClient_GetResponseArrayDateTime(SpodesClient* client,
        DateTimeC* dtC,
        uint8_t* array_size) {
        uint8_t as = *array_size;
        std::vector<DateTime> dtv(as);
        bool ok = client->GetResponseArrayDateTime(dtv.data(), as);
        if (ok) {
            for (uint8_t i = 0; i < as; ++i) {
                dtC[i].date = dtv[i].date;
                dtC[i].time = dtv[i].time;
                dtC[i].year = dtv[i].year;
                dtC[i].month = dtv[i].month;
                dtC[i].month_day = dtv[i].month_day;
                dtC[i].week_day = dtv[i].week_day;
                dtC[i].hour = dtv[i].hour;
                dtC[i].minute = dtv[i].minute;
                dtC[i].second = dtv[i].second;
                dtC[i].hundredths = dtv[i].hundredths;
                dtC[i].deviation = dtv[i].deviation;
                dtC[i].clock_status = dtv[i].clock_status;
            }
            *array_size = as;
        }
        return ok;
    }

    bool SpodesClient_GetResponseArrayStructure(SpodesClient* client,
        void** structure_array,
        uint8_t struct_id,
        uint8_t* array_size) {
        uint8_t as = *array_size;
        bool ok = client->GetResponseArrayStructure(structure_array, struct_id, as);
        *array_size = as;
        return ok;
    }

    bool SpodesClient_GetResponseBuffer(SpodesClient* client,
        uint8_t values[300],
        uint8_t types[300],
        uint8_t lengths[300],
        int* array_size,
        uint8_t* elem_count) {
        int as = *array_size;
        uint8_t ec = *elem_count;
        bool ok = client->GetResponseBuffer(
            reinterpret_cast<uint8_t(*)[300]>(values),
            reinterpret_cast<uint8_t(*)[300]>(types),
            reinterpret_cast<uint8_t(*)[300]>(lengths),
            as,
            ec);
        *array_size = as;
        *elem_count = ec;
        return ok;
    }

    bool SpodesClient_SetRequestByte(SpodesClient* client,
        const RequestParamsC* paramsC,
        uint8_t value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestByte(cpp, value, value_type);
    }

    bool SpodesClient_SetRequestInt(SpodesClient* client,
        const RequestParamsC* paramsC,
        uint64_t value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestInt(cpp, value, value_type);
    }

    bool SpodesClient_SetRequestFloat(SpodesClient* client,
        const RequestParamsC* paramsC,
        double value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestFloat(cpp, value, value_type);
    }

    bool SpodesClient_SetRequestString(SpodesClient* client,
        const RequestParamsC* paramsC,
        const char* value,
        uint8_t value_size) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestString(cpp, value, value_size);
    }

    bool SpodesClient_SetRequestDateTime(SpodesClient* client,
        const RequestParamsC* paramsC,
        const DateTimeC* dtC,
        bool date_format_available) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        DateTime dt;
        dt.date = dtC->date;
        dt.time = dtC->time;
        dt.year = dtC->year;
        dt.month = dtC->month;
        dt.month_day = dtC->month_day;
        dt.week_day = dtC->week_day;
        dt.hour = dtC->hour;
        dt.minute = dtC->minute;
        dt.second = dtC->second;
        dt.hundredths = dtC->hundredths;
        dt.deviation = dtC->deviation;
        dt.clock_status = dtC->clock_status;
        return client->SetRequestDateTime(cpp, dt, date_format_available);
    }

    bool SpodesClient_SetRequestStructure(SpodesClient* client,
        const RequestParamsC* paramsC,
        void* structure,
        uint8_t struct_id) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestStructure(cpp, structure, struct_id);
    }

    bool SpodesClient_SetRequestArrayByte(SpodesClient* client,
        const RequestParamsC* paramsC,
        const uint8_t* value_array,
        uint8_t array_size,
        uint8_t value_type,
        uint8_t element_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayByte(cpp, value_array, array_size, value_type, element_type);
    }

    bool SpodesClient_SetRequestArrayInt(SpodesClient* client,
        const RequestParamsC* paramsC,
        const uint64_t* value_array,
        uint8_t array_size,
        uint8_t element_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayInt(cpp, value_array, array_size, element_type);
    }

    bool SpodesClient_SetRequestArrayFloat(SpodesClient* client,
        const RequestParamsC* paramsC,
        const double* value_array,
        uint8_t array_size,
        uint8_t element_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayFloat(cpp, value_array, array_size, element_type);
    }

    bool SpodesClient_SetRequestArrayString(SpodesClient* client,
        const RequestParamsC* paramsC,
        const char** value_array,
        uint8_t array_size,
        uint8_t element_size) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayString(cpp, value_array, array_size, element_size);
    }

    bool SpodesClient_SetRequestArrayBitString(SpodesClient* client,
        const RequestParamsC* paramsC,
        const uint8_t** value_array,
        uint8_t array_size,
        uint8_t element_size,
        uint8_t elem_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayBitString(cpp, value_array, array_size, element_size, elem_type);
    }

    bool SpodesClient_SetRequestArrayDateTime(SpodesClient* client,
        const RequestParamsC* paramsC,
        const DateTimeC* dtC,
        uint8_t array_size,
        bool date_format_available) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        std::vector<DateTime> dtv(array_size);
        for (uint8_t i = 0; i < array_size; ++i) {
            dtv[i].date = dtC[i].date;
            dtv[i].time = dtC[i].time;
            dtv[i].year = dtC[i].year;
            dtv[i].month = dtC[i].month;
            dtv[i].month_day = dtC[i].month_day;
            dtv[i].week_day = dtC[i].week_day;
            dtv[i].hour = dtC[i].hour;
            dtv[i].minute = dtC[i].minute;
            dtv[i].second = dtC[i].second;
            dtv[i].hundredths = dtC[i].hundredths;
            dtv[i].deviation = dtC[i].deviation;
            dtv[i].clock_status = dtC[i].clock_status;
        }
        return client->SetRequestArrayDateTime(cpp, dtv.data(), array_size, date_format_available);
    }

    bool SpodesClient_SetRequestArrayStructure(SpodesClient* client,
        const RequestParamsC* paramsC,
        void** structure_array,
        uint8_t struct_id,
        uint8_t array_size) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->SetRequestArrayStructure(cpp, structure_array, struct_id, array_size);
    }

    bool SpodesClient_ActionRequestByte(SpodesClient* client,
        const RequestParamsC* paramsC,
        uint8_t value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestByte(cpp, value, value_type);
    }

    bool SpodesClient_ActionRequestInt(SpodesClient* client,
        const RequestParamsC* paramsC,
        uint64_t value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestInt(cpp, value, value_type);
    }

    bool SpodesClient_ActionRequestFloat(SpodesClient* client,
        const RequestParamsC* paramsC,
        double value,
        uint8_t value_type) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestFloat(cpp, value, value_type);
    }

    bool SpodesClient_ActionRequestString(SpodesClient* client,
        const RequestParamsC* paramsC,
        const char* value,
        uint8_t value_size) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestString(cpp, value, value_size);
    }

    bool SpodesClient_ActionRequestDateTime(SpodesClient* client,
        const RequestParamsC* paramsC,
        const DateTimeC* dtC,
        bool date_format_available) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        DateTime dt;
        dt.date = dtC->date;
        dt.time = dtC->time;
        dt.year = dtC->year;
        dt.month = dtC->month;
        dt.month_day = dtC->month_day;
        dt.week_day = dtC->week_day;
        dt.hour = dtC->hour;
        dt.minute = dtC->minute;
        dt.second = dtC->second;
        dt.hundredths = dtC->hundredths;
        dt.deviation = dtC->deviation;
        dt.clock_status = dtC->clock_status;
        return client->ActionRequestDateTime(cpp, dt, date_format_available);
    }

    bool SpodesClient_ActionRequestStructure(SpodesClient* client,
        const RequestParamsC* paramsC,
        void* structure,
        uint8_t struct_id) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestStructure(cpp, structure, struct_id);
    }

    bool SpodesClient_ActionRequestArrayStructure(SpodesClient* client,
        const RequestParamsC* paramsC,
        void** structure_array,
        uint8_t struct_id,
        uint8_t array_size) {
        SpodesClient::RequestParams cpp;
        cpp.class_id = paramsC->class_id;
        cpp.instance_id = paramsC->instance_id ? paramsC->instance_id : std::string();
        cpp.attribute_id = paramsC->attribute_id;
        cpp.retry = paramsC->retry;
        return client->ActionRequestArrayStructure(cpp, structure_array, struct_id, array_size);
    }

    bool SpodesClient_ReceiveConfirmationResponse(SpodesClient* client, bool is_action)
    {
        return client->ReceiveConfirmationResponse(is_action);        
    }

    SpodesMessageBuffer* SpodesClient_GetLastMessageEx(SpodesClient* client)
    {
        if (!client) return nullptr;
        
        auto* buffer = new SpodesMessageBuffer();
        int frames = 0;
        std::vector<uint8_t> v = client->GetLastMessage(frames);
        
        buffer->size = static_cast<int>(v.size());
        buffer->frame_count = frames;
        
        if (buffer->size > 0) {
            buffer->data = new uint8_t[buffer->size];
            std::copy(v.begin(), v.end(), buffer->data);
        } else {
            buffer->data = nullptr;
        }
        
        return buffer;
    }

    void SpodesClient_FreeMessageBuffer(SpodesMessageBuffer* buffer)
    {
        if (buffer)
        {
            if (buffer->data) delete[] buffer->data;
            delete buffer;
        }
    }


#ifdef __cplusplus
}
#endif
