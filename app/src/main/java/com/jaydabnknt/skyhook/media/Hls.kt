package com.jaydabnknt.skyhook.media

import com.jaydabnknt.skyhook.download.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UnsupportedMedia(msg: String) : Exception(msg)

fun resolveUrl(base: String, ref: String): String =
    base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString() ?: ref.trim()

object Hls {
    data class Variant(
        val url: String, val bandwidth: Long, val width: Int, val height: Int,
        val codecs: String, val audioGroup: String?, val frameRate: String?,
    )

    data class Rendition(val groupId: String, val name: String, val language: String, val url: String?, val isDefault: Boolean)

    data class Master(val variants: List<Variant>, val audios: List<Rendition>)

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, base: String): Master {
        val variants = mutableListOf<Variant>()
        val audios = mutableListOf<Rendition>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val a = attrs(line)
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val res = a["RESOLUTION"]?.split('x')
                        variants += Variant(
                            url = resolveUrl(base, lines[j]),
                            bandwidth = a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                            width = res?.getOrNull(0)?.toIntOrNull() ?: 0,
                            height = res?.getOrNull(1)?.toIntOrNull() ?: 0,
                            codecs = a["CODECS"] ?: "",
                            audioGroup = a["AUDIO"],
                            frameRate = a["FRAME-RATE"],
                        )
                    }
                    i = j
                }
                line.startsWith("#EXT-X-MEDIA") -> {
                    val a = attrs(line)
                    if (a["TYPE"] == "AUDIO") audios += Rendition(
                        groupId = a["GROUP-ID"] ?: "",
                        name = a["NAME"] ?: "",
                        language = a["LANGUAGE"] ?: "",
                        url = a["URI"]?.let { resolveUrl(base, it) },
                        isDefault = a["DEFAULT"] == "YES",
                    )
                }
            }
            i++
        }
        return Master(variants, audios)
    }

    fun parseMedia(text: String, base: String, audioOnly: Boolean = false): Track {
        val segs = mutableListOf<Seg>()
        var key: SegKey? = null
        var map: Seg? = null
        var seq = 0L
        var range: LongRange? = null
        var nextStart = 0L
        var ended = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(line)
                    key = when (a["METHOD"]) {
                        null, "NONE" -> null
                        "AES-128" -> SegKey(resolveUrl(base, a["URI"] ?: ""), a["IV"]?.let(::hex))
                        else -> throw UnsupportedMedia("This stream is DRM-protected (${a["METHOD"]}) and can't be downloaded")
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val a = attrs(line)
                    map = Seg(resolveUrl(base, a["URI"] ?: ""), a["BYTERANGE"]?.let { parseRange(it, 0) })
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    range = parseRange(line.substringAfter(':'), nextStart)
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#") -> {}
                else -> {
                    segs += Seg(resolveUrl(base, line), range, key, seq++)
                    range?.let { nextStart = it.last + 1 }
                    range = null
                }
            }
        }
        if (segs.isEmpty()) throw UnsupportedMedia("Playlist has no segments")
        val firstExt = segs.first().url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val container = when {
            map != null -> Container.FMP4
            firstExt in setOf("aac", "mp3", "ac3", "ec3") -> Container.RAW_AUDIO
            firstExt in setOf("mp4", "m4s", "m4v", "m4a", "cmfv", "cmfa", "m4f") -> Container.FMP4
            else -> Container.TS
        }
        return Track(container, map, segs, isAudioOnly = audioOnly || container == Container.RAW_AUDIO, live = !ended)
    }

    private fun parseRange(s: String, defaultStart: Long): LongRange {
        val len = s.substringBefore('@').trim().toLong()
        val start = if ('@' in s) s.substringAfter('@').trim().toLong() else defaultStart
        return start until start + len
    }

    private fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Child playlists of a master, so the UI can hide variant playlists that were also detected. */
    data class Probe(val qualities: List<Quality>, val children: Set<String>)

    suspend fun probe(item: MediaItem): Probe {
        val (finalUrl, text) = Http.text(item.url, item.headers)
        if (!text.trimStart().startsWith("#EXTM3U")) throw UnsupportedMedia("Not an HLS playlist")
        val h = item.headers
        if (!isMaster(text)) {
            val track = parseMedia(text, finalUrl)
            return Probe(listOf(Quality("Original", segInfo(track), 0, 0) { track to null }), emptySet())
        }
        val master = parseMaster(text, finalUrl)
        val children = master.variants.map { it.url }.toSet() + master.audios.mapNotNull { it.url }
        val audioOnlyMaster = master.variants.all { it.height == 0 && it.codecs.isNotBlank() && !it.codecs.contains("avc") && !it.codecs.contains("hvc") && !it.codecs.contains("hev") && !it.codecs.contains("av01") && !it.codecs.contains("vp09") }

        val qualities = master.variants
            .distinctBy { it.url }
            .sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
            .map { v ->
                val label = when {
                    v.height > 0 -> "${v.height}p"
                    audioOnlyMaster -> "Audio"
                    else -> "Stream"
                }
                val detail = buildList {
                    if (v.width > 0) add("${v.width}×${v.height}")
                    if (v.bandwidth > 0) add(formatBitrate(v.bandwidth))
                    v.frameRate?.toDoubleOrNull()?.let { if (it > 31) add("${it.toInt()}fps") }
                }.joinToString(" · ")
                Quality(label, detail, v.height, v.bandwidth) {
                    val (vu, vt) = Http.text(v.url, h)
                    val video = parseMedia(vt, vu, audioOnly = audioOnlyMaster)
                    val group = master.audios.filter { it.groupId == v.audioGroup && it.url != null }
                    val pick = group.firstOrNull { it.isDefault } ?: group.firstOrNull()
                    val audio = pick?.url?.let { au ->
                        val (aUrl, at) = Http.text(au, h)
                        parseMedia(at, aUrl, audioOnly = true)
                    }
                    video to audio
                }
            }
        return Probe(qualities, children)
    }

    private fun segInfo(t: Track) = "${t.segments.size} segments" + if (t.live) " · live" else ""
}

fun formatBitrate(bps: Long): String =
    if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000.0) else "${bps / 1000} kbps"

fun formatBytes(b: Long): String = when {
    b <= 0 -> "?"
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
