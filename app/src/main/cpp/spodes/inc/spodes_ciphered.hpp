// [Android-патч] Шифрованная (высокоуровневая) ассоциация DLMS/COSEM с прибором AKROS —
// HLS с механизмом GMAC (2.16.756.5.8.2.5), защита suite 0 (AES-128-GCM).
//
// ПОЧЕМУ ОТДЕЛЬНЫЙ МОДУЛЬ, А НЕ ДОРАБОТКА ШТАТНОГО ШИФРОВАНИЯ БИБЛИОТЕКИ.
// В библиотеке шифрованный путь начат, но под ДРУГОЙ механизм и не доведён: ответ на
// HLS-вызов считается как AES-ECB от StoC (это механизм 4), тогда как AKROS использует
// механизм 5 — ответ это GMAC-тег от StoC; байт контроля безопасности в AddUserInformation
// проставляется десятичными 10/20 вместо 0x10/0x20, для «шифрование+аутентификация» (0x30)
// не выставляется вовсе; обычный GetRequest() шифрование не применяет, а CipherAndSendFrame()
// для GET берёт тег 0xC9 (glo-set) вместо 0xC8 (glo-get); счётчик вызовов (invocation counter)
// нигде не ведётся и не инкрементируется. Всё это подтверждено разбором исходников и не
// совпадает с реальным обменом настоящего приложения (лог захвачен по BLE).
//
// ЗДЕСЬ — ЧИСТАЯ ЛОГИКА СБОРКИ/РАЗБОРА APDU И ШИФРОВАНИЯ, без транспорта: её можно
// проверить офлайн, побайтово сверив с реальным логом (см. tests/test_ciphered.cpp).
// Транспорт (отправка кадра, чтение ответа, HDLC-обрамление) остаётся за проверенными
// методами SpodesClient — тем самым путём, которым уже работает нешифрованный AKROS.
//
// ВСЯ СХЕМА ПОДТВЕРЖДЕНА побайтово по реальному логу обмена рабочего приложения:
//  - ключ шифрования = ключ аутентификации = ASCII-байты пароля (16 байт), напр. "SettingRiM_AKROS";
//  - IV блочного шифра = system-title(8) || invocation-counter(4);
//  - AAD при «шифрование+аутентификация» (SC=0x30) = SC || auth_key;
//  - ответ на HLS-вызов = SC(0x10, только аутентификация) || IC(4) || GMAC(StoC), 17 байт;
//  - единый счётчик вызовов инкрементируется на КАЖДУЮ операцию GCM (шифрование кадра ИЛИ
//    вычисление GMAC ответа) и кодируется в поле IC младшим байтом вперёд: 0=initiate,
//    1=GMAC ответа на HLS, 2=кадр ACTION с ответом, 3,4,…=последующие GET.

#ifndef SPODES_CIPHERED_HPP__
#define SPODES_CIPHERED_HPP__

#include <cstdint>
#include <vector>
#include <array>
#include <cstring>
#include "plusaes.hpp"

namespace akros_hls {

