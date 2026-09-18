package ru.rim.dd.core.model

import java.time.Instant
import java.time.LocalDateTime

// [Android-патч] Поле signalLevelDbm (уровень сигнала) отсюда УБРАНО — оно никогда не
// заполнялось (мёртвое поле) и по смыслу принадлежит не "информации о приборе", а активному
// BLE-соединению: теперь это MeterBleClient.signalStrengthDbm / MeterRepository.signalStrengthDbm()
// — живое значение (обновляется на каждом цикле автообновления), показывается на экране
// «Настройки» рядом с кнопкой «Отключиться».
data class MeterInfo(
    val model: String,             // напр. "РиМ 189.46"
    val serialNumber: String,
    val firmwareVersion: String,
    val lastSeenAt: Instant? = null,
    // Найдены в реальном буфере счётчика (см. историю диагностики) — статусные OBIS 0.0.96.x
    // и служебные часы устройства (0.0.0.9.1.255 время / 0.0.0.9.2.255 дата).
    val temperatureC: Double? = null,      // OBIS 0.0.96.9.0.255 — внутренняя температура прибора
    val backupVoltageV: Double? = null,    // OBIS 0.0.96.6.3.255 — напряжение резервного питания
    val deviceClock: LocalDateTime? = null, // часы самого счётчика (не время телефона!)
    // [Android-патч] см. CURRENT_TARIFF_OBIS в GetResponseParser.kt — до этого патча значение
    // молча терялось (позиционное поле буфера без обёртки в OBIS-код).
    val currentTariff: Int? = null,        // OBIS 0.0.96.14.0.255 — активный тариф прямо сейчас
    // [Android-патч] см. CLOCK_STATUS_OBIS/isClockStatusValid() в GetResponseParser.kt — null,
    // пока не пришёл ни один успешный цикл (честное "не знаем", как и RelaySource.UNKNOWN).
    val clockValid: Boolean? = null,       // OBIS 0.0.1.0.0.255 — достоверность часов прибора
    // [Android-патч] Откуда РЕАЛЬНО взяты модель и версия ПО — чтобы подпись на экране «Инфо»
    // (см. ObisCaption) не врала. У остальных полей выше источник фиксирован и известен на
    // этапе компиляции, а эти два — нет: версия ПО ищется перебором нескольких кандидатов
    // (FIRMWARE_VERSION_OBIS_CANDIDATES), а модель может прийти либо из буфера
    // (0.0.96.1.1.255 «Тип прибора»), либо, если в буфере её нет, из разбора имени
    // BLE-устройства (applyDeviceNameInfo) — во втором случае OBIS-кода у значения нет вообще
    // и подписывать его каким-либо кодом было бы неправдой. null здесь и означает «не из
    // буфера прибора»; серийный номер такого поля не имеет намеренно — он всегда берётся из
    // имени BLE-устройства, а не из объекта 0.0.96.1.0.255.
    val modelObis: String? = null,
    val firmwareVersionObis: String? = null,
    // [Android-патч] Серийный номер у этого прибора, как выяснилось, ПРИХОДИТ В БУФЕРЕ — объект
    // 0.0.96.1.0.255 («Серийный номер» по паспорту), текстом, без ассоциации. Тогда здесь стоит
    // его OBIS-код и подпись на экране честная; если же в буфере номера не оказалось и значение
    // разобрано из BLE-имени — остаётся null, и экран пишет «из имени BLE-устройства».
    val serialNumberObis: String? = null,
)

data class PairedDevice(
    val serialNumber: String,
    val alias: String? = null,
    val bleAddress: String? = null,
    /** PIN НИКОГДА не хранится тут в открытом виде — только в DeviceStore (Keystore/EncryptedSharedPreferences). */
    val lastConnectedAt: Instant? = null,
)
