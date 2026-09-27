package com.gravarty.htsp.tvinput.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import com.gravarty.htsp.core.HtspSubscription
import kotlinx.coroutines.CoroutineScope

@OptIn(UnstableApi::class)
class HtspMediaSource(
    private val subscription: HtspSubscription,
    private val scope: CoroutineScope,
    private val mediaItem: MediaItem = MediaItem.fromUri("htsp://live"),
    private val onAspectChanged: (Int, Int, Int) -> Unit = { _, _, _ -> }
) : BaseMediaSource() {

    @Volatile
    var currentPeriod: HtspMediaPeriod? = null
        private set

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        val timeline: Timeline = SinglePeriodTimeline(
            /* durationUs= */ C.TIME_UNSET,
            /* isSeekable= */ true,
            /* isDynamic= */ true,
            /* isLive= */ true,
            /* manifest= */ null,
            mediaItem
        )
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
        val period = HtspMediaPeriod(subscription, allocator, scope, onAspectChanged)
        currentPeriod = period
        return period
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        if (mediaPeriod is HtspMediaPeriod) {
            mediaPeriod.release()
        }
        currentPeriod = null
    }

    override fun maybeThrowSourceInfoRefreshError() {}

    override fun releaseSourceInternal() {
        currentPeriod?.release()
        currentPeriod = null
    }
}
