package com.jaydabnknt.skyhook.media

/** How a track's bytes are laid out. */
enum class Container { TS, FMP4, RAW_AUDIO, WHOLE_FILE }

/** HLS AES-128 key reference. */
data class SegKey(val uri: String, val iv: ByteArray?)

data class Seg(
    val url: String,
    val range: LongRange? = null,
    val key: SegKey? = null,
    val sequence: Long = 0,
)

/** A downloadable, playable stream: optional init segment followed by media segments. */
data class Track(
    val container: Container,
    val init: Seg?,
    val segments: List<Seg>,
    val isAudioOnly: Boolean = false,
    val live: Boolean = false,
) {
    val extension: String
        get() = when (container) {
            Container.TS -> ".ts"
            Container.RAW_AUDIO -> segments.firstOrNull()?.url?.substringBefore('?')?.substringAfterLast('.', "aac")
                ?.let { ".${it.take(4)}" } ?: ".aac"
            Container.FMP4, Container.WHOLE_FILE -> if (isAudioOnly) ".m4a" else ".mp4"
        }
    val mime: String
        get() = when (container) {
            Container.TS -> "video/mp2t"
            Container.RAW_AUDIO -> "audio/aac"
            else -> if (isAudioOnly) "audio/mp4" else "video/mp4"
        }
}

/** One choosable quality level, shown to the user. */
data class Quality(
    val label: String,
    val detail: String,
    val height: Int,
    val bandwidth: Long,
    /** Resolves into the concrete tracks to download: video (+ separate audio). */
    val resolve: suspend () -> Pair<Track, Track?>,
)
