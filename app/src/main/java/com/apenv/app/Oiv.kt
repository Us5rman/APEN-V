package com.apenv.app

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

sealed class OivOp
class OivAdd(val source: String, val dest: String) : OivOp()
class OivDelete(val path: String) : OivOp()
class OivArchive(val path: String, val create: Boolean, val ops: List<OivOp>) : OivOp()
class OivSkip(val tag: String) : OivOp()

class OivPackage(
    val file: File,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val ops: List<OivOp>
)

class OivResult(val ok: Boolean, val updateChanged: Boolean)

// Reads and installs OpenIV packages. An .oiv is a zip with assembly.xml next to a content folder.
// Everything is written into <game folder>/mods, never into the game files themselves.
object Oiv {
    fun read(file: File): OivPackage = parse(file)

    fun read(ctx: Context, uri: Uri): OivPackage {
        val file = File(ctx.cacheDir, "package.oiv")
        ctx.contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            ?: throw IOException("Could not open the file")
        return parse(file)
    }

    private fun parse(file: File): OivPackage {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                it.name.replace('\\', '/').substringAfterLast('/').equals("assembly.xml", true)
            } ?: throw IOException("No assembly.xml in this file, it is not an OIV package")

            var name = ""
            var major = ""
            var minor = ""
            var tag = ""
            var author = ""
            var desc = ""
            var ops: List<OivOp> = emptyList()
            val stack = ArrayList<String>()

