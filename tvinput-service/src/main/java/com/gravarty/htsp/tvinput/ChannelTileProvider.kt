package com.gravarty.htsp.tvinput

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.RectF
import android.media.tv.TvContract
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.palette.graphics.Palette
import com.gravarty.htsp.provider.HtspLog
import java.io.File
import java.io.FileNotFoundException

/**
 * 16:9 home-screen tiles for the preview channel: the channel logo centred on its dominant
 * colour darkened by 50 %. Each tile is rendered once and cached; [invalidate] drops it when
 * the logo changes. URI: content://com.gravarty.hts.tiles/channel/<TvContract channel id>
 */
class ChannelTileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val channelId = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val file = tileFile(ctx, channelId)
        if (!file.exists()) render(ctx, channelId, file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun render(context: Context, channelId: Long, out: File) {
        val logo = try {
            context.contentResolver.openInputStream(TvContract.buildChannelLogoUri(channelId))
                ?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }

        val tile = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        drawBackground(canvas, if (logo != null) dominantColor(logo) else FALLBACK_COLOR)

        if (logo != null) {
            val scale = minOf(HEIGHT * LOGO_HEIGHT / logo.height, WIDTH * LOGO_MAX_WIDTH / logo.width)
            val w = logo.width * scale
            val h = logo.height * scale
            val left = (WIDTH - w) / 2f
            val top = (HEIGHT - h) / 2f
            canvas.drawBitmap(logo, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        }

        out.parentFile?.mkdirs()
        out.outputStream().use { tile.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tile.recycle()
    }

    private fun dominantColor(logo: Bitmap): Int =
        Palette.from(logo).generate().dominantSwatch?.rgb ?: FALLBACK_COLOR

    /**
     * Diagonal gradient from the dominant colour (top left, darkened to 65 %) to a deep shade
     * of it (bottom right, 25 %), plus a soft light behind the logo.
     */
    private fun drawBackground(canvas: Canvas, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            shade(color, 0.65f), shade(color, 0.25f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        paint.shader = RadialGradient(
            WIDTH / 2f, HEIGHT / 2f, WIDTH * 0.45f,
            Color.argb(56, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt()
    )

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.gravarty.hts.tiles"
        private const val WIDTH = 640
        private const val HEIGHT = 360
        private const val LOGO_HEIGHT = 0.6f
        private const val LOGO_MAX_WIDTH = 0.8f
        private val FALLBACK_COLOR = Color.rgb(0x60, 0x60, 0x60)

        /** bump when the tile design changes: new file name + URI, so caches refresh */
        private const val DESIGN_VERSION = 2

        fun tileUri(channelId: Long): Uri =
            Uri.parse("content://$AUTHORITY/channel/v$DESIGN_VERSION/$channelId")

        private fun tileFile(context: Context, channelId: Long) =
            File(File(context.cacheDir, "tiles"), "$channelId-v$DESIGN_VERSION.png")

        /** Drop a cached tile (logo changed). */
        fun invalidate(context: Context, channelId: Long) {
            try {
                tileFile(context, channelId).delete()
            } catch (e: Exception) {
                HtspLog.e("Tile invalidate failed", e)
            }
        }
    }
}
