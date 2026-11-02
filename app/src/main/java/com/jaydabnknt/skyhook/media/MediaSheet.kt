package com.jaydabnknt.skyhook.media

import android.net.Uri
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jaydabnknt.skyhook.R
import com.jaydabnknt.skyhook.download.Downloads
import com.jaydabnknt.skyhook.download.Source
import com.jaydabnknt.skyhook.ui.DownloadDialog
import com.jaydabnknt.skyhook.ui.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom sheet listing the videos detected on the current page. */
object MediaSheet {
    private val probeCache = LruCache<String, Hls.Probe>(40)

    private suspend fun probe(item: MediaItem): Hls.Probe = probeCache.get(item.url) ?: withContext(Dispatchers.IO) {
        if (item.kind == MediaKind.HLS) Hls.probe(item) else Dash.probe(item)
    }.also { probeCache.put(item.url, it) }

    private fun kindLabel(k: MediaKind) = when (k) {
        MediaKind.HLS -> "HLS stream"
        MediaKind.DASH -> "DASH stream"
        MediaKind.VIDEO -> "Video file"
        MediaKind.AUDIO -> "Audio file"
    }

    private fun urlName(url: String) =
        Uri.parse(url).lastPathSegment?.takeIf { it.contains('.') } ?: url.substringBefore('?').substringAfterLast('/')

    fun show(activity: AppCompatActivity, items: List<MediaItem>, pageTitle: String) {
        val sheet = BottomSheetDialog(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(12), 0, activity.dp(16))
        }
        list.addView(TextView(activity).apply {
            text = "Videos on this page"
            textSize = 18f
            setPadding(activity.dp(20), 0, activity.dp(20), activity.dp(8))
        })

        class Row(val item: MediaItem, val view: View, val detail: TextView) {
            var probe: Deferred<Hls.Probe>? = null
        }

        val rows = items.reversed().map { item -> // newest first
            val detail = TextView(activity).apply { textSize = 12f; alpha = 0.65f }
            val v = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(20), activity.dp(12), activity.dp(16), activity.dp(12))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(ImageView(activity).apply {
                    setImageResource(if (item.isStream) R.drawable.ic_video else R.drawable.ic_download)
                    setColorFilter(activity.getColor(R.color.video_green))
                }, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { marginEnd = activity.dp(14) })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = if (item.isStream) (pageTitle.ifBlank { item.title }.ifBlank { urlName(item.url) }) else urlName(item.url)
                        maxLines = 2; textSize = 15f
                    })
                    addView(detail)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            detail.text = buildString {
                append(kindLabel(item.kind))
                if (item.size > 0) append(" · ").append(formatBytes(item.size))
                if (item.isStream) append(" · checking qualities…")
            }
            list.addView(v)
            Row(item, v, detail)
        }

        // Probe streams in parallel; hide variant playlists already covered by a master playlist.
        rows.filter { it.item.isStream }.forEach { row ->
            row.probe = activity.lifecycleScope.async {
                probe(row.item)
            }
            activity.lifecycleScope.launch {
                try {
                    val p = row.probe!!.await()
                    val best = p.qualities.firstOrNull()
                    row.detail.text = "${kindLabel(row.item.kind)} · " + when {
                        p.qualities.size > 1 -> "up to ${best?.label} · ${p.qualities.size} qualities"
                        best != null -> listOf(best.label, best.detail).filter { it.isNotBlank() }.joinToString(" · ")
                        else -> "no playable tracks"
                    }
                    if (p.children.isNotEmpty()) rows.forEach { other ->
                        if (other !== row && other.item.url in p.children) other.view.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    row.detail.text = "${kindLabel(row.item.kind)} · ${e.message ?: "can't read stream"}"
                    row.view.alpha = 0.5f
                }
            }
        }

        rows.forEach { row ->
            row.view.setOnClickListener {
                if (row.item.isStream) chooseQuality(activity, row.item, row.probe, pageTitle) { sheet.dismiss() }
                else {
                    sheet.dismiss()
                    val name = urlName(row.item.url).ifBlank { pageTitle }
                    DownloadDialog.show(activity, name, listOfNotNull(row.item.mime.ifBlank { null },
                        row.item.size.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · ")) { n, dest ->
                        Downloads.enqueue(activity, n, dest, Source.Direct(row.item.url, row.item.headers, row.item.mime.ifBlank { null }))
                    }
                }
            }
        }

        list.addView(TextView(activity).apply {
            text = "Nothing listed? Start playing the video, then reopen this. DRM-protected services (Netflix, Disney+, etc.) can't be downloaded."
            textSize = 12f; alpha = 0.5f
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), 0)
        })
        sheet.setContentView(ScrollView(activity).apply { addView(list) })
        sheet.show()
    }

    private fun chooseQuality(
        activity: AppCompatActivity, item: MediaItem, pending: Deferred<Hls.Probe>?, pageTitle: String, done: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val probe = try {
                pending?.await() ?: probe(item)
            } catch (e: Exception) {
                Toast.makeText(activity, e.message ?: "Can't read this stream", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (probe.qualities.isEmpty()) {
                Toast.makeText(activity, "No downloadable tracks", Toast.LENGTH_SHORT).show(); return@launch
            }
            done()
            val labels = probe.qualities.map { q -> if (q.detail.isBlank()) q.label else "${q.label}   ${q.detail}" }.toTypedArray()
            if (probe.qualities.size == 1) prepare(activity, item, probe.qualities[0], pageTitle)
            else MaterialAlertDialogBuilder(activity)
                .setTitle("Choose quality")
                .setItems(labels) { _, i -> prepare(activity, item, probe.qualities[i], pageTitle) }
                .show()
        }
    }

    private fun prepare(activity: AppCompatActivity, item: MediaItem, q: Quality, pageTitle: String) {
        val toast = Toast.makeText(activity, "Preparing ${q.label}…", Toast.LENGTH_SHORT).also { it.show() }
        activity.lifecycleScope.launch {
            val tracks = try {
                withContext(Dispatchers.IO) { q.resolve() }
            } catch (e: Exception) {
                toast.cancel()
                Toast.makeText(activity, e.message ?: "Couldn't load the stream", Toast.LENGTH_LONG).show()
                return@launch
            }
            toast.cancel()
            val (video, audio) = tracks
            val parts = video.segments.size + (audio?.segments?.size ?: 0)
            val info = buildString {
                append(q.label)
                if (video.container != Container.WHOLE_FILE) append(" · $parts parts")
                if (audio != null) append(" · separate audio track (merged into one file)")
                if (video.live) append(" · LIVE — only what's available now")
            }
            val name = pageTitle.ifBlank { item.title }.ifBlank { "video" } +
                if (q.height > 0) " [${q.label}]" else ""
            DownloadDialog.show(activity, name, info) { n, dest ->
                Downloads.enqueue(activity, n, dest, Source.Streamed(video, audio, item.headers))
            }
        }
    }
}
