package com.apenv.app

import android.graphics.Bitmap
import java.io.File

// A small picture for the list, plus a way to build the big one when it is opened. The big
// picture isn't kept in memory, so the list can hold every texture in a large folder.
class PreviewItem(
    val id: String, val title: String, val detail: String, val bitmap: Bitmap?,
    val full: (() -> Bitmap?)? = null
)

// Builds pictures of the textures in loose .ytd files and in .ytd files inside .rpf archives.
// Files without textures are left out. Textures that belong together (a background and the
// foreground drawn over it) are found by name and shown as one picture.
object Previews {
    private const val MAX_LOOSE = 96L * 1024 * 1024
    private const val THUMB = 256
    private const val FULL = 1600

    // Archives pulled out of a .zip or out of another archive are kept on disk while their
    // pictures are listed (opening a picture re-reads from them), then removed on the next load.
    private val temps = ArrayList<File>()
    private fun tempRpf(): File = File.createTempFile("prv_", ".rpf").also { synchronized(temps) { temps += it } }
    private fun cleanTemps() = synchronized(temps) { temps.forEach { it.delete() }; temps.clear() }

    private fun readZipEntry(f: File, name: String): ByteArray? =
        java.util.zip.ZipFile(f).use { z -> z.getEntry(name)?.let { en -> z.getInputStream(en).use { it.readBytes() } } }

    private fun small(b: Bitmap): Bitmap {
        val c = b.copy(Bitmap.Config.RGB_565, false) ?: return b
        if (c !== b) b.recycle()
        return c
    }

    private fun fullSingle(read: () -> ByteArray?, index: Int): Bitmap? {
        val data = read() ?: return null
        val e = YtdConvert.previewAll(data).getOrNull(index) ?: return null
        val px = e.rgba ?: return null
        return TextureGroups.toBitmap(px, e.pw, e.ph, FULL, true)
    }

    private fun fullTogether(read: () -> ByteArray?, bg: Int, fg: Int): Bitmap? {
        val data = read() ?: return null
        val es = YtdConvert.previewAll(data)
        val b = es.getOrNull(bg) ?: return null
        val f = es.getOrNull(fg) ?: return null
        val bp = b.rgba ?: return null
        val fp = f.rgba ?: return null
        val t = Together(
            TextureGroups.toBitmap(bp, b.pw, b.ph, FULL, false),
            TextureGroups.toBitmap(fp, f.pw, f.ph, FULL, false), b.name, f.name
        )
        return TextureGroups.render(t)
    }

    fun load(root: File, key: ByteArray?, cancelled: () -> Boolean, onItem: (PreviewItem) -> Unit) {
        var count = 0
        cleanTemps()

        fun emitYtd(idBase: String, title: String, sizeBytes: Long, read: () -> ByteArray?) {
            val data = runCatching { read() }.getOrNull() ?: return
            val entries = runCatching { YtdConvert.previewAll(data) }.getOrNull()
            if (entries.isNullOrEmpty()) return
            val size = formatSize(sizeBytes)

            val used = HashSet<Int>()
            TextureGroups.detect(entries).forEachIndexed { k, g ->
                if (cancelled()) return
                val bgE = entries[g.bg]
                val fgE = entries[g.fg]
                val t = Together(
                    TextureGroups.toBitmap(bgE.rgba!!, bgE.pw, bgE.ph, THUMB, false),
                    TextureGroups.toBitmap(fgE.rgba!!, fgE.pw, fgE.ph, THUMB, false),
                    bgE.name, fgE.name
                )
                val thumb = small(TextureGroups.render(t))
                val bi = g.bg
                val fi = g.fg
                onItem(
                    PreviewItem(
                        "$idBase#t$k", title, "${bgE.name} + ${fgE.name} · $size", thumb,
                        { runCatching { fullTogether(read, bi, fi) }.getOrNull() }
                    )
                )
                count++
                used += g.bg
                used += g.fg
            }

            entries.forEachIndexed { i, e ->
                if (i in used) return@forEachIndexed
                if (cancelled()) return
                val px = e.rgba ?: return@forEachIndexed
                val thumb = small(TextureGroups.toBitmap(px, e.pw, e.ph, THUMB, true))
                val name = if (entries.size > 1) "$title [${e.name}]" else title
                onItem(
                    PreviewItem(
                        "$idBase#$i", name, "${e.format} · $size", thumb,
                        { runCatching { fullSingle(read, i) }.getOrNull() }
                    )
                )
                count++
            }
        }

        fun walk(a: RpfArchive, dir: Dir, label: String, depth: Int) {
            for (n in dir.kids) {
                if (cancelled()) return
                when (n) {
                    is Dir -> walk(a, n, label, depth)
                    is Leaf -> {
                        val ext = n.name.substringAfterLast('.', "").lowercase()
                        if (ext == "ytd" && n.resource && n.diskLen <= MAX_LOOSE) {
                            emitYtd("$label/${n.name}#$count", n.name, n.diskLen) { a.extractToBytes(n) }
                        } else if (ext == "rpf" && !n.resource && depth < 4) {
                            val tmp = tempRpf()
                            val ok = runCatching { tmp.outputStream().buffered().use { a.extract(n, it) } }.isSuccess
                            if (ok) runCatching {
                                val inner = RpfArchive(tmp, key, false, n.name)
                                walk(inner, inner.dirAt(emptyList()), "$label/${n.name}", depth + 1)
                            }
                        }
                    }
                }
            }
        }

        fun visit(f: File) {
            if (cancelled()) return
            if (f.isDirectory) {
                if (Backups.isInside(f)) return
                f.listFiles()?.sortedBy { it.name }?.forEach { visit(it) }
                return
            }
            val ext = f.extension.lowercase()
            when {
                ext == "ytd" && f.length() <= MAX_LOOSE ->
                    emitYtd(f.absolutePath, f.name, f.length()) { f.readBytes() }
                ext == "rpf" -> runCatching {
                    val a = RpfArchive(f, key, false)
                    walk(a, a.dirAt(emptyList()), f.name, 0)
                }
                ext == "zip" -> runCatching {
                    java.util.zip.ZipFile(f).use { zf ->
                        val en = zf.entries()
                        while (en.hasMoreElements()) {
                            if (cancelled()) break
                            val e = en.nextElement()
                            if (e.isDirectory) continue
                            val entryName = e.name
                            val lower = entryName.lowercase()
                            val shortName = entryName.substringAfterLast('/')
                            if (lower.endsWith(".ytd") && e.size <= MAX_LOOSE) {
                                emitYtd("${f.absolutePath}!$entryName", shortName, maxOf(0L, e.size)) { readZipEntry(f, entryName) }
                            } else if (lower.endsWith(".rpf")) {
                                val tmp = tempRpf()
                                zf.getInputStream(e).use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
                                val a = RpfArchive(tmp, key, false, shortName)
                                walk(a, a.dirAt(emptyList()), "${f.name}/$shortName", 1)
                            }
                        }
                    }
                }
            }
        }

        visit(root)
    }
}
