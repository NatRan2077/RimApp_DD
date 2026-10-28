package ru.rim.dd.core.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import ru.rim.dd.core.log.AppLog
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.MeterProtocolProfile

/**
 * GATT-соединение с одним прибором на базе Nordic BleManager: выбор набора UUID (FFE0 у РиМ,
 * ABF0 у AKROS), согласование MTU, сопряжение AKROS, включение notify на TX и запись в RX.
 * Всё, что зависит от конкретной ревизии платы прибора, живёт здесь; [MeterBleClient] об этом
 * не знает и занимается только состоянием связи и переподключением.
 */
internal class MeterGattManager(
    context: Context,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onRssiRead: (Int) -> Unit,
    private val onDisconnected: (reason: Int) -> Unit,
    private val onEncryptionFailure: () -> Unit,
    /** Имя из рекламы (у неспаренного прибора device.name часто null). */
    private val nameHint: String? = null,
) : BleManager(context) {

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** Набор UUID, найденный на приборе; от него зависит, нужно ли сопряжение (см. initialize()). */
    private var activeProfile: MeterUartProfile? = null

    /** Тип записи в RX: WRITE_NO_RESPONSE, если характеристика его поддерживает, иначе запись с ответом. */
    private var rxWriteType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isAkrosPeer(): Boolean =
        activeProfile == GattUuids.PROFILE_ABF0 ||
                (nameHint ?: bluetoothDevice?.name)?.contains(MeterProtocolProfile.AKROS_NAME_MARKER, ignoreCase = true) == true

    /**
     * Входящие notify-кадры. Именно SharedFlow(replay=0) с большим буфером, а не StateFlow:
     * StateFlow отдаёт новому подписчику последний кадр прошлой сессии (после переподключения
     * ответы съезжали на кадр) и конфлейтит сегменты многосегментного ответа, теряя средние.
     * Без подписчиков кадры отбрасываются — устаревшие байты не должны попасть в новую сессию.
     */
    val incomingBytes = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    init {
        // Идентификатор в Connecting/Connected — MAC (device.address): серийный номер на этом
        // этапе ещё не разобран. Выбор Idle/Reconnecting делает MeterBleClient.handleDisconnected(),
        // здесь только репортим сам факт разрыва.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connecting(device.address))
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // Сервисы/notify ещё не настроены — готовность в onDeviceReady().
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                AppLog.w("MeterGattManager", "onDeviceFailedToConnect: reason=$reason")
                onStateChanged(ConnectionState.Error.Other("Не удалось подключиться (код $reason)"))
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                onStateChanged(ConnectionState.Connected(device.address))
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // Состояние меняем в onDeviceDisconnected(), там известна причина.
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                AppLog.w("MeterGattManager", "onDeviceDisconnected: reason=$reason")
                onDisconnected(reason)
            }
        })
    }

    fun requestRssi() {
        readRssi()
            .with { _, rssi -> onRssiRead(rssi) }
            .fail { _, status -> AppLog.w("MeterGattManager", "readRssi() не удался, status=$status") }
            .enqueue()
    }

    override fun log(priority: Int, message: String) {
        AppLog.println(priority, "MeterGattManager", message)
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Полный список сервисов и характеристик — чтобы по логу можно было добавить новую ревизию платы.
        for (service in gatt.services) {
            AppLog.d(
                "MeterGattManager",
                "Сервис ${service.uuid}: " + service.characteristics.joinToString { "${it.uuid} props=${it.properties}" },
            )
        }
        val found = mutableListOf<Triple<MeterUartProfile, BluetoothGattCharacteristic, BluetoothGattCharacteristic>>()
        for (profile in GattUuids.ALL) {
            val service = gatt.getService(profile.service) ?: continue
            val rx = service.getCharacteristic(profile.rxWrite)
            val tx = service.getCharacteristic(profile.txNotify)
            if (rx == null || tx == null) {
                AppLog.w(
                    "MeterGattManager",
                    "Набор ${profile.label}: сервис есть, но характеристики неполные " +
                            "(RX=${rx?.uuid}, TX=${tx?.uuid}) — пробуем следующий",
                )
                continue
            }
            found += Triple(profile, rx, tx)
        }
        // Если на приборе несколько известных наборов (новый AKROS может нести и FFE0, и ABF0),
        // для прибора с «AKROS» в имени берём набор AKROS, для остальных — первый по порядку.
        val akrosName = (nameHint ?: bluetoothDevice?.name)
            ?.contains(MeterProtocolProfile.AKROS_NAME_MARKER, ignoreCase = true) == true
        val chosen = (if (akrosName) found.firstOrNull { it.first == GattUuids.PROFILE_ABF0 } else null)
            ?: found.firstOrNull()
            ?: findGenericUart(gatt)
        if (chosen == null) {
            AppLog.e(
                "MeterGattManager",
                "Ни один из известных профилей обмена не найден на устройстве " +
                        "(искали: ${GattUuids.ALL.joinToString { it.label }}), и сервиса вида «UART» тоже нет. " +
                        "Сервисы прибора: " + gatt.services.joinToString { it.uuid.toString() },
            )
            return false
        }
        val (profile, rx, tx) = chosen
        rxCharacteristic = rx
        txCharacteristic = tx
        activeProfile = profile
        rxWriteType = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        AppLog.i("MeterGattManager", "Профиль обмена: ${profile.label}")
        AppLog.d(
            "MeterGattManager",
            "RX(write)=${rx.uuid}, props=${rx.properties}; " +
                    "TX(notify)=${tx.uuid}, props=${tx.properties}, " +
                    "descriptors=${tx.descriptors.map { it.uuid }}",
        )
        return true
    }

    /**
     * Запасной поиск «прозрачного UART» для неизвестной ревизии платы: первый нестандартный сервис,
     * где есть характеристика с notify (прибор → телефон) и характеристика на запись.
     */
    private fun findGenericUart(
        gatt: BluetoothGatt,
    ): Triple<MeterUartProfile, BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (service in gatt.services) {
            val id = service.uuid.toString().lowercase()
            if (id.startsWith("00001800") || id.startsWith("00001801") || id.startsWith("0000180a")) continue
            val tx = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            } ?: continue
            val rx = service.characteristics.firstOrNull {
                it !== tx && it.properties and
                        (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            } ?: continue
            AppLog.w(
                "MeterGattManager",
                "Известный профиль не найден — берём сервис ${service.uuid} как UART: " +
                        "RX=${rx.uuid}, TX=${tx.uuid} (автоопределение)",
            )
            return Triple(MeterUartProfile("авто ${service.uuid}", service.uuid, tx.uuid, rx.uuid), rx, tx)
        }
        return null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun initialize() {
        requestMtu(247)
            .done { mtu -> AppLog.d("MeterGattManager", "MTU согласован: $mtu") }
            .fail { _, status -> AppLog.w("MeterGattManager", "requestMtu не удался, status=$status (не критично — .split() всё равно спасёт большие записи)") }
            .enqueue()
        // Как у пульта: AKROS принимает данные только после сопряжения (LE Secure Connections с
        // PIN, при legacy-сопряжении разрывает связь с кодом 0x13), а notify включается уже ПОСЛЕ
        // него. PIN запрашивает системное окно Android. Очередь Nordic выполняется по порядку, так
        // что enableNotifications() ниже стартует только когда bond установлен. Явный createBond()
        // (только если связи ещё нет), а не ensureBond(): на ensureBond() очередь после сопряжения
        // зависала — запрос не завершался, хотя bond уже был создан.
        if (isAkrosPeer()) {
            val alreadyBonded = bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED
            AppLog.d(
                "MeterGattManager",
                "AKROS: сопряжение, уже сопряжены=$alreadyBonded (состояние системы: ${bluetoothDevice?.bondState})",
            )
            if (!alreadyBonded) {
                createBond()
                    .done { AppLog.d("MeterGattManager", "AKROS: сопряжение выполнено") }
                    .fail { _, status -> AppLog.e("MeterGattManager", "AKROS: сопряжение не удалось, status=$status") }
                    .enqueue()
            }
        }
        setNotificationCallback(txCharacteristic).with { _, data ->
            data.value?.let {
                AppLog.d("MeterGattManager", "<< notify (${it.size} байт): ${it.toHex()}")
                if (!incomingBytes.tryEmit(it.copyOf())) {
                    AppLog.e(
                        "MeterGattManager",
                        "ПОТЕРЯН входящий кадр (${it.size} байт) — буфер incomingBytes переполнен, " +
                                "транспортный «насос» не успевает читать",
                    )
                }
            }
        }
        enableNotifications(txCharacteristic)
            .before { AppLog.d("MeterGattManager", "Включаем notify на TX") }
            .done { AppLog.d("MeterGattManager", "Notifications на TX включены успешно") }
            .fail { _, status ->
                AppLog.e("MeterGattManager", "enableNotifications провалился, status=$status")
                // У AKROS notify доступен только по шифрованной связи: отказ при «сопряжённом» приборе
                // значит, что ключи не подходят — запрос PIN сам не появится, связь надо пересоздать.
                if (isAkrosPeer() && bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED) {
                    onEncryptionFailure()
                }
            }
            .enqueue()
    }

    fun writeRxCharacteristic(bytes: ByteArray) {
        AppLog.d("MeterGattManager", ">> write (${bytes.size} байт): ${bytes.toHex()}")
        // split() обязателен для кадров длиннее MTU-3 (по умолчанию 20 байт): иначе Android молча
        // обрезает WRITE_NO_RESPONSE, прибор получает битый кадр (FCS) и не отвечает. Разбивка
        // безопасна: «прозрачный UART» копит записи в RX-буфер побайтово.
        writeCharacteristic(
            rxCharacteristic,
            bytes,
            rxWriteType,
        )
            .split()
            .done { AppLog.d("MeterGattManager", ">> write подтверждён (enqueue done)") }
            .fail { _, status -> AppLog.e("MeterGattManager", ">> write ПРОВАЛИЛСЯ, status=$status") }
            .enqueue()
    }
}

/** Hex-дамп для логов: "7E A0 07 ...". */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
