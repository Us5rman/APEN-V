package com.apenv.app

import android.os.Build
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.concurrent.atomic.AtomicLong

// What the scan learned about this phone. Measured once, kept in the app's private storage.
class Tuning(
    val threads: Int,
    val io: Int,
    val cores: Int,
    val fastCores: Int,
    val maxMhz: Int,
    val mbps: Int,
    val fingerprint: String
)

object ScanTuning {
    private const val VERSION = 2
    private const val GB = 1L shl 30
    private const val SAMPLE = 2 shl 20

    // How much memory the scan may use for files it holds in memory at once. It follows what is free
    // right now, on every phone: about half of the free RAM, between 0.5 GB and 6 GB, and never more
    // than 40% of all RAM.
    fun budgetBytes(totalRam: Long, availRam: Long): Long {
        val want = availRam / 2
        val cap = (totalRam * 4) / 10
        return minOf(want, cap, 6 * GB).coerceAtLeast(GB / 2)
    }

    fun load(dir: File): Tuning? = try {
        val m = HashMap<String, String>()
        File(dir, "scan_tuning.txt").forEachLine { l ->
            val i = l.indexOf('=')
            if (i > 0) m[l.substring(0, i)] = l.substring(i + 1)
        }
        val t = Tuning(
            m.getValue("threads").toInt(), m.getValue("io").toInt(), m.getValue("cores").toInt(),
            m.getValue("fast").toInt(), m.getValue("mhz").toInt(), m.getValue("mbps").toInt(), m.getValue("fp")
        )
        // Measure again after a system update or when the core count changes.
        if (m["version"] != VERSION.toString() || t.fingerprint != Build.FINGERPRINT ||
            t.cores != Runtime.getRuntime().availableProcessors()
        ) null else t
    } catch (e: Exception) {
        null
    }

    private fun save(dir: File, t: Tuning) {
        try {
            dir.mkdirs()
            File(dir, "scan_tuning.txt").writeText(
                "version=$VERSION\nthreads=${t.threads}\nio=${t.io}\ncores=${t.cores}\nfast=${t.fastCores}\n" +
                    "mhz=${t.maxMhz}\nmbps=${t.mbps}\nfp=${t.fingerprint}\n"
            )
        } catch (e: Exception) {
        }
    }

    // About a second: inflate + checksum + byte count, which is the real work of a scan, on 1, 2, 3...
    // threads. The thread count is where adding more stops helping, plus a few for waiting on storage.
    fun benchmark(dir: File): Tuning {
        val cores = Runtime.getRuntime().availableProcessors()
        val raw = ByteArray(SAMPLE)
        var x = 0x2545F491
        for (i in raw.indices) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            raw[i] = (if (i % 7 == 0) x and 0xFF else (x and 0x1F)).toByte()
        }
        val comp = deflate(raw)

        measure(1, 70, comp)
        val candidates = ArrayList<Int>()
        for (t in listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14)) if (t <= cores + 2) candidates += t
        if (cores !in candidates && cores <= 16) candidates += cores
        candidates.sort()

        val results = HashMap<Int, Double>()
        var best = 0.0
        for (t in candidates) {
            val r = measure(t, 110, comp)
            results[t] = r
            if (r > best) best = r
        }
        var pick = candidates.last()
        for (t in candidates) {
            if ((results[t] ?: 0.0) >= best * 0.93) { pick = t; break }
        }
        val threads = (pick + 4).coerceIn(6, 16)
        val io = if (cores >= 8) 3 else 2

        // Fastest core speed, if Android lets the app read it.
        var maxKhz = 0
        val freqs = IntArray(cores)
        for (i in 0 until cores) {
            freqs[i] = try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
            } catch (e: Exception) {
                0
            }
            if (freqs[i] > maxKhz) maxKhz = freqs[i]
        }
        val fast = if (maxKhz > 0) freqs.count { it * 100L >= maxKhz * 75L } else cores

        val t = Tuning(threads, io, cores, fast, maxKhz / 1000, best.toInt(), Build.FINGERPRINT)
        save(dir, t)
        return t
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(6, true)
        d.setInput(data)
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun measure(threads: Int, ms: Long, comp: ByteArray): Double {
        val total = AtomicLong(0L)
        val stopAt = System.nanoTime() + ms * 1_000_000L
        val ts = (0 until threads).map {
            Thread {
                val out = ByteArray(SAMPLE)
                val h = LongArray(256)
                val crc = CRC32()
                var local = 0L
                while (System.nanoTime() < stopAt) {
                    val inf = Inflater(true)
                    inf.setInput(comp)
                    var n = 0
                    while (!inf.finished()) {
                        val r = inf.inflate(out, n, out.size - n)
                        if (r == 0) break
                        n += r
                    }
                    inf.end()
                    crc.reset()
                    crc.update(out, 0, n)
                    var i = 0
                    while (i < n) {
                        h[out[i].toInt() and 255]++
                        i++
                    }
                    local += n
                }
                total.addAndGet(local)
            }
        }
        val t0 = System.nanoTime()
        ts.forEach { it.start() }
        ts.forEach { it.join() }
        val sec = (System.nanoTime() - t0) / 1e9
        return total.get() / 1e6 / sec
    }
}
