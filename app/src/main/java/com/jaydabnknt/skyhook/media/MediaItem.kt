package com.jaydabnknt.skyhook.media

import org.json.JSONObject

enum class MediaKind { HLS, DASH, VIDEO, AUDIO }

/** A piece of media seen on the network by the detector extension. */
data class MediaItem(
    val url: String,
    val kind: MediaKind,
    val mime: String,
    val size: Long,
    val headers: Map<String, String>,
    val pageUrl: String,
    val title: String,
) {
    val isStream get() = kind == MediaKind.HLS || kind == MediaKind.DASH

    companion object {
        fun fromJson(o: JSONObject): MediaItem? {
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: return null
            val kind = when (o.optString("kind")) {
                "hls" -> MediaKind.HLS
                "dash" -> MediaKind.DASH
                "audio" -> MediaKind.AUDIO
                else -> MediaKind.VIDEO
            }
            val h = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { hj -> hj.keys().forEach { k -> h[k] = hj.optString(k) } }
            return MediaItem(
                url = url,
                kind = kind,
                mime = o.optString("mime"),
                size = o.optLong("size", 0),
                headers = h,
                pageUrl = o.optString("pageUrl"),
                title = o.optString("title"),
            )
        }
    }
}
