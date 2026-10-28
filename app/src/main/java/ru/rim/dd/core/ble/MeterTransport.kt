package ru.rim.dd.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rim.dd.core.model.ConnectionState

/**
 * Транспорт «телефон ⇄ прибор»: всё, что сеансу нужно от Bluetooth, без единого Android-класса.
 * Боевая реализация — [MeterBleClient]; в тестах её заменяет фейк, который отвечает заранее
 * заданными байтами и «рвёт связь» по команде, поэтому логику переподключений можно проверять
 * без прибора и без телефона.
 *
 * О DLMS/СПОДЭС транспорт не знает: он только передаёт байты.
 */
interface MeterTransport {

    /** Состояние соединения для экранов и для пересборки сеанса после автопереподключения. */
    val connectionState: StateFlow<ConnectionState>

    /** RSSI активного соединения; null — соединения нет. */
    val signalStrengthDbm: StateFlow<Int?>

    /**
     * Эмитит при каждом НЕОЖИДАННОМ разрыве (не при явном [disconnect]): сеанс должен остановить
     * протокольный обмен и пересобрать его, когда состояние снова станет Connected.
     */
    val unexpectedDisconnects: SharedFlow<Unit>

    /** Сканирование эфира (UC-01). Ошибка сканера приходит исключением с понятным текстом. */
    fun scan(): Flow<BleDevice>

    /** Подключение по MAC-адресу без сканирования (UC-01). [nameHint] — имя из рекламы. */
    suspend fun connectByAddress(address: String, nameHint: String? = null)

    /**
     * Подключение к прибору с ТОЧНО совпадающим серийным номером в имени (UC-02). Нет такого в
     * эфире за [timeoutMs] — исключение: к чужому прибору подключаться нельзя.
     */
    suspend fun connectBySerialNumber(serialNumber: String, timeoutMs: Long = SCAN_BY_SERIAL_TIMEOUT_MS): BleDevice

    /**
     * Принудительный разрыв, когда протокол считает сессию мёртвой при рабочем GATT: дальше
     * действует обычное автопереподключение. [cold] — с паузой перед первой попыткой.
     */
    fun forceReconnect(cold: Boolean = false)

    /** Отправка кадра прибору (запись в RX-характеристику). Без соединения ничего не делает. */
    fun writeBytes(bytes: ByteArray)

    /** Входящие кадры (notify). Бросает исключение, если соединения нет. */
    fun incomingBytes(): Flow<ByteArray>

    /** Асинхронный запрос RSSI; результат придёт в [signalStrengthDbm]. */
    fun requestRssiRead()

    /** Явное отключение пользователем: автопереподключение не запускается. */
    fun disconnect()

    companion object {
        /** Время поиска прибора по номеру: нужный может откликнуться не первым. */
        const val SCAN_BY_SERIAL_TIMEOUT_MS = 15_000L
    }
}
