package ru.rim.dd.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appSettingsStore by preferencesDataStore(name = "app_settings")

/**
 * [Android-патч] Пользовательские настройки ВИДА приложения (перенос дизайна из Figma):
 *  - showObis   — показывать ли OBIS-коды под величинами (тумблер на экране «Настройки»);
 *  - darkTheme  — тёмная/светлая тема.
 *
 * Отдельно от DeviceStore (сопряжённые приборы) намеренно: это настройки интерфейса, а не
 * данные о приборах. Хранится в DataStore, чтобы выбор пользователя переживал перезапуск.
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_SHOW_OBIS = booleanPreferencesKey("show_obis")
    private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")

    /** По умолчанию OBIS-коды скрыты (как в макете), тема — тёмная (как в макете). */
    val showObis: Flow<Boolean> = context.appSettingsStore.data.map { it[KEY_SHOW_OBIS] ?: false }
    val darkTheme: Flow<Boolean> = context.appSettingsStore.data.map { it[KEY_DARK_THEME] ?: true }

    suspend fun setShowObis(value: Boolean) {
        context.appSettingsStore.edit { it[KEY_SHOW_OBIS] = value }
    }

    suspend fun setDarkTheme(value: Boolean) {
        context.appSettingsStore.edit { it[KEY_DARK_THEME] = value }
    }
}
