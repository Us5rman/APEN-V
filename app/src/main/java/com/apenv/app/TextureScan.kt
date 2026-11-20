package com.apenv.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

val ASTC_BLOCKS = listOf(
    4 to 4, 5 to 4, 5 to 5, 6 to 5, 6 to 6, 8 to 5, 8 to 6, 8 to 8,
    10 to 5, 10 to 6, 10 to 8, 10 to 10, 12 to 10, 12 to 12
)

fun blockLabel(b: Pair<Int, Int>) = "${b.first}x${b.second}"

class ScanResult(
    val files: Int,
    val archives: Int,
    val textures: Int,
    val bc: Int,
    val astc: Int,
    val other: Int,
    val errors: Int,
    val bcBytes: Long,
    // ASTC size if every BC texture found were converted, per block size (same order as ASTC_BLOCKS).
    val estimates: List<Long>
)

// Read-only scan of loose .ytd files and .ytd files inside .rpf archives. It never writes anything.
object TexScan {
    private const val MAGIC = 0x37435352
    private const val MAX_RAW = 512 * 1024 * 1024

    class Tex(val w: Int, val h: Int, val levels: Int, val bc: Boolean, val bytes: Long)

    fun scan(root: File, key: ByteArray?, progress: (String) -> Unit, cancelled: () -> Boolean): ScanResult {
        var files = 0; var archives = 0; var textures = 0; var bc = 0; var astc = 0
        var other = 0; var errors = 0; var bcBytes = 0L
        val est = LongArray(ASTC_BLOCKS.size)

        fun add(list: List<Tex>) {
            for (t in list) {
                textures++
                if (t.bc) {
                    bc++
                    bcBytes += t.bytes
                    ASTC_BLOCKS.forEachIndexed { i, b -> est[i] += astcBytes(t.w, t.h, t.levels, b.first, b.second) }
                } else astc++
            }
        }

        // Counts the textures in an archive, going into archives stored inside it.
        fun scanRpf(a: RpfArchive, depth: Int) {
            fun go(dir: Dir) {
                for (n in dir.kids) {
                    if (cancelled()) return
                    when (n) {
                        is Dir -> go(n)
                        is Leaf -> {
                            val lower = n.name.lowercase()
                            if (lower.endsWith(".ytd") && n.resource) {
                                progress(n.name)
                                files++
                                val list = runCatching { parse(a.extractToBytes(n)) }.getOrNull()
                                if (list == null) errors++ else add(list)
                            } else if (lower.endsWith(".rpf") && !n.resource && depth < 4) {
                                val tmp = File.createTempFile("scan_", ".rpf")
                                try {
                                    tmp.outputStream().buffered().use { a.extract(n, it) }
                                    archives++
                                    val inner = RpfArchive(tmp, key, false)
                                    inner.load()
                                    scanRpf(inner, depth + 1)
                                } catch (e: Throwable) {
                                    errors++
                                } finally {
                                    tmp.delete()
                                }
                            }
                        }
                    }
                }
            }
            go(a.dirAt(emptyList()))
        }

        // Counts the textures in a .zip: its .ytd files, and the .rpf archives inside it.
        fun scanZip(f: File) {
            java.util.zip.ZipFile(f).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    if (cancelled()) return
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val lower = e.name.lowercase()
                    if (lower.endsWith(".ytd")) {
                        progress("${f.name} / ${e.name}")
                        files++
                        val list = runCatching { parse(zf.getInputStream(e).use { it.readBytes() }) }.getOrNull()
                        if (list == null) errors++ else add(list)
                    } else if (lower.endsWith(".rpf")) {
                        val tmp = File.createTempFile("scan_", ".rpf")
                        try {
                            progress("${f.name} / ${e.name}")
                            zf.getInputStream(e).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                            archives++
                            val a = RpfArchive(tmp, key, false)
                            a.load()
                            scanRpf(a, 1)
                        } catch (ex: Throwable) {
                            errors++
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }
        }

        fun visit(f: File) {
            if (cancelled()) return
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { visit(it) }
                return
            }
            val ext = f.extension.lowercase()
            if (ext == "ytd") {
                progress(f.name)
                files++
                val list = runCatching { parse(f.readBytes()) }.getOrNull()
                if (list == null) errors++ else add(list)
            } else if (ext == "rpf") {
                progress(f.name)
                archives++
                try {
                    val a = RpfArchive(f, key, false)
                    a.load()
                    scanRpf(a, 0)
                } catch (e: Throwable) {
                    errors++
                }
            } else if (ext == "zip") {
                progress(f.name)
                archives++
                try {
                    scanZip(f)
                } catch (e: Throwable) {
                    errors++
                }
            }
        }
        visit(root)
        return ScanResult(files, archives, textures, bc, astc, other, errors, bcBytes, est.toList())
    }

