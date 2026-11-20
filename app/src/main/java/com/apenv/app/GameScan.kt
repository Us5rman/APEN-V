package com.apenv.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.ln

// ---------------------------------------------------------------------------------------------
// What the scan can tell you about every byte of the game. Every file, and every file inside
// archives, gets exactly one answer in each of four groups, so each group adds up to 100%.
// ---------------------------------------------------------------------------------------------

class ScanTag(val id: String, val group: String, val label: String)

object ScanDefs {
    val groups = listOf(
        "content" to "What the data is",
        "read" to "Can it be read",
        "platform" to "Made for which platform",
        "where" to "Where it is stored"
    )

    val tags = listOf(
        ScanTag("astc", "content", "ASTC textures"),
        ScanTag("bcn", "content", "BCn textures (DXT, BC)"),
        ScanTag("mixtex", "content", "Mixed ASTC and BCn textures"),
        ScanTag("othertex", "content", "Textures without a readable format"),
        ScanTag("models", "content", "Models"),
        ScanTag("collision", "content", "Collision and navigation"),
        ScanTag("maps", "content", "Map and placement data"),
        ScanTag("anims", "content", "Animations"),
        ScanTag("scripts", "content", "Scripts"),
        ScanTag("audio", "content", "Audio"),
        ScanTag("images", "content", "Images"),
        ScanTag("text", "content", "Text and config"),
        ScanTag("code", "content", "Code and apps"),
        ScanTag("db", "content", "Databases"),
        ScanTag("archstruct", "content", "Archive structure and padding"),
        ScanTag("encarch", "content", "Archives that could not be opened"),
        ScanTag("otherdata", "content", "Other data"),
        ScanTag("readable", "read", "Readable"),
        ScanTag("unreadable", "read", "Unreadable"),
        ScanTag("android", "platform", "Native Android"),
        ScanTag("pc", "platform", "Native PC (Windows)"),
        ScanTag("cross", "platform", "Works on both"),
        ScanTag("unkplat", "platform", "Unknown"),
        ScanTag("loose", "where", "Loose files"),
        ScanTag("inrpf", "where", "Inside .rpf archives"),
        ScanTag("inzip", "where", "Inside .zip, .apk and .obb")
    )

    val index: Map<String, Int> = tags.withIndex().associate { it.value.id to it.index }

    fun tag(id: String): ScanTag = tags.first { it.id == id }

    // Column of the index file that holds the answer for a group.
    fun column(group: String): Int = when (group) {
        "content" -> 2
        "read" -> 3
        "platform" -> 4
        else -> 5
    }
}

class ScanProgress(val done: Long, val total: Long, val files: Long, val current: String, val elapsedMs: Long)

class ScanRow(val path: String, val stored: Long, val detail: String)

