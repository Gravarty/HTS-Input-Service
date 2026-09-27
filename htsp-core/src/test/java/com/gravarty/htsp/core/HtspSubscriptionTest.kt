package com.gravarty.htsp.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HtspSubscriptionTest {

    @Test
    fun testHandleSubscriptionStartAndMuxPkt() = runTest {
        val connection = HtspConnection(host = "127.0.0.1", port = 9982)
        val subscription = HtspSubscription(connection, subscriptionId = 42L)

        val startMsg = HtsMessage(
            method = "subscriptionStart",
            fields = mapOf(
                "subscriptionId" to 42L,
                "streams" to listOf(
                    mapOf(
                        "index" to 0L,
                        "type" to "H264",
                        "width" to 1920L,
                        "height" to 1080L
                    )
                )
            )
        )

        val pktMsg = HtsMessage(
            method = "muxpkt",
            fields = mapOf(
                "subscriptionId" to 42L,
                "stream" to 0L,
                "pts" to 1000000L,
                "frametype" to 'I'.code.toLong(),
                "payload" to byteArrayOf(0x00, 0x00, 0x00, 0x01)
            )
        )

        subscription.handleMessage(startMsg)
        assertTrue(subscription.isSubscribed.value)
        assertEquals(1, subscription.streams.value.size)
        assertEquals("H264", subscription.streams.value[0].type)
        assertEquals(1920, subscription.streams.value[0].width)

        subscription.handleMessage(pktMsg)
        val packet = subscription.packets.first()
        assertEquals(42L, packet.subscriptionId)
        assertEquals(0, packet.streamIndex)
        assertEquals(1000000L, packet.pts)
        assertTrue(packet.isKeyframe)
    }
}
