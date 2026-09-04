/*
 * errors.h
 *
 *  Created on: 24 апр. 2025 г.
 *      Author: habarova
 */

#ifndef INC_ERRORS_H_
#define INC_ERRORS_H_

#include <map>
#include <string>

class Errors
{
public:
	Errors() = delete;
	~Errors() = delete;
	Errors(const Errors&) = delete;
	Errors& operator=(const Errors&) = delete;

	static const std::map<uint8_t, std::string> kDataAccessResult_;

	static const std::map<uint8_t, std::string> kActionResult_;

	static const std::map<uint8_t, std::string> kFrameRejectMessage_;

	static const std::map<uint8_t, std::string> kResultSourceDiagnostic_;

	static const std::map<uint8_t, std::string> kConfirmedServiceErrors_;

	static const std::map<uint8_t, std::string> kServiceErrorApplicationReference_;

	static const std::map<uint8_t, std::string> kServiceErrorHardwareResource_;

	static const std::map<uint8_t, std::string> kServiceErrorVdeState_;

	static const std::map<uint8_t, std::string> kServiceErrorService_;

	static const std::map<uint8_t, std::string> kServiceErrorDefinition_;

	static const std::map<uint8_t, std::string> kServiceErrorAccess_;

	static const std::map<uint8_t, std::string> kServiceErrorInitiate_;

	static const std::map<uint8_t, std::string> kServiceErrorLoadDataset_;

	static const std::map<uint8_t, std::string> kServiceErrorTask_;

	static const std::map<uint8_t, const std::map<uint8_t, std::string>*> kServiceErrors_;
};



#endif /* INC_ERRORS_H_ */
