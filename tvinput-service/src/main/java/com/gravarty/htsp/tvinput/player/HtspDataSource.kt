package com.gravarty.htsp.tvinput.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.gravarty.htsp.core.HtspSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class HtspDataSource(
    private val subscription: HtspSubscription,
    private val scope: CoroutineScope
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private val bufferQueue = LinkedBlockingQueue<ByteArray>(2048)
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    private var packetJob: Job? = null
    private var isOpen = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        isOpen = true

        bufferQueue.clear()
        currentChunk = null
        currentOffset = 0

        packetJob = subscription.packets
            .onEach { packet ->
                if (isOpen) {
                    bufferQueue.offer(packet.payload)
                }
            }
            .launchIn(scope)

        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!isOpen) return C.RESULT_END_OF_INPUT

        var bytesRead = 0

        while (bytesRead < length && isOpen) {
            if (currentChunk == null || currentOffset >= currentChunk!!.size) {
                currentChunk = try {
                    bufferQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    throw InterruptedIOException()
                }
                currentOffset = 0
                if (currentChunk == null) {
                    if (bytesRead > 0) break else continue
                }
            }

            val remainingInChunk = currentChunk!!.size - currentOffset
            val toCopy = minOf(length - bytesRead, remainingInChunk)

            System.arraycopy(currentChunk!!, currentOffset, buffer, offset + bytesRead, toCopy)
            currentOffset += toCopy
            bytesRead += toCopy
        }

        if (bytesRead > 0) {
            bytesTransferred(bytesRead)
        }

        return if (bytesRead > 0) bytesRead else 0
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        if (isOpen) {
            isOpen = false
            packetJob?.cancel()
            packetJob = null
            bufferQueue.clear()
            transferEnded()
        }
    }
}
