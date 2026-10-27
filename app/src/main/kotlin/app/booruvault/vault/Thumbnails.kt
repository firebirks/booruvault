package app.booruvault.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import app.booruvault.vault.crypto.SealedFile
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Thumbnails made from sealed media, decoded straight from the decrypting reader. */
object Thumbnails {
    private const val SIZE = 360

    class Result(val jpeg: ByteArray?, val width: Int, val height: Int)

    fun from(file: SealedFile, mimeType: String): Result =
        if (mimeType.startsWith("video/")) fromVideo(file) else fromImage(file)

    private fun fromImage(file: SealedFile): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result(null, 0, 0)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val bitmap = file.inputStream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        return Result(bitmap?.let(::compress), bounds.outWidth, bounds.outHeight)
    }

    private fun fromVideo(file: SealedFile): Result {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    file.read(position, buffer, offset, size)

                override fun getSize(): Long = file.size
                override fun close() = Unit
            })
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
            } else {
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            Result(frame?.let(::compress), width, height)
        } catch (e: Exception) {
            Result(null, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compress(source: Bitmap): ByteArray {
        val scale = SIZE.toFloat() / max(source.width, source.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            source
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
        return out.toByteArray()
    }
}
