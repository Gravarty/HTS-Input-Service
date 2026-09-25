package com.gravarty.htsp.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HtspConnection(
    private val host: String,
    private val port: Int = 9982,
    private val username: String = "",
    private val password: String = "",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val seqCounter = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<HtsMessage>>()

    @Volatile
    private var state = HtspConnectionState.DISCONNECTED
    private val connectMutex = Mutex()
    private val writeLock = Any()

    /** true if the last connect() reached the server but the login was rejected. */
    @Volatile
    var authFailed = false
        private set

    /** "webroot" from the hello reply, used for web URLs like pvr.hts GetWebURL(). */
    @Volatile
    var webRoot: String = ""
        private set
    private var listener: HtspConnectionListener? = null
    private var pingJob: Job? = null

    private val _asyncMessages = MutableSharedFlow<HtsMessage>(extraBufferCapacity = 512)
    val asyncMessages: SharedFlow<HtsMessage> = _asyncMessages.asSharedFlow()

    fun setListener(listener: HtspConnectionListener) {
        this.listener = listener
    }

    fun getState(): HtspConnectionState = state

    private val _stateFlow = MutableStateFlow(HtspConnectionState.DISCONNECTED)
    val stateFlow: StateFlow<HtspConnectionState> = _stateFlow.asStateFlow()

    /** Connects only if not already authenticated. Safe to call from several callers. */
    suspend fun ensureConnected(): Boolean = connectMutex.withLock {
        if (state == HtspConnectionState.AUTHENTICATED) true else connect()
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        authFailed = false
        try {
            updateState(HtspConnectionState.CONNECTING)
            val s = Socket(host, port)
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())

            updateState(HtspConnectionState.CONNECTED)

            scope.launch(Dispatchers.IO) { readLoop() }

            val helloMsg = sendRequest(
                "hello", mapOf(
                    "htspversion" to 28L,
                    "clientname" to "pvr.hts-android",
                    "clientversion" to "1.0.0"
                )
            )

            webRoot = helloMsg.getString("webroot") ?: ""
            val challenge = helloMsg.getByteArray("challenge")

            if (username.isNotEmpty() && challenge != null) {
                val digest = computeSha1Digest(password, challenge)
                val authMsg = sendRequest(
                    "authenticate", mapOf(
                        "username" to username,
                        "digest" to digest
                    )
                )

                val noAuth = authMsg.getInt("noaccess") ?: 0
                if (noAuth != 0) {
                    System.err.println("[HTSP] Authentication failed! Access denied.")
                    authFailed = true
                    updateState(HtspConnectionState.DISCONNECTED)
                    return@withContext false
                }
            }

            updateState(HtspConnectionState.AUTHENTICATED)
            startPingLoop()
            true
        } catch (e: Exception) {
            System.err.println("[HTSP] Failed to connect to $host:$port: ${e.message}")
            updateState(HtspConnectionState.DISCONNECTED)
            listener?.onError(e)
            false
        }
    }

    /** Like pvr.hts CTvheadend::Connected(): epg=1 plus epgMaxTime = now + range (0 = unlimited). */
    suspend fun enableAsyncMetadata(epg: Boolean = true, epgMaxTimeSeconds: Long = 0): Boolean {
        if (state != HtspConnectionState.AUTHENTICATED && state != HtspConnectionState.CONNECTED) return false
        val params = mutableMapOf<String, Any>("epg" to if (epg) 1L else 0L)
        if (epg && epgMaxTimeSeconds > 0) {
            params["epgMaxTime"] = System.currentTimeMillis() / 1000 + epgMaxTimeSeconds
        }
        val response = sendRequest("enableAsyncMetadata", params)
        val success = response.getString("error") == null
        return success
    }

    suspend fun sendRequest(method: String, params: Map<String, Any> = emptyMap()): HtsMessage {
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<HtsMessage>()
        pendingRequests[seq] = deferred

        val msg = HtsMessage(method = method, seq = seq, fields = params)
        val frame = HtsMessageCodec.encodeFrame(msg)

        // pvr.hts sends under its connection mutex; concurrent writes from session,
        // metadata loop and ping must not interleave on the socket.
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                output?.write(frame)
                output?.flush()
            }
        }

        return withTimeout(15000) {
            deferred.await()
        }
    }

    /**
     * Reads a server file via HTSP fileOpen/fileRead/fileClose
     * (e.g. "imagecache/123" for channel icons). Uses the HTSP login, no HTTP auth needed.
     */
    suspend fun readFile(path: String, chunkSize: Long = 65536L): ByteArray? {
        val open = sendRequest("fileOpen", mapOf("file" to path))
        if (open.getString("error") != null) return null
        val id = open.getLong("id") ?: return null
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val read = sendRequest("fileRead", mapOf("id" to id, "size" to chunkSize))
                if (read.getString("error") != null) return null
                val data = read.getByteArray("data") ?: break
                if (data.isEmpty()) break
                out.write(data)
                if (data.size < chunkSize) break
            }
        } finally {
            runCatching { sendRequest("fileClose", mapOf("id" to id)) }
        }
        return out.toByteArray()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && state == HtspConnectionState.AUTHENTICATED) {
                delay(15000)
                try {
                    sendRequest("ping")
                } catch (e: Exception) {
                    System.err.println("[HTSP] Ping ignored/unsupported by server: ${e.message}")
                }
            }
        }
    }

    private suspend fun readLoop() {
        val inStream = input ?: return
        try {
            while (socket?.isConnected == true && !socket!!.isClosed) {
                val length = inStream.readInt()
                val payload = ByteArray(length)
                inStream.readFully(payload)

                val msg = HtsMessageCodec.decode(ByteBuffer.wrap(payload))
                val seq = msg.seq

                if (seq != null && pendingRequests.containsKey(seq)) {
                    pendingRequests.remove(seq)?.complete(msg)
                } else {
                    _asyncMessages.emit(msg)
                    listener?.onAsyncMessage(msg)
                }
            }
        } catch (e: Exception) {
            System.err.println("[HTSP] Read loop error: ${e.message}")
            updateState(HtspConnectionState.DISCONNECTED)
            listener?.onError(e)
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        updateState(HtspConnectionState.DISCONNECTED)
    }

    private fun updateState(newState: HtspConnectionState) {
        state = newState
        _stateFlow.value = newState
        listener?.onStateChanged(newState)
    }

    companion object {
        fun computeSha1Digest(password: String, challenge: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(password.toByteArray(Charsets.UTF_8))
            md.update(challenge)
            return md.digest()
        }
    }
}
