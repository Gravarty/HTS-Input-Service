package com.gravarty.htsp.core

data class HtsMessage(
    val method: String? = null,
    val seq: Long? = null,
    val fields: Map<String, Any> = emptyMap()
) {
    fun getString(key: String): String? = fields[key] as? String
    fun getLong(key: String): Long? = (fields[key] as? Number)?.toLong()
    fun getInt(key: String): Int? = (fields[key] as? Number)?.toInt()
    /** Copy of a binary field (small fields: challenge, file data, meta). */
    fun getByteArray(key: String): ByteArray? = when (val v = fields[key]) {
        is HtsBin -> v.toByteArray()
        is ByteArray -> v
        else -> null
    }

    /** Binary field without copying (packet payload). */
    fun getBin(key: String): HtsBin? = when (val v = fields[key]) {
        is HtsBin -> v
        is ByteArray -> HtsBin(v, 0, v.size)
        else -> null
    }
    @Suppress("UNCHECKED_CAST")
    fun getMap(key: String): Map<String, Any>? = fields[key] as? Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    fun getList(key: String): List<Any>? = fields[key] as? List<Any>
}
