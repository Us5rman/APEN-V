package com.apenv.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val BLOCK = 512L
private const val CHUNK = 1 shl 22
private val directChunk: ThreadLocal<ByteBuffer> = ThreadLocal.withInitial { ByteBuffer.allocateDirect(CHUNK) }
private const val DIR_MARK = 0x7FFFFF00
private const val RSC_MAGIC = 0x37435352
private const val ENC_AES = 0x0FFFFFF9
private const val ENC_OPEN = 0x4E45504F
private const val LARGE = 0xFFFFFFL
private const val PACK_LIMIT = 32L shl 20

private val resourceExt = setOf(
    "ydr", "ytd", "yft", "ydd", "ybn", "ymap", "ytyp", "ycd",
    "ynv", "ypt", "yld", "ynd", "yed", "ymt", "yvr", "ywr", "ysc"
)

object Aes {
    fun decrypt(key: ByteArray, data: ByteArray) = pass(key, data, Cipher.DECRYPT_MODE)

    fun encrypt(key: ByteArray, data: ByteArray) = pass(key, data, Cipher.ENCRYPT_MODE)

    private fun pass(key: ByteArray, data: ByteArray, mode: Int): ByteArray {
        val len = data.size and 15.inv()
        if (len == 0) return data
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        var cur = data.copyOf(len)
        repeat(16) { cur = cipher.doFinal(cur) }
        return if (len == data.size) cur else cur + data.copyOfRange(len, data.size)
    }
}

sealed class Node(var name: String)

class Dir(name: String) : Node(name) {
    val kids = mutableListOf<Node>()
}

class Leaf(
    name: String,
    val resource: Boolean,
    val sizeField: Long,
    val block: Long,
    val raw: Long,
    val encrypted: Boolean,
    val sys: Long,
    val gfx: Long,
    val diskLen: Long,
    val large: Boolean = false,
    val source: File? = null,
    val sourceSkip: Long = 0
) : Node(name) {
    val shownSize: Long get() = if (resource) diskLen else raw
}

class RpfArchive(val file: File, private val key: ByteArray?, private val backups: Boolean = true) {
    lateinit var root: Dir
        private set

    private var magic = ByteArray(4)
    private var encValue = 0
    private val aes get() = encValue == ENC_AES

    init {
        load()
    }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun align(v: Long) = (v + BLOCK - 1) / BLOCK * BLOCK

    private fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xFFFFFFFFL

