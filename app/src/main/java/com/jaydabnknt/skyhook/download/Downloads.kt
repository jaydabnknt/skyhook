package com.jaydabnknt.skyhook.download

import android.content.Context
import android.content.Intent
import com.jaydabnknt.skyhook.SkyhookApp
import com.jaydabnknt.skyhook.cloud.Destination
import com.jaydabnknt.skyhook.cloud.UploadSink
import com.jaydabnknt.skyhook.media.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

sealed class Source {
    /** A plain file URL, fetched with the page's cookies/referer. */
    class Direct(val url: String, val headers: Map<String, String>, val mime: String?) : Source()

    /** A response body Gecko already opened (normal file downloads clicked on a page). */
    class Body(val stream: InputStream, val size: Long?, val mime: String?) : Source()

    /** HLS / DASH: a video track and optionally a separate audio track. */
    class Streamed(val video: Track, val audio: Track?, val headers: Map<String, String>) : Source()
}

enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

class DownloadJob(val id: Int, @Volatile var name: String, val dest: Destination, val source: Source) {
    @Volatile var status = Status.QUEUED
    @Volatile var bytes = 0L
    @Volatile var total = -1L
    @Volatile var segDone = 0
    @Volatile var segTotal = 0
    @Volatile var error: String? = null
    @Volatile var location: String? = null
    @Volatile var note: String? = null
    @Volatile internal var sink: UploadSink? = null
    internal var job: Job? = null
    val createdAt = System.currentTimeMillis()

    /** 0..1, or null if unknown. */
    val fraction: Float?
        get() = when {
            status == Status.DONE -> 1f
            segTotal > 0 -> segDone.toFloat() / segTotal
            total > 0 -> (bytes.toFloat() / total).coerceAtMost(1f)
            else -> null
        }

    val active get() = status == Status.QUEUED || status == Status.RUNNING
}

object Downloads {
    private val ids = AtomicInteger(1)
    val jobs = CopyOnWriteArrayList<DownloadJob>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)
    @Volatile private var lastTick = 0L

    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastTick > 300) {
            lastTick = now
            _changes.value = now + ids.get() // always a new value
        }
    }

    fun enqueue(context: Context, name: String, dest: Destination, source: Source): DownloadJob {
        val job = DownloadJob(ids.getAndIncrement(), name, dest, source)
        jobs.add(0, job)
        changed(true)
        context.startForegroundService(Intent(context, DownloadService::class.java))
        job.job = scope.launch {
            try {
                slots.withPermit {
                    job.status = Status.RUNNING
                    changed(true)
                    Engine(job).run()
                    job.status = Status.DONE
                }
            } catch (e: CancellationException) {
                job.status = Status.CANCELLED
                job.sink?.let { s -> runCatching { s.abort() } }
            } catch (e: Throwable) {
                job.status = Status.FAILED
                job.error = e.message ?: e.javaClass.simpleName
                job.sink?.let { s -> runCatching { s.abort() } }
                if (source is Source.Body) runCatching { source.stream.close() }
            } finally {
                changed(true)
                DownloadService.notifyFinished(SkyhookApp.instance, job)
            }
        }
        return job
    }

    fun cancel(job: DownloadJob) {
        job.job?.cancel()
        // Unblock any blocking network read so the coroutine notices promptly.
        job.sink?.let { s -> scope.launch { runCatching { s.abort() } } }
        if (job.source is Source.Body) runCatching { job.source.stream.close() }
    }

    fun clearFinished() {
        jobs.removeAll { !it.active }
        changed(true)
    }

    val activeCount get() = jobs.count { it.active }
}
