package com.apenv.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class YtdResult(val bytes: ByteArray, val converted: Int, val retainedBc: Int)

// Rewrites a .ytd resource so its DXT1/DXT3/DXT5 textures become ASTC.
// Works on the resource bytes with 1 header (native files) or 2 headers (files extracted from the port).
object YtdConvert {
    private const val MAGIC = 0x37435352
    private const val GFX_BASE = 0x60000000L
    private val LIMITS = intArrayOf(1, 1, 1, 1, 127, 63, 15, 3, 1)

    private class Tex(
        val ptr: Int, val w: Int, val h: Int, val lv: Int, val cc: String, val fmt: Int, val off: Int
    )

    private class Plan(val bits: Long, val total: Long, val offsets: LongArray)

    // Size in bytes described by a resource page flags word.
    fun pagesSize(f: Long): Long {
        val s0 = (f shr 27) and 1L
        val s1 = ((f shr 26) and 1L) shl 1
        val s2 = ((f shr 25) and 1L) shl 2
        val s3 = ((f shr 24) and 1L) shl 3
        val s4 = ((f shr 17) and 0x7FL) shl 4
        val s5 = ((f shr 11) and 0x3FL) shl 5
        val s6 = ((f shr 7) and 0xFL) shl 6
        val s7 = ((f shr 5) and 3L) shl 7
        val s8 = ((f shr 4) and 1L) shl 8
        return (0x200L shl (f and 0xF).toInt()) * (s0 + s1 + s2 + s3 + s4 + s5 + s6 + s7 + s8)
    }

    // FourCC the port uses for an ASTC block size: "AS" + two block dimension characters.
    // Dimensions up to 9 are digits; 10 and 12 use A and C (a guess, change here if the port differs).
    fun fourCc(bw: Int, bh: Int): String = "AS" + dimChar(bw) + dimChar(bh)

    private fun dimChar(n: Int): Char = when {
        n < 10 -> '0' + n
        n == 10 -> 'A'
        else -> 'C'
    }

    internal fun charDim(c: Char): Int = when {
        c in '0'..'9' -> c - '0'
        c == 'A' -> 10
        c == 'C' -> 12
        else -> 0
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    private fun align16(n: Long) = (n + 15) and 15L.inv()

    // Bytes of pixel data for a texture, or null when the format is not known.
    private fun dataSize(t: Tex): Long? {
        val levels = maxOf(t.lv, 1)
        var bw = 4
        var bh = 4
        var per = 0
        val cc = t.cc
        when {
            cc == "DXT1" || cc == "ATI1" -> per = 8
            cc == "DXT3" || cc == "DXT5" || cc == "ATI2" || cc == "BC7 " -> per = 16
            cc.startsWith("AS") -> {
                bw = charDim(cc[2]); bh = charDim(cc[3]); per = 16
                if (bw == 0 || bh == 0) return null
            }
            t.fmt == 28 || t.fmt == 50 -> { bw = 1; bh = 1; per = 1 }
            t.fmt == 21 -> { bw = 1; bh = 1; per = 4 }
            else -> return null
        }
        var total = 0L
        for (l in 0 until levels) {
            val wl = maxOf(1, t.w shr l)
            val hl = maxOf(1, t.h shr l)
            total += ((wl + bw - 1) / bw).toLong() * ((hl + bh - 1) / bh) * per
        }
        return total
    }

    private fun decompose(n: Long): IntArray? {
        val counts = IntArray(9)
        var rem = n
        for (k in 8 downTo 0) {
            val unit = 1L shl k
            val c = minOf(rem / unit, LIMITS[k].toLong())
            counts[k] = c.toInt()
            rem -= c * unit
        }
        return if (rem == 0L) counts else null
    }

    private fun flagBits(ss: Int, c: IntArray): Long =
        (c[0].toLong() shl 27) or (c[1].toLong() shl 26) or (c[2].toLong() shl 25) or (c[3].toLong() shl 24) or
            (c[4].toLong() shl 17) or (c[5].toLong() shl 11) or (c[6].toLong() shl 7) or
            (c[7].toLong() shl 5) or (c[8].toLong() shl 4) or ss.toLong()

    // Chooses a page layout for the graphics segment and puts every blob wholly inside one page.
    private fun plan(sizes: LongArray): Plan? {
        val total = sizes.sum()
        val order = sizes.indices.sortedByDescending { sizes[it] }
        var best: Plan? = null
        var bestScore = Long.MAX_VALUE
        for (ss in 0..15) {
            val base = 0x200L shl ss
            var n = maxOf(1L, (total + base - 1) / base)
            var tries = 0
            while (tries < 64) {
                val counts = decompose(n) ?: break
                val pages = ArrayList<Long>()
                for (k in 8 downTo 0) repeat(counts[k]) { pages += base shl k }
                val starts = LongArray(pages.size)
                var acc = 0L
                for (i in pages.indices) { starts[i] = acc; acc += pages[i] }
                val used = LongArray(pages.size)
                val offsets = LongArray(sizes.size)
                var ok = true
                for (i in order) {
                    var placed = false
                    for (p in pages.indices) {
                        if (pages[p] - used[p] >= sizes[i]) {
                            offsets[i] = starts[p] + used[p]
                            used[p] += sizes[i]
                            placed = true
                            break
                        }
                    }
                    if (!placed) { ok = false; break }
                }
                if (ok) {
                    val score = acc + pages.size * 0x10000L
                    if (score < bestScore) {
                        bestScore = score
                        best = Plan(flagBits(ss, counts), acc, offsets)
                    }
                    break
                }
                n += maxOf(1L, n / 16)
                tries++
            }
        }
        return best
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 1024)
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        DeflaterOutputStream(out, d).use { it.write(data) }
        d.end()
        return out.toByteArray()
    }

