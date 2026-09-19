package watch.rist.assistant

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import java.io.File

/**
 * Pictures the assistant sent, kept with the answer they came with so they stay on the feed
 * after the next question instead of vanishing with it. They live in the app's private storage,
 * go when their answer is cleared, and are capped so a long history cannot fill the phone.
 */
object ReceivedPhotos {

    private const val TAG = "RistPhotos"
    private const val DIR = "received_photos"

    /** At most this many pictures are kept; the oldest go first. */
    internal const val MAX_KEPT = 40

    data class Photo(val file: File, val entryId: Long, val index: Int, val mime: String, val title: String)

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * Only a picture that decodes is kept; a broken one stays a failure card. Judged on the bytes
     * as they arrived, before the feed's own decode, which lets the bytes go to save memory.
     */
    fun isKeepable(a: RistAttachment): Boolean {
        val bytes = a.bytes ?: return false
        if (a.kind != "image" || !a.error.isNullOrBlank() || bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    internal fun extensionFor(mime: String): String = when (mime.trim().lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    internal fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /** `<entry>-<index>.<ext>`, with the credit line in a `.txt` beside it. */
    internal fun parse(name: String): Pair<Long, Int>? {
        val stem = name.substringBeforeLast('.')
        val entry = stem.substringBefore('-').toLongOrNull() ?: return null
        val index = stem.substringAfter('-', "").toIntOrNull() ?: return null
        return entry to index
    }

    fun save(ctx: Context, entryId: Long, photos: List<RistAttachment>) {
        if (entryId == 0L || photos.isEmpty()) return
        val d = dir(ctx)
        photos.forEachIndexed { i, p ->
            val bytes = p.bytes ?: return@forEachIndexed
            runCatching {
                File(d, "$entryId-$i.${extensionFor(p.mime)}").writeBytes(bytes)
                // The credit and licence travel with the picture; they are shown under it.
                File(d, "$entryId-$i.txt").writeText(p.title.trim())
            }.onFailure { Log.w(TAG, "could not keep a received picture", it) }
        }
        enforceCap(ctx)
    }

    private fun images(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.extension.lowercase() != "txt" && parse(it.name) != null }.orEmpty()

    private fun enforceCap(ctx: Context) {
        val all = images(ctx).sortedBy { it.lastModified() }
        if (all.size <= MAX_KEPT) return
        all.take(all.size - MAX_KEPT).forEach { delete(it) }
    }

    private fun delete(image: File) {
        runCatching { image.delete() }
        runCatching { File(image.parentFile, image.nameWithoutExtension + ".txt").delete() }
        cache.remove(image.path)
    }

    /** Everything kept, grouped by answer, in the order each answer received them. */
    fun byEntry(ctx: Context): Map<Long, List<Photo>> =
        images(ctx).mapNotNull { f ->
            val (entry, index) = parse(f.name) ?: return@mapNotNull null
            val title = runCatching { File(f.parentFile, f.nameWithoutExtension + ".txt").readText() }.getOrDefault("")
            Photo(f, entry, index, mimeFor(f), title)
        }.groupBy { it.entryId }.mapValues { (_, v) -> v.sortedBy { it.index } }

    /** A picture goes with its answer: once the answer is cleared, so is the picture. */
    fun prune(ctx: Context, liveEntryIds: Set<Long>) {
        images(ctx).forEach { f ->
            val (entry, _) = parse(f.name) ?: return@forEach
            if (entry !in liveEntryIds) delete(f)
        }
    }

    fun clearAll(ctx: Context) {
        images(ctx).forEach { delete(it) }
    }

    // Decoded pictures for the feed, so a redraw does not decode them all again.
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A decode no larger than the feed needs. */
    fun preview(file: File, reqW: Int, reqH: Int): Bitmap? {
        cache.get(file.path)?.let { return it }
        return decode(file, reqW, reqH)?.also { cache.put(file.path, it) }
    }

    fun decode(file: File, reqW: Int, reqH: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * Copies the picture into the phone's photo library (Pictures/Rist), where the gallery and
     * the photo picker find it. No storage permission is needed to add a picture of one's own.
     */
    fun saveToLibrary(ctx: Context, photo: File): Boolean = runCatching {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Rist_${System.currentTimeMillis()}.${photo.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, mimeFor(photo))
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rist")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> photo.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.onFailure { Log.w(TAG, "could not save to the photo library", it) }.getOrDefault(false)
}
