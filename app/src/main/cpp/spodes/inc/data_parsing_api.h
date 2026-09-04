#ifndef DATA_PARSING_API_H
#define DATA_PARSING_API_H

#include <cstdint>

#if defined(_WIN32) || defined(_WIN64)
#ifdef DATA_PARSING_API_EXPORTS
#define DATA_PARSING_API __declspec(dllexport)
#else
#define DATA_PARSING_API __declspec(dllimport)
#endif
#else
#define DATA_PARSING_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

    DATA_PARSING_API int16_t  dp_get_int16(uint8_t* value);
    DATA_PARSING_API uint16_t dp_get_uint16(uint8_t* value);
    DATA_PARSING_API int32_t  dp_get_int32(uint8_t* value);
    DATA_PARSING_API uint32_t dp_get_uint32(uint8_t* value);
    DATA_PARSING_API int64_t  dp_get_int64(uint8_t* value);
    DATA_PARSING_API uint64_t dp_get_uint64(uint8_t* value);
    DATA_PARSING_API float    dp_get_float(uint8_t* value);
    DATA_PARSING_API double   dp_get_double(uint8_t* value);

#ifdef __cplusplus
}
#endif

#endif // DATA_PARSING_API_H
