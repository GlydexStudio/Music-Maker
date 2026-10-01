package com.glydexstudio.musicmaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Stores square JPEG cover images in internal storage (filesDir/covers).
 * The WebView reads them through WebViewAssetLoader at /covers/<songId>.jpg.
 */
class CoverStore(private val context: Context) {

    val dir: File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun fileFor(songId: String): File = File(dir, "$songId.jpg")

    /** Decodes, centre-crops and downsizes the picked image, then saves it for [songId]. */
    fun importImage(uri: Uri, songId: String): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw AppException(ErrorCode.STORAGE, "Could not open the selected image.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AppException(ErrorCode.BAD_REQUEST, "The selected file is not a supported image.")
        }
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: OutOfMemoryError) {
            throw AppException(ErrorCode.OUT_OF_MEMORY, "The image is too large to process.", e)
        } ?: throw AppException(ErrorCode.BAD_REQUEST, "The selected image could not be decoded.")

        val side = min(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val target = min(SIZE, max(side, 1))
        val scaled = if (side != target) Bitmap.createScaledBitmap(cropped, target, target, true) else cropped

        val out = fileFor(songId)
        val tmp = File(dir, "$songId.jpg.tmp")
        try {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(out)) throw AppException(ErrorCode.STORAGE, "Could not save the cover image.")
        } finally {
            tmp.delete()
            if (scaled !== decoded) scaled.recycle()
            if (cropped !== decoded && cropped !== scaled) cropped.recycle()
            decoded.recycle()
        }
        return out
    }

    fun delete(songId: String) {
        fileFor(songId).delete()
    }

    fun copy(fromId: String, toId: String): Boolean {
        val src = fileFor(fromId)
        if (!src.exists()) return false
        src.copyTo(fileFor(toId), overwrite = true)
        return true
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        const val DIR_NAME = "covers"
        private const val SIZE = 512
    }
}
