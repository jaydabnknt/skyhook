package com.jaydabnknt.skyhook.cloud

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.SkyhookApp
import java.io.IOException
import java.io.OutputStream

enum class Destination(val label: String) {
    LOCAL("This phone (Downloads)"),
    GOOGLE_DRIVE("Google Drive"),
    DROPBOX("Dropbox"),
    YANDEX("Yandex Disk");

    val isCloud get() = this != LOCAL

    fun isConnected(): Boolean = when (this) {
        LOCAL -> true
        GOOGLE_DRIVE -> GoogleDrive.isConnected()
        DROPBOX -> Dropbox.isConnected()
        YANDEX -> Yandex.isConnected()
    }
}

/**
 * Where downloaded bytes go. Blocking API; always called from an IO thread.
 * Cloud sinks never touch local storage: bytes are held in a small in-memory
 * buffer and pushed straight to the provider's upload API.
 */
interface UploadSink {
    /** Human-readable place the file ended up, set after [finish]. */
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size)
    fun finish(): String
    fun abort()
    /** Bytes the provider has confirmed so far (for progress). */
    val committed: Long
}

object Sinks {
    fun open(dest: Destination, fileName: String, mime: String, size: Long?): UploadSink {
        val folder = Prefs.folderName
        return when (dest) {
            Destination.LOCAL -> LocalSink(folder, fileName, mime)
            Destination.GOOGLE_DRIVE -> GoogleDrive.openSink(folder, fileName, mime, size)
            Destination.DROPBOX -> Dropbox.openSink(folder, fileName, mime, size)
            Destination.YANDEX -> Yandex.openSink(folder, fileName, mime, size)
        }
    }
}

/** Downloads/<folder>/ via MediaStore (no storage permission needed). */
class LocalSink(folder: String, fileName: String, mime: String) : UploadSink {
    private val resolver = SkyhookApp.instance.contentResolver
    private val uri: Uri
    private val out: OutputStream
    override var committed = 0L
        private set

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create file in Downloads")
        out = resolver.openOutputStream(uri) ?: throw IOException("Could not open file for writing")
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len); committed += len
    }

    override fun finish(): String {
        out.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return "Downloads/${Prefs.folderName}"
    }

    override fun abort() {
        runCatching { out.close() }
        runCatching { resolver.delete(uri, null, null) }
    }
}

/**
 * Buffers up to [chunkSize] bytes in memory and hands full chunks to [upload].
 * Only one chunk is ever held, so memory use stays flat regardless of file size.
 */
abstract class ChunkedSink(private val chunkSize: Int) : UploadSink {
    private val buf = ByteArray(chunkSize)
    private var fill = 0
    private var offset = 0L // bytes already handed to upload()
    override var committed = 0L
        protected set

    /** Upload [len] bytes of [data] that start at file offset [offset]. If [last], total = offset + len. */
    protected abstract fun upload(data: ByteArray, len: Int, offset: Long, last: Boolean)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, chunkSize - fill)
            System.arraycopy(b, o, buf, fill, n)
            fill += n; o += n; left -= n
            if (fill == chunkSize) flush(false)
        }
    }

    private fun flush(last: Boolean) {
        upload(buf, fill, offset, last)
        offset += fill
        committed = offset
        fill = 0
    }

    final override fun finish(): String {
        flush(true)
        return location()
    }

    protected abstract fun location(): String
}

fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(150).ifBlank { "download" }
