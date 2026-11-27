package com.apenv.app

// JNI bridge to the ARM astcenc encoder (see src/main/cpp).
object Astc {
    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("apenastc")
            true
        } catch (e: Throwable) {
            false
        }
    }

    val available: Boolean get() = loaded

    @JvmStatic external fun create(bx: Int, by: Int, threads: Int, quality: Float): Long
    @JvmStatic external fun encode(handle: Long, rgba: ByteArray, w: Int, h: Int): ByteArray?
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun decode(bx: Int, by: Int, data: ByteArray, w: Int, h: Int): ByteArray?
    @JvmStatic external fun free(handle: Long)
}
