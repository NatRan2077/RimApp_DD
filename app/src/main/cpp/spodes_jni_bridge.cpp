// spodes_jni_bridge.cpp — НАШ код (не часть библиотеки SpodesClient).
// Тонкий адаптер между Kotlin (ru.rim.dd.core.bridge.SpodesClientBridge)
// и C++-классом SpodesClient. Вся протокольная логика — в библиотеке,
// здесь только преобразование типов JNI <-> C++ и владение объектом.
//
// КОНТРАКТ АСИНХРОННОСТИ (см. также ble_transport.h):
// SpodesClient::EstablishConnectionRequest/Response, GetRequest и т.п. — блокирующие
// C++ вызовы, которые изнутри пишут байты в BleTransport (быстро, неблокирующе) и
// затем ЖДУТ байты через ReadData (блокируется до прихода данных или таймаута).
// Поэтому со стороны Kotlin такие nativeXxx-вызовы нужно делать на фоновом потоке
// (Dispatchers.IO), а ПАРАЛЛЕЛЬНО — крутить "насос": в цикле вызывать
// pullOutgoingBytes() и отправлять их по BLE, и пересылать в pushIncomingBytes()
// каждый входящий notify-пакет. Эта связка реализуется в MeterRepositoryImpl
// (см. core/bridge/SpodesClientBridge.kt, комментарий у companion object).
//
// НЕ СКОМПИЛИРОВАНО: в среде, где писался этот файл, нет Android NDK,
// поэтому здесь могут быть мелкие неточности в JNI-сигнатурах/типах — их нужно
// поправить по первым ошибкам сборки (см. CMakeLists.txt, раздел ПРИМЕЧАНИЕ).

#include <jni.h>
#include <string>
#include <vector>

#include "spodes_client.h"

namespace {

    SpodesClient* AsClient(jlong handle) {
        return reinterpret_cast<SpodesClient*>(handle);
    }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* client = new SpodesClient();
    client->ConnectBleTransport();
    return reinterpret_cast<jlong>(client);
}

JNIEXPORT void JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    delete AsClient(handle);
}

