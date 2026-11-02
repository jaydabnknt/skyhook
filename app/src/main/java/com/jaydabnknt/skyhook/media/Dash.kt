package com.jaydabnknt.skyhook.media

import android.util.Xml
import com.jaydabnknt.skyhook.download.Http
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.ceil

/** MPEG-DASH manifest → Tracks. Supports SegmentTemplate (+Timeline), SegmentList and single-file SegmentBase. */
object Dash {

    class Node(val name: String, val attrs: Map<String, String>) {
        val children = mutableListOf<Node>()
        var text = ""
        fun child(n: String) = children.firstOrNull { it.name == n }
        fun all(n: String) = children.filter { it.name == n }
        operator fun get(a: String) = attrs[a]
    }

    fun parseXml(xml: String): Node {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until p.attributeCount).associate { p.getAttributeName(it).substringAfter(':') to p.getAttributeValue(it) }
                    val n = Node(p.name.substringAfter(':'), attrs)
                    stack.lastOrNull()?.children?.add(n) ?: run { root = n }
                    stack.addLast(n)
                }
                XmlPullParser.TEXT -> stack.lastOrNull()?.let { it.text += p.text }
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return root ?: throw UnsupportedMedia("Empty manifest")
    }

    /** ISO-8601 duration like PT1H2M3.5S → seconds. */
    fun isoSeconds(s: String?): Double? {
        if (s == null) return null
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?""").matchEntire(s.trim()) ?: return null
        val (d, h, mi, se) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
            (mi.toDoubleOrNull() ?: 0.0) * 60 + (se.toDoubleOrNull() ?: 0.0)
    }

    private class Rep(
        val id: String, val bandwidth: Long, val width: Int, val height: Int, val codecs: String,
        val isVideo: Boolean, val isAudio: Boolean, val lang: String, val drm: Boolean,
        val node: Node, val adaptation: Node, val base: String, val periodSeconds: Double,
    )

    suspend fun probe(item: MediaItem): Hls.Probe {
        val (finalUrl, xml) = Http.text(item.url, item.headers)
        val mpd = parseXml(xml)
        if (mpd.name != "MPD") throw UnsupportedMedia("Not a DASH manifest")
        val live = mpd["type"] == "dynamic"

        var base = finalUrl
        mpd.child("BaseURL")?.let { base = resolveUrl(base, it.text) }
        val total = isoSeconds(mpd["mediaPresentationDuration"])

        val periods = mpd.all("Period")
        if (periods.isEmpty()) throw UnsupportedMedia("Manifest has no periods")
        // Multi-period manifests are usually ads + content: take the longest period.
        val withDur = periods.mapIndexed { i, p ->
            val start = isoSeconds(p["start"]) ?: 0.0
            val nextStart = periods.getOrNull(i + 1)?.let { isoSeconds(it["start"]) }
            val dur = isoSeconds(p["duration"]) ?: nextStart?.minus(start) ?: total?.minus(start) ?: 0.0
            p to dur
        }
        val (period, periodSec) = withDur.maxByOrNull { it.second }!!
        val pBase = period.child("BaseURL")?.let { resolveUrl(base, it.text) } ?: base

        val reps = mutableListOf<Rep>()
        for (aset in period.all("AdaptationSet")) {
            val aBase = aset.child("BaseURL")?.let { resolveUrl(pBase, it.text) } ?: pBase
            val asDrm = aset.all("ContentProtection").isNotEmpty()
            for (r in aset.all("Representation")) {
                val mime = r["mimeType"] ?: aset["mimeType"] ?: ""
                val ctype = aset["contentType"] ?: ""
                val codecs = r["codecs"] ?: aset["codecs"] ?: ""
                val isVideo = mime.startsWith("video") || ctype == "video" ||
                    (r["height"] ?: aset["height"]) != null && !mime.startsWith("audio")
                val isAudio = !isVideo && (mime.startsWith("audio") || ctype == "audio" || codecs.startsWith("mp4a") ||
                    codecs.startsWith("opus") || codecs.startsWith("ac-3") || codecs.startsWith("ec-3"))
                if (!isVideo && !isAudio) continue // subtitles, images
                reps += Rep(
                    id = r["id"] ?: "",
                    bandwidth = r["bandwidth"]?.toLongOrNull() ?: 0,
                    width = (r["width"] ?: aset["width"])?.toIntOrNull() ?: 0,
                    height = (r["height"] ?: aset["height"])?.toIntOrNull() ?: 0,
                    codecs = codecs,
                    isVideo = isVideo, isAudio = isAudio,
                    lang = aset["lang"] ?: "",
                    drm = asDrm || r.all("ContentProtection").isNotEmpty(),
                    node = r, adaptation = aset,
                    base = r.child("BaseURL")?.let { resolveUrl(aBase, it.text) } ?: aBase,
                    periodSeconds = periodSec,
                )
            }
        }
        if (reps.isEmpty()) throw UnsupportedMedia("No audio or video in manifest")
        if (reps.all { it.drm }) throw UnsupportedMedia("This video is DRM-protected and can't be downloaded")
        val usable = reps.filter { !it.drm }

        val audios = usable.filter { it.isAudio }
        val bestAudio = audios.maxByOrNull { it.bandwidth }
        val videos = usable.filter { it.isVideo }

        val qualities = if (videos.isNotEmpty()) {
            videos.sortedWith(compareByDescending<Rep> { it.height }.thenByDescending { it.bandwidth })
                .distinctBy { "${it.height}/${it.bandwidth}/${it.codecs.take(4)}" }
                .map { v ->
                    val codec = v.codecs.substringBefore('.').let { c ->
                        when (c) { "avc1", "avc3" -> "H.264"; "hvc1", "hev1" -> "H.265"; "vp09", "vp9" -> "VP9"; "av01" -> "AV1"; else -> c }
                    }
                    Quality(
                        label = if (v.height > 0) "${v.height}p" else "Video",
                        detail = listOf(if (v.width > 0) "${v.width}×${v.height}" else "", codec, formatBitrate(v.bandwidth))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        height = v.height, bandwidth = v.bandwidth,
                    ) { toTrack(v, live) to bestAudio?.let { toTrack(it, live) } }
                }
        } else {
            audios.sortedByDescending { it.bandwidth }.map { a ->
                Quality("Audio", listOf(a.codecs, formatBitrate(a.bandwidth), a.lang).filter { it.isNotBlank() }.joinToString(" · "), 0, a.bandwidth) {
                    toTrack(a, live) to null
                }
            }
        }
        return Hls.Probe(qualities, emptySet())
    }

    private fun template(t: String, rep: Rep, number: Long, time: Long): String =
        Regex("""\$(RepresentationID|Number|Bandwidth|Time)(%0(\d+)d)?\$|\$\$""").replace(t) { m ->
            if (m.value == "$$") return@replace "$"
            val v = when (m.groupValues[1]) {
                "RepresentationID" -> return@replace rep.id
                "Number" -> number
                "Bandwidth" -> rep.bandwidth
                else -> time
            }
            val w = m.groupValues[3].toIntOrNull()
            if (w != null) v.toString().padStart(w, '0') else v.toString()
        }

    private fun range(s: String?): LongRange? = s?.split('-')?.let { p ->
        val a = p.getOrNull(0)?.toLongOrNull(); val b = p.getOrNull(1)?.toLongOrNull()
        if (a != null && b != null) a..b else null
    }

    private fun toTrack(rep: Rep, live: Boolean): Track {
        val audioOnly = rep.isAudio
        // SegmentTemplate may be on the AdaptationSet, the Representation, or both (Rep overrides).
        val tA = rep.adaptation.child("SegmentTemplate")
        val tR = rep.node.child("SegmentTemplate")
        if (tA != null || tR != null) {
            fun attr(n: String) = tR?.get(n) ?: tA?.get(n)
            val timeline = (tR?.child("SegmentTimeline") ?: tA?.child("SegmentTimeline"))
            val timescale = attr("timescale")?.toLongOrNull() ?: 1L
            val startNumber = attr("startNumber")?.toLongOrNull() ?: 1L
            val pto = attr("presentationTimeOffset")?.toLongOrNull() ?: 0L
            val media = attr("media") ?: throw UnsupportedMedia("SegmentTemplate without media")
            val initT = attr("initialization")
            val init = initT?.let { Seg(resolveUrl(rep.base, template(it, rep, startNumber, 0))) }
            val segs = mutableListOf<Seg>()
            if (timeline != null) {
                var t = 0L
                var n = startNumber
                val end = pto + (rep.periodSeconds * timescale).toLong()
                val ss = timeline.all("S")
                for ((idx, s) in ss.withIndex()) {
                    s["t"]?.toLongOrNull()?.let { t = it }
                    val d = s["d"]?.toLongOrNull() ?: continue
                    var r = s["r"]?.toLongOrNull() ?: 0L
                    if (r < 0) {
                        val until = ss.getOrNull(idx + 1)?.get("t")?.toLongOrNull() ?: end
                        r = ((until - t + d - 1) / d - 1).coerceAtLeast(0)
                    }
                    for (k in 0..r) {
                        segs += Seg(resolveUrl(rep.base, template(media, rep, n, t)), sequence = n)
                        t += d; n++
                    }
                }
            } else {
                val dur = attr("duration")?.toLongOrNull() ?: throw UnsupportedMedia("SegmentTemplate without duration")
                val count = ceil(rep.periodSeconds * timescale / dur).toLong().coerceIn(1, 200_000)
                for (k in 0 until count) {
                    val n = startNumber + k
                    segs += Seg(resolveUrl(rep.base, template(media, rep, n, pto + k * dur)), sequence = n)
                }
            }
            return Track(Container.FMP4, init, segs, audioOnly, live)
        }

        val list = rep.node.child("SegmentList") ?: rep.adaptation.child("SegmentList")
        if (list != null) {
            val initNode = list.child("Initialization")
            val init = initNode?.let { Seg(resolveUrl(rep.base, it["sourceURL"] ?: ""), range(it["range"])) }
            val segs = list.all("SegmentURL").mapIndexed { i, s ->
                Seg(resolveUrl(rep.base, s["media"] ?: ""), range(s["mediaRange"]), sequence = i.toLong())
            }
            return Track(Container.FMP4, init, segs, audioOnly, live)
        }

        // SegmentBase / plain BaseURL: the whole representation is one file.
        return Track(Container.WHOLE_FILE, null, listOf(Seg(rep.base)), audioOnly, live)
    }
}
