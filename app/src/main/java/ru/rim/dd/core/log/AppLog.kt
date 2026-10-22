package ru.rim.dd.core.log

import android.util.Log

/**
 * Главный выключатель логов приложения (Logcat).
 *
 * false — релизный режим: ничего не пишется, в логах нет серийных номеров и дампов буфера.
 * true — для отладки: подробный журнал обмена с прибором (BLE, HDLC, DLMS).
 * Перед сборкой релиза должно быть false.
 */
const val APP_LOGGING_ENABLED = false

/** Обёртка над [Log]: все уровни выключаются одним флагом [APP_LOGGING_ENABLED]. */
object AppLog {
    fun d(tag: String, message: String) {
        if (APP_LOGGING_ENABLED) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (APP_LOGGING_ENABLED) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (APP_LOGGING_ENABLED) Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!APP_LOGGING_ENABLED) return
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    /** Для библиотек, которые сами передают приоритет (журнал Nordic BLE). */
    fun println(priority: Int, tag: String, message: String) {
        if (APP_LOGGING_ENABLED) Log.println(priority, tag, message)
    }
}