    fun load() {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(16)
            raf.readFully(head)
            val hb = le(head)
            val sig = hb.int
            if (sig != 0x52504637 && sig != 0x37465052) throw IOException("Not an RPF7 archive")
            val count = hb.int
            val namesLen = hb.int
            val enc = hb.int
            if (enc != 0 && enc != ENC_OPEN && enc != ENC_AES) {
                throw IOException("This archive uses NG encryption, which is not supported yet")
            }
            if (enc == ENC_AES && key == null) throw IOException("This archive is encrypted. Set the AES key first.")
            var entries = ByteArray(count * 16)
            raf.readFully(entries)
            var names = ByteArray(namesLen)
            raf.readFully(names)
            if (enc == ENC_AES) {
                entries = Aes.decrypt(key!!, entries)
                names = Aes.decrypt(key, names)
            }
            val eb = le(entries)
            if (count == 0 || eb.getInt(4) != DIR_MARK) {
                throw IOException(if (enc == ENC_AES) "Wrong AES key" else "Corrupt archive")
            }
            val tree = readDir("", eb.getInt(8), eb.getInt(12), eb, names, raf, count)
            magic = head.copyOf(4)
            encValue = enc
            root = tree
        }
    }

    private fun nameAt(names: ByteArray, off: Int): String {
        var end = off
        while (end < names.size && names[end] != 0.toByte()) end++
        return String(names, off, end - off, Charsets.UTF_8)
    }

    private fun readDir(
        name: String,
        first: Int,
        count: Int,
        eb: ByteBuffer,
        names: ByteArray,
        raf: RandomAccessFile,
        total: Int
    ): Dir {
        if (first < 0 || count < 0 || first + count > total) throw IOException("Corrupt archive")
        val dir = Dir(name)
        for (i in first until first + count) {
            val y = u32(eb, i * 16)
            val x = u32(eb, i * 16 + 4)
            val a = u32(eb, i * 16 + 8)
            val b = u32(eb, i * 16 + 12)
            if (x == DIR_MARK.toLong()) {
                dir.kids += readDir(nameAt(names, y.toInt()), a.toInt(), b.toInt(), eb, names, raf, total)
                continue
            }
            val nameOff = (y and 0xFFFF).toInt()
            val resource = (x and 0x80000000L) != 0L
            val size = (y ushr 16) or ((x and 0xFF) shl 16)
            val block = (x ushr 8) and 0x7FFFFF
            val leafName = nameAt(names, nameOff)
            if (resource) {
                var disk = size
                val large = size == LARGE
                if (large) {
                    val buf = ByteArray(16)
                    raf.seek(block * BLOCK)
                    raf.readFully(buf)
                    disk = (buf[7].toLong() and 0xFF) or
                        ((buf[14].toLong() and 0xFF) shl 8) or
                        ((buf[5].toLong() and 0xFF) shl 16) or
                        ((buf[2].toLong() and 0xFF) shl 24)
                }
                dir.kids += Leaf(leafName, true, size, block, 0, false, a, b, disk, large)
            } else {
                dir.kids += Leaf(leafName, false, size, block, a, b == 1L, 0, 0, if (size > 0) size else a)
            }
        }
        return dir
    }

    private fun find(from: Dir, path: List<String>): Dir {
        var dir = from
        for (part in path) dir = dir.kids.filterIsInstance<Dir>().first { it.name == part }
        return dir
    }

    fun dirAt(path: List<String>): Dir = runCatching { find(root, path) }.getOrDefault(root)

    fun extractToBytes(leaf: Leaf): ByteArray {
        val out = ByteArrayOutputStream()
        extract(leaf, out)
        return out.toByteArray()
    }

    fun extract(leaf: Leaf, out: OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            val start = leaf.block * BLOCK
            when {
                leaf.resource -> {
                    val skip = if (leaf.large) 16L else 0L
                    out.write(resourceHeader(leaf))
                    pump(raf, start + skip, leaf.diskLen - skip, false, out)
                }
                leaf.sizeField > 0 -> {
                    val inflater = Inflater(true)
                    val sink = InflaterOutputStream(out, inflater, 1 shl 16)
                    pump(raf, start, leaf.diskLen, leaf.encrypted, sink)
                    sink.finish()
                    inflater.end()
                }
                else -> pump(raf, start, leaf.diskLen, leaf.encrypted, out)
            }
        }
    }

    // For scans: reads the stored bytes of an entry that needs no decrypting or inflating straight into
    // direct memory in big pieces. Returns false when the entry needs extract() instead.
    fun scanStored(leaf: Leaf, sink: OutputStream, chunk: (ByteBuffer, Int) -> Unit): Boolean {
        if (!leaf.resource && (leaf.sizeField > 0 || leaf.encrypted)) return false
        val skip = if (leaf.resource && leaf.large) 16L else 0L
        if (leaf.resource) sink.write(resourceHeader(leaf))
        var pos = leaf.block * BLOCK + skip
        var left = leaf.diskLen - skip
        val buf = directChunk.get()!!
        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
            while (left > 0) {
                val n = minOf(left, buf.capacity().toLong()).toInt()
                buf.clear()
                buf.limit(n)
                while (buf.hasRemaining()) {
                    val r = ch.read(buf, pos + buf.position())
                    if (r < 0) throw IOException("Unexpected end of archive")
                }
                chunk(buf, n)
                pos += n
                left -= n
            }
        }
        return true
    }

    private fun resourceHeader(leaf: Leaf): ByteArray {
        val version = ((leaf.sys ushr 28) and 0xF) or (((leaf.gfx ushr 28) and 0xF) shl 4)
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(RSC_MAGIC)
            .putInt(version.toInt())
            .putInt(leaf.sys.toInt())
            .putInt(leaf.gfx.toInt())
            .array()
    }

    private fun pump(raf: RandomAccessFile, pos: Long, length: Long, decrypt: Boolean, out: OutputStream) {
        if (decrypt && !aes) throw IOException("Entry uses NG encryption, which is not supported yet")
        raf.seek(pos)
        val buf = ByteArray(minOf(CHUNK.toLong(), maxOf(length, 1L)).toInt())
        var left = length
        while (left > 0) {
            val n = minOf(left, buf.size.toLong()).toInt()
            raf.readFully(buf, 0, n)
            if (decrypt) {
                val plain = Aes.decrypt(key ?: throw IOException("AES key missing"), buf.copyOf(n))
                out.write(plain, 0, n)
            } else {
                out.write(buf, 0, n)
            }
            left -= n
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank() || name.contains('/') || name.contains('\\')) throw IOException("Invalid name")
    }

    private fun prepareImport(name: String, src: File, temps: MutableList<File>): Leaf {
        checkName(name)
        val length = src.length()
        val head = ByteArray(16)
        if (length >= 16) RandomAccessFile(src, "r").use { it.readFully(head) }
        val hb = le(head)
        if (length >= 16 && hb.getInt(0) == RSC_MAGIC) {
            val payload = length - 16
            if (payload >= LARGE) throw IOException("$name is too large to import")
            return Leaf(name, true, payload, 0, 0, false, u32(hb, 8), u32(hb, 12), payload, false, src, 16)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in resourceExt) throw IOException("$name is not a valid resource file (missing RSC7 header)")
        if (length in 1..PACK_LIMIT && ext != "rpf") {
            val packed = File.createTempFile("rpf", ".z")
            temps += packed
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            DeflaterOutputStream(packed.outputStream().buffered(), deflater).use { out ->
                src.inputStream().buffered().use { it.copyTo(out) }
            }
            deflater.end()
            val packedLen = packed.length()
            if (packedLen in 1L until LARGE) {
                return Leaf(name, false, packedLen, 0, length, false, 0, 0, packedLen, false, packed, 0)
            }
        }
        return Leaf(name, false, 0, 0, length, false, 0, 0, length, false, src, 0)
    }

    fun importFiles(path: List<String>, files: List<File>) = importAs(path, files.map { it.name to it })

    fun importAs(path: List<String>, entries: List<Pair<String, File>>) {
        val temps = mutableListOf<File>()
        try {
            val leaves = entries.map { (name, src) -> prepareImport(name, src, temps) }
            modify { top ->
                val dir = find(top, path)
                for (leaf in leaves) {
                    dir.kids.removeAll { it.name.equals(leaf.name, true) }
                    dir.kids += leaf
                }
            }
        } finally {
            temps.forEach { it.delete() }
        }
    }

    class Replacement(val path: List<String>, val name: String, val file: File)

    // Replace many files in one rewrite, so the archive is backed up and rewritten only once.
    fun replaceMany(items: List<Replacement>) {
        val temps = mutableListOf<File>()
        try {
            val leaves = items.map { it.path to prepareImport(it.name, it.file, temps) }
            modify { top ->
                for ((path, leaf) in leaves) {
                    val dir = find(top, path)
                    dir.kids.removeAll { it.name.equals(leaf.name, true) }
                    dir.kids += leaf
                }
            }
        } finally {
            temps.forEach { it.delete() }
        }
    }

    fun delete(path: List<String>, names: Set<String>) {
        modify { top -> find(top, path).kids.removeAll { it.name in names } }
    }

    fun newFolder(path: List<String>, name: String) {
        checkName(name)
        if (find(root, path).kids.any { it.name.equals(name, true) }) throw IOException("\"$name\" already exists")
        modify { top -> find(top, path).kids += Dir(name) }
    }

    fun rename(path: List<String>, old: String, name: String) {
        checkName(name)
        val dir = find(root, path)
        if (dir.kids.any { it.name.equals(name, true) && it.name != old }) throw IOException("\"$name\" already exists")
        modify { top -> find(top, path).kids.first { it.name == old }.name = name }
    }

    private fun modify(edit: (Dir) -> Unit) {
        if (backups) Backups.create(file)
        try {
            edit(root)
            rewrite()
        } finally {
            load()
        }
    }

    private class Slot(val node: Node) {
        var first = 0
    }

    private fun rewrite() {
        val slots = ArrayList<Slot>()
        slots += Slot(root)
        var i = 0
        while (i < slots.size) {
            val node = slots[i].node
            if (node is Dir) {
                slots[i].first = slots.size
                node.kids.sortedBy { it.name.lowercase() }.forEach { slots += Slot(it) }
            }
            i++
        }

        val nameBytes = ByteArrayOutputStream()
        val seen = HashMap<String, Int>()
        val nameOff = IntArray(slots.size)
        slots.forEachIndexed { idx, slot ->
            nameOff[idx] = seen.getOrPut(slot.node.name) {
                val at = nameBytes.size()
                nameBytes.write(slot.node.name.toByteArray(Charsets.UTF_8))
                nameBytes.write(0)
                at
            }
            if (slot.node is Leaf && nameOff[idx] > 0xFFFF) throw IOException("Name table too large")
        }
        val namesLen = (nameBytes.size() + 15) and 15.inv()
        val names = nameBytes.toByteArray().copyOf(namesLen)
        val count = slots.size
        val dataStart = align(16L + count * 16 + namesLen)
        val blocks = LongArray(count)
        val tmp = File(file.parentFile, file.name + ".tmp")

        try {
            RandomAccessFile(file, "r").use { src ->
                RandomAccessFile(tmp, "rw").use { out ->
                    out.setLength(0)
                    out.seek(dataStart)
                    slots.forEachIndexed { idx, slot ->
                        val leaf = slot.node as? Leaf ?: return@forEachIndexed
                        val pos = align(out.filePointer)
                        if (pos / BLOCK >= 0x800000) throw IOException("Archive would exceed the 4 GB limit")
                        out.seek(pos)
                        blocks[idx] = pos / BLOCK
                        if (leaf.source != null) {
                            RandomAccessFile(leaf.source, "r").use { copy(it, leaf.sourceSkip, leaf.diskLen, out) }
                        } else {
                            copy(src, leaf.block * BLOCK, leaf.diskLen, out)
                        }
                    }
                    out.setLength(align(maxOf(out.filePointer, dataStart)))

                    val eb = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN)
                    slots.forEachIndexed { idx, slot ->
                        when (val n = slot.node) {
                            is Dir -> {
                                eb.putInt(nameOff[idx])
                                eb.putInt(DIR_MARK)
                                eb.putInt(slot.first)
                                eb.putInt(n.kids.size)
                            }
                            is Leaf -> {
                                val y = (nameOff[idx].toLong() and 0xFFFF) or ((n.sizeField and 0xFFFF) shl 16)
                                val flag = if (n.resource) 0x800000L else 0L
                                val x = ((n.sizeField ushr 16) and 0xFF) or ((blocks[idx] or flag) shl 8)
                                eb.putInt(y.toInt())
                                eb.putInt(x.toInt())
                                if (n.resource) {
                                    eb.putInt(n.sys.toInt())
                                    eb.putInt(n.gfx.toInt())
                                } else {
                                    eb.putInt(n.raw.toInt())
                                    eb.putInt(if (n.encrypted) 1 else 0)
                                }
                            }
                        }
                    }
                    var entryBytes = eb.array()
                    var nameTable = names
                    if (aes) {
                        entryBytes = Aes.encrypt(key!!, entryBytes)
                        nameTable = Aes.encrypt(key, nameTable)
                    }
                    val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    head.put(magic).putInt(count).putInt(namesLen).putInt(encValue)
                    out.seek(0)
                    out.write(head.array())
                    out.write(entryBytes)
                    out.write(nameTable)
                    out.fd.sync()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }

        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace the archive. A backup was kept.")
        }
    }

    private fun copy(src: RandomAccessFile, pos: Long, length: Long, out: RandomAccessFile) {
        src.seek(pos)
        val buf = ByteArray(CHUNK)
        var left = length
        while (left > 0) {
            val n = minOf(left, CHUNK.toLong()).toInt()
            src.readFully(buf, 0, n)
            out.write(buf, 0, n)
            left -= n
        }
    }
}