    class TexPreview(val formats: String, val rgba: ByteArray?, val pw: Int, val ph: Int)
    class TexEntry(val name: String, val rgba: ByteArray?, val pw: Int, val ph: Int, val format: String)

    private fun formatLabel(cc: String, fmt: Int): String = when {
        cc == "DXT1" -> "BC1"
        cc == "DXT3" -> "BC2"
        cc == "DXT5" -> "BC3"
        cc == "ATI1" -> "BC4"
        cc == "ATI2" -> "BC5"
        cc == "BC7 " -> "BC7"
        cc.startsWith("AS") && charDim(cc[2]) > 0 && charDim(cc[3]) > 0 -> "ASTC ${charDim(cc[2])}x${charDim(cc[3])}"
        fmt == 28 || fmt == 50 -> "A8"
        fmt == 21 -> "RGBA8"
        else -> "other"
    }

    // Decodes a picture of the first BC or ASTC texture in a .ytd and lists the formats it contains.
    // Returns null when the file is not understood.
    fun preview(data: ByteArray): TexPreview? {
        if (data.size < 20) return null
        val head = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (head.getInt(0) != MAGIC) return null
        val headers = if (data.size >= 36 && head.getInt(16) == MAGIC) 2 else 1
        val sysSize = pagesSize(head.getInt(8).toLong() and 0xFFFFFFFFL)
        val raw = TexScan.inflate(data, headers * 16) ?: return null
        if (raw.size < sysSize) return null
        val rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val count = rb.getShort(0x38).toInt() and 0xFFFF
        val arr = (rb.getLong(0x30) and 0xFFFFFFFL).toInt()
        if (count == 0 || count > 8192 || arr < 0 || arr + count * 8L > sysSize) return null

        val labels = LinkedHashSet<String>()
        var pixels: ByteArray? = null
        var pw = 0
        var ph = 0
        for (i in 0 until count) {
            val tp = (rb.getLong(arr + 8 * i) and 0xFFFFFFFL).toInt()
            if (tp < 0 || tp + 0x90L > sysSize) continue
            val dptr = rb.getLong(tp + 0x70)
            if ((dptr ushr 28) != 6L) continue
            val w = u16(raw, tp + 0x50)
            val h = u16(raw, tp + 0x52)
            val levels = maxOf(raw[tp + 0x5D].toInt() and 255, 1)
            val cc = String(raw, tp + 0x58, 4, Charsets.ISO_8859_1)
            labels += formatLabel(cc, rb.getInt(tp + 0x58))
            if (pixels != null || w < 1 || h < 1) continue

            val kind = when (cc) { "DXT1" -> Bc.DXT1; "DXT3" -> Bc.DXT3; "DXT5" -> Bc.DXT5; else -> 0 }
            var bx = 4
            var by = 4
            var per = 16
            if (kind != 0) {
                per = if (kind == Bc.DXT1) 8 else 16
            } else if (cc.startsWith("AS") && charDim(cc[2]) > 0 && charDim(cc[3]) > 0 && Astc.available) {
                bx = charDim(cc[2])
                by = charDim(cc[3])
            } else {
                continue
            }

            var level = 0
            while (level < levels - 1 && maxOf(w shr level, h shr level) > 384) level++
            var p = (sysSize + (dptr and 0xFFFFFFFL)).toInt()
            for (k in 0 until level) {
                p += ((maxOf(1, w shr k) + bx - 1) / bx) * ((maxOf(1, h shr k) + by - 1) / by) * per
            }
            val wl = maxOf(1, w shr level)
            val hl = maxOf(1, h shr level)
            val size = ((wl + bx - 1) / bx) * ((hl + by - 1) / by) * per
            if (p < 0 || size <= 0 || p + size > raw.size) continue
            val decoded = if (kind != 0) Bc.decode(kind, raw, p, wl, hl)
            else Astc.decode(bx, by, raw.copyOfRange(p, p + size), wl, hl)
            if (decoded != null) {
                pixels = decoded
                pw = wl
                ph = hl
            }
        }
        return TexPreview(labels.joinToString(" · ").ifEmpty { "unknown" }, pixels, pw, ph)
    }