            val p = XmlPullParserFactory.newInstance().newPullParser()
            zip.getInputStream(entry).use { input ->
                p.setInput(input, null)
                while (true) {
                    val e = p.next()
                    if (e == XmlPullParser.END_DOCUMENT) break
                    if (e == XmlPullParser.END_TAG) {
                        stack.removeLastOrNull()
                        continue
                    }
                    if (e != XmlPullParser.START_TAG) continue
                    val parent = stack.lastOrNull()
                    when {
                        p.name == "content" -> ops = parseOps(p)
                        p.name == "name" && parent == "metadata" -> name = p.nextText().trim()
                        p.name == "major" && parent == "version" -> major = p.nextText().trim()
                        p.name == "minor" && parent == "version" -> minor = p.nextText().trim()
                        p.name == "tag" && parent == "version" -> tag = p.nextText().trim()
                        p.name == "displayName" && parent == "author" -> author = p.nextText().trim()
                        p.name == "description" && parent == "metadata" -> desc = p.nextText().trim()
                        else -> stack.add(p.name)
                    }
                }
            }
            val version = listOf(major, minor).filter { it.isNotEmpty() }.joinToString(".") +
                if (tag.isNotEmpty()) " $tag" else ""
            return OivPackage(file, name.ifEmpty { "Unnamed package" }, version.trim(), author, desc, ops)
        }
    }

    private fun parseOps(p: XmlPullParser): List<OivOp> {
        val ops = ArrayList<OivOp>()
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_TAG || e == XmlPullParser.END_DOCUMENT) break
            if (e != XmlPullParser.START_TAG) continue
            when (p.name.lowercase()) {
                "add" -> {
                    val src = p.getAttributeValue(null, "source") ?: ""
                    ops += OivAdd(src, p.nextText().trim())
                }
                "delete" -> ops += OivDelete(p.nextText().trim())
                "archive" -> {
                    val path = p.getAttributeValue(null, "path") ?: ""
                    val create = p.getAttributeValue(null, "createIfNotExist").equals("true", true)
                    ops += OivArchive(path, create, parseOps(p))
                }
                else -> {
                    ops += OivSkip(p.name)
                    skip(p)
                }
            }
        }
        return ops
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    fun summary(pkg: OivPackage): String {
        var files = 0
        var deletes = 0
        var archives = 0
        var skipped = 0
        fun walk(ops: List<OivOp>) {
            for (o in ops) when (o) {
                is OivAdd -> files++
                is OivDelete -> deletes++
                is OivArchive -> { archives++; walk(o.ops) }
                is OivSkip -> skipped++
            }
        }
        walk(pkg.ops)
        val out = mutableListOf("$files file${if (files == 1) "" else "s"}")
        if (deletes > 0) out += "$deletes delete${if (deletes == 1) "" else "s"}"
        if (archives > 0) out += "$archives archive${if (archives == 1) "" else "s"}"
        if (skipped > 0) out += "$skipped unsupported step${if (skipped == 1) "" else "s"}"
        return out.joinToString(" · ")
    }

    fun install(pkg: OivPackage, gameRoot: File, key: ByteArray?, cache: File, log: (String) -> Unit): OivResult {
        val mods = ModLoader.modsFolder(gameRoot)
        if (!mods.isDirectory) {
            log("Run Enable mods first, the mods folder is missing.")
            return OivResult(false, false)
        }
        var updateChanged = false
        return try {
            ZipFile(pkg.file).use { zip ->
                val names = zip.entries().asSequence().associateBy { it.name.replace('\\', '/').lowercase() }
                val job = Job(zip, names, key, cache, log)
                log("• ${pkg.name}")
                for (op in pkg.ops) {
                    when (op) {
                        is OivAdd -> job.looseAdd(mods, op)
                        is OivDelete -> job.looseDelete(mods, op)
                        is OivArchive -> {
                            job.archive(gameRoot, mods, op)
                            if (job.parts(op.path).joinToString("/").equals("update/update.rpf", true)) updateChanged = true
                        }
                        is OivSkip -> log("• Skipped <${op.tag}>, not supported")
                    }
                }
            }
            log("Done. ${pkg.name} is in the mods folder.")
            OivResult(true, updateChanged)
        } catch (e: Exception) {
            log("Failed: ${e.message ?: e.javaClass.simpleName}")
            OivResult(false, updateChanged)
        }
    }

    // PC packages say x64 where the Android game says android (x64v.rpf is androidv.rpf there)
    private val x64 = Regex("x64", RegexOption.IGNORE_CASE)

    private class Job(
        val zip: ZipFile,
        val names: Map<String, ZipEntry>,
        val key: ByteArray?,
        val cache: File,
        val log: (String) -> Unit
    ) {
        fun parts(path: String): List<String> {
            val p = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (p.any { it == ".." }) throw IOException("Bad path in the package: $path")
            return p.map { x64.replace(it, "android") }
        }

        // Copies one file out of the zip into a temp file. The caller deletes it.
        fun pull(source: String): File? {
            val s = source.replace('\\', '/').trim('/').lowercase()
            val entry = names["content/$s"] ?: names[s] ?: return null
            val out = File.createTempFile("oiv", ".bin", cache)
            zip.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            return out
        }

        fun looseAdd(mods: File, op: OivAdd) {
            val p = parts(op.dest)
            if (p.isEmpty()) return
            val src = pull(op.source)
            if (src == null) {
                log("  missing in the package: ${op.source}")
                return
            }
            try {
                val dst = File(mods, p.joinToString("/"))
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                log("• Added ${p.joinToString("/")}")
            } finally {
                src.delete()
            }
        }

        fun looseDelete(mods: File, op: OivDelete) {
            val p = parts(op.path)
            if (p.isEmpty()) return
            val f = File(mods, p.joinToString("/"))
            if (f.exists()) {
                f.deleteRecursively()
                log("• Deleted ${p.joinToString("/")} from the mods folder")
            }
        }

        fun archive(gameRoot: File, mods: File, op: OivArchive) {
            val rel = parts(op.path).joinToString("/")
            val asked = op.path.replace('\\', '/').trim('/')
            if (!asked.equals(rel, true)) log("• $asked is $rel on Android")
            val target = File(mods, rel)
            if (!target.isFile) {
                val orig = File(gameRoot, rel)
                when {
                    orig.isFile -> {
                        log("• Copying $rel from the game folder (${orig.length() shr 20} MB)")
                        target.parentFile?.mkdirs()
                        orig.copyTo(target, bufferSize = 4 shl 20)
                    }
                    op.create -> {
                        target.parentFile?.mkdirs()
                        RpfArchive.createEmpty(target)
                        log("• Created $rel")
                    }
                    else -> throw IOException("$rel is not in the game folder")
                }
            }
            log("• Editing $rel")
            edit(RpfArchive(target, key, true, target.name), op.ops)
        }

        // One rewrite per archive. Nested archives are edited first and put back as a file.
        fun edit(arc: RpfArchive, ops: List<OivOp>) {
            val adds = ArrayList<RpfArchive.Replacement>()
            val removes = ArrayList<Pair<List<String>, String>>()
            val temps = ArrayList<File>()
            var missing = 0
            try {
                for (op in ops) {
                    when (op) {
                        is OivAdd -> {
                            val p = parts(op.dest)
                            if (p.isEmpty()) continue
                            val f = pull(op.source)
                            if (f == null) {
                                log("  missing in the package: ${op.source}")
                                missing++
                            } else {
                                temps += f
                                adds += RpfArchive.Replacement(p.dropLast(1), p.last(), f)
                            }
                        }
                        is OivDelete -> {
                            val p = parts(op.path)
                            if (p.isNotEmpty()) removes += p.dropLast(1) to p.last()
                        }
                        is OivArchive -> {
                            val p = parts(op.path)
                            if (p.isEmpty()) continue
                            val dir = p.dropLast(1)
                            val name = p.last()
                            val leaf = arc.leafAt(dir, name)
                            val tmp = File.createTempFile("oiv", ".rpf", cache)
                            temps += tmp
                            when {
                                leaf != null -> tmp.outputStream().use { arc.extract(leaf, it) }
                                op.create -> RpfArchive.createEmpty(tmp)
                                else -> throw IOException("${p.joinToString("/")} is not inside ${arc.file.name}")
                            }
                            edit(RpfArchive(tmp, key, false, name), op.ops)
                            adds += RpfArchive.Replacement(dir, name, tmp)
                        }
                        is OivSkip -> log("• Skipped <${op.tag}>, not supported")
                    }
                }
                if (adds.isNotEmpty() || removes.isNotEmpty()) arc.applyChanges(adds, removes)
                log("  ${arc.file.name}: ${adds.size} added, ${removes.size} removed" +
                    if (missing > 0) ", $missing missing" else "")
            } finally {
                temps.forEach { it.delete() }
            }
        }
    }
}