JNIEXPORT jbyteArray JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativePullOutgoing(JNIEnv* env, jobject /*thiz*/,
                                                                 jlong handle) {
    std::vector<uint8_t> bytes = AsClient(handle)->PullOutgoingBytes();
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!bytes.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativePushIncoming(JNIEnv* env, jobject /*thiz*/,
                                                                 jlong handle, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    AsClient(handle)->FeedIncomingBytes(buf.data(), static_cast<unsigned long>(len));
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeSetNormalResponseMode(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
        jint sourceAddress, jint logicalAddress, jint physicalAddress) {
    SpodesClient* client = AsClient(handle);

    SpodesClient::ConnectionAddresses addr{};
    addr.source_address = static_cast<uint8_t>(sourceAddress);
    addr.logical_address = static_cast<uint8_t>(logicalAddress);
    addr.physical_address = static_cast<uint8_t>(physicalAddress);

    // Дефолты ConnectionParams (128/128/7/7) — согласование окна/размера кадра HDLC.
    SpodesClient::ConnectionParams params{};

    return client->SetNormalResponseMode(addr, &params) ? JNI_TRUE : JNI_FALSE;
}

// [Android-патч] см. SetNormalResponseModeStandard() в spodes_client.h — SNRM строго по
// стандарту, для приборов, которые на него реально отвечают UA (AKROS). Отдельный метод, чтобы
// кадр, уходящий приборам РиМ, не изменился ни на бит.
JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeSetNormalResponseModeStandard(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
        jint sourceAddress, jint logicalAddress, jint physicalAddress,
        jint maxInfoTransmit, jint maxInfoReceive) {
    SpodesClient* client = AsClient(handle);

    SpodesClient::ConnectionAddresses addr{};
    addr.source_address = static_cast<uint8_t>(sourceAddress);
    addr.logical_address = static_cast<uint8_t>(logicalAddress);
    addr.physical_address = static_cast<uint8_t>(physicalAddress);

    return client->SetNormalResponseModeStandard(
            addr,
            static_cast<uint16_t>(maxInfoTransmit),
            static_cast<uint16_t>(maxInfoReceive)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeReceiveUnnumberedAcknowledge(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jint>(AsClient(handle)->ReceiveUnnumberedAcknowledge());
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeEstablishConnection(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint securityLevel, jstring password) {
    SpodesClient* client = AsClient(handle);

    SpodesClient::SecurityParams sec_params{};
    const char* pw_chars = password ? env->GetStringUTFChars(password, nullptr) : nullptr;
    if (pw_chars) {
        sec_params.password = pw_chars;
    }

    // TODO: высокий уровень безопасности (AES) требует заполненного OptionalParams
    // (ключи, sys_title и т.д.) — пока не прокинуто из Kotlin, см. открытые вопросы
    // в ТЗ ("нужны реальные параметры security для конкретного ПУ").
    constexpr uint16_t kClientMaxReceivePduSize = 128;
    bool request_ok = client->EstablishConnectionRequest(
            static_cast<uint8_t>(securityLevel),
            securityLevel == 0 ? nullptr : &sec_params,
            kClientMaxReceivePduSize,
            nullptr);

    if (pw_chars) {
        env->ReleaseStringUTFChars(password, pw_chars);
    }

    if (!request_ok) {
        return JNI_FALSE;
    }
    return client->EstablishConnectionResponse() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeEstablishConnectionFlatAddress(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint securityLevel, jstring password,
        jint destAddress, jint srcAddress) {
    SpodesClient* client = AsClient(handle);

    SpodesClient::SecurityParams sec_params{};
    const char* pw_chars = password ? env->GetStringUTFChars(password, nullptr) : nullptr;
    if (pw_chars) {
        sec_params.password = pw_chars;
    }

    constexpr uint16_t kClientMaxReceivePduSize = 128;
    bool request_ok = client->EstablishConnectionRequestFlatAddress(
            static_cast<uint8_t>(securityLevel),
            securityLevel == 0 ? nullptr : &sec_params,
            kClientMaxReceivePduSize,
            static_cast<uint8_t>(destAddress),
            static_cast<uint8_t>(srcAddress));

    if (pw_chars) {
        env->ReleaseStringUTFChars(password, pw_chars);
    }

    return request_ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeEstablishConnectionResponseRaw(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    std::vector<uint8_t> response;
    bool ok = AsClient(handle)->EstablishConnectionResponseRaw(response);
    if (!ok) {
        return env->NewByteArray(0);
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(response.size()));
    if (!response.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(response.size()),
                                reinterpret_cast<const jbyte*>(response.data()));
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetRequest(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint classId, jstring obisCode,
        jint attributeId) {
    SpodesClient* client = AsClient(handle);

    const char* obis_chars = env->GetStringUTFChars(obisCode, nullptr);
    SpodesClient::RequestParams params{};
    params.class_id = static_cast<uint16_t>(classId);
    params.instance_id = std::string(obis_chars);
    params.attribute_id = static_cast<uint8_t>(attributeId);
    params.retry = false;
    env->ReleaseStringUTFChars(obisCode, obis_chars);

    return client->GetRequest(params, nullptr, 0) ? JNI_TRUE : JNI_FALSE;
}

// [Android-патч] Установление ШИФРОВАННОЙ (высокоуровневой) ассоциации AKROS (HLS-GMAC).
// key — ASCII-байты пароля (16 байт), напр. "SettingRiM_AKROS". Вызывать после SNRM/UA.
JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeEstablishCipheredConnection(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring key) {
    SpodesClient* client = AsClient(handle);
    const char* key_chars = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    jsize key_len = key ? env->GetStringUTFLength(key) : 0;
    bool ok = key_chars && client->EstablishCipheredConnection(key_chars, static_cast<uint8_t>(key_len));
    if (key_chars) env->ReleaseStringUTFChars(key, key_chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// [Android-патч] GET внутри шифрованной ассоциации AKROS. Ответ читать обычным
// nativeGetResponseRaw — он расшифровывается автоматически (см. GetResponseRaw()).
JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetRequestCiphered(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint classId, jstring obisCode,
        jint attributeId) {
    SpodesClient* client = AsClient(handle);
    const char* obis_chars = env->GetStringUTFChars(obisCode, nullptr);
    SpodesClient::RequestParams params{};
    params.class_id = static_cast<uint16_t>(classId);
    params.instance_id = std::string(obis_chars);
    params.attribute_id = static_cast<uint8_t>(attributeId);
    params.retry = false;
    env->ReleaseStringUTFChars(obisCode, obis_chars);
    return client->GetRequestCiphered(params) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetRequestFlatAddress(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint classId, jstring obisCode,
        jint attributeId, jint destAddress, jint srcAddress) {
    SpodesClient* client = AsClient(handle);

    const char* obis_chars = env->GetStringUTFChars(obisCode, nullptr);
    SpodesClient::RequestParams params{};
    params.class_id = static_cast<uint16_t>(classId);
    params.instance_id = std::string(obis_chars);
    params.attribute_id = static_cast<uint8_t>(attributeId);
    params.retry = false;
    env->ReleaseStringUTFChars(obisCode, obis_chars);

    return client->GetRequestFlatAddress(params, static_cast<uint8_t>(destAddress),
                                         static_cast<uint8_t>(srcAddress)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeActionRequestFlatAddress(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint classId, jstring obisCode,
        jint methodId, jboolean hasParameter, jint parameterType, jint parameterValue,
        jint destAddress, jint srcAddress) {
    SpodesClient* client = AsClient(handle);

    const char* obis_chars = env->GetStringUTFChars(obisCode, nullptr);
    SpodesClient::RequestParams params{};
    params.class_id = static_cast<uint16_t>(classId);
    params.instance_id = std::string(obis_chars);
    params.attribute_id = static_cast<uint8_t>(methodId); // AddAddress() пишет это как method-id для ACTION
    params.retry = false;
    env->ReleaseStringUTFChars(obisCode, obis_chars);

    return client->ActionRequestFlatAddress(params, hasParameter == JNI_TRUE,
                                            static_cast<uint8_t>(parameterType),
                                            static_cast<uint8_t>(parameterValue),
                                            static_cast<uint8_t>(destAddress),
                                            static_cast<uint8_t>(srcAddress)) ? JNI_TRUE : JNI_FALSE;
}

// [Android-патч] Запрос СЛЕДУЮЩЕГО блока ответа (get-request-next, C0 02) — нужен, когда
// прибор не может уместить ответ в один кадр и переходит на блочную передачу. Так отвечает
// AKROS на чтение capture_objects: при согласованном размере информационного поля 128 байт
// описание 14 колонок (больше 250 байт) физически не помещается в один кадр.
JNIEXPORT jboolean JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeSendReadyToReceive(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint blockNumber) {
    // SendReadyToReceiveBlock(), а не SendReadyToReceive(): сам метод объявлен в приватной
    // секции SpodesClient, снаружи класса он недоступен — см. обёртку в spodes_client.h.
    return AsClient(handle)->SendReadyToReceiveBlock(static_cast<int8_t>(blockNumber)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetResponseRaw(JNIEnv* env, jobject /*thiz*/,
                                                                   jlong handle) {
    std::vector<uint8_t> response;
    bool ok = AsClient(handle)->GetResponseRaw(response);
    if (!ok) {
        return env->NewByteArray(0);
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(response.size()));
    if (!response.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(response.size()),
                                reinterpret_cast<const jbyte*>(response.data()));
    }
    return result;
}

JNIEXPORT jdouble JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetResponseFloat(JNIEnv* /*env*/,
                                                                     jobject /*thiz*/,
                                                                     jlong handle) {
    double value = 0.0;
    AsClient(handle)->GetResponseFloat(value);
    return value;
}

JNIEXPORT jlong JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetResponseInt(JNIEnv* /*env*/,
                                                                   jobject /*thiz*/,
                                                                   jlong handle) {
    uint64_t value = 0;
    AsClient(handle)->GetResponseInt(value);
    return static_cast<jlong>(value);
}

JNIEXPORT jstring JNICALL
Java_ru_rim_dd_core_bridge_SpodesClientBridge_nativeGetErrorMessage(JNIEnv* env, jobject /*thiz*/,
                                                                    jlong handle) {
    return env->NewStringUTF(AsClient(handle)->GetErrorMessage());
}

}  // extern "C"
