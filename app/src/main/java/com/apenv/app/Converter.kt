package com.apenv.app

import java.io.File
import java.util.concurrent.CancellationException

class ConvertReport {
    var archives = 0
    var files = 0
    var converted = 0
    var retainedBc = 0
    // Textures stored inside model files (.ydr, .ydd, .yft). They are counted but not converted.
    var modelFiles = 0
    var embedded = 0
    var modelChecks = 0
    var errors = 0
    var before = 0L
    var after = 0L
    var cancelled = false
    val notes = ArrayList<String>()
}

// Converts BC textures in .ytd files and inside .rpf archives to ASTC.
// Originals are always backed up (APEN V folder) before they are replaced.
object Converter {
    @Volatile private var active = 0L
    private val lock = Any()

    // Returns true if the RPF contains any BC-compressed textures that need ASTC conversion.
    // Returns true on any read error (safe default: attempt conversion).
    fun needsConversion(src: File, key: ByteArray?): Boolean {
        return try {
            val a = RpfArchive(src, key, false, src.name)
            a.load()
            var found = false
            fun walk(dir: Dir) {
                for (child in dir.kids) {
                    if (found) return
                    when (child) {
                        is Leaf -> if (child.name.endsWith(".ytd", ignoreCase = true)) {
                            val bytes = runCatching { a.extractToBytes(child) }.getOrNull() ?: return
                            val texs = TexScan.parse(bytes) ?: return
                            if (texs.any { it.bc }) { found = true; return }
                        }
                        is Dir -> walk(child)
                    }
                }
            }
            walk(a.root)
            found
        } catch (e: Exception) {
            true  // assume conversion needed if we can't read the file
        }
    }

    fun abort() {
        synchronized(lock) {
            if (active != 0L) Astc.cancel(active)
        }
    }

