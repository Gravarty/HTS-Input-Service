package com.gravarty.htsp.core

/**
 * A binary field that points into the received frame instead of copying it, like
 * tvheadend's htsmsg_binary_deserialize (HMF_BIN references the input buffer).
 * The packet data is copied only once, into ExoPlayer's SampleQueue (Kodi: the demux packet).
 */
class HtsBin(val array: ByteArray, val offset: Int, val length: Int) {
    fun toByteArray(): ByteArray = array.copyOfRange(offset, offset + length)
    operator fun get(i: Int): Byte = array[offset + i]
}
