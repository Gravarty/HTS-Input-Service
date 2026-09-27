package com.gravarty.htsp.core

interface HtspConnectionListener {
    fun onStateChanged(state: HtspConnectionState)
    fun onAsyncMessage(message: HtsMessage)
    fun onError(error: Throwable)
}

enum class HtspConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED
}
