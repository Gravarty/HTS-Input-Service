package com.gravarty.htsp.tvinput.player

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The TV's hardware decoder has one display path. A new decoder created while the previous
 * session's decoder is still alive gets no display path and falls back to GPU output without
 * deinterlacing (MediaTek resource manager: "AcquireDispBuf ... change to planar mode").
 * Only used when a recording starts (live zapping never had the problem). A new player therefore waits until the previous players of this process are released and the
 * resource manager had a moment to free the display path.
 */
object PlayerGate {
    private const val MAX_WAIT_MS = 2_000L
    private const val SETTLE_MS = 250L

    private val alive: MutableSet<ExoPlayer> = Collections.newSetFromMap(ConcurrentHashMap())
    private val aliveCount = MutableStateFlow(0)
    @Volatile private var lastReleaseMs = 0L

    fun onCreated(player: ExoPlayer) {
        alive.add(player)
        aliveCount.value = alive.size
    }

    fun onReleased(player: ExoPlayer) {
        alive.remove(player)
        lastReleaseMs = SystemClock.elapsedRealtime()
        aliveCount.value = alive.size
    }

    /** Suspends until no other player is alive and the last release is [SETTLE_MS] ago (at most [MAX_WAIT_MS]). */
    suspend fun awaitFree() {
        withTimeoutOrNull(MAX_WAIT_MS) { aliveCount.first { it == 0 } }
        val wait = SETTLE_MS - (SystemClock.elapsedRealtime() - lastReleaseMs)
        if (wait > 0) delay(wait)
    }
}
