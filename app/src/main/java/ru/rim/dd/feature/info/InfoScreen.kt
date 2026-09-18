package ru.rim.dd.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.dlms.CLOCK_STATUS_OBIS
import ru.rim.dd.core.dlms.CURRENT_TARIFF_OBIS
import ru.rim.dd.core.dlms.TAMPER_STATUS_OBIS
import ru.rim.dd.core.model.TamperCondition
import ru.rim.dd.feature.common.ObisCaption
import ru.rim.dd.feature.common.SourceCaption
import java.time.format.DateTimeFormatter

private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

// [Android-патч] OBIS-коды показываемых здесь величин — ровно те, по которым значения
// читаются в MeterRepositoryImpl.applyDecodedBuffer() (см. ObisCaption).
private const val OBIS_TEMPERATURE = "0.0.96.9.0.255"
private const val OBIS_BACKUP_VOLTAGE = "0.0.96.6.3.255"
private const val OBIS_DEVICE_TIME = "0.0.0.9.1.255"
private const val OBIS_DEVICE_DATE = "0.0.0.9.2.255"

/**
 * Экран «Инфо» — соответствует макету wf4_device_info.png из ТЗ. Температура/резервное
 * питание/часы прибора добавлены по факту находки в реальном буфере счётчика (см. историю
 * диагностики, OBIS 0.0.96.9.0.255 / 0.0.96.6.3.255 / 0.0.0.9.x).
 */
@Composable
fun InfoScreen(viewModel: InfoViewModel = hiltViewModel()) {
    val info by viewModel.meterInfo.collectAsState()
    val tamper by viewModel.tamperState.collectAsState()

    // [Android-патч] verticalScroll ЗДЕСЬ ОТСУТСТВОВАЛ — в отличие от «Показаний» и «Сети»,
    // где он был с самого начала. Пока на экране было несколько коротких строк, это не
    // проявлялось: содержимое помещалось целиком. После добавления подписей OBIS (см.
    // ObisCaption) экран стал заметно выше, и всё, что не влезло по высоте, просто
    // обрезалось — без возможности прокрутить (Column без скролла не «уезжает» вверх, он
    // молча отрезает лишнее). Тот же риск был и раньше на маленьком экране или при крупном
    // системном шрифте, просто никто в него не упирался.
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("О счётчике", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }
        // [Android-патч] Диагностическая кнопка (см. InfoViewModel.probeLogs()) — пробует все
        // известные из паспорта прибора "журналы"; результат смотреть в logcat (MeterRepository),
        // отдельного UI для журналов пока нет — формат записей заранее неизвестен.
        Button(onClick = { viewModel.probeLogs() }) { Text("Проверить журналы (диагностика)") }
        // [Android-патч] см. ObisCaption — под каждой величиной её название из паспорта прибора
        // и OBIS-код объекта, из которого значение реально взято.
        Text("Модель: ${info?.model ?: "—"}")
        // Модель приходит либо из буфера (0.0.96.1.1.255 «Тип прибора»), либо из разбора имени
        // BLE-устройства — см. MeterInfo.modelObis. Во втором случае OBIS-кода у значения нет,
        // и вместо выдуманной подписи честно пишем, откуда оно взято на самом деле.
        val modelObis = info?.modelObis
        if (modelObis != null) ObisCaption(modelObis) else SourceCaption("из имени BLE-устройства")
        Text("Серийный номер: ${info?.serialNumber ?: "—"}")
        // [Android-патч] ИСПРАВЛЕНО: серийный номер, как выяснилось при разборе паспорта, этот
        // прибор ПРИСЫЛАЕТ САМ — объект 0.0.96.1.0.255 приходит текстом в том же буфере (в логе:
        // 0.0.96.1.0.255="05000112"). Раньше приложение читало его только из имени BLE-устройства
        // и здесь стояла безусловная подпись «из имени». Теперь, когда номер пришёл от прибора,
        // подписываем его настоящим OBIS-кодом, а к подписи-источнику откатываемся, только если
        // в буфере номера не оказалось (см. MeterInfo.serialNumberObis).
        val serialObis = info?.serialNumberObis
        if (serialObis != null) ObisCaption(serialObis) else SourceCaption("из имени BLE-устройства")
        Text("Версия ПО: ${info?.firmwareVersion ?: "—"}")
        info?.firmwareVersionObis?.let { ObisCaption(it) }
        info?.temperatureC?.let {
            Text("Температура прибора: ${"%.1f".format(it)} °C")
            ObisCaption(OBIS_TEMPERATURE)
        }
        info?.backupVoltageV?.let {
            Text("Напряжение резервного питания: ${"%.3f".format(it)} В")
            ObisCaption(OBIS_BACKUP_VOLTAGE)
        }
        info?.deviceClock?.let {
            Text("Часы прибора: ${it.format(CLOCK_FORMATTER)}")
            // Значение собирается ИЗ ДВУХ объектов сразу (время + дата), поэтому подписи две.
            ObisCaption(OBIS_DEVICE_TIME)
            ObisCaption(OBIS_DEVICE_DATE)
        }
        // [Android-патч] см. CURRENT_TARIFF_OBIS в GetResponseParser.kt.
        info?.currentTariff?.let {
            Text("Текущий тариф: $it")
            ObisCaption(CURRENT_TARIFF_OBIS)
        }
        // [Android-патч] см. CLOCK_STATUS_OBIS/isClockStatusValid() в GetResponseParser.kt.
        info?.clockValid?.let { valid ->
            if (!valid) {
                Text("Часы прибора: НЕДОСТОВЕРНЫ")
                ObisCaption(CLOCK_STATUS_OBIS)
            }
        }

        // [Android-патч] см. TamperState.kt/TAMPER_STATUS_OBIS в GetResponseParser.kt —
        // раскладка бит расшифрована из исходников прошивки самого пульта РиМ 040.40
        // (thread_dataRequest.c::read_status()), приходит внутри того же буфера, что и
        // остальная диагностика на этом экране — без отдельного запроса и ассоциации.
        // Перенесено с экрана «Сеть»: по смыслу это диагностика/безопасность прибора.
        Text("Пломбы и датчики", style = MaterialTheme.typography.titleMedium)
        // Все пять признаков ниже — разные биты ОДНОГО объекта буфера («Значки ДД»), поэтому
        // подпись общая для всего раздела, а не под каждой строкой.
        ObisCaption(TAMPER_STATUS_OBIS)
        if (tamper.unknown) {
            Text("НЕИЗВЕСТНО (нет доступа на чтение)")
        } else {
            Text("Пломба корпуса: ${tamperConditionLabel(tamper.cover)}")
            Text("Пломба клеммника: ${tamperConditionLabel(tamper.meter)}")
            Text("Магнитное поле: ${tamperConditionLabel(tamper.magnet)}")
            Text("Батарея: ${tamperConditionLabel(tamper.battery)}")
            Text("СВЧ-поле: ${tamperConditionLabel(tamper.rf)}")
            if (tamper.powerLimitExceeded) Text("Превышен лимит мощности")
        }
    }
}

/** ACTIVE — нарушение прямо сейчас, FLAG — было зафиксировано ранее, OK — норма. */
private fun tamperConditionLabel(condition: TamperCondition): String = when (condition) {
    TamperCondition.OK -> "норма"
    TamperCondition.FLAG -> "было нарушение (сброшено)"
    TamperCondition.ACTIVE -> "НАРУШЕНА"
}
