package com.gravarty.htsp.tvinput.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspVfs
import com.gravarty.htsp.provider.HtspRecordingMapper
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Plays a recording through HTSP file access, like pvr.hts HTSPVFS (fileOpen "dvr/<id>",
 * fileSeek, fileRead). ExoPlayer detects the container itself (Kodi: ffmpeg), so recordings
 * made with any tvheadend profile (TS, Matroska, ...) play.
 * [growing]: recording still running -> no fixed length.
 */
@OptIn(UnstableApi::class)
class HtspVfsDataSource(
    private val connection: HtspConnection,
    private val growing: Boolean
) : BaseDataSource(/* isNetwork= */ true) {

    private var vfs: HtspVfs? = null
    private var uri: Uri? = null
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var eof = false

    override fun open(dataSpec: DataSpec): Long {
        val id = HtspRecordingMapper.recordingIdFromDataUri(dataSpec.uri)
            ?: throw IOException("Not a recording URI: ${dataSpec.uri}")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val v = HtspVfs(connection, id)
        runBlocking {
            connection.ensureConnected()
            if (!v.open()) throw IOException("fileOpen dvr/$id failed")
            if (dataSpec.position > 0 && v.seek(dataSpec.position) < 0) {
                v.close(); throw IOException("fileSeek dvr/$id failed")
            }
        }
        vfs = v
        buffer = ByteArray(0); bufferPos = 0; eof = false

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            growing -> C.LENGTH_UNSET.toLong()
            else -> runBlocking { v.size() }.let { if (it < 0) C.LENGTH_UNSET.toLong() else it - dataSpec.position }
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufferPos >= buffer.size) {
            if (eof) return C.RESULT_END_OF_INPUT
            val chunk = runBlocking { vfs?.read(READ_SIZE) } ?: throw IOException("fileRead failed")
            if (chunk.isEmpty()) { eof = true; return C.RESULT_END_OF_INPUT }
            buffer = chunk; bufferPos = 0
        }
        var n = minOf(length, buffer.size - bufferPos)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) n = minOf(n.toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, bufferPos, target, offset, n)
        bufferPos += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val v = vfs ?: return
        vfs = null
        uri = null
        runBlocking { v.close() }
        transferEnded()
    }

    class Factory(private val connection: HtspConnection, private val growing: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = HtspVfsDataSource(connection, growing)
    }

    private companion object {
        /** bytes per fileRead round trip */
        const val READ_SIZE = 512 * 1024
    }
}
