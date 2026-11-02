package com.jaydabnknt.skyhook.media

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Minimal ISO-BMFF (MP4) toolkit for streaming fragmented MP4:
 * merges a separate video and audio track into one playable file on the fly,
 * fragment by fragment, so nothing needs to be stored locally.
 */
object Mp4 {
    class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val end get() = start + size
        val body get() = start + header
    }

    class NotFragmented : IOException("Stream isn't fragmented MP4")

    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun put64(b: ByteArray, o: Int, v: Long) { put32(b, o, v ushr 32); put32(b, o + 4, v and 0xffffffffL) }

    fun type(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    fun boxes(b: ByteArray, from: Int = 0, to: Int = b.size): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) { size = u64(b, p + 8); header = 16 }
            else if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(type(b, p + 4), p, size.toInt(), header)
            p += size.toInt()
        }
        return out
    }

    fun children(b: ByteArray, box: Box) = boxes(b, box.body, box.end)

    private fun slice(b: ByteArray, box: Box) = b.copyOfRange(box.start, box.end)

    private fun wrap(type: String, parts: List<ByteArray>): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val out = ByteArray(size)
        put32(out, 0, size.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        var p = 8
        for (x in parts) { x.copyInto(out, p); p += x.size }
        return out
    }

    /** Timescale of the (first) track in a moov. */
    fun timescale(init: ByteArray): Long? {
        val moov = boxes(init).firstOrNull { it.type == "moov" } ?: return null
        val trak = children(init, moov).firstOrNull { it.type == "trak" } ?: return null
        val mdia = children(init, trak).firstOrNull { it.type == "mdia" } ?: return null
        val mdhd = children(init, mdia).firstOrNull { it.type == "mdhd" } ?: return null
        val v = init[mdhd.body].toInt()
        return u32(init, mdhd.body + if (v == 1) 20 else 12)
    }

    private fun patchTkhd(trak: ByteArray, id: Long) {
        val t = boxes(trak).first()
        val tkhd = children(trak, t).firstOrNull { it.type == "tkhd" } ?: return
        val v = trak[tkhd.body].toInt()
        put32(trak, tkhd.body + if (v == 1) 20 else 12, id)
    }

    private fun trex(id: Long): ByteArray {
        val b = ByteArray(32)
        put32(b, 0, 32); "trex".toByteArray().copyInto(b, 4)
        put32(b, 12, id); put32(b, 16, 1) // track_ID, default_sample_description_index
        return b
    }

    /** Keeps only ftyp + moov from an init segment (drops sidx, styp, free…). */
    fun cleanInit(init: ByteArray): ByteArray {
        val keep = boxes(init).filter { it.type == "ftyp" || it.type == "moov" }
        if (keep.none { it.type == "moov" }) throw IOException("Init segment has no moov")
        val out = ByteArrayOutputStream()
        keep.forEach { out.write(init, it.start, it.size) }
        return out.toByteArray()
    }

    /** ftyp + one moov holding the video trak as track 1 and the audio trak as track 2. */
    fun mergeInit(video: ByteArray, audio: ByteArray): ByteArray {
        val vTop = boxes(video)
        val aTop = boxes(audio)
        val ftyp = vTop.firstOrNull { it.type == "ftyp" }?.let { slice(video, it) }
        val vMoov = vTop.first { it.type == "moov" }
        val aMoov = aTop.first { it.type == "moov" }
        val vKids = children(video, vMoov)
        val aKids = children(audio, aMoov)

        val mvhd = slice(video, vKids.first { it.type == "mvhd" })
        put32(mvhd, mvhd.size - 4, 3) // next_track_ID

        val vTrak = slice(video, vKids.first { it.type == "trak" }).also { patchTkhd(it, 1) }
        val aTrak = slice(audio, aKids.first { it.type == "trak" }).also { patchTkhd(it, 2) }

        val vMvex = vKids.firstOrNull { it.type == "mvex" }
        val aMvex = aKids.firstOrNull { it.type == "mvex" }
        val mvexParts = mutableListOf<ByteArray>()
        vMvex?.let { m -> children(video, m).filter { it.type == "mehd" }.forEach { mvexParts += slice(video, it) } }
        mvexParts += vMvex?.let { m -> children(video, m).firstOrNull { it.type == "trex" } }
            ?.let { slice(video, it).also { t -> put32(t, 12, 1) } } ?: trex(1)
        mvexParts += aMvex?.let { m -> children(audio, m).firstOrNull { it.type == "trex" } }
            ?.let { slice(audio, it).also { t -> put32(t, 12, 2) } } ?: trex(2)

        val others = vKids.filter { it.type !in setOf("mvhd", "trak", "mvex", "pssh") }.map { slice(video, it) }
        val moov = wrap("moov", listOf(mvhd, vTrak, aTrak) + others + listOf(wrap("mvex", mvexParts)))
        return (ftyp ?: ByteArray(0)) + moov
    }

    /** A moof + its mdat(s), cut contiguously from a segment or file. */
    class Fragment(val data: ByteArray, val origPos: Long) {
        /** baseMediaDecodeTime from tfdt, in track timescale units. */
        val decodeTime: Long? = run {
            val moof = boxes(data).firstOrNull { it.type == "moof" } ?: return@run null
            val traf = children(data, moof).firstOrNull { it.type == "traf" } ?: return@run null
            val tfdt = children(data, traf).firstOrNull { it.type == "tfdt" } ?: return@run null
            if (data[tfdt.body].toInt() == 1) u64(data, tfdt.body + 4) else u32(data, tfdt.body + 4)
        }

        /** Renumber, retarget to [trackId], and fix absolute data offsets for output position [outPos]. */
        fun patch(seq: Long, trackId: Long?, outPos: Long) {
            val moof = boxes(data).firstOrNull { it.type == "moof" } ?: return
            for (c in children(data, moof)) {
                if (c.type == "mfhd") put32(data, c.body + 4, seq)
                if (c.type == "traf") for (t in children(data, c)) if (t.type == "tfhd") {
                    val flags = u32(data, t.body) and 0xffffff
                    if (trackId != null) put32(data, t.body + 4, trackId)
                    if (flags and 0x1L != 0L) {
                        val orig = u64(data, t.body + 8)
                        put64(data, t.body + 8, outPos + (orig - origPos))
                    }
                }
            }
        }
    }

    /** Splits a whole segment into fragments, skipping styp/sidx/emsg and any ftyp/moov. */
    fun fragments(seg: ByteArray): List<Fragment> {
        val bs = boxes(seg)
        val out = mutableListOf<Fragment>()
        var i = 0
        while (i < bs.size) {
            if (bs[i].type != "moof") { i++; continue }
            var j = i + 1
            var end = bs[i].end
            while (j < bs.size && bs[j].type != "moof" && bs[j].type in setOf("mdat", "free", "skip", "uuid", "meta")) {
                end = bs[j].end; j++
            }
            out += Fragment(seg.copyOfRange(bs[i].start, end), bs[i].start.toLong())
            i = j
        }
        return out
    }

    /** Reads a fragmented MP4 file box-by-box from a stream without buffering the whole thing. */
    class StreamReader(private val input: InputStream) {
        private var pos = 0L
        private var pending: Pair<ByteArray, Long>? = null // a read-ahead moof

        private fun readFully(n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            pos += n
            return b
        }

        /** Next whole box as bytes, or null at EOF. Refuses giant non-fragment boxes. */
        private fun nextBox(): Pair<ByteArray, Long>? {
            val start = pos
            val head = try { readFully(8) } catch (e: EOFException) { return null }
            var size = u32(head, 0)
            var header = head
            if (size == 1L) { val ext = readFully(8); size = u64(ext, 0); header = head + ext }
            if (size == 0L || size > Int.MAX_VALUE) throw NotFragmented()
            val t = type(head, 4)
            if (t == "mdat" && size > 512L * 1024 * 1024) throw NotFragmented()
            return (header + readFully(size.toInt() - header.size)) to start
        }

        /** Reads ftyp+moov up to the first fragment. */
        fun init(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val (box, start) = nextBox() ?: throw NotFragmented()
                when (type(box, 4)) {
                    "ftyp", "moov" -> out.write(box)
                    "moof" -> { pending = box to start; return out.toByteArray() }
                    "mdat" -> throw NotFragmented()
                }
            }
        }

        fun next(): Fragment? {
            var head = pending
            pending = null
            while (head == null) {
                val b = nextBox() ?: return null
                if (type(b.first, 4) == "moof") head = b
            }
            val (moof, start) = head!!
            val out = ByteArrayOutputStream()
            out.write(moof)
            while (true) {
                val b = nextBox() ?: break
                if (type(b.first, 4) == "moof") { pending = b; break }
                if (type(b.first, 4) in setOf("mdat", "free", "skip", "uuid", "meta")) out.write(b.first) else break
            }
            return Fragment(out.toByteArray(), start)
        }
    }
}
