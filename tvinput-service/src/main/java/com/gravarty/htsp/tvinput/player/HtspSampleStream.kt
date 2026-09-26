package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream

@OptIn(UnstableApi::class)
class HtspSampleStream(
    val streamIndex: Int,
    private val sampleQueue: SampleQueue
) : SampleStream {

    override fun isReady(): Boolean = sampleQueue.isReady(/* loadingFinished= */ false)

    override fun maybeThrowError() {
        sampleQueue.maybeThrowError()
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int
    ): Int {
        return sampleQueue.read(formatHolder, buffer, readFlags, /* loadingFinished= */ false)
    }

    override fun skipData(positionUs: Long): Int {
        val count = sampleQueue.getSkipCount(positionUs, /* loadingFinished= */ false)
        sampleQueue.skip(count)
        return count
    }
}