    private fun walk(
        a: RpfArchive, dir: Dir, onFile: () -> Unit, onTex: (List<Tex>) -> Unit,
        onError: () -> Unit, cancelled: () -> Boolean
    ) {
        for (n in dir.kids) {
            if (cancelled()) return
            when (n) {
                is Dir -> walk(a, n, onFile, onTex, onError, cancelled)
                is Leaf -> if (n.name.lowercase().endsWith(".ytd") && n.resource) {
                    onFile()
                    val list = runCatching { parse(a.extractToBytes(n)) }.getOrNull()
                    if (list == null) onError() else onTex(list)
                }
            }
        }
    }

    // Bytes needed by one ASTC texture: whole blocks, 16 bytes each, summed over the mip chain.
    fun astcBytes(w: Int, h: Int, levels: Int, bw: Int, bh: Int): Long {
        var cw = w; var ch = h; var total = 0L
        repeat(levels) {
            total += ((cw + bw - 1) / bw).toLong() * ((ch + bh - 1) / bh) * 16
            cw = maxOf(1, cw / 2); ch = maxOf(1, ch / 2)
        }
        return total
    }

    // Accepts a resource with a 16 byte header (native) or the port's extra 16 byte wrapper.
    fun parse(data: ByteArray): List<Tex>? {
        if (data.size < 20) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != MAGIC) return null
        val off = if (data.size >= 36 && bb.getInt(16) == MAGIC) 32 else 16
        val raw = inflate(data, off) ?: return null
        val out = ArrayList<Tex>()
        var i = 8
        while (i < raw.size - 6) {
            val kind = classify(raw, i)
            if (kind != 0) {
                val w = u16(raw, i - 8); val h = u16(raw, i - 6); val depth = u16(raw, i - 4)
                if (w in 1..16384 && h in 1..16384 && depth == 1) {
                    var lv = raw[i + 5].toInt() and 0xFF
                    if (lv !in 1..16) lv = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(w, h)))
                    val isBc = kind == 1 || kind == 2
                    val per = if (kind == 2) 8 else 16
                    var bytes = 0L
                    if (isBc) {
                        var cw = w; var ch = h
                        repeat(lv) {
                            bytes += ((cw + 3) / 4).toLong() * ((ch + 3) / 4) * per
                            cw = maxOf(1, cw / 2); ch = maxOf(1, ch / 2)
                        }
                    }
                    out += Tex(w, h, lv, isBc, bytes)
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }

    // 1 = BC (16 byte blocks), 2 = BC (8 byte blocks), 3 = ASTC, 0 = not a format tag
    private fun classify(b: ByteArray, i: Int): Int {
        val c0 = b[i].toInt().toChar(); val c1 = b[i + 1].toInt().toChar()
        val c2 = b[i + 2].toInt().toChar(); val c3 = b[i + 3].toInt().toChar()
        val s = "" + c0 + c1 + c2 + c3
        return when {
            s == "DXT1" || s == "ATI1" -> 2
            s == "DXT3" || s == "DXT5" || s == "ATI2" || s == "BC7 " -> 1
            c0 == 'A' && c1 == 'S' -> 3
            else -> 0
        }
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun inflate(data: ByteArray, off: Int): ByteArray? {
        val inf = Inflater(true)
        return try {
            inf.setInput(data, off, data.size - off)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_RAW) return null
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            null
        } finally {
            inf.end()
        }
    }
}
