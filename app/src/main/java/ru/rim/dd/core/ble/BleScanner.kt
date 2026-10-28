package ru.rim.dd.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.rim.dd.core.log.AppLog

/**
 * Сканирование BLE-эфира (UC-01). Знает только про сам сканер Android; состояние соединения не
 * трогает — о начале и конце поиска сообщает лямбдами, а [MeterBleClient] решает, что показать.
 * Разрешения (BLUETOOTH_SCAN / ACCESS_FINE_LOCATION) запрашивает PairingScreen до вызова.
 *
 * @param adapter текущий Bluetooth-адаптер (null — Bluetooth недоступен на телефоне).
 * @param onStarting вызывается перед запуском сканера.
 * @param onStartFailed сканер не удалось запустить (например, нет разрешения или выключен Bluetooth).
 * @param onStopped поиск закончился: кнопка «Стоп», найден нужный прибор или ошибка сканера.
 */
internal class BleScanner(
    private val adapter: () -> BluetoothAdapter?,
    private val onStarting: () -> Unit,
    private val onStartFailed: () -> Unit,
    private val onStopped: () -> Unit,
) {

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleDevice> = callbackFlow {
        val scanner = adapter()?.bluetoothLeScanner
            ?: run { close(); return@callbackFlow }

        // Новые приборы (AKROS-17…) рекламируются расширенными объявлениями Bluetooth 5
        // (Advertising Extension, вторичный PHY LE 2M), и по умолчанию Android их не показывает.
        // Но телефоны с Bluetooth 4.x расширенных объявлений не умеют: запрос setLegacy(false) на них
        // у части сборок Android молча давал пустой поиск. Поэтому расширенный режим включаем только
        // там, где контроллер его поддерживает, а при отказе сканера возвращаемся к обычному.
        var extended = adapter()?.isLeExtendedAdvertisingSupported == true

        fun settings(withExtended: Boolean): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { if (withExtended) setLegacy(false) }
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleDevice(
                        address = result.device.address,
                        // Имя из рекламы приоритетнее device.name: тот берётся из кэша стека и для
                        // неспаренного прибора часто null, а список и поиск по номеру работают по имени.
                        name = result.scanRecord?.deviceName ?: result.device.name,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                AppLog.e("MeterBleClient", "Сканирование не запустилось, код $errorCode (расширенный режим: $extended)")
                if (extended) {
                    // Расширенный режим не принят — пробуем обычный поиск тем же сканером.
                    extended = false
                    try {
                        scanner.stopScan(this)
                        scanner.startScan(null, settings(false), this)
                        return
                    } catch (ex: Exception) {
                        AppLog.e("MeterBleClient", "Повторный запуск поиска не удался: ${ex.message}")
                    }
                }
                close(IllegalStateException(failureText(errorCode)))
            }
        }

        onStarting()
        try {
            scanner.startScan(null, settings(extended), callback)
        } catch (ex: Exception) {
            // Например, SecurityException без разрешения или IllegalStateException при выключенном Bluetooth.
            onStartFailed()
            throw IllegalStateException("Не удалось начать поиск: ${ex.message ?: "Bluetooth недоступен"}")
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
            onStopped()
        }
    }

    /** Понятное описание кода ошибки ScanCallback.onScanFailed. */
    private fun failureText(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Поиск уже запущен"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Не удалось запустить поиск Bluetooth. Выключите и снова включите Bluetooth на телефоне"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
            "Ошибка Bluetooth на телефоне при поиске. Выключите и снова включите Bluetooth"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Этот телефон не поддерживает такой поиск Bluetooth"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth занят другими приложениями — закройте их и повторите"
        6 -> "Android ограничил частоту поиска Bluetooth — подождите полминуты и повторите"
        else -> "Не удалось начать поиск (код $errorCode)"
    }
}
