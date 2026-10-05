package com.gravarty.htsp.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Direct Kotlin port of Kodi pvr.hts / libhts 'htsmsg' binary encoder & decoder.
 *
 * Wire format for an HTSP message:
 * [4 bytes uint32_be: Total Payload Length N]
 * [N bytes: Root Map Payload]
 *
 * Each field in a map:
 * [1 byte: HtsType]
 * [1 byte: Name Length L]
 * [4 bytes uint32_be: Value Length V]
 * [L bytes: Field Name UTF-8]
 * [V bytes: Value Payload]
 */
object HtsMessageCodec {

    fun encodeFrame(msg: HtsMessage): ByteArray {
        val payload = encode(msg)
        val frame = ByteArray(4 + payload.size)
        val buffer = ByteBuffer.wrap(frame)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return frame
    }

    fun encode(msg: HtsMessage): ByteArray {
        val map = mutableMapOf<String, Any>()
        map.putAll(msg.fields)
        msg.method?.let { map["method"] = it }
        msg.seq?.let { map["seq"] = it }
        return encodeMap(map)
    }

    private fun encodeMap(map: Map<String, Any>): ByteArray = encodeFields(map.map { it.key to it.value })

    private fun encodeFields(fields: List<Pair<String, Any>>): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        for ((key, value) in fields) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            require(keyBytes.size <= 255) { "HTSP field name too long: $key" }

            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val valBytes = encodeMap(value as Map<String, Any>)
                    dos.writeByte(HtsType.MAP.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(valBytes.size)
                    dos.write(keyBytes)
                    dos.write(valBytes)
                }
                is List<*> -> {
                    val valBytes = encodeFields(value.filterNotNull().map { "" to it })
                    dos.writeByte(HtsType.LIST.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(valBytes.size)
                    dos.write(keyBytes)
                    dos.write(valBytes)
                }
                is String -> {
                    val valBytes = value.toByteArray(Charsets.UTF_8)
                    dos.writeByte(HtsType.STR.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(valBytes.size)
                    dos.write(keyBytes)
                    dos.write(valBytes)
                }
                is Boolean -> {
                    dos.writeByte(HtsType.BOOL.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(1)
                    dos.write(keyBytes)
                    dos.writeByte(if (value) 1 else 0)
                }
                is Double -> {
                    dos.writeByte(HtsType.FLOAT.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(8)
                    dos.write(keyBytes)
                    dos.writeDouble(value)
                }
                is Float -> {
                    dos.writeByte(HtsType.FLOAT.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(8)
                    dos.write(keyBytes)
                    dos.writeDouble(value.toDouble())
                }
                is Number -> {
                    val valBytes = encodeS64(value.toLong())
                    dos.writeByte(HtsType.S64.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(valBytes.size)
                    dos.write(keyBytes)
                    dos.write(valBytes)
                }
                is ByteArray -> {
                    dos.writeByte(HtsType.BIN.id)
                    dos.writeByte(keyBytes.size)
                    dos.writeInt(value.size)
                    dos.write(keyBytes)
                    dos.write(value)
                }
            }
        }
        dos.flush()
        return baos.toByteArray()
    }

    /**
     * S64 as in tvheadend htsmsg_binary.c: little endian, only as many bytes as needed
     * (0 -> zero bytes, negative -> 8 bytes).
     */
    private fun encodeS64(value: Long): ByteArray {
        val bytes = ArrayList<Byte>(8)
        var temp = value
        while (temp != 0L) {
            bytes.add((temp and 0xFF).toByte())
            temp = temp ushr 8
        }
        return bytes.toByteArray()
    }

    fun decode(buffer: ByteBuffer): HtsMessage {
        val start = buffer.arrayOffset() + buffer.position()
        return decode(buffer.array(), start, buffer.remaining())
    }

    fun decode(a: ByteArray, offset: Int = 0, length: Int = a.size - offset): HtsMessage {
        val start = offset.coerceAtLeast(0)
        val end = (offset + length).coerceAtMost(a.size)
        val fields = LinkedHashMap<String, Any>()
        decodeInto(a, start, end, fields, null)
        val method = fields["method"] as? String
        val seq = (fields["seq"] as? Number)?.toLong()
        return HtsMessage(method, seq, fields)
    }

    /**
     * Decodes fields directly into [map] or [list] (list entries have empty names, tvheadend
     * htsmsg_add_msg(list, NULL, ...)). Binary fields are HtsBin views into [a], no copy.
     * Field header: type (1), name length (1), value length (4, big endian).
     */
    private fun decodeInto(a: ByteArray, from: Int, to: Int, map: MutableMap<String, Any>?, list: MutableList<Any>?) {
        var p = from
        while (p + 6 <= to) {
            val type = a[p].toInt() and 0xFF
            val nameLen = a[p + 1].toInt() and 0xFF
            val valLen = ((a[p + 2].toInt() and 0xFF) shl 24) or ((a[p + 3].toInt() and 0xFF) shl 16) or
                ((a[p + 4].toInt() and 0xFF) shl 8) or (a[p + 5].toInt() and 0xFF)
            p += 6
            val name = if (nameLen == 0) "" else FieldNames.get(a, p, nameLen)
            p += nameLen
            val v = p
            p += valLen

            val value: Any? = when (HtsType.fromId(type)) {
                HtsType.MAP -> LinkedHashMap<String, Any>().also { decodeInto(a, v, v + valLen, it, null) }
                HtsType.S64 -> decodeS64(a, v, valLen)
                HtsType.STR -> String(a, v, valLen, Charsets.UTF_8)
                HtsType.BIN, HtsType.UUID -> HtsBin(a, v, valLen)
                HtsType.LIST -> ArrayList<Any>().also { decodeInto(a, v, v + valLen, null, it) }
                HtsType.FLOAT -> ByteBuffer.wrap(a, v, valLen).double
                HtsType.BOOL -> valLen > 0 && a[v] == 1.toByte()
                null -> null // skip unknown field types
            }
            if (value != null) {
                map?.put(name, value)
                list?.add(value)
            }
        }
    }

    /** S64 as in tvheadend htsmsg_binary.c: little endian, only as many bytes as needed. */
    private fun decodeS64(a: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset + length - 1 downTo offset) {
            value = (value shl 8) or (a[i].toLong() and 0xFF)
        }
        return value
    }
}

/**
 * Field names repeat in every message (eventId, channelId, title, start ...). Instead of a new
 * String per field, a small cache returns the existing instance when the bytes match. Only
 * startup sync allocations change (tens of thousands of eventAdd messages); the decoded
 * messages are identical. Thread-safe: each slot holds an immutable pair, replaced atomically.
 */
internal object FieldNames {
    private class Entry(val bytes: ByteArray, val name: String)

    private const val SIZE = 256 // power of two
    private val slots = arrayOfNulls<Entry>(SIZE)

    fun get(a: ByteArray, offset: Int, length: Int): String {
        var h = length
        for (i in offset until offset + length) h = 31 * h + a[i]
        val index = h and (SIZE - 1)
        val e = slots[index]
        if (e != null && e.bytes.size == length) {
            var same = true
            for (i in 0 until length) if (e.bytes[i] != a[offset + i]) { same = false; break }
            if (same) return e.name
        }
        val bytes = a.copyOfRange(offset, offset + length)
        val name = String(bytes, Charsets.UTF_8)
        slots[index] = Entry(bytes, name)
        return name
    }
}
