#include "data_parsing.h"
#include "data_parsing_api.h"

static DataParsing parser;

extern "C" {

	int16_t  dp_get_int16(uint8_t* value) { return parser.GetInt16(value); }
	uint16_t dp_get_uint16(uint8_t* value) { return parser.GetUint16(value); }
	int32_t  dp_get_int32(uint8_t* value) { return parser.GetInt32(value); }
	uint32_t dp_get_uint32(uint8_t* value) { return parser.GetUint32(value); }
	int64_t  dp_get_int64(uint8_t* value) { return parser.GetInt64(value); }
	uint64_t dp_get_uint64(uint8_t* value) { return parser.GetUint64(value); }
	float    dp_get_float(uint8_t* value) { return parser.GetFloat(value); }
	double   dp_get_double(uint8_t* value) { return parser.GetDouble(value); }
}