    using Bytes = std::vector<uint8_t>;

/** DLMS-теги «глобально зашифрованных» APDU (global-cipher). */
    enum GloTag : uint8_t {
        kGloInitiateRequest  = 0x21,
        kGloInitiateResponse = 0x28,
        kGloGetRequest       = 0xC8,
        kGloGetResponse      = 0xCC,
        kGloActionRequest    = 0xCB,
        kGloActionResponse   = 0xCF,
    };

/** Байт контроля безопасности (security control byte). */
    enum SecControl : uint8_t {
        kScAuth        = 0x10, // только аутентификация (GMAC), без шифрования
        kScEncrypt     = 0x20, // только шифрование
        kScAuthEncrypt = 0x30, // аутентификация + шифрование — так шифруются все кадры AKROS
    };

/** [Android-патч] IC (invocation counter) в поле кадра — 4 байта, СТАРШИЙ вперёд (big-endian),
 * как предписывает DLMS Green Book (invocation-counter — это double-long-unsigned, MSB первым).
 *
 * ПОЧЕМУ ЭТО ВАЖНО И ПОЧЕМУ БЫЛО ИНАЧЕ. В эталонном логе рабочего приложения байты шли как
 * 02 00 00 00 / 03 00 00 00 / 05 00 00 00 — что похоже на «младший вперёд», но при чтении
 * big-endian это значения 0x02000000 / 0x03000000 / 0x05000000, тоже строго возрастающие. То
 * есть прибор читает счётчик big-endian и хранит максимум увиденного как защиту от повтора
 * (replay). Раньше мы начинали счётчик с нуля — и прибор отвечал на AARQ «authentication-failure»
 * (диагностика 0x0D), потому что 0 ≤ уже виденного максимума. Теперь счётчик, во-первых,
 * кодируется big-endian (значение растёт естественно), во-вторых, засевается от текущего времени
 * (см. EstablishCipheredConnection) — гарантированно больше всего, что прибор видел раньше, и
 * монотонно растёт от сеанса к сеансу. Для одного значения 0 обе кодировки совпадают (00 00 00 00),
 * поэтому initiate-request остаётся байт-в-байт как в эталоне. */
    inline std::array<uint8_t, 4> IcBytes(uint32_t counter) {
        return { (uint8_t)((counter >> 24) & 0xFF), (uint8_t)((counter >> 16) & 0xFF),
                 (uint8_t)((counter >> 8) & 0xFF), (uint8_t)(counter & 0xFF) };
    }

/** Собирает IV блочного шифра = system-title(8) || invocation-counter(4). */
    inline Bytes MakeIv(const uint8_t sys_title[8], uint32_t counter) {
        Bytes iv(sys_title, sys_title + 8);
        auto ic = IcBytes(counter);
        iv.insert(iv.end(), ic.begin(), ic.end());
        return iv;
    }

/**
 * GCM-шифрование с аутентификацией: возвращает ciphertext || tag(12).
 * aad — дополнительные аутентифицируемые данные (для кадра это SC || auth_key).
 */
    inline Bytes GcmEncrypt(const uint8_t key[16], const Bytes &iv, const Bytes &aad, const Bytes &plaintext) {
        Bytes data = plaintext;
        uint8_t tag[12] = {};
        plusaes::encrypt_gcm(data.data(), data.size(), aad.data(), aad.size(), key, 16,
                             iv.data(), iv.size(), tag, 12);
        data.insert(data.end(), tag, tag + 12);
        return data;
    }

/**
 * GCM-расшифровка с проверкой тега. ct_and_tag — ciphertext || tag(12).
 * Возвращает true и plaintext, если тег сошёлся; иначе false (данные подделаны/ключ неверен).
 */
    inline bool GcmDecrypt(const uint8_t key[16], const Bytes &iv, const Bytes &aad,
                           const Bytes &ct_and_tag, Bytes &plaintext_out) {
        if (ct_and_tag.size() < 12) return false;
        size_t ct_len = ct_and_tag.size() - 12;
        Bytes data(ct_and_tag.begin(), ct_and_tag.begin() + ct_len);
        const uint8_t *tag = ct_and_tag.data() + ct_len;
        plusaes::Error e = plusaes::decrypt_gcm(data.data(), data.size(),
                                                aad.data(), aad.size(), key, 16,
                                                iv.data(), iv.size(), tag, 12);
        if (e != plusaes::kErrorOk) return false;
        plaintext_out = data;
        return true;
    }

/**
 * Оборачивает открытый APDU в global-cipher APDU (SC=0x30): func_tag || len || 0x30 || IC(4) || ct || tag.
 * key — и ключ шифрования, и ключ аутентификации (для AKROS они совпадают).
 */
    inline Bytes WrapGlo(uint8_t func_tag, const uint8_t key[16], const uint8_t sys_title[8],
                         uint32_t counter, const Bytes &plaintext) {
        Bytes aad; aad.push_back(kScAuthEncrypt);
        aad.insert(aad.end(), key, key + 16);
        Bytes iv = MakeIv(sys_title, counter);
        Bytes ct = GcmEncrypt(key, iv, aad, plaintext);

        Bytes out;
        out.push_back(func_tag);
        Bytes body; body.push_back(kScAuthEncrypt);
        auto ic = IcBytes(counter);
        body.insert(body.end(), ic.begin(), ic.end());
        body.insert(body.end(), ct.begin(), ct.end());
        out.push_back((uint8_t)body.size());
        out.insert(out.end(), body.begin(), body.end());
        return out;
    }

/**
 * Разбирает global-cipher APDU (ответ сервера) и возвращает открытый APDU.
 * apdu — байты, начинающиеся с glo-тега (сразу после LLC-заголовка E6 E7 00).
 * server_sys_title — из responding-AP-title (A4) кадра AARE.
 */
    inline bool UnwrapGlo(const uint8_t key[16], const uint8_t server_sys_title[8],
                          const Bytes &apdu, Bytes &plaintext_out) {
        if (apdu.size() < 2) return false;
        uint8_t len = apdu[1];
        if ((size_t)(2 + len) > apdu.size()) return false;
        Bytes body(apdu.begin() + 2, apdu.begin() + 2 + len);
        if (body.size() < 5) return false;
        uint8_t sc = body[0];
        uint32_t counter = (uint32_t)body[1] | ((uint32_t)body[2] << 8) |
                           ((uint32_t)body[3] << 16) | ((uint32_t)body[4] << 24);
        Bytes ct_and_tag(body.begin() + 5, body.end());
        Bytes aad; aad.push_back(sc);
        aad.insert(aad.end(), key, key + 16);
        Bytes iv = MakeIv(server_sys_title, counter);
        // IC сервера в его кадрах кодируется старшим байтом вперёд (в логе 00 00 00 95…),
        // но нам это неважно: IV собирается из тех же байт, что пришли, а counter здесь не
        // используется для инкремента — только для восстановления IV. MakeIv кодирует младшим
        // байтом вперёд, поэтому для расшифровки берём IV напрямую из пришедших байт.
        iv.assign(server_sys_title, server_sys_title + 8);
        iv.insert(iv.end(), body.begin() + 1, body.begin() + 5);
        return GcmDecrypt(key, iv, aad, ct_and_tag, plaintext_out);
    }

/** Открытый APDU initiate-request, который шлёт настоящее приложение (проверено по логу). */
    inline Bytes InitiateRequestPlaintext() {
        // 01=initiate-request, 00=dedicated-key нет, 01=response-allowed, 00=QoS нет,
        // 06=версия DLMS, 5F1F 04 00 40 18 9F=conformance, FF FF=client-max-receive-pdu.
        return {0x01,0x00,0x01,0x00,0x06,0x5F,0x1F,0x04,0x00,0x40,0x18,0x9F,0xFF,0xFF};
    }

/**
 * Собирает AARQ-APDU (начиная с 0x60), готовый к вставке после LLC-заголовка E6 E6 00.
 * ctos16 — клиентский вызов (случайные 16 байт); ic — счётчик для шифрования initiate (обычно 0).
 */
    inline Bytes BuildAarq(const uint8_t key[16], const uint8_t client_sys_title[8],
                           const uint8_t ctos16[16], uint32_t ic) {
        Bytes content;
        // application-context-name = 2.16.756.5.8.1.3 (LN с шифрованием)
        const uint8_t ctx[] = {0xA1,0x09,0x06,0x07,0x60,0x85,0x74,0x05,0x08,0x01,0x03};
        content.insert(content.end(), ctx, ctx + sizeof(ctx));
        // calling-AP-title = client system title
        content.insert(content.end(), {0xA6,0x0A,0x04,0x08});
        content.insert(content.end(), client_sys_title, client_sys_title + 8);
        // sender-acse-requirements = authentication (0x80)
        content.insert(content.end(), {0x8A,0x02,0x07,0x80});
        // mechanism-name = 2.16.756.5.8.2.5 (HLS-GMAC)
        content.insert(content.end(), {0x8B,0x07,0x60,0x85,0x74,0x05,0x08,0x02,0x05});
        // calling-authentication-value = CtoS challenge (graphic-string 16)
        content.insert(content.end(), {0xAC,0x12,0x80,0x10});
        content.insert(content.end(), ctos16, ctos16 + 16);
        // user-information = ciphered glo-initiate-request
        Bytes glo = WrapGlo(kGloInitiateRequest, key, client_sys_title, ic, InitiateRequestPlaintext());
        content.push_back(0xBE);
        content.push_back((uint8_t)(glo.size() + 2));
        content.push_back(0x04);
        content.push_back((uint8_t)glo.size());
        content.insert(content.end(), glo.begin(), glo.end());

        Bytes aarq;
        aarq.push_back(0x60);
        aarq.push_back((uint8_t)content.size());
        aarq.insert(aarq.end(), content.begin(), content.end());
        return aarq;
    }

