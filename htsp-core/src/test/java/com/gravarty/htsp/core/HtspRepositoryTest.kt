package com.gravarty.htsp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspRepositoryTest {

    @Test
    fun testHandleChannelAndEventAdd() {
        val repo = HtspRepository()

        val channelMsg = HtsMessage(
            method = "channelAdd",
            fields = mapOf(
                "channelId" to 101L,
                "channelName" to "Das Erste HD",
                "channelNumber" to 1L
            )
        )

        val eventMsg = HtsMessage(
            method = "eventAdd",
            fields = mapOf(
                "eventId" to 5001L,
                "channelId" to 101L,
                "title" to "Tagesschau",
                "start" to 1700000000L,
                "stop" to 1700000900L
            )
        )

        repo.handleAsyncMessage(channelMsg)
        repo.handleAsyncMessage(eventMsg)
        repo.handleAsyncMessage(HtsMessage(method = "initialSyncCompleted"))

        val channel = repo.channels.value[101L]
        assertEquals("Das Erste HD", channel?.name)
        assertEquals(1, channel?.number)

        val event = repo.events.value[5001L]
        assertEquals("Tagesschau", event?.title)
        assertEquals(101L, event?.channelId)

        assertTrue(repo.isInitialSyncCompleted.value)
    }
}
