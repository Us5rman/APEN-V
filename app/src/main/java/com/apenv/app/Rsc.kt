package com.apenv.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

// An unpacked GTA V resource: the virtual (system) pages come first, the physical (graphics) pages after.
class Rsc(val raw: ByteArray, val sysSize: Int) {
    private val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(o: Int): Int = raw[o].toInt() and 255
    fun u16(o: Int): Int = buf.getShort(o).toInt() and 0xFFFF
    fun u32(o: Int): Long = buf.getInt(o).toLong() and 0xFFFFFFFFL
    fun i32(o: Int): Int = buf.getInt(o)
    fun u64(o: Int): Long = buf.getLong(o)
    fun f32(o: Int): Float = buf.getFloat(o)

    // Pointer into the virtual pages as an offset into raw, or -1 when it is not one.
    fun virt(p: Long): Int {
        if ((p ushr 28) != 5L) return -1
        val o = (p and 0xFFFFFFFL).toInt()
        return if (o < sysSize) o else -1
    }

    // Pointer into the physical pages as an offset into raw, or -1 when it is not one.
    fun phys(p: Long): Int {
        if ((p ushr 28) != 6L) return -1
        val o = sysSize + (p and 0xFFFFFFFL).toInt()
        return if (o < raw.size) o else -1
    }

    fun text(o: Int): String {
        if (o < 0) return ""
        var end = o
        while (end < sysSize && end - o < 160 && raw[end] != 0.toByte()) end++
        return String(raw, o, end - o, Charsets.ISO_8859_1)
    }

    companion object {
        private const val MAGIC = 0x37435352

        fun open(data: ByteArray): Rsc? {
            if (data.size < 20) return null
            val h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (h.getInt(0) != MAGIC) return null
            val headers = if (data.size >= 36 && h.getInt(16) == MAGIC) 2 else 1
            val sys = YtdConvert.pagesSize(h.getInt(8).toLong() and 0xFFFFFFFFL)
            val raw = TexScan.inflate(data, headers * 16) ?: return null
            if (sys <= 0 || raw.size < sys) return null
            return Rsc(raw, sys.toInt())
        }
    }
}
