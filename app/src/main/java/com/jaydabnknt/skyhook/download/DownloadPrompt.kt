package com.jaydabnknt.skyhook.download

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import com.jaydabnknt.skyhook.browser.Gecko
import com.jaydabnknt.skyhook.media.formatBytes
import com.jaydabnknt.skyhook.ui.DownloadDialog
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.net.URLDecoder

/** Normal file downloads (links Gecko can't display) and "Download link" from the context menu. */
object DownloadPrompt {

    fun fromResponse(activity: Activity, response: WebResponse, pageUrl: String) {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val name = fileName(headers["content-disposition"], response.uri)
        val mime = headers["content-type"]?.substringBefore(';')?.trim()
        val size = headers["content-length"]?.toLongOrNull()
        val body = response.body
        if (body == null) {
            // Gecko gave us no stream; fetch it ourselves.
            DownloadDialog.show(activity, name, size?.let(::formatBytes)) { n, dest ->
                Downloads.enqueue(activity, n, dest, Source.Direct(response.uri, mapOf("Referer" to pageUrl), mime))
            }
            return
        }
        DownloadDialog.show(
            activity, name, listOfNotNull(mime, size?.let(::formatBytes)).joinToString(" · "),
            onCancel = { runCatching { body.close() } },
        ) { n, dest ->
            Downloads.enqueue(activity, n, dest, Source.Body(body, size, mime))
        }
    }

    /** Fetches a URL through Gecko (so the site's cookies apply), then asks where to save it. */
    fun fetchWithGecko(activity: Activity, url: String, pageUrl: String) {
        val req = WebRequest.Builder(url).addHeader("Referer", pageUrl).build()
        GeckoWebExecutor(Gecko.runtime).fetch(req).accept({ resp ->
            activity.runOnUiThread {
                if (resp == null || resp.statusCode >= 400) {
                    Toast.makeText(activity, "Download failed (HTTP ${resp?.statusCode})", Toast.LENGTH_SHORT).show()
                    runCatching { resp?.body?.close() }
                } else fromResponse(activity, resp, pageUrl)
            }
        }, { e ->
            activity.runOnUiThread { Toast.makeText(activity, "Download failed: ${e?.message}", Toast.LENGTH_SHORT).show() }
        })
    }

    fun fileName(disposition: String?, url: String): String {
        if (disposition != null) {
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(disposition)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim('"'), "UTF-8") }.getOrDefault(it.groupValues[1])
            }
            Regex("""filename\s*=\s*"?([^";]+)"?""").find(disposition)?.let { return it.groupValues[1].trim() }
        }
        return Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
    }
}
