package com.apenv.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Decryption for GTA V "NG" archives. The key and table files are not part of the app. They are
// imported from files you supply (menu, Import keys) and kept in the app's private folder.
//
// An NG archive scrambles its table of contents, its name table and any entry marked as
// encrypted, in independent 16-byte blocks. Which of the 101 keys is used depends on the
// file's name and size, so a nested archive must be opened under its original name.
object NgCrypto {
    private const val KEYS = 101
    private const val KEY_WORDS = 68
    private const val ROUNDS = 17
    private const val TABLE_WORDS = ROUNDS * 16 * 256

    @Volatile private var keys: IntArray? = null
    @Volatile private var tables: IntArray? = null
    @Volatile private var lut: ByteArray? = null

    val ready: Boolean get() = keys != null && tables != null

    fun status(): String = when {
        ready -> "NG keys loaded"
        keys == null && tables == null -> "NG keys and tables missing"
        keys == null -> "NG keys missing"
        else -> "NG tables missing"
    }

    private fun words(b: ByteArray, off: Int, n: Int): IntArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(n) { bb.getInt(off + it * 4) }
    }

    // Takes either the key file (101 keys of 272 bytes) or the tables file (17 x 16 x 256 words,
    // optionally followed by a 256-byte name table). Returns what it was, or null if it is neither.
    fun accept(blob: ByteArray): String? = when {
        blob.size == KEYS * KEY_WORDS * 4 -> {
            keys = words(blob, 0, KEYS * KEY_WORDS)
            "NG keys"
        }
        blob.size >= TABLE_WORDS * 4 -> {
            tables = words(blob, 0, TABLE_WORDS)
            if (blob.size >= TABLE_WORDS * 4 + 256) lut = blob.copyOfRange(TABLE_WORDS * 4, TABLE_WORDS * 4 + 256)
            "NG tables"
        }
        else -> null
    }

    fun loadFrom(dir: File) {
        for (n in listOf("ng_key.dat", "ng_tables.dat")) {
            val f = File(dir, n)
            if (f.isFile) runCatching { accept(f.readBytes()) }
        }
    }

    // Also picks up key files placed in the app's own assets folder (assets/keys/), if any.
    fun loadAssets(ctx: Context) {
        runCatching {
            for (n in ctx.assets.list("keys") ?: emptyArray()) {
                ctx.assets.open("keys/$n").use { accept(it.readBytes()) }
            }
        }
    }

    private fun defaultLut(): ByteArray = ByteArray(256) {
        when (it) {
            in 'A'.code..'Z'.code -> (it + 32).toByte()
            '\\'.code -> '/'.code.toByte()
            else -> it.toByte()
        }
    }

    // The game's name hash, using the lower-casing table.
    private fun hash(name: String): Long {
        val l = lut ?: defaultLut()
        var result = 0
        for (ch in name) {
            val t = 1025 * ((l[ch.code and 255].toInt() and 255) + result)
            result = (t ushr 6) xor t
        }
        return (32769 * (((9 * result) ushr 11) xor (9 * result))).toLong() and 0xFFFFFFFFL
    }

    private fun keyIndex(name: String, length: Long): Int =
        (((hash(name) + length + (KEYS - 40)) and 0xFFFFFFFFL) % KEYS).toInt()

    fun decrypt(data: ByteArray, name: String, length: Long): ByteArray {
        val k = keys ?: throw IOException("The NG keys are not loaded. Use Import keys first.")
        val t = tables ?: throw IOException("The NG tables are not loaded. Use Import keys first.")
        val out = data.copyOf()
        val kb = keyIndex(name, length) * KEY_WORDS
        val s = IntArray(16)
        var o = 0
        val end = out.size and 15.inv()
        while (o < end) {
            for (i in 0 until 16) s[i] = out[o + i].toInt() and 255
            roundA(s, k, kb, t, 0)
            roundA(s, k, kb + 4, t, 1)
            for (r in 2..15) roundB(s, k, kb + 4 * r, t, r)
            roundA(s, k, kb + 64, t, 16)
            for (i in 0 until 16) out[o + i] = s[i].toByte()
            o += 16
        }
        return out
    }

    private fun put(s: IntArray, at: Int, x: Int) {
        s[at] = x and 255
        s[at + 1] = (x ushr 8) and 255
        s[at + 2] = (x ushr 16) and 255
        s[at + 3] = (x ushr 24) and 255
    }

    private fun roundA(s: IntArray, k: IntArray, ko: Int, t: IntArray, r: Int) {
        val b = r * 16 * 256
        val x1 = t[b + s[0]] xor t[b + 256 + s[1]] xor t[b + 512 + s[2]] xor t[b + 768 + s[3]] xor k[ko]
        val x2 = t[b + 1024 + s[4]] xor t[b + 1280 + s[5]] xor t[b + 1536 + s[6]] xor t[b + 1792 + s[7]] xor k[ko + 1]
        val x3 = t[b + 2048 + s[8]] xor t[b + 2304 + s[9]] xor t[b + 2560 + s[10]] xor t[b + 2816 + s[11]] xor k[ko + 2]
        val x4 = t[b + 3072 + s[12]] xor t[b + 3328 + s[13]] xor t[b + 3584 + s[14]] xor t[b + 3840 + s[15]] xor k[ko + 3]
        put(s, 0, x1); put(s, 4, x2); put(s, 8, x3); put(s, 12, x4)
    }

    private fun roundB(s: IntArray, k: IntArray, ko: Int, t: IntArray, r: Int) {
        val b = r * 16 * 256
        val x1 = t[b + s[0]] xor t[b + 1792 + s[7]] xor t[b + 2560 + s[10]] xor t[b + 3328 + s[13]] xor k[ko]
        val x2 = t[b + 256 + s[1]] xor t[b + 1024 + s[4]] xor t[b + 2816 + s[11]] xor t[b + 3584 + s[14]] xor k[ko + 1]
        val x3 = t[b + 512 + s[2]] xor t[b + 1280 + s[5]] xor t[b + 2048 + s[8]] xor t[b + 3840 + s[15]] xor k[ko + 2]
        val x4 = t[b + 768 + s[3]] xor t[b + 1536 + s[6]] xor t[b + 2304 + s[9]] xor t[b + 3072 + s[12]] xor k[ko + 3]
        put(s, 0, x1); put(s, 4, x2); put(s, 8, x3); put(s, 12, x4)
    }
}
