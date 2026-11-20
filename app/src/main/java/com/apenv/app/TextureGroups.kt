package com.apenv.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

// Where the foreground sits on the background.
enum class Placement { LEFT, CENTER, RIGHT }

// A background texture and the foreground texture that belongs on top of it. The position is
// worked out automatically from the pictures themselves.
class Together(val bg: Bitmap, val fg: Bitmap, val bgName: String, val fgName: String) {
    val placement: Placement = TextureGroups.autoPlace(bg, fg)
}

// Finds textures inside one .ytd that belong together and draws them as one picture.
object TextureGroups {
    class Link(val bg: Int, val fg: Int)

    private class Role(val isBg: Boolean, val key: String)

    // "background", "foreground", "beach_bg", "beach_fg", "bg1", "fg1" ...
    // key is the rest of the name, so beach_bg only pairs with beach_fg.
    private fun roleOf(name: String): Role? {
        val toks = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for ((i, t) in toks.withIndex()) {
            val base = t.trimEnd { it.isDigit() }
            val isBg = base == "background" || base == "backdrop" || base == "bg"
            val isFg = base == "foreground" || base == "fg"
            if (isBg || isFg) {
                val rest = toks.filterIndexed { j, _ -> j != i } + t.substring(base.length)
                return Role(isBg, rest.filter { it.isNotEmpty() }.joinToString("_"))
            }
        }
        return null
    }

    private fun hasTransparency(e: YtdConvert.TexEntry): Boolean {
        val px = e.rgba ?: return false
        val stride = maxOf(1, (px.size / 4) / 65536) * 4
        var i = 3
        while (i < px.size) {
            if ((px[i].toInt() and 255) < 250) return true
            i += stride
        }
        return false
    }

    private fun usable(e: YtdConvert.TexEntry) = e.rgba != null && e.pw > 0 && e.ph > 0

    // Pairs by name first. If the names say nothing and the file holds exactly two pictures,
    // one fully opaque and one with transparent areas, the opaque one is the background.
    fun detect(entries: List<YtdConvert.TexEntry>): List<Link> {
        val roles = entries.map { roleOf(it.name) }
        val bgs = entries.indices.filter { usable(entries[it]) && roles[it]?.isBg == true }
        val fgs = entries.indices.filter { usable(entries[it]) && roles[it]?.isBg == false }
        val links = ArrayList<Link>()
        val taken = HashSet<Int>()
        for (b in bgs) {
            val key = roles[b]!!.key
            val f = fgs.firstOrNull { it !in taken && roles[it]!!.key == key } ?: continue
            links += Link(b, f)
            taken += f
        }
        if (links.isEmpty() && bgs.size == 1 && fgs.size == 1) links += Link(bgs[0], fgs[0])
        if (links.isEmpty() && entries.size == 2 && usable(entries[0]) && usable(entries[1])) {
            val t0 = hasTransparency(entries[0])
            val t1 = hasTransparency(entries[1])
            if (t0 != t1) {
                val bg = if (t0) 1 else 0
                val fg = 1 - bg
                val abg = entries[bg].pw.toLong() * entries[bg].ph
                val afg = entries[fg].pw.toLong() * entries[fg].ph
                if (abg >= afg) links += Link(bg, fg)
            }
        }
        return links
    }

