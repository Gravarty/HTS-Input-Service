package com.gravarty.htsp.tvinput

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.provider.HtspLog

/**
 * Home-screen preview channel "Zuletzt gesehen" (TvContract preview channel, shown by launchers
 * such as Catapult): the last [MAX_ENTRIES] channels the user tuned, newest first.
 * Updated only when a channel is tuned - no timer, no background work. Each tile shows the
 * channel logo and name; a click opens the channel in the TV app (ACTION_VIEW channel URI).
 */
object RecentChannelsPreview {
    private const val MAX_ENTRIES = 10
    private const val PREFS = "htsp_recent_channels"
    private const val KEY_PREVIEW_CHANNEL = "preview_channel_id"
    private const val KEY_RECENT = "recent_ids"
    private const val KEY_CHANNEL_META = "channel_meta_version"
    /** bump when the preview channel's name / description change */
    private const val CHANNEL_META_VERSION = 2
    private const val KEY_TILE_VERSION = "tile_version"
    /** bump when the tile image format changes, so existing tiles are rewritten once */
    private const val TILE_VERSION = 2

    @Volatile
    private var browsableRequested = false

    private fun isBrowsable(context: Context, channelId: Long): Boolean =
        context.contentResolver.query(
            TvContract.buildChannelUri(channelId), arrayOf(TvContract.Channels.COLUMN_BROWSABLE), null, null, null
        )?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } ?: false

    private fun description(context: Context) = context.getString(R.string.preview_recent_channels_description)

    /** Channel name = app name: Catapult then shows just the app name (Suggestions.getChannelTitle) */
    private fun appName(context: Context) = context.applicationInfo.loadLabel(context.packageManager).toString()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(HtspSettings.KEY_HOME_PREVIEW, false)

    /** Setting turned off: remove the preview channel (its tiles go with it) and the history. */
    fun remove(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getLong(KEY_PREVIEW_CHANNEL, -1L)
            if (id >= 0) context.contentResolver.delete(TvContract.buildChannelUri(id), null, null)
            prefs.edit().clear().apply()
            browsableRequested = false
        } catch (e: Exception) {
            HtspLog.e("Removing preview channel failed", e)
        }
    }

    /** Call with the TvContract channel URI that was tuned. Runs on the caller's (IO) thread. */
    fun onChannelTuned(context: Context, channelUri: Uri) {
        if (!isEnabled(context)) {
            // e.g. channel from a build where the feature had no setting yet: remove it once
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_PREVIEW_CHANNEL)) remove(context)
            return
        }
        try {
            val tunedId = channelUri.lastPathSegment?.toLongOrNull() ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_RECENT, "")!!.split(',').mapNotNull { it.toLongOrNull() }
            val recent = (listOf(tunedId) + old.filter { it != tunedId }).take(MAX_ENTRIES)
            val previewChannelId = previewChannel(context) ?: return
            val tilesCurrent = prefs.getInt(KEY_TILE_VERSION, 0) == TILE_VERSION
            if (recent == old && tilesCurrent) return // already first, nothing to write
            prefs.edit().putInt(KEY_TILE_VERSION, TILE_VERSION).apply()
            prefs.edit().putString(KEY_RECENT, recent.joinToString(",")).apply()
            writePrograms(context, previewChannelId, recent)
        } catch (e: Exception) {
            HtspLog.e("Recent channels preview failed", e)
        }
    }

    /** The app's preview channel, created on first use. */
    private fun previewChannel(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_PREVIEW_CHANNEL, -1L)
        if (stored >= 0 && exists(context, TvContract.buildChannelUri(stored))) {
            // Channels created by older versions: update name / description once
            if (prefs.getInt(KEY_CHANNEL_META, 0) != CHANNEL_META_VERSION) {
                context.contentResolver.update(
                    TvContract.buildChannelUri(stored),
                    ContentValues().apply {
                        put(TvContract.Channels.COLUMN_DISPLAY_NAME, appName(context))
                        put(TvContract.Channels.COLUMN_DESCRIPTION, description(context))
                    },
                    null, null
                )
                prefs.edit().putInt(KEY_CHANNEL_META, CHANNEL_META_VERSION).apply()
            }
            // Only the system/launcher can make a preview channel browsable. If the request at
            // creation went to a launcher that ignores it, ask again (once per process start).
            if (!browsableRequested && !isBrowsable(context, stored)) {
                browsableRequested = true
                runCatching { TvContract.requestChannelBrowsable(context, stored) }
            }
            return stored
        }

        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, appName(context))
            // Catapult hides preview channels without a description (PreviewChannelsFlow)
            put(TvContract.Channels.COLUMN_DESCRIPTION, description(context))
            put(
                TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                    ?.toUri(Intent.URI_INTENT_SCHEME)
            )
        }
        val uri = context.contentResolver.insert(TvContract.Channels.CONTENT_URI, values) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        prefs.edit().putLong(KEY_PREVIEW_CHANNEL, id).putInt(KEY_CHANNEL_META, CHANNEL_META_VERSION).apply()
        // Ask the launcher to show it (the system decides by its policy)
        runCatching { TvContract.requestChannelBrowsable(context, id) }
        return id
    }

    private fun writePrograms(context: Context, previewChannelId: Long, recent: List<Long>) {
        val resolver = context.contentResolver
        val ops = ArrayList<ContentProviderOperation>()

        // remove the old tiles of this preview channel (by row URI, selections are not allowed)
        resolver.query(
            TvContract.PreviewPrograms.CONTENT_URI,
            arrayOf(TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) == previewChannelId) {
                    ops.add(ContentProviderOperation.newDelete(TvContract.buildPreviewProgramUri(c.getLong(0))).build())
                }
            }
        }

        recent.forEachIndexed { index, channelId ->
            val name = channelName(context, channelId) ?: return@forEachIndexed // channel gone
            val values = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, previewChannelId)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, name)
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, ChannelTileProvider.tileUri(channelId).toString())
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
                put(
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(channelId)).toUri(Intent.URI_INTENT_SCHEME)
                )
                put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, channelId.toString())
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, MAX_ENTRIES - index) // newest first
            }
            ops.add(ContentProviderOperation.newInsert(TvContract.PreviewPrograms.CONTENT_URI).withValues(values).build())
        }
        resolver.applyBatch(TvContract.AUTHORITY, ops)
    }

    private fun channelName(context: Context, channelId: Long): String? =
        context.contentResolver.query(
            TvContract.buildChannelUri(channelId), arrayOf(TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun exists(context: Context, uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(TvContract.Channels._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
}
