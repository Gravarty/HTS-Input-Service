package com.gravarty.htsp.provider

import android.content.Context
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Channel icons like pvr.hts: GetImageURL() builds http://host:httpPort + webroot + path,
 * Kodi's curl then answers the server's auth challenge (Basic or Digest) with the
 * Tvheadend credentials. HttpURLConnection has no Digest, so it is done here.
 */
object HtspLogoFetcher {

    /** pvr.hts CTvheadend::GetImageURL */
    fun buildImageUrl(icon: String, host: String, httpPort: Int, webRoot: String): String? {
        if (icon.isEmpty()) return null
        val base = "http://${if (host.contains(':')) "[$host]" else host}:$httpPort$webRoot"
        return when {
            icon.startsWith("/") -> base + icon
            icon.startsWith("imagecache/") -> "$base/$icon"
            else -> icon
        }
    }

    suspend fun fetchAndStoreLogo(
        context: Context,
        channelDbId: Long,
        url: String?,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return@withContext false
        }
        try {
            val bytes = httpGet(url, username, password) ?: return@withContext false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return@withContext false

            context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(channelDbId))
                ?.use { it.write(bytes) } ?: return@withContext false
            true
        } catch (e: Exception) {
            HtspLog.e("Logo failed for '$url': ${e.message}")
            false
        }
    }

    private fun httpGet(url: String, user: String, pass: String): ByteArray? {
        var conn = open(url, null)
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && user.isNotEmpty()) {
                val challenge = conn.getHeaderField("WWW-Authenticate") ?: return null
                val auth = authorization(challenge, URL(url).file, user, pass) ?: return null
                conn.disconnect()
                conn = open(url, auth)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                HtspLog.e("Logo HTTP ${conn.responseCode} for $url")
                return null
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, auth: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            if (auth != null) setRequestProperty("Authorization", auth)
        }

    private fun authorization(challenge: String, uri: String, user: String, pass: String): String? {
        val scheme = challenge.substringBefore(' ').trim()
        if (scheme.equals("Basic", ignoreCase = true)) {
            return "Basic " + android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
        }
        if (!scheme.equals("Digest", ignoreCase = true)) return null

        // RFC 2617, MD5
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
            .findAll(challenge.substringAfter(' '))
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("GET:$uri")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            val nc = "00000001"
            val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            sb.append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        params["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        params["algorithm"]?.let { sb.append(", algorithm=$it") }
        return sb.toString()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
