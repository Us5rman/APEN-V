package com.apenv.app

import java.io.File

// Backups live in the APEN V folder under the same name as the original file.
// When that name is already taken the copy gets " (2)", " (3)" ... before the extension.
// A small index file remembers which original each copy belongs to.
object Backups {
    private const val KEEP = 10
    private var root = File("/sdcard/APEN V")
    private val index get() = File(root, ".index")

    fun init(dir: File) {
        root = dir
    }

    fun isInside(f: File): Boolean = f.absolutePath.startsWith(root.absolutePath)

    private fun slotFor(original: File): File {
        root.mkdirs()
        val base = original.nameWithoutExtension
        val ext = if (original.extension.isEmpty()) "" else "." + original.extension
        var dest = File(root, original.name)
        var n = 2
        while (dest.exists()) {
            dest = File(root, "$base ($n)$ext")
            n++
        }
        return dest
    }

    @Synchronized
    private fun record(backup: File, original: File) {
        index.appendText("${backup.name}\t${original.absolutePath}\n")
    }

    @Synchronized
    private fun known(original: File): List<File> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 2 && p[1] == original.absolutePath) File(root, p[0]).takeIf { it.isFile } else null
        }.distinct()
    }

    private fun backup(original: File): File {
        val dest = slotFor(original)
        original.copyTo(dest)
        record(dest, original)
        list(original).drop(KEEP).forEach { it.delete() }
        return dest
    }

    fun create(archive: File): File = backup(archive)

    // Backup of a loose file (for example a .ytd) before it is replaced.
    fun createLoose(file: File): File = backup(file)

    fun list(archive: File): List<File> {
        val tag = Integer.toHexString(archive.absolutePath.hashCode())
        val older = File(root, "${archive.nameWithoutExtension}_$tag")
            .listFiles { f -> f.extension == "rpf" }.orEmpty().toList()
        return (known(archive) + older).sortedByDescending { it.lastModified() }
    }

    fun restore(archive: File, backup: File) {
        val staged = File(archive.parentFile, archive.name + ".restore")
        backup.copyTo(staged, overwrite = true)
        create(archive)
        archive.delete()
        if (!staged.renameTo(archive)) {
            staged.copyTo(archive, overwrite = true)
            staged.delete()
        }
    }
}
