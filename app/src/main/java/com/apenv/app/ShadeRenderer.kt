package com.apenv.app

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ShadePass(
    val fx: FxDef?,
    val values: Map<String, String>,
    val extra: Map<String, String> = emptyMap(),   // preset-wide definitions (e.g. LUT_TileSizeXY)
    val on: () -> Boolean = { true }
)

// Takes captured game frames (as an external texture), runs them through the preset's passes and
// draws the result to the overlay surface.
internal class ShadeRenderer(
    private val out: Surface,
    private var w: Int,
    private var h: Int,
    private val passes: List<ShadePass>,
    private val lutPath: String?,
    private val onSource: (Surface) -> Unit,
    private val onError: (String) -> Unit
) {
    private val thread = HandlerThread("shade-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var st: SurfaceTexture? = null
    private var srcSurface: Surface? = null
    private var oesTex = 0
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var oesProg = 0
    private class Prog(val id: Int, val pass: ShadePass)
    private val progs = mutableListOf<Prog>()
    private var identProg: Prog? = null
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val ident = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val tm = FloatArray(16)
    private var released = false
    private var reported = false
    private var lutTex = 0
    private val autoLut = HashMap<String, String>()
    private val t0 = SystemClock.elapsedRealtime()

    fun start() { handler.post { try { init() } catch (e: Throwable) { onError(e.message ?: e.toString()) } } }

    fun stop() {
        handler.post { release(); thread.quitSafely() }
    }

    private fun init() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(dpy, v, 0, v, 1)
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0)
        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], out, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        allocTargets()
        loadLut()

        oesProg = link(VERT, OES_FRAG)
        identProg = Prog(link(VERT, fragFor(null)), ShadePass(null, emptyMap()))
        for (p in passes) {
            try { progs += Prog(link(VERT, fragFor(p.fx)), p) }
            catch (e: Throwable) { onError("An effect failed to compile: ${e.message}") }
        }

        val s = SurfaceTexture(oesTex).apply { setDefaultBufferSize(w, h) }
        st = s
        s.setOnFrameAvailableListener({ draw() }, handler)
        srcSurface = Surface(s)
        onSource(srcSurface!!)
    }

    // The LUT image (PNG/JPG). Strip LUTs (width = tiles x height) get their tile size detected.
    private fun loadLut() {
        val path = lutPath ?: return
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(path, opts)
        if (bmp == null) { fail("Could not read the LUT image"); return }
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        lutTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (bmp.height > 0 && bmp.width % bmp.height == 0) {
            autoLut["LUT_TileSizeXY"] = bmp.height.toString()
            autoLut["LUT_TileAmount"] = (bmp.width / bmp.height).toString()
        }
        bmp.recycle()
    }

    // (Re)creates the two ping-pong targets at the current capture size.
    private fun allocTargets() {
        if (tex[0] != 0) {
            GLES30.glDeleteTextures(2, tex, 0)
            GLES30.glDeleteFramebuffers(2, fbo, 0)
        }
        GLES30.glGenTextures(2, tex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            var levels = 1
            var m = maxOf(w, h)
            while (m > 1) { m = m shr 1; levels++ }
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
    }

    // The screen rotated or changed size: the capture and every intermediate target must follow it,
    // otherwise the picture is stretched, rotated or tiled.
    fun resize(nw: Int, nh: Int) {
        handler.post {
            if (released || nw <= 0 || nh <= 0 || (nw == w && nh == h)) return@post
            w = nw
            h = nh
            runCatching {
                allocTargets()
                st?.setDefaultBufferSize(w, h)
            }.onFailure { fail("Resize failed: ${it.message}") }
        }
    }

    private fun fail(msg: String) {
        if (reported) return
        reported = true
        onError(msg)
    }

    private fun fragFor(fx: FxDef?): String {
        val sb = StringBuilder()
        sb.append("#version 300 es\nprecision highp float;\nuniform sampler2D uTex;\nuniform vec2 uPx;\nuniform float uTime;\n")
        if (fx?.lut == true) sb.append("uniform sampler2D uLut;\n")
        sb.append("in vec2 vUv;\nout vec4 oColor;\n")
        sb.append("vec3 smp(vec2 uv){ return textureLod(uTex, uv, 0.0).rgb; }\nvec3 smpL(vec2 uv, float lod){ return textureLod(uTex, uv, lod).rgb; }\nfloat lum(vec3 c){ return dot(c, vec3(0.2126,0.7152,0.0722)); }\n")
        fx?.params?.forEach { sb.append("uniform ").append(if (it.vec) "vec3 " else "float ").append(it.key).append(";\n") }
        sb.append(fx?.body ?: "vec3 effect(vec3 c, vec2 uv){ return c; }").append("\n")
        sb.append("void main(){ vec3 c = smp(vUv); oColor = vec4(clamp(effect(c, vUv), 0.0, 1.0), 1.0); }\n")
        return sb.toString()
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES30.glGetShaderInfoLog(s).take(200))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES30.glGetProgramInfoLog(p).take(200))
        return p
    }

    private fun setUniforms(p: Prog) {
        GLES30.glUniform2f(GLES30.glGetUniformLocation(p.id, "uPx"), 1f / w, 1f / h)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p.id, "uTime"), ((SystemClock.elapsedRealtime() - t0) % 100000L) / 1000f)
        p.pass.fx?.params?.forEach { prm ->
            val loc = GLES30.glGetUniformLocation(p.id, prm.key)
            if (loc < 0) return@forEach
            val raw = p.pass.values[prm.key] ?: p.pass.extra[prm.key] ?: autoLut[prm.key] ?: prm.def
            val parts = raw.split(',').map { it.trim().toFloatOrNull() ?: 0f }
            if (prm.vec) {
                val a = parts.getOrElse(0) { 0f }
                GLES30.glUniform3f(loc, a, parts.getOrElse(1) { a }, parts.getOrElse(2) { a })
            } else GLES30.glUniform1f(loc, parts.getOrElse(0) { 0f })
        }
    }

    private fun drawQuad() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun draw() {
        if (released) return
        val s = st ?: return
        try {
            s.updateTexImage()
            s.getTransformMatrix(tm)
            val q = IntArray(1)
            EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, q, 0)
            val sw = q[0]
            EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, q, 0)
            val sh = q[0]
            GLES30.glViewport(0, 0, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glUseProgram(oesProg)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(oesProg, "uM"), 1, false, tm, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProg, "uTex"), 0)
            drawQuad()
            var cur = 0
            val active = progs.filter { it.pass.on() }.ifEmpty { listOfNotNull(identProg) }
            for ((i, p) in active.withIndex()) {
                val last = i == active.lastIndex
                val dst = if (last) -1 else 1 - cur
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (last) 0 else fbo[dst])
                if (last && sw > 0 && sh > 0) GLES30.glViewport(0, 0, sw, sh) else GLES30.glViewport(0, 0, w, h)
                GLES30.glUseProgram(p.id)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p.id, "uM"), 1, false, ident, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[cur])
                if (p.pass.fx?.mips == true) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(p.id, "uTex"), 0)
                if (p.pass.fx?.lut == true && lutTex != 0) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
                    GLES30.glUniform1i(GLES30.glGetUniformLocation(p.id, "uLut"), 1)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                }
                setUniforms(p)
                drawQuad()
                if (!last) cur = dst
            }
            EGL14.eglSwapBuffers(dpy, surf)
        } catch (e: Throwable) {
            android.util.Log.e("ShadeRenderer", "draw failed", e)
            fail("Render error: ${e.message}")
        }
    }

    private fun release() {
        released = true
        runCatching { st?.setOnFrameAvailableListener(null) }
        runCatching { srcSurface?.release() }
        runCatching { st?.release() }
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val VERT = "#version 300 es\nin vec2 aPos;\nuniform mat4 uM;\nout vec2 vUv;\n" +
            "void main(){ vUv = (uM * vec4(aPos*0.5+0.5, 0.0, 1.0)).xy; gl_Position = vec4(aPos, 0.0, 1.0); }\n"
        const val OES_FRAG = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\n" +
            "uniform samplerExternalOES uTex;\nin vec2 vUv;\nout vec4 oColor;\nvoid main(){ oColor = vec4(texture(uTex, vUv).rgb, 1.0); }\n"
    }
}
