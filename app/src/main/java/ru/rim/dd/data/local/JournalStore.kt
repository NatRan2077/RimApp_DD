package ru.rim.dd.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Одна строка показаний в карточке журнала: «A+ · Т1» → «1 234,567 кВт·ч». */
data class JournalLine(val label: String, val value: String)

/**
 * Карточка журнала — одно подключение к прибору. Время берётся с телефона.
 */
data class JournalEntry(
    val id: Long,
    val meterType: String,
    val serialNumber: String,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    val lastUpdateAt: Long,
    val energy: List<JournalLine>,
    val voltage: String?,
    val current: String?,
    val rawBytes: Long,
    /** true — это подключение идёт прямо сейчас (проставляется при публикации списка, на диск не пишется). */
    val isActive: Boolean = false,
) {
    /** Сколько байт в HEX-логе (rawBytes — размер текста: по 3 символа на байт, кроме последнего). */
    val byteCount: Long get() = if (rawBytes > 0) (rawBytes + 1) / 3 else 0
}

/**
 *
 * Все методы потокобезопасны (общий lock) и блокируют вызывающий поток на файловой работе,
 * поэтому вызывать их нужно не с главного потока.
 */
@Singleton
class JournalStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val dir: File get() = File(context.filesDir, "journal").also { it.mkdirs() }
    private val exportDir: File get() = File(context.cacheDir, "journal_export").also { it.mkdirs() }

    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())

    /** Все карточки, новые сверху. */
    val entries: StateFlow<List<JournalEntry>> = _entries.asStateFlow()

    private var loaded = false
    private var rawOut: FileOutputStream? = null
    private var rawSize = 0L

    /** Перечитывает журнал с диска (вызывается при открытии экрана). */
    fun refresh() = synchronized(lock) {
        loadLocked()
        publishLocked()
    }

    fun begin() = synchronized(lock) {
        loadLocked()
        endLocked()
        rawSize = 0
        publishLocked()
    }

    fun discard() = synchronized(lock) {
        closeRawLocked()
        publishLocked()
    }

    fun end() = synchronized(lock) {
        loadLocked()
        endLocked()
        publishLocked()
    }

    /** Добавляет пришедшие байты в лог: HEX, через пробел, чанки подряд одним потоком. */
    fun appendBytes(chunk: ByteArray) {
        synchronized(lock) {
            val out = rawOut ?: return
            val sb = StringBuilder(chunk.size * 3 + 1)
            if (rawSize > 0) sb.append(' ')
            for (i in chunk.indices) {
                if (i > 0) sb.append(' ')
                val v = chunk[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            val bytes = sb.toString().toByteArray(Charsets.US_ASCII)
            try {
                out.write(bytes)
                rawSize += bytes.size
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось записать лог байтов: ${e.message}")
                return
            }
            if (rawSize > MAX_RAW_FILE_BYTES) trimRawLocked()
        }
    }

    fun update(
        meterType: String?,
        serialNumber: String?,
        energy: List<JournalLine>,
        voltage: String?,
        current: String?,
    ) = synchronized(lock) {
    }

    /** Удаляет выбранные карточки (вместе с логами байтов); активное подключение не трогает. */
    fun delete(ids: Set<Long>) = synchronized(lock) {
        loadLocked()
            jsonFile(it).delete()
            rawFile(it).delete()
        }
        publishLocked()
    }

    /** Очищает журнал целиком, кроме идущего сейчас подключения. */
    fun clearAll() = synchronized(lock) {
        loadLocked()
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension.toLongOrNull()
        }
        publishLocked()
    }

    /** Файл .txt с карточками журнала (все, если [ids] == null) — для отправки. */
    fun exportText(ids: Set<Long>?): File? = synchronized(lock) {
        loadLocked()
        val list = _entries.value.filter { ids == null || it.id in ids }
        if (list.isEmpty()) return@synchronized null
        val sb = StringBuilder()
        sb.appendLine("ЖУРНАЛ ПОДКЛЮЧЕНИЙ — РиМ Дистанционный дисплей")
        sb.appendLine("Сформирован: ${formatTime(System.currentTimeMillis())} (время телефона)")
        sb.appendLine("Записей: ${list.size}")
        list.forEach { e ->
            sb.appendLine()
            sb.appendLine("==============================================")
            sb.appendLine("Прибор: ${e.meterType}    № ${e.serialNumber}")
            sb.appendLine("Подключено: ${formatTime(e.connectedAt)}")
            val end = e.disconnectedAt
            sb.appendLine(
                "Отключено:  " + when {
                    end != null -> formatTime(end)
                    e.isActive -> "— (подключение активно)"
                    else -> "${formatTime(e.lastUpdateAt)} (связь прервана, приложение закрыто)"
                }
            )
            sb.appendLine()
            sb.appendLine("Электроэнергия:")
            if (e.energy.isEmpty()) sb.appendLine("  нет данных")
            e.energy.forEach { sb.appendLine("  ${it.label}: ${it.value}") }
            sb.appendLine()
            sb.appendLine("Напряжение: ${e.voltage ?: "нет данных"}")
            sb.appendLine("Ток: ${e.current ?: "нет данных"}")
        }
        val file = File(exportDir, "RimApp_journal_${stamp()}.txt")
        file.writeText(sb.toString(), Charsets.UTF_8)
        file
    }

    /** Файл .txt с HEX-логом одного подключения — «просто лог» байтов одной строкой. */
    fun exportRaw(id: Long): File? = synchronized(lock) {
        val src = rawFile(id)
        if (!src.exists() || src.length() == 0L) return@synchronized null
        val e = _entries.value.firstOrNull { it.id == id }
        val name = "RimApp_bytes_${e?.serialNumber?.takeIf { it != "—" } ?: id}_${stamp()}.txt"
        val file = File(exportDir, name)
        src.copyTo(file, overwrite = true)
        file
    }

    // ---- внутреннее ----

    private fun jsonFile(id: Long) = File(dir, "$id.json")
    private fun rawFile(id: Long) = File(dir, "$id.hex")

    private fun closeRawLocked() {
        runCatching { rawOut?.close() }
        rawOut = null
    }

    private fun endLocked() {
        closeRawLocked()
        }

    /** Лог не растёт бесконечно: при превышении предела остаётся «хвост» — последние байты. */
    private fun trimRawLocked() {
        try {
            closeRawLocked()
            val f = rawFile(cur.id)
            val tail = ByteArray(RAW_KEEP_BYTES)
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(raf.length() - RAW_KEEP_BYTES)
                raf.readFully(tail)
            }
            // Начинаем с границы байта (после пробела), а не с середины «7E».
            var start = tail.indexOf(' '.code.toByte()) + 1
            if (start <= 0) start = 0
            val kept = tail.copyOfRange(start, tail.size)
            FileOutputStream(f, false).use { it.write(kept) }
            rawOut = FileOutputStream(f, true)
            rawSize = kept.size.toLong()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обрезать лог байтов: ${e.message}")
            rawOut = runCatching { FileOutputStream(rawFile(cur.id), true) }.getOrNull()
        }
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        // Незакрытые карточки прошлых запусков закрываем временем последнего чтения.
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            val e = parse(f) ?: return@forEach
                saveLocked(e.copy(disconnectedAt = e.lastUpdateAt))
            }
        }
    }

                ?: emptyList()
    }

    private fun saveLocked(e: JournalEntry) {
        runCatching {
            val o = JSONObject()
                .put("id", e.id)
                .put("type", e.meterType)
                .put("serial", e.serialNumber)
                .put("connectedAt", e.connectedAt)
                .put("disconnectedAt", e.disconnectedAt ?: JSONObject.NULL)
                .put("lastUpdateAt", e.lastUpdateAt)
                .put("voltage", e.voltage ?: JSONObject.NULL)
                .put("current", e.current ?: JSONObject.NULL)
                .put("rawBytes", e.rawBytes)
                .put(
                    "energy",
                    JSONArray().apply {
                        e.energy.forEach { put(JSONObject().put("l", it.label).put("v", it.value)) }
                    },
                )
            val target = jsonFile(e.id)
            val tmp = File(dir, "${e.id}.tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(o.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Не удалось сохранить карточку журнала: ${it.message}") }
    }

    private fun parse(f: File): JournalEntry? = runCatching {
        val o = JSONObject(f.readText(Charsets.UTF_8))
        val arr = o.optJSONArray("energy") ?: JSONArray()
        JournalEntry(
            id = o.getLong("id"),
            meterType = o.optString("type", "—"),
            serialNumber = o.optString("serial", "—"),
            connectedAt = o.getLong("connectedAt"),
            disconnectedAt = if (o.isNull("disconnectedAt")) null else o.getLong("disconnectedAt"),
            lastUpdateAt = o.optLong("lastUpdateAt", o.getLong("connectedAt")),
            energy = (0 until arr.length()).map {
                val item = arr.getJSONObject(it)
                JournalLine(item.getString("l"), item.getString("v"))
            },
            voltage = if (o.isNull("voltage")) null else o.getString("voltage"),
            current = if (o.isNull("current")) null else o.getString("current"),
            rawBytes = o.optLong("rawBytes", 0),
        )
    }.getOrNull()

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private companion object {
        const val TAG = "JournalStore"
        val HEX = "0123456789ABCDEF".toCharArray()

        /** Предел файла лога одного подключения; при превышении остаётся последняя половина. */
        const val MAX_RAW_FILE_BYTES = 4L * 1024 * 1024
        const val RAW_KEEP_BYTES = 2 * 1024 * 1024
    }
}
