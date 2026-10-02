package ru.rim.dd.core.dlms

/**
 * Значение DLMS/COSEM (A-XDR, теги по IEC 62056-6-2). Библиотечные GetResponseInt()/GetResponseFloat()
 * разбирают только простые скаляры, а прибор отвечает вложенными массивами/структурами.
 */
sealed class DlmsValue {
    data class Struct(val items: List<DlmsValue>) : DlmsValue()
    data class Arr(val items: List<DlmsValue>) : DlmsValue()
    data class BoolVal(val value: Boolean) : DlmsValue()
    data class U8(val value: Int) : DlmsValue()
    data class I8(val value: Int) : DlmsValue()
    data class U16(val value: Int) : DlmsValue()
    data class I16(val value: Int) : DlmsValue()
    data class U32(val value: Long) : DlmsValue()
    data class I32(val value: Long) : DlmsValue()
    data class U64(val value: Long) : DlmsValue()
    data class I64(val value: Long) : DlmsValue()
    data class EnumVal(val value: Int) : DlmsValue()
    data class OctetStr(val bytes: ByteArray) : DlmsValue()
    data class VisibleStr(val text: String) : DlmsValue()
    data class Float32Val(val value: Float) : DlmsValue()
    data class Float64Val(val value: Double) : DlmsValue()
    object NullVal : DlmsValue()
}

class DlmsDecodeException(message: String) : Exception(message)

/**
 * Разбирает A-XDR-значение из байтов с произвольной позиции. Поддержаны только нужные типы
 * (bit-string, date-time и т.п. — нет); неизвестный тег — [DlmsDecodeException].
 */
class DlmsDecoder(private val data: ByteArray) {

    /** Значение, начинающееся с [start], и позиция сразу после него. */
    fun decodeAt(start: Int): Pair<DlmsValue, Int> {
        if (start >= data.size) throw DlmsDecodeException("Конец данных при чтении тега (pos=$start)")
        val tag = data[start].toInt() and 0xFF
        val pos = start + 1
        return when (tag) {
            0x00 -> DlmsValue.NullVal to pos
            0x01 -> decodeCollection(pos) { DlmsValue.Arr(it) }
            0x02 -> decodeCollection(pos) { DlmsValue.Struct(it) }
            0x03 -> (DlmsValue.BoolVal(byteAt(pos) != 0) to pos + 1)
            0x05 -> DlmsValue.I32(readSigned(pos, 4)) to pos + 4
            0x06 -> DlmsValue.U32(readUnsigned(pos, 4)) to pos + 4
            0x09 -> decodeOctets(pos) { DlmsValue.OctetStr(it) }
            0x0A -> decodeOctets(pos) { DlmsValue.VisibleStr(String(it, Charsets.US_ASCII)) }
            0x0F -> DlmsValue.I8(data[pos].toInt()) to pos + 1
            0x10 -> DlmsValue.I16(readSigned(pos, 2).toInt()) to pos + 2
            0x11 -> DlmsValue.U8(byteAt(pos)) to pos + 1
            0x12 -> DlmsValue.U16(readUnsigned(pos, 2).toInt()) to pos + 2
            0x14 -> DlmsValue.I64(readSigned(pos, 8)) to pos + 8
            0x15 -> DlmsValue.U64(readUnsigned(pos, 8)) to pos + 8
            0x16 -> DlmsValue.EnumVal(byteAt(pos)) to pos + 1
            0x17 -> DlmsValue.Float32Val(Float.fromBits(readUnsigned(pos, 4).toInt())) to pos + 4
            0x18 -> DlmsValue.Float64Val(Double.fromBits(readUnsigned(pos, 8))) to pos + 8
            else -> throw DlmsDecodeException("Неподдерживаемый DLMS-тег 0x%02X на позиции %d".format(tag, start))
        }
    }

    private fun byteAt(pos: Int): Int {
        if (pos >= data.size) throw DlmsDecodeException("Конец данных на позиции $pos")
        return data[pos].toInt() and 0xFF
    }

    private fun readUnsigned(pos: Int, size: Int): Long {
        if (pos + size > data.size) throw DlmsDecodeException("Конец данных при чтении $size байт с позиции $pos")
        var result = 0L
        for (i in 0 until size) result = (result shl 8) or (data[pos + i].toLong() and 0xFF)
        return result
    }

    private fun readSigned(pos: Int, size: Int): Long {
        val unsigned = readUnsigned(pos, size)
        val signBit = 1L shl (size * 8 - 1)
        return if (unsigned and signBit != 0L) unsigned - (1L shl (size * 8)) else unsigned
    }

    /** A-XDR-длина: байт < 0x80 — сама длина; иначе младшие 7 бит — число следующих байт длины (big-endian). */
    private fun readLength(pos: Int): Pair<Int, Int> {
        val first = byteAt(pos)
        if (first < 0x80) return first to pos + 1
        val extraBytes = first and 0x7F
        val length = readUnsigned(pos + 1, extraBytes).toInt()
        return length to pos + 1 + extraBytes
    }

    private fun decodeCollection(start: Int, ctor: (List<DlmsValue>) -> DlmsValue): Pair<DlmsValue, Int> {
        val (count, afterLen) = readLength(start)
        val items = mutableListOf<DlmsValue>()
        var pos = afterLen
        for (i in 0 until count) {
            try {
                val (value, next) = decodeAt(pos)
                items.add(value)
                pos = next
            } catch (ex: DlmsDecodeException) {
                break
            }
        }
        return ctor(items) to pos
    }

    private fun decodeOctets(start: Int, ctor: (ByteArray) -> DlmsValue): Pair<DlmsValue, Int> {
        val (length, afterLen) = readLength(start)
        if (afterLen + length > data.size) throw DlmsDecodeException("Конец данных при чтении строки байт длиной $length с позиции $afterLen")
        val bytes = data.copyOfRange(afterLen, afterLen + length)
        return ctor(bytes) to afterLen + length
    }
}
