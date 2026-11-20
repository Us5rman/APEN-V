package com.apenv.app

import java.nio.ByteBuffer

// Native byte counting for the game scan (see src/main/cpp/scan_native.cpp). Adds to h[0..255].
object FastHist {
    val ok: Boolean = Astc.available

    @JvmStatic external fun bytes(a: ByteArray, off: Int, len: Int, h: LongArray)
    @JvmStatic external fun direct(b: ByteBuffer, len: Int, h: LongArray)
}
