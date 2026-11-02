package com.jaydabnknt.skyhook.download

import com.jaydabnknt.skyhook.cloud.Sinks
import com.jaydabnknt.skyhook.cloud.UploadSink
import com.jaydabnknt.skyhook.cloud.safeFileName
import com.jaydabnknt.skyhook.media.Container
import com.jaydabnknt.skyhook.media.Mp4
import com.jaydabnknt.skyhook.media.Seg
import com.jaydabnknt.skyhook.media.SegKey
import com.jaydabnknt.skyhook.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Runs one download: pulls bytes from the source and pushes them into the chosen sink. */
class Engine(private val job: DownloadJob) {

    suspend fun run() {
        when (val s = job.source) {
            is Source.Direct -> direct(s)
            is Source.Body -> body(s)
            is Source.Streamed -> streamed(s)
        }
    }

    // ------------------------------------------------------------- helpers

    private fun nameWith(ext: String, suffix: String = ""): String {
        val base = safeFileName(job.name).replace(EXT_RE, "")
        return base + suffix + ext
    }

    private fun openSink(fileName: String, mime: String, size: Long?): UploadSink {
        val s = Sinks.open(job.dest, fileName, mime, size)
        job.sink = s
        return s
    }

    private fun finish(sink: UploadSink) {
        job.location = sink.finish()
        job.sink = null
    }

