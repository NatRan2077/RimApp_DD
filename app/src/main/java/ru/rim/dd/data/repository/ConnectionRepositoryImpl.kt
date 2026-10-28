package ru.rim.dd.data.repository

import kotlinx.coroutines.flow.Flow
import ru.rim.dd.core.ble.BleDevice
import ru.rim.dd.core.ble.MeterTransport
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.core.model.PairedDevice
import ru.rim.dd.data.local.DeviceStore
import ru.rim.dd.data.session.MeterSessionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val ble: MeterTransport,
    private val session: MeterSessionManager,
    private val deviceStore: DeviceStore,
) : ConnectionRepository {

    override fun connectionState(): Flow<ConnectionState> = ble.connectionState

    override fun signalStrengthDbm(): Flow<Int?> = ble.signalStrengthDbm

    override fun scanDevices(): Flow<BleDevice> = ble.scan()

    override suspend fun connectByAddress(address: String, pin: String, remember: Boolean, deviceName: String?) {
        val meter = session.connectByAddress(address, deviceName)
        if (remember) rememberDevice(meter.serialNumber, meter.bleAddress)
        session.startPolling()
    }

    override suspend fun connectBySerialNumber(serialNumber: String, pin: String, remember: Boolean) {
        val meter = session.connectBySerialNumber(serialNumber)
        if (remember) rememberDevice(meter.serialNumber, meter.bleAddress)
        session.startPolling()
    }

    private suspend fun rememberDevice(serialNumber: String, bleAddress: String) {
        deviceStore.rememberDevice(PairedDevice(serialNumber = serialNumber, bleAddress = bleAddress))
    }

    override suspend fun disconnect() = session.disconnect()

    override suspend fun forgetDevice(serialNumber: String) {
        deviceStore.forget(serialNumber)
        if (session.activeSerialNumber == serialNumber) disconnect()
    }
}