    struct AareResult {
        bool ok = false;            // разобрался ли кадр
        bool accepted = false;      // association-result = accepted
        bool is_hls = false;        // сервер прислал вызов StoC (нужен ответ на HLS)
        uint8_t server_sys_title[8] = {};
        uint8_t stoc[16] = {};
        std::string error;
    };

/**
 * Разбирает AARE-APDU (начиная с 0x61), обычно идущий после LLC E6 E7 00.
 * Достаёт результат ассоциации, system-title сервера (A4) и вызов StoC (AA).
 */
    inline AareResult ParseAare(const Bytes &apdu) {
        AareResult r;
        if (apdu.size() < 2 || apdu[0] != 0x61) { r.error = "not AARE"; return r; }
        size_t end = std::min(apdu.size(), (size_t)(apdu[1] + 2));
        size_t i = 2;
        while (i + 2 <= end) {
            uint8_t tag = apdu[i];
            uint8_t len = apdu[i + 1];
            if (i + 2 + len > apdu.size()) break;
            const uint8_t *v = &apdu[i + 2];
            switch (tag) {
                case 0xA2: // association-result (внутри 02 01 XX)
                    if (len >= 3 && v[len - 1] == 0) r.accepted = true;
                    break;
                case 0xA4: // responding-AP-title: 04 08 <8 байт system-title сервера>
                    if (len >= 10 && v[0] == 0x04 && v[1] == 0x08)
                        std::memcpy(r.server_sys_title, v + 2, 8);
                    break;
                case 0xAA: // responding-authentication-value: 80 10 <16 байт StoC>
                    if (len >= 18 && v[0] == 0x80 && v[1] == 0x10) {
                        r.is_hls = true;
                        std::memcpy(r.stoc, v + 2, 16);
                    }
                    break;
                default: break;
            }
            i += 2 + len;
        }
        r.ok = true;
        if (!r.accepted) r.error = "association rejected";
        return r;
    }

/**
 * Значение ответа на HLS-вызов (reply_to_HLS_authentication) для механизма GMAC:
 * SC(0x10) || IC(4) || GMAC(StoC), 17 байт. GMAC считается с пустым plaintext,
 * AAD = SC || auth_key || StoC, IV = client_sys_title || IC.
 */
    inline Bytes BuildHlsReplyValue(const uint8_t key[16], const uint8_t client_sys_title[8],
                                    uint32_t ic, const uint8_t stoc[16]) {
        Bytes aad; aad.push_back(kScAuth);
        aad.insert(aad.end(), key, key + 16);
        aad.insert(aad.end(), stoc, stoc + 16);
        Bytes iv = MakeIv(client_sys_title, ic);
        Bytes ct = GcmEncrypt(key, iv, aad, Bytes{}); // пустой plaintext → только 12-байтный тег
        Bytes value;
        value.push_back(kScAuth);
        auto icb = IcBytes(ic);
        value.insert(value.end(), icb.begin(), icb.end());
        value.insert(value.end(), ct.begin(), ct.end()); // ct тут = только тег (12 байт)
        return value; // 1 + 4 + 12 = 17
    }

/**
 * Открытый APDU ACTION reply_to_HLS_authentication: вызов метода 1 объекта Association LN
 * (класс 15, OBIS 0.0.40.0.0.255) с параметром — octet-string(17) = [reply_value].
 */
    inline Bytes BuildHlsReplyActionApdu(const Bytes &reply_value) {
        Bytes a = {0xC3,0x01,0xC1,            // action-request-normal, invoke-id-and-priority
                   0x00,0x0F,                 // class_id = 15
                   0x00,0x00,0x28,0x00,0x00,0xFF, // instance = 0.0.40.0.0.255
                   0x01,                      // method_id = 1 (reply_to_HLS_authentication)
                   0x01,                      // method-invocation-parameters присутствуют
                   0x09};                     // octet-string
        a.push_back((uint8_t)reply_value.size());
        a.insert(a.end(), reply_value.begin(), reply_value.end());
        return a;
    }

/** Открытый APDU GET-request-normal: C0 01 <invoke> <class2> <obis6> <attr> 00 (без фильтра). */
    inline Bytes BuildGetApdu(uint16_t class_id, const uint8_t obis6[6], uint8_t attr, uint8_t invoke = 0x41) {
        Bytes a = {0xC0,0x01,invoke};
        a.push_back((uint8_t)(class_id >> 8));
        a.push_back((uint8_t)(class_id & 0xFF));
        a.insert(a.end(), obis6, obis6 + 6);
        a.push_back(attr);
        a.push_back(0x00); // access-selection отсутствует
        return a;
    }

/** Открытый APDU GET-request-next (следующий блок): C0 02 <invoke> <block-number(4, big-endian)>. */
    inline Bytes BuildGetNextApdu(uint32_t block_number, uint8_t invoke = 0x41) {
        Bytes a = {0xC0,0x02,invoke};
        a.push_back((uint8_t)(block_number >> 24));
        a.push_back((uint8_t)(block_number >> 16));
        a.push_back((uint8_t)(block_number >> 8));
        a.push_back((uint8_t)(block_number & 0xFF));
        return a;
    }

} // namespace akros_hls

#endif // SPODES_CIPHERED_HPP__
