package ru.rim.dd.testutil

import ru.rim.dd.data.local.JournalLine
import ru.rim.dd.data.local.JournalWriter

/** Журнал в памяти: считает вызовы жизненного цикла и хранит последний снимок. */
class FakeJournalWriter : JournalWriter {
    val events = mutableListOf<String>()
    val rawBytes = mutableListOf<ByteArray>()
    var updates = 0
        private set
    var lastSerial: String? = null
        private set
    var lastMeterType: String? = null
        private set
    var commitResult = true

    override fun begin() { events += "begin" }
    override fun discard() { events += "discard" }
    override fun end() { events += "end" }
    override fun commit(): Boolean { events += "commit"; return commitResult }
    override fun appendBytes(chunk: ByteArray) { rawBytes += chunk }

    override fun update(
        meterType: String?,
        serialNumber: String?,
        energy: List<JournalLine>,
        voltage: String?,
        current: String?,
    ) {
        updates++
        lastSerial = serialNumber
        lastMeterType = meterType
    }
}
