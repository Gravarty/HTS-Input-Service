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
        val fields = decodeMap(buffer)
        val method = fields["method"] as? String
        val seq = (fields["seq"] as? Number)?.toLong()
        return HtsMessage(method, seq, fields)
    }

    /** Fields in wire order. List entries have empty names (tvheadend htsmsg_add_msg(list, NULL, ...)). */
    private fun decodeFields(buffer: ByteBuffer): List<Pair<String, Any>> {
        val result = ArrayList<Pair<String, Any>>()
        while (buffer.hasRemaining()) {
            val typeId = buffer.get().toInt() and 0xFF
            val type = HtsType.fromId(typeId)

            val nameLen = buffer.get().toInt() and 0xFF
            val valLen = buffer.int

            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            val valueBytes = ByteArray(valLen)
            buffer.get(valueBytes)
            val valBuffer = ByteBuffer.wrap(valueBytes)

            if (type == null) continue // skip unknown field types

            val value: Any = when (type) {
                HtsType.MAP -> decodeMap(valBuffer)
                HtsType.S64 -> decodeS64(valueBytes)
                HtsType.STR -> String(valueBytes, Charsets.UTF_8)
                HtsType.BIN, HtsType.UUID -> valueBytes
                HtsType.LIST -> decodeList(valBuffer)
                HtsType.FLOAT -> valBuffer.double
                HtsType.BOOL -> valueBytes.firstOrNull() == 1.toByte()
            }
            result.add(name to value)
        }
        return result
    }

    private fun decodeMap(buffer: ByteBuffer): Map<String, Any> =
        LinkedHashMap<String, Any>().apply { decodeFields(buffer).forEach { (k, v) -> put(k, v) } }

    private fun decodeList(buffer: ByteBuffer): List<Any> = decodeFields(buffer).map { it.second }

    private fun decodeS64(bytes: ByteArray): Long {
        var value = 0L
        for (i in bytes.indices.reversed()) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return value
    }
}