    private const val PREVIEW_MAX = 1024

    // Texture name stored in the system segment (the hash table holds only hashes).
    private fun textureName(raw: ByteArray, rb: ByteBuffer, tp: Int, sysSize: Long): String? {
        val np = rb.getLong(tp + 0x28)
        if ((np ushr 28) != 5L) return null
        val o = (np and 0xFFFFFFFL).toInt()
        if (o < 0 || o >= sysSize) return null
        var e = o
        while (e < raw.size && e - o < 128 && raw[e].toInt() != 0) e++
        if (e == o) return null
        return String(raw, o, e - o, Charsets.ISO_8859_1)
    }

    // Decodes every texture in the dictionary, with its real name, so textures that belong
    // together (a background and its foreground) can be recognised and shown as one picture.
    fun previewAll(data: ByteArray): List<TexEntry> {
        if (data.size < 20) return emptyList()
        val head = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (head.getInt(0) != MAGIC) return emptyList()
        val headers = if (data.size >= 36 && head.getInt(16) == MAGIC) 2 else 1
        val sysSize = pagesSize(head.getInt(8).toLong() and 0xFFFFFFFFL)
        val raw = TexScan.inflate(data, headers * 16) ?: return emptyList()
        if (raw.size < sysSize) return emptyList()
        val rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val count = rb.getShort(0x38).toInt() and 0xFFFF
        val arr = (rb.getLong(0x30) and 0xFFFFFFFL).toInt()
        if (count == 0 || count > 8192 || arr < 0 || arr + count * 8L > sysSize) return emptyList()

        val out = ArrayList<TexEntry>()
        for (i in 0 until count) {
            val tp = (rb.getLong(arr + 8 * i) and 0xFFFFFFFL).toInt()
            if (tp < 0 || tp + 0x90L > sysSize) continue
            val dptr = rb.getLong(tp + 0x70)
            if ((dptr ushr 28) != 6L) continue
            val name = textureName(raw, rb, tp, sysSize) ?: "texture ${out.size}"
            val w = u16(raw, tp + 0x50)
            val h = u16(raw, tp + 0x52)
            val levels = maxOf(raw[tp + 0x5D].toInt() and 255, 1)
            val cc = String(raw, tp + 0x58, 4, Charsets.ISO_8859_1)
            val fmt = rb.getInt(tp + 0x58)
            val label = formatLabel(cc, fmt)
            if (w < 1 || h < 1) {
                out += TexEntry(name, null, 0, 0, label)
                continue
            }

            // Plain 32-bit pixels (stored blue, green, red, alpha).
            if (fmt == 21) {
                var level = 0
                while (level < levels - 1 && maxOf(w shr level, h shr level) > PREVIEW_MAX) level++
                var p = (sysSize + (dptr and 0xFFFFFFFL)).toInt()
                for (k in 0 until level) p += maxOf(1, w shr k) * maxOf(1, h shr k) * 4
                val wl = maxOf(1, w shr level)
                val hl = maxOf(1, h shr level)
                val size = wl * hl * 4
                if (p < 0 || p + size > raw.size) {
                    out += TexEntry(name, null, 0, 0, label)
                    continue
                }
                val px = ByteArray(size)
                var o = 0
                while (o < size) {
                    px[o] = raw[p + o + 2]
                    px[o + 1] = raw[p + o + 1]
                    px[o + 2] = raw[p + o]
                    px[o + 3] = raw[p + o + 3]
                    o += 4
                }
                out += TexEntry(name, px, wl, hl, label)
                continue
            }

            val kind = when (cc) { "DXT1" -> Bc.DXT1; "DXT3" -> Bc.DXT3; "DXT5" -> Bc.DXT5; else -> 0 }
            var bx = 4
            var by = 4
            var per = 16
            if (kind != 0) {
                per = if (kind == Bc.DXT1) 8 else 16
            } else if (cc.startsWith("AS") && charDim(cc[2]) > 0 && charDim(cc[3]) > 0 && Astc.available) {
                bx = charDim(cc[2])
                by = charDim(cc[3])
            } else {
                out += TexEntry(name, null, 0, 0, label)
                continue
            }

            var level = 0
            while (level < levels - 1 && maxOf(w shr level, h shr level) > PREVIEW_MAX) level++
            var p = (sysSize + (dptr and 0xFFFFFFFL)).toInt()
            for (k in 0 until level) {
                p += ((maxOf(1, w shr k) + bx - 1) / bx) * ((maxOf(1, h shr k) + by - 1) / by) * per
            }
            val wl = maxOf(1, w shr level)
            val hl = maxOf(1, h shr level)
            val size = ((wl + bx - 1) / bx) * ((hl + by - 1) / by) * per
            if (p < 0 || size <= 0 || p + size > raw.size) {
                out += TexEntry(name, null, 0, 0, label)
                continue
            }
            val decoded = if (kind != 0) Bc.decode(kind, raw, p, wl, hl)
            else Astc.decode(bx, by, raw.copyOfRange(p, p + size), wl, hl)
            out += TexEntry(name, decoded, wl, hl, label)
        }
        return out
    }

