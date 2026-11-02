package com.jaydabnknt.skyhook.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.jaydabnknt.skyhook.R
import com.jaydabnknt.skyhook.download.DownloadJob
import com.jaydabnknt.skyhook.download.Downloads
import com.jaydabnknt.skyhook.download.Status
import com.jaydabnknt.skyhook.media.formatBytes
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val rows = mutableMapOf<Int, RowViews>()

    private class RowViews(val root: View, val title: TextView, val detail: TextView, val bar: ProgressBar, val cancel: ImageButton)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "Downloads"; textSize = 24f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialButton(context).apply {
                text = "Clear finished"
                setOnClickListener { Downloads.clearFinished(); render() }
            })
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.applySystemBarPadding()
        setContentView(scroll)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Downloads.changes.collect { render() }
            }
        }
    }

    private fun render() {
        val jobs = Downloads.jobs.toList()
        if (jobs.isEmpty()) {
            list.removeAllViews(); rows.clear()
            list.addView(TextView(this).apply {
                text = "Nothing yet. Tap the video icon in the address bar when a page is playing something."
                alpha = 0.6f; setPadding(0, dp(24), 0, 0)
            })
            return
        }
        // Rebuild rows when the set of jobs changes; otherwise update in place.
        if (rows.keys != jobs.map { it.id }.toSet()) {
            list.removeAllViews(); rows.clear()
            jobs.forEach { j -> rows[j.id] = makeRow(j).also { list.addView(it.root) } }
        }
        jobs.forEach { j -> rows[j.id]?.let { bind(j, it) } }
    }

    private fun makeRow(j: DownloadJob): RowViews {
        val title = TextView(this).apply { textSize = 15f; maxLines = 2 }
        val detail = TextView(this).apply { textSize = 12f; alpha = 0.7f }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val cancel = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close); background = null
            setOnClickListener { Downloads.cancel(j) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(ImageView(context).apply {
                setImageResource(if (j.dest.isCloud) R.drawable.ic_cloud else R.drawable.ic_download)
                alpha = 0.8f
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(bar); addView(detail)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cancel)
        }
        return RowViews(root, title, detail, bar, cancel)
    }

    private fun bind(j: DownloadJob, r: RowViews) {
        r.title.text = j.name
        val f = j.fraction
        r.bar.visibility = if (j.active) View.VISIBLE else View.GONE
        r.bar.isIndeterminate = f == null
        if (f != null) r.bar.progress = (f * 1000).toInt()
        r.cancel.visibility = if (j.active) View.VISIBLE else View.GONE
        val amount = formatBytes(j.bytes) + if (j.total > 0) " / ${formatBytes(j.total)}" else ""
        val parts = if (j.segTotal > 0) " · ${j.segDone}/${j.segTotal} parts" else ""
        r.detail.text = when (j.status) {
            Status.QUEUED -> "Waiting… → ${j.dest.label}"
            Status.RUNNING -> "$amount$parts → ${j.dest.label}" + (j.note?.let { "\n$it" } ?: "")
            Status.DONE -> "Done · ${formatBytes(j.bytes)} · ${j.location ?: j.dest.label}" + (j.note?.let { "\n$it" } ?: "")
            Status.FAILED -> "Failed: ${j.error}"
            Status.CANCELLED -> "Cancelled"
        }
    }
}
