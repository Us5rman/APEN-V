package com.apenv.app

// Decoders for DXT1 / DXT3 / DXT5 (BC1 / BC2 / BC3) into 8-bit RGBA.
object Bc {
    const val DXT1 = 1
    const val DXT3 = 2
    const val DXT5 = 3

    fun decode(kind: Int, src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        val bx = (w + 3) / 4
        val by = (h + 3) / 4
        val bpb = if (kind == DXT1) 8 else 16
        val blk = ByteArray(64)
        var p = off
        for (y in 0 until by) {
            for (x in 0 until bx) {
                block(kind, src, p, blk)
                p += bpb
                for (j in 0 until 4) {
                    val yy = y * 4 + j
                    if (yy >= h) break
                    for (i in 0 until 4) {
                        val xx = x * 4 + i
                        if (xx >= w) break
                        System.arraycopy(blk, (j * 4 + i) * 4, out, (yy * w + xx) * 4, 4)
                    }
                }
            }
        }
        return out
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    private fun block(kind: Int, s: ByteArray, o: Int, out: ByteArray) {
        val co = if (kind == DXT1) o else o + 8
        val c0 = u16(s, co)
        val c1 = u16(s, co + 2)
        val pal = IntArray(16)
        fun expand(c: Int, k: Int) {
            val r = (c shr 11) and 31
            val g = (c shr 5) and 63
            val b = c and 31
            pal[k * 4] = (r shl 3) or (r shr 2)
            pal[k * 4 + 1] = (g shl 2) or (g shr 4)
            pal[k * 4 + 2] = (b shl 3) or (b shr 2)
            pal[k * 4 + 3] = 255
        }
        expand(c0, 0)
        expand(c1, 1)
        if (kind != DXT1 || c0 > c1) {
            for (ch in 0..2) {
                pal[8 + ch] = (2 * pal[ch] + pal[4 + ch]) / 3
                pal[12 + ch] = (pal[ch] + 2 * pal[4 + ch]) / 3
            }
            pal[11] = 255
            pal[15] = 255
        } else {
            for (ch in 0..2) {
                pal[8 + ch] = (pal[ch] + pal[4 + ch]) / 2
                pal[12 + ch] = 0
            }
            pal[11] = 255
            pal[15] = 0
        }
        val bits = u16(s, co + 4) or (u16(s, co + 6) shl 16)
        for (i in 0 until 16) {
            val idx = (bits ushr (2 * i)) and 3
            out[i * 4] = pal[idx * 4].toByte()
            out[i * 4 + 1] = pal[idx * 4 + 1].toByte()
            out[i * 4 + 2] = pal[idx * 4 + 2].toByte()
            out[i * 4 + 3] = pal[idx * 4 + 3].toByte()
        }
        when (kind) {
            DXT3 -> for (i in 0 until 16) {
                val n = ((s[o + i / 2].toInt() and 255) shr (4 * (i and 1))) and 15
                out[i * 4 + 3] = (n * 17).toByte()
            }
            DXT5 -> {
                val a0 = s[o].toInt() and 255
                val a1 = s[o + 1].toInt() and 255
                val al = IntArray(8)
                al[0] = a0
                al[1] = a1
                if (a0 > a1) {
                    for (k in 1..6) al[k + 1] = ((7 - k) * a0 + k * a1) / 7
                } else {
                    for (k in 1..4) al[k + 1] = ((5 - k) * a0 + k * a1) / 5
                    al[6] = 0
                    al[7] = 255
                }
                var ab = 0L
                for (k in 0 until 6) ab = ab or ((s[o + 2 + k].toLong() and 255L) shl (8 * k))
                for (i in 0 until 16) {
                    out[i * 4 + 3] = al[((ab ushr (3 * i)) and 7L).toInt()].toByte()
                }
            }
        }
    }
}
