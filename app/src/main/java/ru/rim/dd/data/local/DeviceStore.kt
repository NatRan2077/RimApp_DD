package ru.rim.dd.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.rim.dd.core.model.PairedDevice
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "device_store")

/** Хранит последний активный серийный номер ПУ. PIN-коды здесь не хранятся. */
@Singleton
class DeviceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_LAST_SERIAL = stringPreferencesKey("last_serial_number")

    private val KEY_RECENT_SERIALS = stringPreferencesKey("recent_entered_serials")

    val lastSerialNumber: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_SERIAL] }

    /** Последние [MAX_RECENT_SERIALS] номеров, введённых вручную; самый свежий — первый. */
    val recentEnteredSerials: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECENT_SERIALS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** Запоминает введённый номер: дубликат поднимается наверх, хранится не больше трёх записей. */
    suspend fun addRecentEnteredSerial(serial: String) {
        val clean = serial.trim().filter { it.isDigit() }
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val old = prefs[KEY_RECENT_SERIALS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(clean) + old.filter { it != clean }).take(MAX_RECENT_SERIALS)
            prefs[KEY_RECENT_SERIALS] = updated.joinToString(",")
        }
    }

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

    private companion object {
        const val MAX_RECENT_SERIALS = 3
    }
}
