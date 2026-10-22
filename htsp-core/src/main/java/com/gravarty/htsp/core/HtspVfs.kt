package com.gravarty.htsp.core

/**
 * Recording file access, pvr.hts HTSPVFS: fileOpen "dvr/<id>", fileRead, fileSeek, fileStat,
 * fileClose. Not thread-safe; one reader at a time.
 */
class HtspVfs(private val connection: HtspConnection, recordingId: Long) {
    private val path = "dvr/$recordingId"
    private var fileId: Long? = null

    /** Returns false if the server could not open the recording. */
    suspend fun open(): Boolean {
        val m = connection.sendRequest("fileOpen", mapOf("file" to path))
        if (m.getString("error") != null) return false
        fileId = m.getLong("id") ?: return false
        return true
    }

    /** File size in bytes (fileStat), -1 if unknown. Grows while a recording is running. */
    suspend fun size(): Long {
        val id = fileId ?: return -1
        val m = connection.sendRequest("fileStat", mapOf("id" to id))
        return m.getLong("size") ?: -1
    }

    /** Reads up to [length] bytes; empty array = end of file, null = error. */
    suspend fun read(length: Int): ByteArray? {
        val id = fileId ?: return null
        val m = connection.sendRequest("fileRead", mapOf("id" to id, "size" to length.toLong()))
        if (m.getString("error") != null) return null
        return m.getByteArray("data") ?: ByteArray(0)
    }

    /** SEEK_SET like pvr.hts' default whence; returns the new offset or -1. */
    suspend fun seek(offset: Long): Long {
        val id = fileId ?: return -1
        val m = connection.sendRequest("fileSeek", mapOf("id" to id, "offset" to offset))
        return m.getLong("offset") ?: -1
    }

    suspend fun close() {
        val id = fileId ?: return
        fileId = null
        // pvr.hts SendFileClose (dvr_playstatus on): HTSP_DVR_PLAYCOUNT_KEEP, otherwise tvheadend
        // increments playcount on every close; the play count is set via updateDvrEntry instead
        val m = if (connection.serverProtocol >= 27) mapOf("id" to id, "playcount" to PLAYCOUNT_KEEP)
                else mapOf("id" to id)
        runCatching { connection.sendRequest("fileClose", m) }
    }

    companion object {
        /** tvheadend htsp_server.h HTSP_DVR_PLAYCOUNT_KEEP (INT32_MAX - 1) */
        private const val PLAYCOUNT_KEEP = 2147483646L
    }
}
