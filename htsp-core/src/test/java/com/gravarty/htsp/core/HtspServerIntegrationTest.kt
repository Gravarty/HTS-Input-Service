package com.gravarty.htsp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer

class HtspServerIntegrationTest {

    @Test
    fun testFullHandshakeAndMetadataSyncWithMockServer() = runBlocking(Dispatchers.IO) {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverJob = launch(Dispatchers.IO) {
            val socket = serverSocket.accept()
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            val helloLen = input.readInt()
            val helloPayload = ByteArray(helloLen)
            input.readFully(helloPayload)
            val helloMsg = HtsMessageCodec.decode(ByteBuffer.wrap(helloPayload))
            assertEquals("hello", helloMsg.method)

            val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val helloResp = HtsMessage(
                seq = helloMsg.seq,
                fields = mapOf(
                    "htspversion" to 28L,
                    "servername" to "MockTvheadend",
                    "challenge" to challenge
                )
            )
            output.write(HtsMessageCodec.encodeFrame(helloResp))
            output.flush()

            val authLen = input.readInt()
            val authPayload = ByteArray(authLen)
            input.readFully(authPayload)
            val authMsg = HtsMessageCodec.decode(ByteBuffer.wrap(authPayload))
            assertEquals("authenticate", authMsg.method)

            val authResp = HtsMessage(seq = authMsg.seq, fields = emptyMap())
            output.write(HtsMessageCodec.encodeFrame(authResp))
            output.flush()

            val epgLen = input.readInt()
            val epgPayload = ByteArray(epgLen)
            input.readFully(epgPayload)
            val epgMsg = HtsMessageCodec.decode(ByteBuffer.wrap(epgPayload))
            assertEquals("enableAsyncMetadata", epgMsg.method)

            val epgResp = HtsMessage(seq = epgMsg.seq, fields = emptyMap())
            output.write(HtsMessageCodec.encodeFrame(epgResp))

            val channelAdd = HtsMessage(
                method = "channelAdd",
                fields = mapOf(
                    "channelId" to 1L,
                    "channelName" to "ZDF HD",
                    "channelNumber" to 2L
                )
            )
            output.write(HtsMessageCodec.encodeFrame(channelAdd))

            val initialCompleted = HtsMessage(method = "initialSyncCompleted")
            output.write(HtsMessageCodec.encodeFrame(initialCompleted))
            output.flush()

            socket.close()
            serverSocket.close()
        }

        val repo = HtspRepository()
        val client = HtspConnection("127.0.0.1", port, "user", "pass")

        val collectJob = launch(Dispatchers.IO) {
            client.asyncMessages.collect { msg ->
                repo.handleAsyncMessage(msg)
            }
        }

        val connected = client.connect()
        assertTrue(connected)

        val metadataEnabled = client.enableAsyncMetadata()
        assertTrue(metadataEnabled)

        val isCompleted = repo.isInitialSyncCompleted.first { it }
        assertTrue(isCompleted)

        val zdfChannel = repo.channels.value[1L]
        assertEquals("ZDF HD", zdfChannel?.name)
        assertEquals(2, zdfChannel?.number)

        collectJob.cancel()
        client.disconnect()
        serverJob.join()
    }
}
