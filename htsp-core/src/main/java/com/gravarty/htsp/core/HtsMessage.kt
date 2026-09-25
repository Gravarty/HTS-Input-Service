package com.gravarty.htsp.core

data class HtsMessage(
    val method: String? = null,
    val seq: Long? = null,
    val fields: Map<String, Any> = emptyMap()
) {
    fun getString(key: String): String? = fields[key] as? String
    fun getLong(key: String): Long? = (fields[key] as? Number)?.toLong()
    fun getInt(key: String): Int? = (fields[key] as? Number)?.toInt()
    fun getByteArray(key: String): ByteArray? = fields[key] as? ByteArray
    @Suppress("UNCHECKED_CAST")
    fun getMap(key: String): Map<String, Any>? = fields[key] as? Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    fun getList(key: String): List<Any>? = fields[key] as? List<Any>
}
