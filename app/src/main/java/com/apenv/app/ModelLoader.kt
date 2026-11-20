package com.apenv.app

import java.io.File

// One texture file that may hold what a model asks for.
class TexSource(val name: String, val read: () -> ByteArray)

// The models that sit next to each other (one folder or one folder inside an archive), plus a way
// to find the texture files that go with any of them.
class ModelSource(
    val models: List<String>,
    val read: (String) -> ByteArray,
    val textures: (String) -> List<TexSource>
)

class Part(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
    val texture: Textures.Image?
)

class Scene(val parts: List<Part>, val cx: Float, val cy: Float, val cz: Float, val radius: Float)

class Loaded(val scene: Scene?, val info: String)

object TexSources {
    private const val LIMIT = 300

    // How many leading characters two names share. Textures named like the model are tried first.
    private fun shared(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var n = 0
        while (n < x.length && n < y.length && x[n] == y[n]) n++
        return n
    }

    private fun isYtd(f: File) = f.isFile && f.extension.equals("ytd", true)

    // The .ytd files in a folder first, then in the folders below it.
    fun loose(dir: File, prefer: String): List<TexSource> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            out += kids.filter { isYtd(it) }.sortedBy { it.name }.sortedByDescending { shared(it.name, prefer) }
            if (out.size >= LIMIT || depth >= 4) return
            for (k in kids.filter { it.isDirectory && !Backups.isInside(it) }.sortedBy { it.name }) walk(k, depth + 1)
        }
        walk(dir, 0)
        return out.take(LIMIT).map { f -> TexSource(f.name) { f.readBytes() } }
    }

    // The .ytd files in one archive folder first, then everything else in the archive.
    fun archive(a: RpfArchive, path: List<String>, prefer: String): List<TexSource> {
        val found = ArrayList<Leaf>()
        val seen = HashSet<Leaf>()
        fun walk(d: Dir, first: Boolean) {
            val here = d.kids.filterIsInstance<Leaf>()
                .filter { it.resource && it.name.endsWith(".ytd", true) }
                .sortedBy { it.name }
                .sortedByDescending { if (first) shared(it.name, prefer) else 0 }
            for (l in here) if (found.size < LIMIT && seen.add(l)) found += l
            for (k in d.kids.filterIsInstance<Dir>()) if (found.size < LIMIT) walk(k, false)
        }
        walk(a.dirAt(path), true)
        if (path.isNotEmpty()) walk(a.dirAt(emptyList()), false)
        return found.map { l -> TexSource(l.name) { a.extractToBytes(l) } }
    }

    // A folder or archive the person picked by hand. Loose files and the archives inside are both searched.
    fun folder(root: File, key: ByteArray?): List<TexSource> {
        val out = ArrayList<TexSource>()
        fun archiveOf(f: File) {
            runCatching { out += archive(RpfArchive(f, key, false), emptyList(), "") }
        }
        if (root.isFile) {
            if (root.extension.equals("rpf", true)) archiveOf(root)
            else if (isYtd(root)) out += TexSource(root.name) { root.readBytes() }
            return out
        }
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles() ?: return
            for (f in kids.sortedBy { it.name }) {
                if (out.size >= LIMIT) return
                if (isYtd(f)) out += TexSource(f.name) { f.readBytes() }
                else if (f.isFile && f.extension.equals("rpf", true)) archiveOf(f)
            }
            if (depth < 4) {
                for (k in kids.filter { it.isDirectory && !Backups.isInside(it) }.sortedBy { it.name }) walk(k, depth + 1)
            }
        }
        walk(root, 0)
        return out.take(LIMIT)
    }
}

object ModelSources {
    private val modelExt = setOf("ydr", "ydd", "yft")

    fun fromDir(dir: File): ModelSource {
        val names = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in modelExt }
            .map { it.name }
            .sortedBy { it.lowercase() }
        return ModelSource(names, { File(dir, it).readBytes() }, { TexSources.loose(dir, it) })
    }

    fun fromArchive(a: RpfArchive, path: List<String>): ModelSource {
        val names = a.dirAt(path).kids.filterIsInstance<Leaf>()
            .filter { it.resource && it.name.substringAfterLast('.', "").lowercase() in modelExt }
            .map { it.name }
            .sortedBy { it.lowercase() }
        return ModelSource(
            names,
            { n -> a.extractToBytes(a.dirAt(path).kids.filterIsInstance<Leaf>().first { it.name == n }) },
            { n -> TexSources.archive(a, path, n) }
        )
    }
}

object ModelLoader {
    private const val MAX_TEX = 1024

    fun load(src: ModelSource, name: String, extra: File?, key: ByteArray?, cancelled: () -> Boolean): Loaded =
        try {
            loadModel(src, name, extra, key, cancelled)
        } catch (e: Throwable) {
            Loaded(null, if (e is OutOfMemoryError) "Not enough memory for this model" else "Could not load this model")
        }

    private fun loadModel(src: ModelSource, name: String, extra: File?, key: ByteArray?, cancelled: () -> Boolean): Loaded {
        val ext = name.substringAfterLast('.', "").lowercase()
        val parsed = runCatching { ModelParser.parse(src.read(name), ext) }.getOrNull()
            ?: return Loaded(null, "Could not read the meshes in this file")

        val needed = parsed.meshes.mapNotNull { it.texName?.lowercase() }.toMutableSet()
        val total = needed.size
        val images = HashMap<String, Textures.Image>()

        for (info in parsed.embedded) {
            val n = info.name.lowercase()
            if (n !in needed) continue
            val img = runCatching { Textures.decode(parsed.rsc, info, MAX_TEX) }.getOrNull() ?: continue
            images[n] = img
            needed.remove(n)
        }

        if (needed.isNotEmpty()) {
            val candidates = (if (extra != null) TexSources.folder(extra, key) else emptyList()) + src.textures(name)
            for (c in candidates) {
                if (cancelled() || needed.isEmpty()) break
                val rsc = runCatching { Rsc.open(c.read()) }.getOrNull() ?: continue
                val dict = runCatching { Textures.dictionary(rsc, 0) }.getOrDefault(emptyList())
                for (info in dict) {
                    val n = info.name.lowercase()
                    if (n !in needed) continue
                    val img = runCatching { Textures.decode(rsc, info, MAX_TEX) }.getOrNull() ?: continue
                    images[n] = img
                    needed.remove(n)
                }
            }
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var vertices = 0
        val parts = parsed.meshes.map { m ->
            val p = m.positions
            vertices += p.size / 3
            var i = 0
            while (i < p.size) {
                if (p[i] < minX) minX = p[i]
                if (p[i] > maxX) maxX = p[i]
                if (p[i + 1] < minY) minY = p[i + 1]
                if (p[i + 1] > maxY) maxY = p[i + 1]
                if (p[i + 2] < minZ) minZ = p[i + 2]
                if (p[i + 2] > maxZ) maxZ = p[i + 2]
                i += 3
            }
            Part(m.positions, m.normals, m.uvs, m.indices, m.texName?.let { images[it.lowercase()] })
        }
        val hx = (maxX - minX) / 2
        val hy = (maxY - minY) / 2
        val hz = (maxZ - minZ) / 2
        val radius = Math.sqrt((hx * hx + hy * hy + hz * hz).toDouble()).toFloat().coerceAtLeast(0.01f)
        val scene = Scene(parts, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, radius)

        val textureInfo = if (total == 0) "no textures used" else "textures ${total - needed.size}/$total"
        return Loaded(scene, "${parts.size} meshes · $vertices vertices · $textureInfo")
    }
}
