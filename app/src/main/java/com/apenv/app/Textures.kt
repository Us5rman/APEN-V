package com.apenv.app

// Reads texture entries out of an unpacked resource, either a .ytd or the dictionary inside a model.
object Textures {
    class Info(val name: String, val w: Int, val h: Int, val levels: Int, val cc: String, val dataOff: Int)

    class Image(val w: Int, val h: Int, val rgba: ByteArray)

    fun info(r: Rsc, t: Int): Info? {
        if (t < 0 || t + 0x90 > r.sysSize) return null
        val data = r.phys(r.u64(t + 0x70))
        if (data < 0) return null
        val w = r.u16(t + 0x50)
        val h = r.u16(t + 0x52)
        if (w !in 1..16384 || h !in 1..16384) return null
        val levels = maxOf(r.u8(t + 0x5D), 1)
        if (levels > 15) return null
        val cc = String(r.raw, t + 0x58, 4, Charsets.ISO_8859_1)
        return Info(r.text(r.virt(r.u64(t + 0x28))), w, h, levels, cc, data)
    }

    fun dictionary(r: Rsc, dict: Int): List<Info> {
        if (dict < 0 || dict + 0x40 > r.sysSize) return emptyList()
        val count = r.u16(dict + 0x38)
        val arr = r.virt(r.u64(dict + 0x30))
        if (arr < 0 || count !in 1..8192 || arr + count * 8 > r.sysSize) return emptyList()
        val out = ArrayList<Info>()
        for (i in 0 until count) {
            val tex = info(r, r.virt(r.u64(arr + 8 * i)))
            if (tex != null) out += tex
        }
        return out
    }

    // Decodes the largest mip that fits in maxDim. Returns null for formats that cannot be shown.
    fun decode(r: Rsc, t: Info, maxDim: Int): Image? {
        val kind = when (t.cc) {
            "DXT1" -> Bc.DXT1
            "DXT3" -> Bc.DXT3
            "DXT5" -> Bc.DXT5
            else -> 0
        }
        var bx = 4
        var by = 4
        val per = if (kind == Bc.DXT1) 8 else 16
        if (kind == 0) {
            if (!t.cc.startsWith("AS") || !Astc.available) return null
            bx = YtdConvert.charDim(t.cc[2])
            by = YtdConvert.charDim(t.cc[3])
            if (bx < 4 || by < 4) return null
        }
        var level = 0
        while (level < t.levels - 1 && maxOf(t.w shr level, t.h shr level) > maxDim) level++
        var p = t.dataOff
        for (k in 0 until level) {
            p += ((maxOf(1, t.w shr k) + bx - 1) / bx) * ((maxOf(1, t.h shr k) + by - 1) / by) * per
        }
        val wl = maxOf(1, t.w shr level)
        val hl = maxOf(1, t.h shr level)
        val size = ((wl + bx - 1) / bx) * ((hl + by - 1) / by) * per
        if (size <= 0 || p < 0 || p + size > r.raw.size) return null
        val pixels = if (kind != 0) Bc.decode(kind, r.raw, p, wl, hl)
        else Astc.decode(bx, by, r.raw.copyOfRange(p, p + size), wl, hl)
        return if (pixels == null) null else Image(wl, hl, pixels)
    }
}
