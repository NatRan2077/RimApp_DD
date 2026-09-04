#ifndef INC_SPODES_CLIENT_C_API_H
#define INC_SPODES_CLIENT_C_API_H

#include <stdint.h>
#include <stdbool.h>


#ifdef SPODES_CLIENT_C_API_EXPORTS
#  define SPODES_CLIENT_C_API __declspec(dllexport)
#else
#  define SPODES_CLIENT_C_API __declspec(dllimport)
#endif



#ifdef __cplusplus
extern "C" {
#endif

#ifdef _WIN32 || __WIN64
#ifdef SPODESCLIENT_EXPORTS
#define SPODES_API __declspec(dllexport)
#else
#define SPODES_API __declspec(dllimport)
#endif
#else
#define SPODES_API
#endif

    /* Opaque handle to underlying C++ SpodesClient */
    typedef struct SpodesClient SpodesClient;

    /* ------------------------- Structures ------------------------- */

    typedef struct {
        const char* port_name;
        int baudrate;
        char parity;
        uint8_t stop_bits;
        bool hardware_flow_control;
        unsigned long timeout_s;
        unsigned long timeout_ms;
    } SerialPortParamsC;

    typedef struct {
        uint16_t max_info_length_transmit;
        uint16_t max_info_length_receive;
        uint8_t window_size_transmit;
        uint8_t window_size_receive;
    } ConnectionParamsC;

    typedef struct {
        uint16_t class_id;
        const char* instance_id;
        uint8_t attribute_id;
        bool retry;
    } RequestParamsC;

    typedef struct {
        const char* password;
        const char* key;
        bool has_ciphering;
    } SecurityParamsC;

    typedef struct {
        uint8_t calling_ap_title[8];
        uint32_t calling_ae_invocation_id;
        bool auth;
        bool ciph;
        uint8_t auth_key[16];
        uint8_t ciph_key[16];
        uint8_t sys_title[8];
        uint8_t invoke_counter[4];
    } OptionalParamsC;

    typedef struct {
        uint8_t source_address;
        uint8_t logical_address;
        uint8_t physical_address;
    } ConnectionAddressesC;

    typedef struct {
        bool date;
        bool time;
        uint16_t year;
        uint8_t month;
        uint8_t month_day;
        uint8_t week_day;
        uint8_t hour;
        uint8_t minute;
        uint8_t second;
        uint8_t hundredths;
        uint16_t deviation;
        uint8_t clock_status;
    } DateTimeC;

    /* ------------------------- Lifecycle ------------------------- */

    SPODES_API SpodesClient* SpodesClient_Create(void);
    SPODES_API void SpodesClient_Destroy(SpodesClient* client);

    /* ------------------------- Common helpers ------------------------- */

    SPODES_API const char* SpodesClient_GetErrorMessage(SpodesClient* client);
    SPODES_API uint8_t    SpodesClient_GetCurrentElemNumber(SpodesClient* client);

    /* ------------------------- Connection ------------------------- */

    SPODES_API bool SpodesClient_ConnectSerialPort(SpodesClient* client,
        const SerialPortParamsC* params);
    SPODES_API bool SpodesClient_Disconnect(SpodesClient* client);

    SPODES_API bool SpodesClient_SetNormalResponseMode(SpodesClient* client,
        const ConnectionAddressesC* addr,
        const ConnectionParamsC* params);
    SPODES_API bool SpodesClient_EstablishConnectionRequest(SpodesClient* client,
        uint8_t security_level,
        const SecurityParamsC* sec_params,
        uint16_t client_max_receive_pdu_size,
        const OptionalParamsC* optional_params);
    SPODES_API bool SpodesClient_EstablishConnectionResponse(SpodesClient* client);
    SPODES_API bool SpodesClient_SendDisconnectRequest(SpodesClient* client);
    SPODES_API uint8_t SpodesClient_ReceiveUnnumberedAcknowledge(SpodesClient* client);

    /* ------------------------- GET requests ------------------------- */

    SPODES_API bool SpodesClient_MultipleGetRequest(SpodesClient* client,
        const RequestParamsC* params,
        uint8_t param_count);
    SPODES_API bool SpodesClient_GetRequest(SpodesClient* client,
        const RequestParamsC* params,
        void* selective_access,
        uint8_t selector);

    SPODES_API bool SpodesClient_GetResponseByte(SpodesClient* client, uint8_t* value);
    SPODES_API bool SpodesClient_GetResponseInt(SpodesClient* client, uint64_t* value);
    SPODES_API bool SpodesClient_GetResponseFloat(SpodesClient* client, double* value);
    SPODES_API bool SpodesClient_GetResponseString(SpodesClient* client,
        char* result,
        uint8_t* str_size);
    SPODES_API bool SpodesClient_GetResponseDateTime(SpodesClient* client, DateTimeC* date_time);
    SPODES_API bool SpodesClient_GetResponseStructure(SpodesClient* client,
        void* structure,
        uint8_t struct_id);
    SPODES_API bool SpodesClient_GetResponseArrayByte(SpodesClient* client,
        uint8_t* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list);
    SPODES_API bool SpodesClient_GetResponseArrayInt(SpodesClient* client,
        uint64_t* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list);
    SPODES_API bool SpodesClient_GetResponseArrayFloat(SpodesClient* client,
        double* result,
        uint8_t* array_size,
        uint8_t* value_type,
        bool is_list);
    SPODES_API bool SpodesClient_GetResponseArrayString(SpodesClient* client,
        char** result,
        uint8_t* array_size,
        uint8_t* element_size);
    SPODES_API bool SpodesClient_GetResponseArrayBitString(SpodesClient* client,
        uint8_t** result,
        uint8_t* array_size,
        uint8_t* element_size);
    SPODES_API bool SpodesClient_GetResponseArrayDateTime(SpodesClient* client,
        DateTimeC* date_time,
        uint8_t* array_size);
    SPODES_API bool SpodesClient_GetResponseArrayStructure(SpodesClient* client,
        void** structure_array,
        uint8_t struct_id,
        uint8_t* array_size);
    SPODES_API bool SpodesClient_GetResponseBuffer(SpodesClient* client,
        uint8_t values[300],
        uint8_t types[300],
        uint8_t lengths[300],
        int* array_size,
        uint8_t* elem_count);



    /* Копирует «сырой» буфер последнего сообщения.
   Формат буфера: [len_hi][len_lo] + payload, затем следующий кадр и т.д.
   Возвращает ОБЩИЙ размер буфера в байтах.
   Если buffer == NULL или buffer_size == 0 — ничего не копирует, а просто возвращает нужный размер.
   frame_count (если не NULL) — заполняется количеством кадров (то самое total_length из C++).
*/
    typedef struct
    {
        uint8_t* data;
        int size;
        int frame_count;
    } SpodesMessageBuffer;

    SPODES_API void SpodesClient_FreeMessageBuffer(SpodesMessageBuffer* buffer);
    SPODES_API SpodesMessageBuffer* SpodesClient_GetLastMessageEx(SpodesClient* client);


    /* ------------------------- SET requests ------------------------- */

    SPODES_API bool SpodesClient_SetRequestByte(SpodesClient* client,
        const RequestParamsC* params,
        uint8_t value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_SetRequestInt(SpodesClient* client,
        const RequestParamsC* params,
        uint64_t value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_SetRequestFloat(SpodesClient* client,
        const RequestParamsC* params,
        double value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_SetRequestString(SpodesClient* client,
        const RequestParamsC* params,
        const char* value,
        uint8_t value_size);
    SPODES_API bool SpodesClient_SetRequestDateTime(SpodesClient* client,
        const RequestParamsC* params,
        const DateTimeC* date_time,
        bool date_format_available);
    SPODES_API bool SpodesClient_SetRequestStructure(SpodesClient* client,
        const RequestParamsC* params,
        void* structure,
        uint8_t struct_id);
    SPODES_API bool SpodesClient_SetRequestArrayByte(SpodesClient* client,
        const RequestParamsC* params,
        const uint8_t* value_array,
        uint8_t array_size,
        uint8_t value_type,
        uint8_t element_type);
    SPODES_API bool SpodesClient_SetRequestArrayInt(SpodesClient* client,
        const RequestParamsC* params,
        const uint64_t* value_array,
        uint8_t array_size,
        uint8_t element_type);
    SPODES_API bool SpodesClient_SetRequestArrayFloat(SpodesClient* client,
        const RequestParamsC* params,
        const double* value_array,
        uint8_t array_size,
        uint8_t element_type);
    SPODES_API bool SpodesClient_SetRequestArrayString(SpodesClient* client,
        const RequestParamsC* params,
        const char** value_array,
        uint8_t array_size,
        uint8_t element_size);
    SPODES_API bool SpodesClient_SetRequestArrayBitString(SpodesClient* client,
        const RequestParamsC* params,
        const uint8_t** value_array,
        uint8_t array_size,
        uint8_t element_size,
        uint8_t elem_type);
    SPODES_API bool SpodesClient_SetRequestArrayDateTime(SpodesClient* client,
        const RequestParamsC* params,
        const DateTimeC* date_time,
        uint8_t array_size,
        bool date_format_available);
    SPODES_API bool SpodesClient_SetRequestArrayStructure(SpodesClient* client,
        const RequestParamsC* params,
        void** structure_array,
        uint8_t struct_id,
        uint8_t array_size);

    /* ------------------------- ACTION requests ------------------------- */

    SPODES_API bool SpodesClient_ActionRequestByte(SpodesClient* client,
        const RequestParamsC* params,
        uint8_t value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_ActionRequestInt(SpodesClient* client,
        const RequestParamsC* params,
        uint64_t value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_ActionRequestFloat(SpodesClient* client,
        const RequestParamsC* params,
        double value,
        uint8_t value_type);
    SPODES_API bool SpodesClient_ActionRequestString(SpodesClient* client,
        const RequestParamsC* params,
        const char* value,
        uint8_t value_size);
    SPODES_API bool SpodesClient_ActionRequestDateTime(SpodesClient* client,
        const RequestParamsC* params,
        const DateTimeC* date_time,
        bool date_format_available);
    SPODES_API bool SpodesClient_ActionRequestStructure(SpodesClient* client,
        const RequestParamsC* params,
        void* structure,
        uint8_t struct_id);
    SPODES_API bool SpodesClient_ActionRequestArrayStructure(SpodesClient* client,
        const RequestParamsC* params,
        void** structure_array,
        uint8_t struct_id,
        uint8_t array_size);

    /* ------------------------- Confirmation ------------------------- */

    SPODES_API bool SpodesClient_ReceiveConfirmationResponse(SpodesClient* client,
        bool is_action);


        

#ifdef __cplusplus
}
#endif

#endif /* SPODES_CLIENT_C_API_H */