    // A .ytd names the textures but doesn't record where the game draws one over the other.
    // So the foreground is put where it covers the least of the background's detail: both
    // pictures are shrunk to a small grid, the background's edge strength is measured, and the
    // side (left, centre or right) with the least detail under the foreground's opaque pixels wins.
    fun autoPlace(bg: Bitmap, fg: Bitmap): Placement {
        val gw = 96
        val gh = 48
        val fw = (gh * fg.width.toFloat() / fg.height).toInt().coerceIn(1, gw)
        if (fw >= gw) return Placement.LEFT
        val b = Bitmap.createScaledBitmap(bg, gw, gh, true)
        val bp = IntArray(gw * gh)
        b.getPixels(bp, 0, gw, 0, 0, gw, gh)
        val lum = FloatArray(gw * gh) {
            val v = bp[it]
            0.299f * ((v shr 16) and 255) + 0.587f * ((v shr 8) and 255) + 0.114f * (v and 255)
        }
        val en = FloatArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val dx = lum[y * gw + minOf(x + 1, gw - 1)] - lum[y * gw + maxOf(x - 1, 0)]
                val dy = lum[minOf(y + 1, gh - 1) * gw + x] - lum[maxOf(y - 1, 0) * gw + x]
                en[y * gw + x] = Math.abs(dx) + Math.abs(dy)
            }
        }
        val f = Bitmap.createScaledBitmap(fg, fw, gh, true)
        val fp = IntArray(fw * gh)
        f.getPixels(fp, 0, fw, 0, 0, fw, gh)
        fun cost(x0: Int): Float {
            var sum = 0f
            for (y in 0 until gh) {
                for (x in 0 until fw) {
                    val a = ((fp[y * fw + x] ushr 24) and 255) / 255f
                    if (a > 0f) sum += a * en[y * gw + x0 + x]
                }
            }
            return sum
        }
        val left = cost(0)
        val right = cost(gw - fw)
        val center = cost((gw - fw) / 2) * 1.1f
        val side = if (right < left) Placement.RIGHT else Placement.LEFT
        return if (center < minOf(left, right)) Placement.CENTER else side
    }

    // Draws the foreground over the background at its automatic position. The picture is as big
    // as the background; the foreground is scaled to the background's height.
    fun render(t: Together): Bitmap {
        val w = t.bg.width
        val h = t.bg.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(t.bg, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        val fw = t.fg.width * h.toFloat() / t.fg.height
        val dest = when (t.placement) {
            Placement.LEFT -> RectF(0f, 0f, fw, h.toFloat())
            Placement.CENTER -> RectF((w - fw) / 2f, 0f, (w + fw) / 2f, h.toFloat())
            Placement.RIGHT -> RectF(w - fw, 0f, w.toFloat(), h.toFloat())
        }
        c.drawBitmap(t.fg, null, dest, p)
        return out
    }

    // Shrinks RGBA pixels so the longest side is at most maxDim, averaging each box of pixels
    // (weighted by alpha) rather than skipping pixels. With checker = true, transparent areas
    // are drawn over a checkerboard so you can see they are transparent; with false the real
    // alpha is kept so the picture can be layered.
    fun toBitmap(rgba: ByteArray, w: Int, h: Int, maxDim: Int, checker: Boolean): Bitmap {
        val step = maxOf(1, (maxOf(w, h) + maxDim - 1) / maxDim)
        val ow = maxOf(1, w / step)
        val oh = maxOf(1, h / step)
        val px = IntArray(ow * oh)
        val n = step * step
        var anyAlpha = false
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var r = 0; var g = 0; var b = 0; var a = 0
                for (dy in 0 until step) {
                    val row = ((y * step + dy) * w + x * step) * 4
                    for (dx in 0 until step) {
                        val o = row + dx * 4
                        val al = rgba[o + 3].toInt() and 255
                        a += al
                        r += (rgba[o].toInt() and 255) * al
                        g += (rgba[o + 1].toInt() and 255) * al
                        b += (rgba[o + 2].toInt() and 255) * al
                    }
                }
                val aa = a / n
                if (aa < 250) anyAlpha = true
                val rr = if (a > 0) r / a else 0
                val gg = if (a > 0) g / a else 0
                val bb = if (a > 0) b / a else 0
                px[y * ow + x] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        if (checker && anyAlpha) {
            for (y in 0 until oh) {
                for (x in 0 until ow) {
                    val v = px[y * ow + x]
                    val aa = (v ushr 24) and 255
                    if (aa == 255) continue
                    val dark = ((x / 8) + (y / 8)) % 2 == 0
                    val base = if (dark) 0x2A else 0x36
                    val rr = (((v shr 16) and 255) * aa + base * (255 - aa)) / 255
                    val gg = (((v shr 8) and 255) * aa + (base + 5) * (255 - aa)) / 255
                    val bb = ((v and 255) * aa + (base + 4) * (255 - aa)) / 255
                    px[y * ow + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, ow, 0, 0, ow, oh)
        return bmp
    }
}