    fun run(
        src: File, key: ByteArray?, bw: Int, bh: Int, replace: Boolean, quality: Float, cache: File,
        progress: (String) -> Unit, cancelled: () -> Boolean
    ): ConvertReport {
        val rep = ConvertReport()
        if (!Astc.available) {
            rep.errors++
            rep.notes += "The ASTC encoder library could not be loaded on this device."
            return rep
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val h = Astc.create(bw, bh, threads, quality)
        if (h == 0L) {
            rep.errors++
            rep.notes += "The ASTC encoder could not be started for ${bw}x$bh blocks (${Astc.lastError()})."
            return rep
        }
        synchronized(lock) { active = h }

        fun note(msg: String) {
            rep.errors++
            if (rep.notes.size < 20) rep.notes += msg
        }

        fun convertBytes(name: String, data: ByteArray): YtdResult? {
            return try {
                val r = YtdConvert.convert(data, bw, bh, h, cancelled)
                if (r != null) {
                    rep.converted += r.converted
                    rep.retainedBc += r.retainedBc
                    rep.before += data.size
                    rep.after += r.bytes.size
                }
                r
            } catch (e: CancellationException) {
                rep.cancelled = true
                null
            } catch (e: Exception) {
                note("$name: ${e.message ?: "failed"}")
                null
            } catch (e: OutOfMemoryError) {
                note("$name: not enough memory")
                null
            }
        }

        fun loose(f: File) {
            progress(f.name)
            rep.files++
            val data = try { f.readBytes() } catch (e: Exception) { note("${f.name}: ${e.message}"); return }
            val r = convertBytes(f.name, data) ?: return
            try {
                if (replace) {
                    Backups.createLoose(f)
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeBytes(r.bytes)
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                } else {
                    File(f.parentFile, f.nameWithoutExtension + "_astc.ytd").writeBytes(r.bytes)
                }
            } catch (e: Exception) {
                note("${f.name}: ${e.message}")
            }
        }

        // Converts every .ytd in the archive, including archives stored inside it.
        // Returns true when the archive was rewritten.
        fun convertArchive(a: RpfArchive, label: String, depth: Int): Boolean {
            val jobs = ArrayList<RpfArchive.Replacement>()
            val temps = ArrayList<File>()
            try {
                fun walk(dir: Dir, path: List<String>) {
                    for (n in dir.kids) {
                        if (cancelled()) return
                        when (n) {
                            is Dir -> walk(n, path + n.name)
                            is Leaf -> {
                                val lower = n.name.lowercase()
                                if (n.resource && lower.endsWith(".ytd")) {
                                    progress("$label / ${n.name}")
                                    rep.files++
                                    val bytes = try { a.extractToBytes(n) } catch (e: Exception) { note("${n.name}: ${e.message}"); continue }
                                    val r = convertBytes("$label / ${n.name}", bytes) ?: continue
                                    val tmp = File.createTempFile("ytd", ".bin", cache)
                                    temps += tmp
                                    tmp.writeBytes(r.bytes)
                                    jobs += RpfArchive.Replacement(path, n.name, tmp)
                                } else if (n.resource && rep.modelChecks < 300 && n.diskLen <= 32L * 1024 * 1024 &&
                                    (lower.endsWith(".ydr") || lower.endsWith(".ydd") || lower.endsWith(".yft"))
                                ) {
                                    // Not converted: a model keeps its geometry in the same memory as
                                    // its textures. Counted so they are not mistaken for "no textures".
                                    rep.modelChecks++
                                    val list = runCatching { TexScan.parse(a.extractToBytes(n)) }.getOrNull()
                                    if (!list.isNullOrEmpty()) {
                                        rep.modelFiles++
                                        rep.embedded += list.size
                                    }
                                } else if (!n.resource && lower.endsWith(".rpf") && depth < 4) {
                                    val tmp = File.createTempFile("nested", ".rpf", cache)
                                    temps += tmp
                                    try {
                                        tmp.outputStream().buffered().use { a.extract(n, it) }
                                        rep.archives++
                                        val inner = RpfArchive(tmp, key, false, n.name)
                                        if (convertArchive(inner, "$label / ${n.name}", depth + 1)) {
                                            jobs += RpfArchive.Replacement(path, n.name, tmp)
                                        }
                                    } catch (e: Exception) {
                                        note("$label / ${n.name}: ${e.message ?: "failed"}")
                                    }
                                }
                            }
                        }
                    }
                }
                walk(a.dirAt(emptyList()), emptyList())

                if (cancelled() || jobs.isEmpty()) return false
                progress("Saving $label")
                a.replaceMany(jobs)
                return true
            } finally {
                temps.forEach { it.delete() }
            }
        }

        fun archive(f: File) {
            progress(f.name)
            rep.archives++
            val target = if (replace) f else File(f.parentFile, f.nameWithoutExtension + "_astc.rpf")
            try {
                if (!replace) f.copyTo(target, overwrite = true)
                val a = RpfArchive(target, key, backups = replace, keyName = f.name)
                val changed = convertArchive(a, f.name, 0)
                if (!changed && !replace) target.delete()
            } catch (e: Exception) {
                note("${f.name}: ${e.message ?: "failed"}")
                if (!replace) target.delete()
            }
        }

        // Rewrites a .zip with its .ytd files and .rpf archives converted (archives inside the
        // zip are entered too). Everything else in the zip is copied across untouched.
        fun zipFile(f: File) {
            progress(f.name)
            rep.archives++
            val outTmp = File.createTempFile("zip", ".tmp", cache)
            val temps = ArrayList<File>()
            var changed = false
            try {
                java.util.zip.ZipFile(f).use { zf ->
                    java.util.zip.ZipOutputStream(outTmp.outputStream().buffered()).use { zo ->
                        val en = zf.entries()
                        while (en.hasMoreElements()) {
                            if (cancelled()) break
                            val e = en.nextElement()
                            val lower = e.name.lowercase()
                            val label = "${f.name} / ${e.name}"
                            var newBytes: ByteArray? = null
                            var newFile: File? = null
                            if (!e.isDirectory && lower.endsWith(".ytd")) {
                                progress(label)
                                rep.files++
                                val bytes = try {
                                    zf.getInputStream(e).use { it.readBytes() }
                                } catch (ex: Exception) {
                                    note("$label: ${ex.message ?: "failed"}")
                                    null
                                }
                                if (bytes != null) newBytes = convertBytes(label, bytes)?.bytes
                            } else if (!e.isDirectory && lower.endsWith(".rpf")) {
                                val tmp = File.createTempFile("zrpf", ".rpf", cache)
                                temps += tmp
                                try {
                                    zf.getInputStream(e).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                                    rep.archives++
                                    val inner = RpfArchive(tmp, key, false, e.name.substringAfterLast('/'))
                                    if (convertArchive(inner, label, 1)) newFile = tmp
                                } catch (ex: Exception) {
                                    note("$label: ${ex.message ?: "failed"}")
                                }
                            }
                            val ze = java.util.zip.ZipEntry(e.name)
                            if (e.time != -1L) ze.time = e.time
                            zo.putNextEntry(ze)
                            val nb = newBytes
                            val nf = newFile
                            when {
                                nb != null -> { zo.write(nb); changed = true }
                                nf != null -> { nf.inputStream().buffered().use { it.copyTo(zo) }; changed = true }
                                !e.isDirectory -> zf.getInputStream(e).use { it.copyTo(zo) }
                            }
                            zo.closeEntry()
                        }
                    }
                }
                if (cancelled() || !changed) return
                if (replace) {
                    Backups.createLoose(f)
                    if (!outTmp.renameTo(f)) outTmp.copyTo(f, overwrite = true)
                } else {
                    outTmp.copyTo(File(f.parentFile, f.nameWithoutExtension + "_astc.zip"), overwrite = true)
                }
            } catch (e: Exception) {
                note("${f.name}: ${e.message ?: "failed"}")
            } finally {
                temps.forEach { it.delete() }
                outTmp.delete()
            }
        }

        fun visit(f: File) {
            if (cancelled()) return
            if (f.isDirectory) {
                if (Backups.isInside(f)) return
                f.listFiles()?.sortedBy { it.name }?.forEach { visit(it) }
                return
            }
            val name = f.name.lowercase()
            if (name.endsWith("_astc.ytd") || name.endsWith("_astc.rpf") || name.endsWith("_astc.zip") || name.endsWith(".tmp")) return
            when (f.extension.lowercase()) {
                "ytd" -> loose(f)
                "rpf" -> archive(f)
                "zip" -> zipFile(f)
            }
        }

        try {
            visit(src)
        } finally {
            synchronized(lock) {
                active = 0L
                Astc.free(h)
            }
        }
        if (cancelled()) rep.cancelled = true
        return rep
    }
}