class ScanSummary(
    val root: String,
    val total: Long,
    val files: Long,
    val bytes: LongArray,
    val counts: LongArray,
    val hist: LongArray,
    val elapsedMs: Long,
    val errors: Int,
    val cancelled: Boolean,
    val time: Long,
    val keyUsed: Boolean
) {
    fun pct(id: String): Double {
        val i = ScanDefs.index[id] ?: return 0.0
        return if (total <= 0L) 0.0 else bytes[i] * 100.0 / total
    }

    fun write(f: File) {
        f.bufferedWriter().use { w ->
            w.write("root\t$root\n")
            w.write("total\t$total\n")
            w.write("files\t$files\n")
            w.write("elapsed\t$elapsedMs\n")
            w.write("errors\t$errors\n")
            w.write("cancelled\t${if (cancelled) 1 else 0}\n")
            w.write("time\t$time\n")
            w.write("key\t${if (keyUsed) 1 else 0}\n")
            ScanDefs.tags.forEachIndexed { i, t -> w.write("tag\t${t.id}\t${bytes[i]}\t${counts[i]}\n") }
            w.write("hist\t${hist.joinToString(",")}\n")
        }
    }

    companion object {
        fun read(f: File): ScanSummary? = try {
            var root = ""; var total = 0L; var files = 0L; var elapsed = 0L; var errors = 0
            var cancelled = false; var time = 0L; var key = false
            val bytes = LongArray(ScanDefs.tags.size)
            val counts = LongArray(ScanDefs.tags.size)
            var hist = LongArray(256)
            f.forEachLine { line ->
                val p = line.split('\t')
                when (p[0]) {
                    "root" -> root = p.getOrElse(1) { "" }
                    "total" -> total = p[1].toLong()
                    "files" -> files = p[1].toLong()
                    "elapsed" -> elapsed = p[1].toLong()
                    "errors" -> errors = p[1].toInt()
                    "cancelled" -> cancelled = p[1] == "1"
                    "time" -> time = p[1].toLong()
                    "key" -> key = p[1] == "1"
                    "tag" -> ScanDefs.index[p[1]]?.let { i -> bytes[i] = p[2].toLong(); counts[i] = p[3].toLong() }
                    "hist" -> hist = p[1].split(',').map { it.toLong() }.toLongArray()
                }
            }
            if (hist.size != 256) hist = LongArray(256)
            ScanSummary(root, total, files, bytes, counts, hist, elapsed, errors, cancelled, time, key)
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The scanner. Every file is read from the first byte to the last. Archives are opened and every
// entry inside them is read too, down to archives inside archives. Nothing is trusted from file
// names alone: each entry is classified from its own contents.
// ---------------------------------------------------------------------------------------------

private class Cls(val content: String, val read: String, val plat: String, val detail: String)

private class Sink : OutputStream() {
    val crc = CRC32()
    private val h0 = LongArray(256)
    private val h1 = LongArray(256)
    private val h2 = LongArray(256)
    private val h3 = LongArray(256)
    private val hn = LongArray(256)
    private var merged: LongArray? = null
    var n = 0L
    val head = ByteArray(65536)
    var headLen = 0

    // Count of every byte value (all the tables added together).
    val h: LongArray
        get() {
            merged?.let { return it }
            val m = LongArray(256)
            for (i in 0 until 256) m[i] = h0[i] + h1[i] + h2[i] + h3[i] + hn[i]
            merged = m
            return m
        }

    override fun write(b: Int) {
        val one = byteArrayOf(b.toByte())
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        crc.update(b, off, len)
        merged = null
        if (FastHist.ok && len >= 4096) {
            FastHist.bytes(b, off, len, hn)
        } else {
            val a0 = h0; val a1 = h1; val a2 = h2; val a3 = h3
            var i = off
            val end = off + len
            val end4 = end - 3
            while (i < end4) {
                a0[b[i].toInt() and 255]++
                a1[b[i + 1].toInt() and 255]++
                a2[b[i + 2].toInt() and 255]++
                a3[b[i + 3].toInt() and 255]++
                i += 4
            }
            while (i < end) {
                a0[b[i].toInt() and 255]++
                i++
            }
        }
        if (headLen < head.size) {
            val c = minOf(head.size - headLen, len)
            System.arraycopy(b, off, head, headLen, c)
            headLen += c
        }
        n += len
    }

    // Bytes that sit in direct memory (read straight from storage): counted without copying them.
    fun writeDirect(b: java.nio.ByteBuffer, len: Int) {
        if (!FastHist.ok) {
            val tmp = ByteArray(len)
            val d = b.duplicate()
            d.position(0)
            d.limit(len)
            d.get(tmp)
            write(tmp, 0, len)
            return
        }
        merged = null
        FastHist.direct(b, len, hn)
        if (headLen < head.size) {
            val c = minOf(head.size - headLen, len)
            val d = b.duplicate()
            d.position(0)
            d.limit(len)
            d.get(head, headLen, c)
            headLen += c
        }
        b.position(0)
        b.limit(len)
        crc.update(b)
        n += len
    }

    fun entropy(): Double {
        if (n <= 0L) return 0.0
        var e = 0.0
        for (c in h) {
            if (c > 0L) {
                val p = c.toDouble() / n
                e -= p * ln(p) / ln(2.0)
            }
        }
        return e
    }

    fun zeroPct(): Double = if (n <= 0L) 0.0 else h[0] * 100.0 / n
}

private class ScanWorker(p: ForkJoinPool) : java.util.concurrent.ForkJoinWorkerThread(p)

// Reader threads that pre-read files ahead of the scan. Measured slower on a real phone (they read the
// same data twice and fight the scan for memory), so they are off.
private const val READ_AHEAD = false

private const val INSPECT_LIMIT = 64L * 1024 * 1024
private const val MAX_DEPTH = 4

private class Engine(
    val root: File,
    val key: ByteArray?,
    val cache: File,
    val indexFile: File,
    val tune: Tuning,
    val budget: Long,
    val progress: (ScanProgress) -> Unit,
    val cancelled: () -> Boolean
) {
    val bytes = LongArray(ScanDefs.tags.size)
    val counts = LongArray(ScanDefs.tags.size)
    val hist = LongArray(256)
    @Volatile var files = 0L
    var errors = 0
    val done = AtomicLong(0L)
    var total = 0L
    val startMs = System.currentTimeMillis()
    val lastTick = AtomicLong(0L)
    lateinit var w: java.io.BufferedWriter

    // Every worker thread shares this engine, so the shared totals are only touched under this lock.
    private val lock = Any()

    // Work is spread over many threads: the files of the game, and the entries inside each archive.
    private val threads = tune.threads.coerceIn(2, 16)
    private lateinit var pool: ForkJoinPool

    // How many bytes of file contents may be held in memory at once (the RAM budget, but never more than
    // half of what Android lets this app use), so many threads can read big files without running out.
    private val gateUnits = (minOf(budget, Runtime.getRuntime().maxMemory() / 2) shr 20).toInt().coerceAtLeast(96)
    private val gate = Semaphore(gateUnits, true)

    // The file currently being scanned on this thread, so an error can be told apart per file.
    class FileCtx { val recs = AtomicInteger(0) }
    private val curCtx = ThreadLocal<FileCtx?>()

    private val bufs: ThreadLocal<ByteArray> = ThreadLocal.withInitial { ByteArray(4 shl 20) }

    fun tick(current: String) {
        val now = System.currentTimeMillis()
        val last = lastTick.get()
        if (now - last >= 150 && lastTick.compareAndSet(last, now)) {
            progress(ScanProgress(minOf(done.get(), total), total, files, current, now - startMs))
        }
    }

    private fun <T> gated(bytes: Long, block: () -> T): T {
        val units = ((bytes * 6) / (1L shl 20) + 1).coerceIn(1L, gateUnits.toLong()).toInt()
        gate.acquireUninterruptibly(units)
        try {
            return block()
        } finally {
            gate.release(units)
        }
    }

    // Runs action for every item on all threads. Each thread takes the next item when it is free,
    // so one huge file does not hold up the rest. Works from inside a worker too (archives in archives).
    private fun <T> parallelFor(list: List<T>, action: (T) -> Unit) {
        if (list.isEmpty()) return
        val next = AtomicInteger(0)
        val body = {
            while (!cancelled()) {
                val i = next.getAndIncrement()
                if (i >= list.size) break
                action(list[i])
            }
        }
        val helpers = ArrayList<java.util.concurrent.ForkJoinTask<*>>()
        for (k in 1 until minOf(threads, list.size)) helpers += pool.submit(Runnable { body() })
        body()
        helpers.forEach { it.join() }
    }

    fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun record(path: String, stored: Long, c: Cls, where: String, s: Sink?) {
        curCtx.get()?.recs?.incrementAndGet()
        var crc = 0L
        var ent = 0.0
        var zero = 0.0
        var cb = 0L
        var sh: LongArray? = null
        if (s != null) {
            crc = s.crc.value
            ent = s.entropy()
            zero = s.zeroPct()
            cb = s.n
            sh = s.h
        }
        val sb = StringBuilder(path.length + 120)
        sb.append(clean(path)).append('\t').append(stored).append('\t')
            .append(c.content).append('\t').append(c.read).append('\t').append(c.plat).append('\t')
            .append(where).append('\t').append(clean(c.detail)).append('\t')
            .append(String.format(Locale.US, "%08x", crc)).append('\t')
            .append(String.format(Locale.US, "%.3f", ent)).append('\t')
            .append(String.format(Locale.US, "%.2f", zero)).append('\t')
            .append(cb).append('\n')
        val line = sb.toString()
        synchronized(lock) {
            for (id in arrayOf(c.content, c.read, c.plat, where)) {
                val i = ScanDefs.index.getValue(id)
                bytes[i] += stored
                counts[i]++
            }
            files++
            if (c.read == "unreadable") errors++
            if (sh != null) for (i in 0 until 256) hist[i] += sh[i]
            w.write(line)
        }
    }

    // ---- classification from the contents ----

    private fun looksText(head: ByteArray, hl: Int): Boolean {
        if (hl == 0) return false
        val b0 = head[0].toInt() and 255
        val b1 = if (hl > 1) head[1].toInt() and 255 else -1
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return true
        if (b0 == 0xEF && b1 == 0xBB) return true
        val n = minOf(hl, 4096)
        var bad = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 255
            if (b == 0) return false
            if (b < 9 || (b in 14..31 && b != 27)) bad++
        }
        return bad * 50 < n
    }

    fun ytdClass(data: ByteArray): Cls {
        val list = TexScan.parse(data)
            ?: return Cls("othertex", "unreadable", "unkplat", "not a valid texture dictionary")
        if (list.isEmpty()) return Cls("othertex", "unreadable", "unkplat", "no recognised texture format")
        val bc = list.count { it.bc }
        val astc = list.size - bc
        return when {
            astc > 0 && bc == 0 -> Cls("astc", "readable", "android", "$astc ASTC textures")
            bc > 0 && astc == 0 -> Cls("bcn", "readable", "pc", "$bc BCn textures")
            else -> Cls("mixtex", "readable", "cross", "$astc ASTC and $bc BCn textures")
        }
    }

    fun classify(name: String, head: ByteArray, hl: Int): Cls {
        val ext = name.substringAfterLast('.', "").lowercase()
        fun b(i: Int) = if (i < hl) head[i].toInt() and 255 else -1
        val m0 = b(0); val m1 = b(1); val m2 = b(2); val m3 = b(3)
        if (hl == 0) return Cls("otherdata", "readable", "cross", "empty file")

        // GTA resource files start with RSC7.
        if (m0 == 0x52 && m1 == 0x53 && m2 == 0x43 && m3 == 0x37) {
            val cat = when (ext) {
                "ydr", "yft", "ydd", "yld", "ypt" -> "models"
                "ybn", "ynv", "ynd" -> "collision"
                "ymap", "ytyp", "ymt" -> "maps"
                "ycd", "yed", "yvr", "ywr" -> "anims"
                "ysc" -> "scripts"
                "ytd" -> "othertex"
                else -> "otherdata"
            }
            return Cls(cat, "readable", "cross", "game resource .$ext")
        }
        if (m0 == 0x7F && m1 == 0x45 && m2 == 0x4C && m3 == 0x46) return Cls("code", "readable", "android", "ELF native library")
        if (m0 == 0x64 && m1 == 0x65 && m2 == 0x78 && m3 == 0x0A) return Cls("code", "readable", "android", "Dalvik executable")
        if (m0 == 3 && m1 == 0 && m2 == 8 && m3 == 0) return Cls("code", "readable", "android", "Android binary XML")
        if (m0 == 0x4D && m1 == 0x5A) return Cls("code", "readable", "pc", "Windows executable")
        if (m0 == 0x89 && m1 == 0x50 && m2 == 0x4E && m3 == 0x47) return Cls("images", "readable", "cross", "PNG image")
        if (m0 == 0xFF && m1 == 0xD8 && m2 == 0xFF) return Cls("images", "readable", "cross", "JPEG image")
        if (m0 == 0x52 && m1 == 0x49 && m2 == 0x46 && m3 == 0x46) {
            if (b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50) return Cls("images", "readable", "cross", "WebP image")
            if (b(8) == 0x57 && b(9) == 0x41 && b(10) == 0x56 && b(11) == 0x45) return Cls("audio", "readable", "cross", "WAV audio")
        }
        if (m0 == 0x4F && m1 == 0x67 && m2 == 0x67 && m3 == 0x53) return Cls("audio", "readable", "cross", "Ogg audio")
        if ((m0 == 0x49 && m1 == 0x44 && m2 == 0x33) || (m0 == 0xFF && (m1 and 0xE0) == 0xE0)) {
            if (ext == "mp3" || m0 == 0x49) return Cls("audio", "readable", "cross", "MP3 audio")
        }
        if ((m0 == 0x41 && m1 == 0x44 && m2 == 0x41 && m3 == 0x54) || (m0 == 0x54 && m1 == 0x41 && m2 == 0x44 && m3 == 0x41)) {
            return Cls("audio", "readable", "cross", "game audio container")
        }
        if (m0 == 0x53 && m1 == 0x51 && m2 == 0x4C && m3 == 0x69) return Cls("db", "readable", "cross", "SQLite database")
        if ((m0 == 0x50 && m1 == 0x53 && m2 == 0x49 && m3 == 0x4E) || (m0 == 0x52 && m1 == 0x42 && m2 == 0x46 && m3 == 0x30)) {
            return Cls("text", "readable", "cross", "binary config")
        }
        if (m0 == 0x50 && m1 == 0x4B && m2 == 3 && m3 == 4) {
            val android = ext == "apk" || ext == "obb"
            return Cls(if (android) "code" else "otherdata", "readable", if (android) "android" else "cross", "zip archive (not opened)")
        }
        if (m0 == 0x52 && m1 == 0x50 && m2 == 0x46 && m3 == 0x37) return Cls("otherdata", "readable", "cross", "archive (nested too deep to open)")
        if (looksText(head, hl)) {
            return if (ext == "lua" || ext == "js") Cls("scripts", "readable", "cross", "script text")
            else Cls("text", "readable", "cross", "text")
        }
        val imgExt = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        val audExt = setOf("ogg", "mp3", "wav", "awc")
        if (ext in imgExt) return Cls("images", "unreadable", "unkplat", "name says image, contents do not match")
        if (ext in audExt) return Cls("audio", "unreadable", "unkplat", "name says audio, contents do not match")
        if (ext == "so" || ext == "dex") return Cls("code", "unreadable", "unkplat", "name says code, contents do not match")
        return Cls("otherdata", "unreadable", "unkplat", "unknown format")
    }

    // ---- reading ----

    private fun streamAll(ins: java.io.InputStream, sink: Sink, rel: String): Boolean {
        val buf = bufs.get()
        while (true) {
            val r = ins.read(buf)
            if (r < 0) break
            sink.write(buf, 0, r)
            done.addAndGet(r.toLong())
            tick(rel)
            if (cancelled()) return false
        }
        return true
    }

    private val dbufs: ThreadLocal<java.nio.ByteBuffer> =
        ThreadLocal.withInitial { java.nio.ByteBuffer.allocateDirect(8 shl 20) }

    // Reads a whole file into direct memory in big pieces and counts it there, with no copies on the way.
    private fun streamDirect(f: File, sink: Sink, rel: String): Boolean {
        val buf = dbufs.get()!!
        java.nio.channels.FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
            var pos = 0L
            while (true) {
                buf.clear()
                val r = ch.read(buf, pos)
                if (r < 0) break
                if (r == 0) continue
                sink.writeDirect(buf, r)
                pos += r
                done.addAndGet(r.toLong())
                tick(rel)
                if (cancelled()) return false
            }
        }
        return true
    }

    private fun scanLeafFile(f: File, rel: String) {
        val sink = Sink()
        val cls: Cls
        if (f.extension.equals("ytd", true) && f.length() <= INSPECT_LIMIT) {
            cls = gated(f.length()) {
                val data = f.readBytes()
                sink.write(data, 0, data.size)
                done.addAndGet(data.size.toLong())
                if (data.size >= 4) ytdClass(data) else Cls("othertex", "unreadable", "unkplat", "too small")
            }
        } else {
            val ok = streamDirect(f, sink, rel)
            if (!ok) return
            cls = classify(f.name, sink.head, sink.headLen)
        }
        record(rel, f.length(), cls, "loose", sink)
    }

    private fun scanUnopenable(f: File, rel: String, why: String) {
        val sink = Sink()
        val ok = streamDirect(f, sink, rel)
        if (!ok) return
        record(rel, f.length(), Cls("encarch", "unreadable", "unkplat", why), "loose", sink)
    }

    private fun scanRpfLeaf(a: RpfArchive, n: Leaf, full: String) {
        val sink = Sink()
        val cls: Cls = try {
            val ext = n.name.substringAfterLast('.', "").lowercase()
            if (n.resource && ext == "ytd" && n.diskLen <= INSPECT_LIMIT) {
                gated(n.diskLen) {
                    val data = a.extractToBytes(n)
                    sink.write(data, 0, data.size)
                    ytdClass(data)
                }
            } else if (n.resource && ext == "ytd") {
                Cls("othertex", "readable", "cross", "texture dictionary over 64 MB, not opened")
            } else {
                if (!a.scanStored(n, sink) { buf, len -> sink.writeDirect(buf, len) }) a.extract(n, sink)
                classify(n.name, sink.head, sink.headLen)
            }
        } catch (e: Throwable) {
            Cls("otherdata", "unreadable", "unkplat", "read error: ${e.message ?: e.javaClass.simpleName}")
        }
        record(full, n.diskLen, cls, "inrpf", sink)
        done.addAndGet(n.diskLen)
    }

    private fun scanNestedRpf(a: RpfArchive, n: Leaf, full: String, depth: Int) {
        val tmp = File.createTempFile("gs_", ".rpf", cache)
        try {
            val inner = try {
                tmp.outputStream().buffered().use { a.extract(n, it) }
                RpfArchive(tmp, key, false)
            } catch (e: Throwable) {
                record(full, n.diskLen, Cls("encarch", "unreadable", "unkplat", e.message ?: "cannot open"), "inrpf", null)
                done.addAndGet(n.diskLen)
                return
            }
            scanRpf(inner, full, depth + 1, n.diskLen, "inrpf")
        } finally {
            tmp.delete()
        }
    }

    // Reads every entry of an archive. What the entries do not cover (the table of contents,
    // names and padding) is recorded as "archive structure", so sizes add up exactly.
    private fun scanRpf(a: RpfArchive, label: String, depth: Int, size: Long, where: String) {
        var childSum = 0L
        val items = ArrayList<Pair<Leaf, String>>()
        fun gather(dir: Dir, path: String) {
            for (n in dir.kids) {
                when (n) {
                    is Dir -> gather(n, path + n.name + "/")
                    is Leaf -> {
                        childSum += n.diskLen
                        items += n to "$label/$path${n.name}"
                    }
                }
            }
        }
        gather(a.dirAt(emptyList()), "")
        val ctx = curCtx.get()
        parallelFor(items) { (n, full) ->
            val prev = curCtx.get()
            curCtx.set(ctx)
            try {
                val lower = n.name.lowercase()
                if (!n.resource && lower.endsWith(".rpf") && depth < MAX_DEPTH) scanNestedRpf(a, n, full, depth)
                else scanRpfLeaf(a, n, full)
                tick(full)
            } finally {
                curCtx.set(prev)
            }
        }
        if (cancelled()) return
        val overhead = (size - childSum).coerceAtLeast(0L)
        record("$label/[archive structure]", overhead, Cls("archstruct", "readable", "cross", "table of contents, names and padding"), where, null)
        done.addAndGet(overhead)
    }

    private fun scanTopRpf(f: File, rel: String) {
        val a = try {
            RpfArchive(f, key, false)
        } catch (e: Throwable) {
            scanUnopenable(f, rel, e.message ?: "cannot open archive")
            return
        }
        scanRpf(a, rel, 0, f.length(), "loose")
    }

    private fun scanTopZip(f: File, rel: String) {
        val zf = try {
            ZipFile(f)
        } catch (e: Throwable) {
            scanUnopenable(f, rel, "cannot open zip: ${e.message ?: "corrupt"}")
            return
        }
        zf.use {
            val android = zf.getEntry("AndroidManifest.xml") != null || f.extension.lowercase() in setOf("apk", "obb")
            var childSum = 0L
            val en = zf.entries()
            while (en.hasMoreElements()) {
                if (cancelled()) return
                val e = en.nextElement()
                if (e.isDirectory) continue
                val stored = if (e.compressedSize >= 0) e.compressedSize else maxOf(0L, e.size)
                childSum += stored
                val full = "$rel/${e.name}"
                val lower = e.name.lowercase()
                if (lower.endsWith(".rpf")) {
                    val tmp = File.createTempFile("gz_", ".rpf", cache)
                    try {
                        val inner = try {
                            zf.getInputStream(e).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                            RpfArchive(tmp, key, false)
                        } catch (ex: Throwable) {
                            record(full, stored, Cls("encarch", "unreadable", "unkplat", ex.message ?: "cannot open"), "inzip", null)
                            done.addAndGet(stored)
                            continue
                        }
                        scanRpf(inner, full, 1, stored, "inzip")
                    } finally {
                        tmp.delete()
                    }
                } else {
                    val sink = Sink()
                    val cls: Cls = try {
                        if (lower.endsWith(".ytd") && e.size in 0..INSPECT_LIMIT) {
                            gated(e.size) {
                                val data = zf.getInputStream(e).use { it.readBytes() }
                                sink.write(data, 0, data.size)
                                ytdClass(data)
                            }
                        } else {
                            zf.getInputStream(e).use { i ->
                                val buf = bufs.get()
                                while (true) {
                                    val r = i.read(buf)
                                    if (r < 0) break
                                    sink.write(buf, 0, r)
                                    if (cancelled()) return
                                }
                            }
                            classify(e.name, sink.head, sink.headLen)
                        }
                    } catch (ex: Throwable) {
                        Cls("otherdata", "unreadable", "unkplat", "read error: ${ex.message ?: ex.javaClass.simpleName}")
                    }
                    record(full, stored, cls, "inzip", sink)
                    done.addAndGet(stored)
                }
                tick(full)
            }
            val overhead = (f.length() - childSum).coerceAtLeast(0L)
            record(
                "$rel/[archive structure]", overhead,
                Cls("archstruct", "readable", if (android) "android" else "cross", "zip directory and headers"),
                "loose", null
            )
        }
    }

    private fun magicOf(f: File): Int {
        val b = ByteArray(4)
        val n = f.inputStream().use { it.read(b) }
        if (n < 4) return 0
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }

    private fun scanFile(f: File, rel: String) {
        if (cancelled()) return
        val ctx = FileCtx()
        val prev = curCtx.get()
        curCtx.set(ctx)
        tick(rel)
        try {
            val m = magicOf(f)
            when {
                m == 0x52504637 || m == 0x37465052 -> scanTopRpf(f, rel)
                m == 0x04034B50 -> scanTopZip(f, rel)
                else -> scanLeafFile(f, rel)
            }
        } catch (e: Throwable) {
            if (ctx.recs.get() == 0) {
                record(rel, f.length(), Cls("otherdata", "unreadable", "unkplat", "scan error: ${e.message ?: e.javaClass.simpleName}"), "loose", null)
            }
        } finally {
            curCtx.set(prev)
        }
        tick(rel)
    }

    private fun readAhead(
        list: List<File>, sizes: Map<File, Long>, k: Int, n: Int, ahead: AtomicLong, stop: AtomicBoolean
    ) {
        val buf = java.nio.ByteBuffer.allocateDirect(8 shl 20)
        var i = k
        while (i < list.size && !stop.get() && !cancelled()) {
            val f = list[i]
            val len = sizes[f] ?: 0L
            i += n
            if (len < (1L shl 20)) {
                ahead.addAndGet(len)
                continue
            }
            try {
                java.nio.channels.FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
                    var pos = 0L
                    while (pos < len && !stop.get() && !cancelled()) {
                        while (ahead.get() - done.get() > budget && !stop.get() && !cancelled()) Thread.sleep(4)
                        buf.clear()
                        val r = ch.read(buf, pos)
                        if (r <= 0) break
                        pos += r
                        ahead.addAndGet(r.toLong())
                    }
                }
            } catch (e: Throwable) {
                ahead.addAndGet(len)
            }
        }
    }

    private fun collect(dir: File, out: ArrayList<File>) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) collect(f, out) else out += f
        }
    }

    private fun rel(f: File): String {
        val r = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        return if (r.isEmpty()) root.name else root.name + "/" + r
    }

    fun run(): ScanSummary {
        indexFile.parentFile?.mkdirs()
        w = indexFile.bufferedWriter(Charsets.UTF_8, 1 shl 16)
        w.write("path\tstored_bytes\tcontent\treadability\tplatform\twhere\tdetail\tcrc32\tentropy\tzero_pct\tcontent_bytes\n")
        val list = ArrayList<File>()
        if (root.isDirectory) collect(root, list) else list += root
        // Biggest first, so the long jobs start early and the small ones fill the gaps.
        val sizes = HashMap<File, Long>(list.size * 2)
        var sum = 0L
        for (f in list) {
            val len = f.length()
            sizes[f] = len
            sum += len
        }
        total = sum
        list.sortByDescending { sizes[it] ?: 0L }
        pool = ForkJoinPool(threads, ForkJoinPool.ForkJoinWorkerThreadFactory { p -> ScanWorker(p) }, null, false)
        // Storage, memory and CPU all work at once: reader threads pull the files into memory ahead of the
        // scan (never more than the RAM budget ahead), while the worker threads decompress and check them.
        val stopReaders = AtomicBoolean(false)
        val aheadCount = AtomicLong(0L)
        val readers = (0 until (if (READ_AHEAD) tune.io.coerceIn(1, 4) else 0)).map { k ->
            thread(name = "scan-io-$k", isDaemon = true) {
                readAhead(list, sizes, k, tune.io.coerceIn(1, 4), aheadCount, stopReaders)
            }
        }
        try {
            parallelFor(list) { f -> scanFile(f, if (root.isDirectory) rel(f) else root.name) }
        } finally {
            stopReaders.set(true)
            readers.forEach { it.join(3000) }
            pool.shutdown()
            w.close()
        }
        val now = System.currentTimeMillis()
        progress(ScanProgress(total, total, files, "", now - startMs))
        return ScanSummary(
            root.absolutePath, total, files, bytes, counts, hist, now - startMs, errors,
            cancelled(), now, key != null
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Reads one group's answers back out of the index, a page at a time.
// ---------------------------------------------------------------------------------------------

class TagReader(private val index: File, private val col: Int, private val tagId: String) {
    private var r: BufferedReader? = null

    fun next(n: Int): List<ScanRow> {
        val out = ArrayList<ScanRow>()
        val rd = r ?: index.bufferedReader(Charsets.UTF_8, 1 shl 16).also { it.readLine(); r = it }
        while (out.size < n) {
            val line = rd.readLine() ?: break
            val p = line.split('\t')
            if (p.size < 7) continue
            if (p[col] == tagId) out += ScanRow(p[0], p[1].toLongOrNull() ?: 0L, p[6])
        }
        return out
    }

    fun close() {
        try { r?.close() } catch (_: Exception) { }
        r = null
    }
}

// ---------------------------------------------------------------------------------------------
// Holds the scan between screens, and writes the two kinds of export.
// ---------------------------------------------------------------------------------------------

class ScanController {
    var running by mutableStateOf(false)
    var progress by mutableStateOf<ScanProgress?>(null)
    var summary by mutableStateOf<ScanSummary?>(null)
    var message by mutableStateOf<String?>(null)
    var info by mutableStateOf("")
    @Volatile var cancel = false
    private var base: File? = null

    private fun dir(): File? = base?.let { File(it, "gamescan") }
    private fun indexFile(): File? = dir()?.let { File(it, "index.tsv") }

    fun loadLast(filesDir: File) {
        base = filesDir
        if (summary != null || running) return
        val f = File(File(filesDir, "gamescan"), "summary.txt")
        if (f.exists() && File(File(filesDir, "gamescan"), "index.tsv").exists()) summary = ScanSummary.read(f)
    }

    fun start(root: File, key: ByteArray?, filesDir: File, cacheDir: File, totalRam: Long, availRam: Long) {
        if (running) return
        base = filesDir
        running = true
        cancel = false
        message = null
        progress = null
        info = ""
        thread(name = "game-scan") {
            try {
                val d = File(filesDir, "gamescan")
                d.mkdirs()
                // First scan on this phone: a one-second benchmark picks the thread count, kept for next time.
                var tune = ScanTuning.load(filesDir)
                if (tune == null) {
                    progress = ScanProgress(0L, 0L, 0L, "Tuning for this phone (first time only)", 0L)
                    tune = ScanTuning.benchmark(filesDir)
                }
                val budget = ScanTuning.budgetBytes(totalRam, availRam)
                info = String.format(
                    Locale.US, "%d threads · %.1f GB memory budget · CPU test %d MB/s",
                    tune.threads, budget / 1073741824.0, tune.mbps
                )
                val sum = Engine(root, key, cacheDir, File(d, "index.tsv"), tune, budget, { progress = it }, { cancel }).run()
                sum.write(File(d, "summary.txt"))
                summary = sum
                message = if (sum.cancelled) "Scan stopped. These results cover only the part that was scanned." else null
            } catch (e: Throwable) {
                message = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        cancel = true
    }

    fun reader(tag: ScanTag): TagReader =
        TagReader(indexFile() ?: File("none"), ScanDefs.column(tag.group), tag.id)

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9 ._+-]"), "_").replace(Regex("_+"), "_").trim()

    private fun forEachInTag(tagId: String, block: (path: String, stored: Long) -> Unit) {
        val idx = indexFile() ?: return
        val col = ScanDefs.column(ScanDefs.tag(tagId).group)
        idx.bufferedReader(Charsets.UTF_8, 1 shl 16).use { rd ->
            rd.readLine()
            while (true) {
                val line = rd.readLine() ?: break
                val p = line.split('\t')
                if (p.size > col && p[col] == tagId) block(p[0], p[1].toLongOrNull() ?: 0L)
            }
        }
    }

    // One text file for one row of the results, named after it and its percentage. It lists every
    // folder and sub-folder (archives count as folders), then every file with its size.
    fun extractList(tagId: String, outDir: File): File {
        val s = summary ?: error("No scan results yet")
        val t = ScanDefs.tag(tagId)
        outDir.mkdirs()
        val pct = String.format(Locale.US, "%.1f", s.pct(tagId))
        val f = File(outDir, safeName("${t.label} $pct percent") + ".txt")

        val folders = TreeSet<String>()
        var count = 0L
        forEachInTag(tagId) { path, _ ->
            count++
            var p = path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && folders.add(p)) p = p.substringBeforeLast('/', "")
        }
        val ti = ScanDefs.index.getValue(tagId)
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("APEN V scan list\n")
            w.write("Mode: ${t.label}\n")
            w.write("Share of the game: $pct%  (${formatSize(s.bytes[ti])} of ${formatSize(s.total)})\n")
            w.write("Files: $count\n")
            w.write("Folders: ${folders.size}\n")
            w.write("Scanned folder: ${s.root}\n\n")
            w.write("=== FOLDERS AND SUB-FOLDERS (${folders.size}) ===\n")
            for (p in folders) w.write("$p/\n")
            w.write("\n=== FILES ($count) ===\n")
            forEachInTag(tagId) { path, stored -> w.write("$stored\t$path\n") }
        }
        return f
    }

    // Everything in one maximum-compression zip: a report, the full table with one line per file
    // (size, type, answers, CRC-32, entropy, share of zero bytes), the count of every byte value,
    // and a list for every row of the results.
    fun extractLogs(outDir: File): File {
        val s = summary ?: error("No scan results yet")
        val idx = indexFile() ?: error("No scan results yet")
        outDir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val f = File(outDir, "APEN_V_scan_logs_$stamp.zip")
        ZipOutputStream(f.outputStream().buffered(1 shl 16)).use { zo ->
            zo.setLevel(Deflater.BEST_COMPRESSION)

            fun put(name: String, block: (java.io.Writer) -> Unit) {
                zo.putNextEntry(ZipEntry(name))
                val w = zo.writer(Charsets.UTF_8).buffered(1 shl 16)
                block(w)
                w.flush()
                zo.closeEntry()
            }

            val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            put("scan_report.txt") { w ->
                w.write("APEN V game scan report\n")
                w.write("Scanned folder: ${s.root}\n")
                w.write("Scanned at: ${df.format(java.util.Date(s.time))}\n")
                w.write("Took: ${s.elapsedMs / 1000} seconds\n")
                w.write("Total size: ${s.total} bytes (${formatSize(s.total)})\n")
                w.write("Entries scanned: ${s.files}\n")
                w.write("Entries that could not be read: ${s.errors}\n")
                w.write("AES key supplied: ${if (s.keyUsed) "yes" else "no"}\n")
                w.write("Finished: ${if (s.cancelled) "no, stopped early" else "yes"}\n\n")
                w.write("How to read this: sizes are bytes as stored on disk. Every file and every file\n")
                w.write("inside an archive is counted once in each group, so each group adds up to 100%.\n")
                w.write("What archives do not cover (table of contents, names, padding) is listed as\n")
                w.write("\"Archive structure and padding\".\n\n")
                for ((gid, gtitle) in ScanDefs.groups) {
                    w.write("== $gtitle ==\n")
                    for (t in ScanDefs.tags.filter { it.group == gid }) {
                        val i = ScanDefs.index.getValue(t.id)
                        w.write(
                            String.format(
                                Locale.US, "%-42s %7.2f%%  %16d bytes  %10d entries\n",
                                t.label, s.pct(t.id), s.bytes[i], s.counts[i]
                            )
                        )
                    }
                    w.write("\n")
                }
            }

            put("byte_histogram.txt") { w ->
                var n = 0L
                for (c in s.hist) n += c
                var e = 0.0
                for (c in s.hist) if (c > 0L && n > 0L) { val p = c.toDouble() / n; e -= p * ln(p) / ln(2.0) }
                w.write("Count of every byte value across the contents of every file read.\n")
                w.write("Archive entries are counted after decompression; archive headers are not included.\n")
                w.write("Total bytes counted: $n\n")
                w.write(String.format(Locale.US, "Entropy of the whole game: %.4f bits per byte\n\n", e))
                w.write("value\tcount\tpercent\n")
                for (v in 0 until 256) {
                    val pc = if (n > 0L) s.hist[v] * 100.0 / n else 0.0
                    w.write(String.format(Locale.US, "0x%02X\t%d\t%.5f\n", v, s.hist[v], pc))
                }
            }

            zo.putNextEntry(ZipEntry("files.tsv"))
            idx.inputStream().use { it.copyTo(zo, 1 shl 16) }
            zo.closeEntry()

            for (t in ScanDefs.tags) {
                val i = ScanDefs.index.getValue(t.id)
                if (s.counts[i] <= 0L) continue
                put("lists/${t.group}_${t.id}.txt") { w ->
                    w.write("${t.label}: ${String.format(Locale.US, "%.2f", s.pct(t.id))}% of the game, ${s.counts[i]} entries\n")
                    w.write("size_bytes\tpath\n")
                    forEachInTag(t.id) { path, stored -> w.write("$stored\t$path\n") }
                }
            }
        }
        return f
    }
}
