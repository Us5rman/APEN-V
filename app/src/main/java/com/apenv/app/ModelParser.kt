package com.apenv.app

class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray
) {
    var texName: String? = null
}

class ParsedModel(val rsc: Rsc, val meshes: List<MeshData>, val embedded: List<Textures.Info>)

// Reads the meshes out of .ydr (drawable), .ydd (drawable dictionary) and .yft (fragment) resources.
// Drawables are found by their structure, so one reader serves all three file types.
object ModelParser {
    private val DIFFUSE = joaat("diffusesampler")

    fun joaat(text: String): Int {
        var h = 0
        for (c in text.lowercase()) {
            h += c.code
            h += h shl 10
            h = h xor (h ushr 6)
        }
        h += h shl 3
        h = h xor (h ushr 11)
        h += h shl 15
        return h
    }

    private class Candidate(val meshes: List<MeshData>, val vertices: Int, val dict: Int)

    fun parse(data: ByteArray, ext: String): ParsedModel? {
        val r = Rsc.open(data) ?: return null
        var best: Candidate? = null
        if (ext == "ydr") best = tryDrawable(r, 0)
        if (best == null) {
            var o = 0
            while (o + 0xA0 <= r.sysSize) {
                if (looksLikeDrawable(r, o)) {
                    val c = tryDrawable(r, o)
                    if (c != null && (best == null || c.vertices > best.vertices)) best = c
                }
                o += 16
            }
        }
        val found = best ?: return null
        val embedded = runCatching { Textures.dictionary(r, found.dict) }.getOrDefault(emptyList())
        return ParsedModel(r, found.meshes, embedded)
    }

    private fun looksLikeDrawable(r: Rsc, o: Int): Boolean {
        if (r.virt(r.u64(o + 0x10)) < 0) return false
        var any = false
        for (k in 0..3) if (r.virt(r.u64(o + 0x50 + 8 * k)) >= 0) any = true
        if (!any) return false
        val radius = r.f32(o + 0x2C)
        return radius > 0f && radius < 100000f
    }

    private fun tryDrawable(r: Rsc, o: Int): Candidate? = try {
        drawable(r, o)
    } catch (e: Exception) {
        null
    }

    private fun drawable(r: Rsc, o: Int): Candidate? {
        if (o < 0 || o + 0xA0 > r.sysSize) return null
        val group = r.virt(r.u64(o + 0x10))
        if (group < 0) return null
        val shaderNames = shaderTextures(r, group)

        var list = -1
        for (k in 0..3) {
            val v = r.virt(r.u64(o + 0x50 + 8 * k))
            if (v >= 0) {
                list = v
                break
            }
        }
        if (list < 0) return null
        val arr = r.virt(r.u64(list))
        val count = r.u16(list + 8)
        if (arr < 0 || count !in 1..64) return null

        val meshes = ArrayList<MeshData>()
        for (m in 0 until count) {
            val model = r.virt(r.u64(arr + 8 * m))
            if (model < 0) continue
            val geoArr = r.virt(r.u64(model + 8))
            val geoCount = r.u16(model + 0x10)
            val map = r.virt(r.u64(model + 0x20))
            if (geoArr < 0 || geoCount !in 1..128) continue
            for (g in 0 until geoCount) {
                val geo = r.virt(r.u64(geoArr + 8 * g))
                val mesh = if (geo < 0) null else geometry(r, geo)
                if (mesh == null) continue
                val shader = if (map >= 0) r.u16(map + 2 * g) else 0
                mesh.texName = shaderNames.getOrNull(shader)
                meshes += mesh
            }
        }
        if (meshes.isEmpty()) return null
        return Candidate(meshes, meshes.sumOf { it.positions.size / 3 }, r.virt(r.u64(group + 8)))
    }

    private fun shaderTextures(r: Rsc, group: Int): List<String?> {
        val arr = r.virt(r.u64(group + 0x10))
        val count = r.u16(group + 0x18)
        if (arr < 0 || count !in 1..256) return emptyList()
        return List(count) { i ->
            val shader = r.virt(r.u64(arr + 8 * i))
            if (shader < 0) null else diffuseName(r, shader)
        }
    }

    // The diffuse texture name of a shader, or the first texture it uses when none is marked diffuse.
    private fun diffuseName(r: Rsc, shader: Int): String? {
        val block = r.virt(r.u64(shader))
        val n = r.u8(shader + 0x10)
        if (block < 0 || n !in 1..64) return null
        var first: String? = null
        for (p in 0 until n) {
            val e = block + 16 * p
            if (r.u8(e) != 0) continue
            val tex = r.virt(r.u64(e + 8))
            if (tex < 0) continue
            val name = r.text(r.virt(r.u64(tex + 0x28)))
            if (name.isEmpty() || !name.all { it.code in 32..126 }) continue
            if (first == null) first = name
            if (r.i32(block + 16 * n + 4 * p) == DIFFUSE) return name
        }
        return first
    }

    private fun typeSize(t: Int): Int = when (t) {
        1, 2, 4, 8, 9, 10, 11 -> 4
        3, 5 -> 8
        6 -> 12
        7 -> 16
        else -> 0
    }