    // Returns null when there is nothing to convert. Throws IOException when the file is not understood.
    fun convert(data: ByteArray, bw: Int, bh: Int, enc: Long, cancelled: () -> Boolean): YtdResult? {
        if (data.size < 20) throw IOException("File too small")
        val head = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (head.getInt(0) != MAGIC) throw IOException("Not a resource file")
        val headers = if (data.size >= 36 && head.getInt(16) == MAGIC) 2 else 1
        val skip = headers * 16
        val sysFlags = head.getInt(8).toLong() and 0xFFFFFFFFL
        val gfxFlags = head.getInt(12).toLong() and 0xFFFFFFFFL
        val sysSize = pagesSize(sysFlags)
        val gfxSize = pagesSize(gfxFlags)
        val raw = TexScan.inflate(data, skip) ?: throw IOException("Could not unpack the resource")
        if (raw.size.toLong() != sysSize + gfxSize) throw IOException("Unexpected resource size")
        val rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val count = rb.getShort(0x38).toInt() and 0xFFFF
        val arr = (rb.getLong(0x30) and 0xFFFFFFFL).toInt()
        if (count == 0 || count > 8192 || arr < 0 || arr + count * 8L > sysSize) throw IOException("Unsupported layout")

        val texs = ArrayList<Tex>()
        for (i in 0 until count) {
            val tp = (rb.getLong(arr + 8 * i) and 0xFFFFFFFL).toInt()
            if (tp < 0 || tp + 0x90L > sysSize) throw IOException("Bad texture pointer")
            val dptr = rb.getLong(tp + 0x70)
            if ((dptr ushr 28) != 6L) throw IOException("Bad pixel data pointer")
            texs += Tex(
                tp, u16(raw, tp + 0x50), u16(raw, tp + 0x52), raw[tp + 0x5D].toInt() and 255,
                String(raw, tp + 0x58, 4, Charsets.ISO_8859_1), rb.getInt(tp + 0x58),
                (dptr and 0xFFFFFFFL).toInt()
            )
        }

        val order = texs.indices.sortedBy { texs[it].off }
        val blobs = arrayOfNulls<ByteArray>(texs.size)
        val newLv = IntArray(texs.size) { texs[it].lv }
        val newCc = arrayOfNulls<String>(texs.size)
        var converted = 0
        var retained = 0

        for ((pos, idx) in order.withIndex()) {
            if (cancelled()) throw CancellationException()
            val t = texs[idx]
            val next = if (pos + 1 < order.size) texs[order[pos + 1]].off.toLong() else gfxSize
            val span = next - t.off
            val kind = when (t.cc) { "DXT1" -> Bc.DXT1; "DXT3" -> Bc.DXT3; "DXT5" -> Bc.DXT5; else -> 0 }
            val start = (sysSize + t.off).toInt()

            if (kind == 0) {
                if (t.cc == "ATI1" || t.cc == "ATI2" || t.cc == "BC7 ") retained++
                val known = dataSize(t)
                val size = if (known != null && known in 1..span) known else span
                if (size <= 0 || start + size > raw.size) throw IOException("Texture data out of range")
                blobs[idx] = raw.copyOfRange(start, start + size.toInt())
                continue
            }

            val per = if (kind == Bc.DXT1) 8 else 16
            val lvN = maxOf(t.lv, 1)
            val lvA = if (lvN <= 1) 1 else minOf(lvN, maxOf(1, 31 - Integer.numberOfLeadingZeros(minOf(t.w, t.h))))
            val out = ByteArrayOutputStream()
            var p = start
            for (l in 0 until lvA) {
                val wl = maxOf(1, t.w shr l)
                val hl = maxOf(1, t.h shr l)
                val bytes = ((wl + 3) / 4) * ((hl + 3) / 4) * per
                if (p + bytes > raw.size) throw IOException("Texture data out of range")
                val rgba = Bc.decode(kind, raw, p, wl, hl)
                val packed = Astc.encode(enc, rgba, wl, hl) ?: throw IOException("Encoder failed")
                if (cancelled()) throw CancellationException()
                out.write(packed)
                p += bytes
            }
            blobs[idx] = out.toByteArray()
            newLv[idx] = lvA
            newCc[idx] = fourCc(bw, bh)
            converted++
        }
        if (converted == 0) return null

        val sizes = LongArray(texs.size) { align16(blobs[it]!!.size.toLong()) }
        val plan = plan(sizes) ?: throw IOException("Could not lay out the texture pages")
        val gfx = ByteArray(plan.total.toInt())
        for (i in texs.indices) {
            val b = blobs[i]!!
            System.arraycopy(b, 0, gfx, plan.offsets[i].toInt(), b.size)
            val t = texs[i]
            rb.putLong(t.ptr + 0x70, GFX_BASE + plan.offsets[i])
            val cc = newCc[i]
            if (cc != null) {
                for (k in 0 until 4) raw[t.ptr + 0x58 + k] = cc[k].code.toByte()
                raw[t.ptr + 0x5D] = newLv[i].toByte()
                val blocksX = (t.w + bw - 1) / bw
                val stride = (blocksX * 16) / bh
                raw[t.ptr + 0x56] = (stride and 255).toByte()
                raw[t.ptr + 0x57] = ((stride shr 8) and 255).toByte()
                // The port marks its textures with this flag.
                raw[t.ptr + 0x32] = (raw[t.ptr + 0x32].toInt() or 0x80).toByte()
            }
        }

        val full = ByteArray(sysSize.toInt() + gfx.size)
        System.arraycopy(raw, 0, full, 0, sysSize.toInt())
        System.arraycopy(gfx, 0, full, sysSize.toInt(), gfx.size)

        val newGfx = (gfxFlags and 0xF0000000L) or plan.bits
        val packed = deflate(full)
        val result = ByteArray(skip + packed.size)
        System.arraycopy(data, 0, result, 0, skip)
        val rb2 = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (h in 0 until headers) rb2.putInt(h * 16 + 12, newGfx.toInt())
        System.arraycopy(packed, 0, result, skip, packed.size)

        // Safety check: the new file must unpack and hold no BC textures other than the ones deliberately kept.
        val back = TexScan.parse(result)
        if (back == null || back.count { it.bc } != retained) {
            throw IOException("Converted file failed the read-back check")
        }
        return YtdResult(result, converted, retained)
    }
}