    private suspend fun pump(input: InputStream, sink: UploadSink, onBytes: (Int) -> Unit = {}) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = runInterruptible { input.read(buf) }
            if (n < 0) break
            if (n == 0) continue
            sink.write(buf, 0, n)
            job.bytes += n
            onBytes(n)
            Downloads.changed()
        }
    }

    // -------------------------------------------------------------- direct

    private suspend fun direct(s: Source.Direct) {
        var resp: Response = Http.retry(4) { Http.open(s.url, s.headers) }
        val size = resp.body?.contentLength()?.takeIf { it > 0 }
        job.total = size ?: -1
        val mime = resp.body?.contentType()?.let { "${it.type}/${it.subtype}" } ?: s.mime ?: "application/octet-stream"
        val canResume = resp.header("Accept-Ranges")?.contains("bytes") == true
        val sink = openSink(nameWith(extFor(job.name, mime)), mime, size)
        var resumes = 0
        while (true) {
            try {
                resp.use { pump(it.body!!.byteStream(), sink) }
                break
            } catch (e: IOException) {
                if (!canResume || resumes++ >= 6) throw e
                kotlinx.coroutines.delay(1500L * resumes)
                resp = Http.open(s.url, s.headers, rangeFrom = job.bytes)
                if (resp.code != 206) { resp.close(); throw IOException("Connection lost and the server can't resume", e) }
            }
        }
        if (size != null && job.bytes < size) throw IOException("Download ended early (${job.bytes} of $size bytes)")
        finish(sink)
    }

    private suspend fun body(s: Source.Body) {
        job.total = s.size ?: -1
        val mime = s.mime ?: "application/octet-stream"
        val sink = openSink(nameWith(extFor(job.name, mime)), mime, s.size)
        s.stream.use { pump(it, sink) }
        finish(sink)
    }

    // ------------------------------------------------------------ streamed

    private suspend fun streamed(s: Source.Streamed) = coroutineScope {
        val v = s.video
        val a = s.audio
        job.segTotal = v.segments.size + (a?.segments?.size ?: 0)
        if (v.live) job.note = "Live stream: saving what's available now"

        val mergeable = a != null &&
            v.container in setOf(Container.FMP4, Container.WHOLE_FILE) &&
            a.container in setOf(Container.FMP4, Container.WHOLE_FILE)

        if (a == null) {
            single(this, v, s.headers, "")
            return@coroutineScope
        }
        if (mergeable) {
            val vs = fragSource(this, v, s.headers)
            val aS = fragSource(this, a, s.headers)
            val inits = try {
                vs.init() to aS.init()
            } catch (e: Mp4.NotFragmented) {
                vs.close(); aS.close(); null
            }
            if (inits != null) {
                vs.use { aS.use { mux(vs, aS, inits.first, inits.second, nameWith(".mp4")) } }
                return@coroutineScope
            }
        }
        // Can't combine on the fly: save video and audio as two files.
        job.note = "Video and audio saved as separate files"
        single(this, v, s.headers, "")
        single(this, a, s.headers, " (audio)")
    }

    private suspend fun single(scope: CoroutineScope, t: Track, headers: Map<String, String>, suffix: String) {
        when (t.container) {
            Container.TS, Container.RAW_AUDIO -> {
                val f = SegmentFetcher(scope, t, headers)
                val sink = openSink(nameWith(t.extension, suffix), t.mime, null)
                f.init()?.let { sink.write(it); job.bytes += it.size }
                while (true) {
                    val seg = f.next() ?: break
                    sink.write(seg)
                    job.bytes += seg.size
                    job.segDone++
                    Downloads.changed()
                }
                finish(sink)
            }
            Container.WHOLE_FILE -> {
                val resp = Http.retry(4) { Http.open(t.segments.first().url, headers) }
                val size = resp.body?.contentLength()?.takeIf { it > 0 }
                val sink = openSink(nameWith(t.extension, suffix), t.mime, size)
                val before = job.bytes
                resp.use { pump(it.body!!.byteStream(), sink) }
                job.segDone++
                if (size != null && job.bytes - before < size) throw IOException("Stream ended early")
                finish(sink)
            }
            Container.FMP4 -> {
                val src = fragSource(scope, t, headers)
                src.use { val init = it.init(); mux(it, null, init, null, nameWith(t.extension, suffix)) }
            }
        }
    }

    /** Writes init + fragments in decode-time order, merging audio into the video file when present. */
    private suspend fun mux(video: FragSource, audio: FragSource?, vInit: ByteArray, aInit: ByteArray?, fileName: String) {
        val header = if (audio != null && aInit != null) Mp4.mergeInit(vInit, aInit) else vInit
        val sink = openSink(fileName, if (audio == null && video.track.isAudioOnly) "audio/mp4" else "video/mp4", null)
        sink.write(header)
        var pos = header.size.toLong()
        job.bytes += header.size

        val vScale = (Mp4.timescale(vInit) ?: 90000).toDouble()
        val aScale = (aInit?.let { Mp4.timescale(it) } ?: 48000).toDouble()
        var vNext = video.next()
        var aNext = audio?.next()
        var vCount = 0L
        var aCount = 0L
        var seq = 1L
        while (vNext != null || aNext != null) {
            currentCoroutineContext().ensureActive()
            val takeVideo = when {
                aNext == null -> true
                vNext == null -> false
                vNext.decodeTime != null && aNext.decodeTime != null ->
                    vNext.decodeTime!! / vScale <= aNext.decodeTime!! / aScale
                else -> vCount <= aCount
            }
            val frag = if (takeVideo) vNext!! else aNext!!
            frag.patch(seq++, if (audio == null) null else if (takeVideo) 1L else 2L, pos)
            sink.write(frag.data)
            pos += frag.data.size
            job.bytes += frag.data.size
            Downloads.changed()
            if (takeVideo) { vCount++; vNext = video.next() } else { aCount++; aNext = audio!!.next() }
        }
        finish(sink)
    }

    // ------------------------------------------------------- fragment input

    private abstract inner class FragSource(val track: Track) : Closeable {
        abstract suspend fun init(): ByteArray
        abstract suspend fun next(): Mp4.Fragment?
        override fun close() {}
    }

    private fun fragSource(scope: CoroutineScope, t: Track, headers: Map<String, String>): FragSource =
        if (t.container == Container.WHOLE_FILE) FileFrags(t, headers) else SegmentFrags(scope, t, headers)

    /** fMP4 delivered as many small segment files (HLS fMP4, DASH templates/lists). */
    private inner class SegmentFrags(scope: CoroutineScope, t: Track, headers: Map<String, String>) : FragSource(t) {
        private val fetcher = SegmentFetcher(scope, t, headers)
        private val queue = ArrayDeque<Mp4.Fragment>()

        override suspend fun init(): ByteArray {
            fetcher.init()?.let { return Mp4.cleanInit(it) }
            // No separate init: the first segment carries the moov.
            val first = fetcher.next() ?: throw IOException("Stream has no segments")
            job.segDone++
            queue.addAll(Mp4.fragments(first))
            return Mp4.cleanInit(first)
        }

        override suspend fun next(): Mp4.Fragment? {
            while (queue.isEmpty()) {
                val seg = fetcher.next() ?: return null
                job.segDone++
                queue.addAll(Mp4.fragments(seg))
            }
            return queue.removeFirst()
        }
    }

    /** fMP4 delivered as one big file (DASH SegmentBase); read box by box from the network. */
    private inner class FileFrags(t: Track, private val headers: Map<String, String>) : FragSource(t) {
        private var resp: Response? = null
        private lateinit var reader: Mp4.StreamReader

        override suspend fun init(): ByteArray {
            val r = Http.retry(4) { Http.open(track.segments.first().url, headers) }
            resp = r
            reader = Mp4.StreamReader(r.body!!.byteStream())
            return runInterruptible { reader.init() }
        }

        override suspend fun next(): Mp4.Fragment? = runInterruptible { reader.next() }

        override fun close() { resp?.close() }
    }

    // -------------------------------------------------------- segment input

    /** Fetches segments in order with a few in flight, decrypting HLS AES-128 if needed. */
    private class SegmentFetcher(private val scope: CoroutineScope, private val track: Track, private val headers: Map<String, String>) {
        private val window = ArrayDeque<Deferred<ByteArray>>()
        private var nextIdx = 0
        private val keys = HashMap<String, ByteArray>()
        private val keyLock = Mutex()

        suspend fun init(): ByteArray? = track.init?.let { fetch(it) }

        suspend fun next(): ByteArray? {
            while (window.size < PARALLEL && nextIdx < track.segments.size) {
                val seg = track.segments[nextIdx++]
                window.addLast(scope.async { fetch(seg) })
            }
            return window.removeFirstOrNull()?.await()
        }

        private suspend fun fetch(seg: Seg): ByteArray {
            val data = Http.bytes(seg.url, headers, seg.range)
            val key = seg.key ?: return data
            return decrypt(data, key, seg.sequence)
        }

        private suspend fun decrypt(data: ByteArray, k: SegKey, sequence: Long): ByteArray {
            val keyBytes = keyLock.withLock {
                keys[k.uri] ?: Http.bytes(k.uri, headers).also { keys[k.uri] = it }
            }
            val iv = k.iv ?: ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (sequence shr (8 * i)).toByte() }
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes.copyOf(16), "AES"), IvParameterSpec(iv))
            return c.doFinal(data)
        }

        companion object { const val PARALLEL = 4 }
    }

    companion object {
        private val MIME_EXT = mapOf(
            "video/mp4" to ".mp4", "video/webm" to ".webm", "video/x-matroska" to ".mkv", "video/quicktime" to ".mov",
            "video/mp2t" to ".ts", "video/x-flv" to ".flv", "video/3gpp" to ".3gp",
            "audio/mpeg" to ".mp3", "audio/mp4" to ".m4a", "audio/aac" to ".aac", "audio/ogg" to ".ogg",
            "audio/webm" to ".weba", "audio/wav" to ".wav", "audio/flac" to ".flac", "audio/opus" to ".opus",
            "image/jpeg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp", "image/gif" to ".gif",
            "application/pdf" to ".pdf", "application/zip" to ".zip",
            "application/vnd.android.package-archive" to ".apk",
        )

        /** Keeps an existing sensible extension, otherwise derives one from the MIME type. */
        fun extFor(name: String, mime: String): String =
            EXT_RE.find(name)?.value ?: MIME_EXT[mime.lowercase()] ?: ""

        private val EXT_RE = Regex("""\.[A-Za-z][A-Za-z0-9]{1,4}$""")
    }
}