    private fun validDecl(r: Rsc, d: Int, stride: Int): Boolean {
        if (d < 0 || d + 16 > r.sysSize) return false
        if (r.u16(d + 4) != stride) return false
        if ((r.u32(d) and 1L) == 0L) return false
        return (r.u64(d + 8) and 0xF) == 6L
    }

    private fun geometry(r: Rsc, g: Int): MeshData? {
        val vb = r.virt(r.u64(g + 0x18))
        val ib = r.virt(r.u64(g + 0x38))
        if (vb < 0 || ib < 0) return null
        val stride = r.u16(vb + 8)
        val vcount = r.u32(vb + 0x18).toInt()
        if (stride !in 8..160 || vcount !in 3..400000) return null

        var vdata = r.phys(r.u64(vb + 0x10))
        if (vdata < 0) vdata = r.phys(r.u64(vb + 0x20))
        var decl = r.virt(r.u64(vb + 0x30))
        if (!validDecl(r, decl, stride)) decl = r.virt(r.u64(vb + 0x28))
        if (vdata < 0 || !validDecl(r, decl, stride)) return null
        if (vdata.toLong() + vcount.toLong() * stride > r.raw.size) return null

        val icount = r.u32(ib + 8).toInt()
        val idata = r.phys(r.u64(ib + 0x10))
        if (idata < 0 || icount < 3 || icount % 3 != 0 || icount > 3_000_000) return null
        if (idata.toLong() + icount * 2L > r.raw.size) return null

        val flags = r.u32(decl).toInt()
        val types = r.u64(decl + 8)
        val offs = IntArray(16) { -1 }
        val kinds = IntArray(16)
        var at = 0
        for (s in 0 until 16) {
            if (((flags shr s) and 1) == 0) continue
            val t = ((types ushr (4 * s)) and 0xF).toInt()
            offs[s] = at
            kinds[s] = t
            at += typeSize(t)
        }
        if (offs[0] < 0 || kinds[0] != 6 || at > stride) return null

        val hasNormal = offs[3] >= 0 && (kinds[3] == 6 || kinds[3] == 7 || kinds[3] == 10)
        val hasUv = offs[6] >= 0 && (kinds[6] == 5 || kinds[6] == 1)
        val pos = FloatArray(vcount * 3)
        val nrm = FloatArray(vcount * 3)
        val uv = FloatArray(vcount * 2)
        for (i in 0 until vcount) {
            val b = vdata + i * stride
            val p = b + offs[0]
            for (k in 0..2) {
                val v = r.f32(p + 4 * k)
                if (v.isNaN() || v.isInfinite() || v > 1_000_000f || v < -1_000_000f) return null
                pos[3 * i + k] = v
            }
            if (hasNormal) {
                val n = b + offs[3]
                if (kinds[3] == 10) {
                    val v = r.i32(n)
                    nrm[3 * i] = ((v shl 22) shr 22) / 511f
                    nrm[3 * i + 1] = ((v shl 12) shr 22) / 511f
                    nrm[3 * i + 2] = ((v shl 2) shr 22) / 511f
                } else {
                    for (k in 0..2) nrm[3 * i + k] = finite(r.f32(n + 4 * k))
                }
            }
            if (hasUv) {
                val t = b + offs[6]
                if (kinds[6] == 5) {
                    uv[2 * i] = finite(r.f32(t))
                    uv[2 * i + 1] = finite(r.f32(t + 4))
                } else {
                    uv[2 * i] = half(r.u16(t))
                    uv[2 * i + 1] = half(r.u16(t + 2))
                }
            }
        }

        val idx = IntArray(icount)
        for (i in 0 until icount) {
            val v = r.u16(idata + 2 * i)
            if (v >= vcount) return null
            idx[i] = v
        }
        if (!hasNormal) computeNormals(pos, idx, nrm)
        return MeshData(pos, nrm, uv, idx)
    }

    private fun finite(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v

    private fun half(h: Int): Float {
        val sign = (h shr 15) and 1
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        val f = when (e) {
            0 -> m / 1024f * 6.1035156E-5f
            31 -> 0f
            else -> (1f + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
        }
        return if (sign == 1) -f else f
    }

    private fun computeNormals(pos: FloatArray, idx: IntArray, out: FloatArray) {
        var i = 0
        while (i + 2 < idx.size) {
            val a = idx[i] * 3
            val b = idx[i + 1] * 3
            val c = idx[i + 2] * 3
            val ux = pos[b] - pos[a]
            val uy = pos[b + 1] - pos[a + 1]
            val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]
            val vy = pos[c + 1] - pos[a + 1]
            val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) {
                out[v] += nx
                out[v + 1] += ny
                out[v + 2] += nz
            }
            i += 3
        }
        var k = 0
        while (k + 2 < out.size) {
            val len = Math.sqrt((out[k] * out[k] + out[k + 1] * out[k + 1] + out[k + 2] * out[k + 2]).toDouble()).toFloat()
            if (len > 0f) {
                out[k] /= len
                out[k + 1] /= len
                out[k + 2] /= len
            }
            k += 3
        }
    }
}
