package ru.rim.dd.data.local

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.rim.dd.core.model.PairedDevice
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "device_store")

/**
 * Хранит сопряжённые ПУ и последний активный серийный номер.
 * PIN-коды НЕ хранятся в этом файле — см. SecurePinStore
 * (EncryptedSharedPreferences / Keystore, добавляется отдельным классом
 * на этапе реализации UC-01/UC-02 — вынесено намеренно, чтобы секреты
 * не смешивались с обычными настройками).
 */
@Singleton
class DeviceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_LAST_SERIAL = stringPreferencesKey("last_serial_number")

    val lastSerialNumber: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_SERIAL] }

    suspend fun rememberDevice(device: PairedDevice) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SERIAL] = device.serialNumber
            // TODO: список из нескольких устройств — на этапе UC-12 (settings), пока один активный ПУ
        }
    }

    suspend fun forget(serialNumber: String) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_LAST_SERIAL] == serialNumber) prefs.remove(KEY_LAST_SERIAL)
        }
    }
}
