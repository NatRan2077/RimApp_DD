/*
 * errors.cpp
 *
 *  Created on: 25 апр. 2025 г.
 *      Author: habarova
 */

#include "errors.h"

const std::map<uint8_t, std::string> Errors::kDataAccessResult_ =
{
	{1, "Hardware fault"},
	{2, "Temporary failure"},
	{3, "Read-write denied"},
	{4, "Object undefined"},
	{9, "Object class inconsistent"},
	{11, "Object unavailable"},
	{12, "Type unmatched"},
	{13, "Scope of access violated"},
	{14, "Data block unavailable"},
	{15, "Long get aborted"},
	{16, "No long get in progress"},
	{17, "Long set aborted"},
	{18, "No long set in progress"},
	{19, "Data block number invalid"},
	{250, "Other reason"}
};

const std::map<uint8_t, std::string> Errors::kActionResult_ =
{
	{1, "Hardware fault"},
	{2, "Temporary failure"},
	{3, "Read-write denied"},
	{4, "Object undefined"},
	{9, "Object class inconsistent"},
	{11, "Object unavailable"},
	{12, "Type unmatched"},
	{13, "Scope of access violated"},
	{14, "Data block unavailable"},
	{15, "Long action aborted"},
	{16, "No long action in progress"},
	{250, "Other reason"}
};

const std::map<uint8_t, std::string> Errors::kFrameRejectMessage_ =
{
	{1, "Invalid frame type"},
	{2, "Sequence error"},
	{3, "Invalid data length"},
	{4, "Invalid number of expected I frame"},
	{5, "Buffer overflow"},
	{6, "Unsupported command"},
	{7, "FCS error"},
	{8, "Addressing error"}
};

const std::map<uint8_t, std::string> Errors::kResultSourceDiagnostic_ =
{
	{1, "No reason given"},
	{2, "Application context name not supported"},
	{3, "Calling AP title not recognized"},
	{4, "Calling AP invocation identifier not recognized"},
	{5, "Calling AE qualifier not recognized"},
	{6, "Calling invocation identifier not recognized"},
	{7, "Called AP title not recognized"},
	{8, "Called AP invocation identifier not recognized"},
	{9, "Called AE qualifier not recognized"},
	{10, "Called AE invocation identifier not recognized"},
	{11, "Authentication mechanism name not recognized"},
	{12, "Authentication mechanism name required"},
	{13, "Authentication failure"}
};

const std::map<uint8_t, std::string> Errors::kConfirmedServiceErrors_ =
{
	{1,  "initiateError"},
	{2,  "getStatus"},
	{3,  "getNameList"},
	{4,  "getVariableAttribute"},
	{5,  "read"},
	{6,  "write"},
	{7,  "getDataSetAttribute"},
	{8,  "getTIAttribute"},
	{9,  "changeScope"},
	{10, "start"},
	{11, "stop"},
	{12, "resume"},
	{13, "makeUsable"},
	{14, "initiateLoad"},
	{15, "loadSegment"},
	{16, "terminateLoad"},
	{17, "initiateUpLoad"},
	{18, "upLoadSegment"},
	{19, "terminateUpLoad"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorApplicationReference_ =
{
	{0, "other"},
	{1, "time elapsed"},
	{2, "application unreachable"},
	{3, "application reference invalid"},
	{4, "application context unsupported"},
	{5, "provider communication error"},
	{6, "deciphering error"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorHardwareResource_ =
{
	{0, "other"},
	{1, "memory unavailable"},
	{2, "processor resource unavailable"},
	{3, "mass storage unavailable"},
	{4, "other resource unavailable"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorVdeState_ =
{
	{0, "other"},
	{1, "no dlms context"},
	{2, "loading data set"},
	{3, "status nochange"},
	{4, "status inoperable"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorService_ =
{
	{0, "other"},
	{1, "pdu size"},
	{2, "service unsupported"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorDefinition_ =
{
	{0, "other"},
	{1, "object undefined"},
	{2, "object class inconsistent"},
	{3, "object attribute inconsistent"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorAccess_ =
{
	{0, "other"},
	{1, "scope of access violated"},
	{2, "object access violated"},
	{3, "hardware fault"},
	{4, "object unavailable"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorInitiate_ =
{
	{0, "other"},
	{1, "dlms version too low"},
	{2, "incompatible conformance"},
	{3, "pdu size too short"},
	{4, "refused by the VDE Handler"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorLoadDataset_ =
{
	{0, "other"},
	{1, "primitive out of sequence"},
	{2, "not loadable"},
	{3, "dataset size too large"},
	{4, "not awaited segment"},
	{5, "uint8_terpretation failure"},
	{6, "storage failure"},
	{7, "data set not ready"}
};

const std::map<uint8_t, std::string> Errors::kServiceErrorTask_ =
{
	{0, "other"},
	{1, "no remote control"},
	{2, "ti stopped"},
	{3, "ti running"},
	{4, "ti unusable"}
};

const std::map<uint8_t, const std::map<uint8_t, std::string>*> Errors::kServiceErrors_ =
{
	{0, &kServiceErrorApplicationReference_},
	{1, &kServiceErrorHardwareResource_},
	{2, &kServiceErrorVdeState_},
	{3, &kServiceErrorService_},
	{4, &kServiceErrorDefinition_},
	{5, &kServiceErrorAccess_},
	{6, &kServiceErrorInitiate_},
	{7, &kServiceErrorLoadDataset_},
	{9, &kServiceErrorTask_}
};
