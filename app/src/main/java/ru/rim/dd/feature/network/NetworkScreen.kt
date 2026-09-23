package ru.rim.dd.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.rim.dd.core.dlms.RELAY_CONTROL_STATE_OBIS
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.feature.common.ObisCaption

// [Android-патч] OBIS-коды показываемых здесь величин — ровно те, по которым значения
// читаются в MeterRepositoryImpl.applyDecodedBuffer(). Вынесены в константы, чтобы подпись
// на экране и источник данных нельзя было случайно рассинхронизировать.
private const val OBIS_VOLTAGE = "1.0.12.7.0.255"
private const val OBIS_CURRENT = "1.0.11.7.0.255"
// [Android-патч] Пофазные коды для трёхфазных приборов (РиМ 489) — подпись, когда показываем
// напряжение/ток по фазам A/B/C (см. applyDecodedBuffer() и isThreePhase ниже).
private const val OBIS_VOLTAGE_L1 = "1.0.32.7.0.255"
private const val OBIS_CURRENT_L1 = "1.0.31.7.0.255"
private const val OBIS_NEUTRAL_CURRENT = "1.0.91.7.0.255"
private const val OBIS_ACTIVE_POWER = "1.0.1.7.0.255"
private const val OBIS_REACTIVE_POWER = "1.0.3.7.0.255"
private const val OBIS_APPARENT_POWER = "1.0.9.7.0.255"
private const val OBIS_FREQUENCY = "1.0.14.7.0.255"

/**
 * Экран «Сеть» — соответствует макету wf3_network_relay.png из ТЗ. Помимо изначальных
 * напряжения/тока/мощности/частоты добавлены реактивная и полная мощность, ток нейтрали —
 * реально найдены в буфере счётчика (см. историю диагностики, OBIS 1.0.3.7.0.255 /
 * 1.0.9.7.0.255 / 1.0.91.7.0.255).
 */
@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val params by viewModel.networkParams.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    val countdown by viewModel.turnOnCountdown.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Параметры сети", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { viewModel.refresh() }) { Text("Обновить") }

        // [Android-патч] см. ObisCaption — под каждой величиной её объект из паспорта прибора
        // и точный OBIS-код. Коды здесь не «по стандарту», а те самые, по которым значение
        // реально достаётся из буфера индикации в MeterRepositoryImpl.applyDecodedBuffer():
        // напряжение и ток у этого прибора лежат под НЕСТАНДАРТНЫМИ кодами (1.0.12.7.0.255 и
        // 1.0.11.7.0.255 вместо привычных 1.0.32.7.0.255 / 1.0.31.7.0.255), поэтому коды
        // продублированы здесь как константы рядом с местом показа — если в applyDecodedBuffer()
        // источник когда-нибудь поменяется, подпись обязана поменяться вместе с ним.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // [Android-патч] Трёхфазные приборы (РиМ 489) отдают напряжение и ток ПО ФАЗАМ,
                // а не одним значением. Если фазы есть (isThreePhase) — показываем каждую (A/B/C);
                // иначе одно суммарное значение, как у однофазных 189.xx / AKROS.
                if (params.voltage.isThreePhase) {
                    Text("Напряжение по фазам:")
                    Text("  A: ${"%.2f".format(params.voltage.l1 ?: 0.0)} В   B: ${"%.2f".format(params.voltage.l2 ?: 0.0)} В   C: ${"%.2f".format(params.voltage.l3 ?: 0.0)} В")
                    ObisCaption(OBIS_VOLTAGE_L1)
                } else {
                    Text("Напряжение: ${"%.2f".format(params.voltage.total)} В")
                    ObisCaption(OBIS_VOLTAGE)
                }
                if (params.current.isThreePhase) {
                    Text("Ток по фазам:")
                    Text("  A: ${"%.3f".format(params.current.l1 ?: 0.0)} А   B: ${"%.3f".format(params.current.l2 ?: 0.0)} А   C: ${"%.3f".format(params.current.l3 ?: 0.0)} А")
                    ObisCaption(OBIS_CURRENT_L1)
                } else {
                    Text("Ток: ${"%.3f".format(params.current.total)} А")
                    ObisCaption(OBIS_CURRENT)
                }
                params.neutralCurrentA?.let {
                    Text("Ток нейтрали: ${"%.3f".format(it)} А")
                    ObisCaption(OBIS_NEUTRAL_CURRENT)
                }
                Text("Активная мощность: ${"%.3f".format(params.power.total)} кВт")
                ObisCaption(OBIS_ACTIVE_POWER)
                if (params.power.isThreePhase) {
                    Text("  A: ${"%.3f".format(params.power.l1 ?: 0.0)}   B: ${"%.3f".format(params.power.l2 ?: 0.0)}   C: ${"%.3f".format(params.power.l3 ?: 0.0)} кВт")
                }
                params.reactivePowerKvar?.let {
                    Text("Реактивная мощность: ${"%.3f".format(it)} квар")
                    ObisCaption(OBIS_REACTIVE_POWER)
                }
                params.apparentPowerKva?.let {
                    Text("Полная мощность: ${"%.3f".format(it)} кВА")
                    ObisCaption(OBIS_APPARENT_POWER)
                }
                params.frequencyHz?.let {
                    Text("Частота: ${"%.2f".format(it)} Гц")
                    ObisCaption(OBIS_FREQUENCY)
                }
            }
        }

        Text("Состояние реле", style = MaterialTheme.typography.titleMedium)
        // [Android-патч] см. историю диагностики: ПРЯМОЙ GET на output_state/control_state
        // размыкателя (0.0.96.3.10.255) без DLMS-ассоциации возвращает отказ доступа (0x0D) —
        // подтверждено на ДВУХ разных счётчиках. НО тот же control_state, как выяснилось, заодно
        // приходит КАЖДЫЙ цикл автообновления внутри обычного буфера индикации (0.0.21.0.2.255,
        // который мы и так читаем) — без всякой ассоциации (см. RELAY_CONTROL_STATE_OBIS в
        // GetResponseParser.kt и применение в applyDecodedBuffer()/MeterRepositoryImpl). Поэтому
        // source становится METER_READ уже на первом же успешном цикле после подключения — это
        // САМОЕ достоверное значение (то же, которым руководствуется индикация самого пульта).
        // source==UNKNOWN остаётся честным состоянием экрана ТОЛЬКО до первого такого цикла (или
        // если сам буфер почему-то не разобрался) — чтобы не показать пользователю ложное
        // "ОТКЛЮЧЕНО" вместо "мы ещё не знаем", что для силового оборудования недопустимо.
        Text(
            when {
                relay.source == RelaySource.UNKNOWN -> "НЕИЗВЕСТНО (нет доступа на чтение)"
                relay.isOn -> "ВКЛЮЧЕНО"
                else -> "ОТКЛЮЧЕНО"
            }
        )
        // [Android-патч] см. RELAY_CONTROL_STATE_OBIS — в паспорте прибора этот объект назван
        // «Размыкатель» (класс Disconnect Control), значение берётся из поля control_state,
        // приходящего внутри того же буфера индикации.
        ObisCaption(RELAY_CONTROL_STATE_OBIS)
        Text("Лимит мощности: ${relay.powerLimitKw} кВт")

        countdown?.let { Text("Включение через: $it c") }

        Button(onClick = viewModel::turnRelayOff) { Text("Отключить") }
        Button(onClick = viewModel::turnRelayOn, enabled = relay.remoteTurnOnAllowed || !relay.isOn) {
            Text("Включить")
        }
    }
}
