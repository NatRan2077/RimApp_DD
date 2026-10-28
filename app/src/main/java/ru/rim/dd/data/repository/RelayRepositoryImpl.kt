package ru.rim.dd.data.repository

import ru.rim.dd.core.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import ru.rim.dd.core.coroutines.DispatcherProvider
import ru.rim.dd.core.model.RelaySource
import ru.rim.dd.core.model.RelayState
import ru.rim.dd.data.dlms.DlmsSessionClient
import ru.rim.dd.data.session.MeterSessionManager
import ru.rim.dd.data.state.MeterState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayRepositoryImpl @Inject constructor(
    private val session: MeterSessionManager,
    private val dlms: DlmsSessionClient,
    private val state: MeterState,
    private val dispatchers: DispatcherProvider,
) : RelayRepository {

    override fun relayState(): Flow<RelayState> = state.relay

    /**
     * Включение реле: ACTION method 2 («remote_reconnect») Disconnect Control. Формат
     * подтверждён логом пульта: параметр Integer8 = 0. 60-секундный отсчёт — на стороне ViewModel.
     */
    override suspend fun turnRelayOn() {
        sendDisconnectControlAction(methodId = 2, label = "remote_reconnect/включение")
    }

    /**
     * Отключение реле: ACTION method 1 («remote_disconnect»). В логе пульта нашлась только команда
     * включения, поэтому параметр (Integer8 = 0) собран по аналогии — предположение. Если прибор
     * вернёт ошибку, первым делом стоит попробовать вызов без параметра: по DLMS UA 1000-1 у
     * remote_disconnect параметров нет.
     */
    override suspend fun turnRelayOff() {
        sendDisconnectControlAction(methodId = 1, label = "remote_disconnect/отключение")
    }

    /**
     * Состояние реле после успешного Action-Result (0) обновляем сразу, по факту команды: прямое
     * чтение атрибутов без ассоциации отказывает (0x0D). Реальное значение придёт на следующем
     * автообновлении из буфера индикации и перезапишет предположение.
     */
    private suspend fun sendDisconnectControlAction(methodId: Int, label: String) {
        if (!session.isActive) {
            AppLog.w(TAG, "Размыкатель ($label): нет активного BLE-соединения — команда не отправлена")
            return
        }
        session.exclusive {
            val done = withContext(dispatchers.io) { dlms.disconnectControlAction(methodId, label) }
            if (done) state.updateRelay { it.copy(isOn = methodId == 2, source = RelaySource.REMOTE_CONTROL) }
        }
    }

    private companion object {
        const val TAG = "RelayRepository"
    }
}
