/*
 * get_processing.h
 *
 *  Created on: 25 июл. 2025 г.
 *      Author: habarova
 */

#ifndef INC_GET_PROCESSING_H_
#define INC_GET_PROCESSING_H_

#include <iostream>
#include <vector>

/**
 * @brief Вспомогательные методы для обработки ответа (сервис GET)
 */
class GetProcessing
{
public:
	void ProcessResponseByte(const std::vector<uint8_t> &response, uint8_t &value);
	void ProcessResponseInt(const std::vector<uint8_t> &response, uint64_t &value, const unsigned long &result_size);
	void ProcessResponseFloat(const std::vector<uint8_t> &response, double &value, const unsigned long &result_size);
	void ProcessResponseArrayByte(const std::vector<uint8_t> &response, uint8_t* result, uint8_t &array_size, uint8_t &value_type);
	void ProcessResponseArrayInt(const std::vector<uint8_t> &response, uint64_t* result, uint8_t &array_size, uint8_t &value_type);
	void ProcessResponseArrayFloat(const std::vector<uint8_t> &response, double* result, uint8_t &array_size, uint8_t &value_type);
	void ProcessResponseArrayBitString(const std::vector<uint8_t> &response, uint8_t** result, uint8_t &array_size, uint8_t &element_size);
	void ProcessResponseBuffer(const std::vector<uint8_t> &response, uint8_t (*values)[300], uint8_t (*types)[300], uint8_t (*lengths)[300],
								int &array_size, uint8_t &elem_count, uint8_t &element_number);
};


#endif /* INC_GET_PROCESSING_H_ */
