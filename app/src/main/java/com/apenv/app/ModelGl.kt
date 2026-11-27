package com.apenv.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val VERTEX_SRC = """#version 300 es
uniform mat4 uMvp;
uniform mat3 uNormalMat;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vNormal = uNormalMat * aNormal;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

private const val FRAGMENT_SRC = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uHasTex;
in vec3 vNormal;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec4 base = uHasTex > 0.5 ? texture(uTex, vUv) : vec4(0.62, 0.66, 0.68, 1.0);
    if (base.a < 0.1) discard;
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    float key = max(dot(n, normalize(vec3(0.35, 0.55, 0.75))), 0.0);
    float fill = max(dot(n, normalize(vec3(-0.6, -0.2, 0.3))), 0.0);
    float sky = 0.5 + 0.5 * n.y;
    vec3 c = base.rgb * (0.28 + 0.22 * sky + 0.55 * key + 0.15 * fill);
    outColor = vec4(c, 1.0);
}
"""

// Orbit viewer: drag to turn the model, pinch to zoom. It coasts after a flick and turns slowly when left alone.
class ModelGlView(context: Context) : GLSurfaceView(context) {
    private val renderer = ModelRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoom(1f / detector.scaleFactor)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun show(scene: Scene?) {
        renderer.pending = scene
        renderer.hasPending = true
    }

    fun resetView() = renderer.reset()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                dragging = true
                renderer.touch(true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1 && !scaler.isInProgress) {
                renderer.drag(e.x - lastX, e.y - lastY)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i)
                lastY = e.getY(i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                renderer.touch(false)
            }
        }
        return true
    }
}

private class ModelRenderer : GLSurfaceView.Renderer {
    @Volatile var pending: Scene? = null
    @Volatile var hasPending = false
    @Volatile private var yaw = -55f
    @Volatile private var pitch = 14f
    @Volatile private var dist = 5f
    @Volatile private var velocity = 0f
    @Volatile private var touching = false
    @Volatile private var lastTouch = 0L

    private class Gpu(val vbo: Int, val ibo: Int, val count: Int, val tex: Int)

    private var baseDist = 5f
    private var scene: Scene? = null
    private var gpu: List<Gpu> = emptyList()
    private var program = 0
    private var uMvp = 0
    private var uNormal = 0
    private var uTex = 0
    private var uHas = 0
    private var aspect = 1f
    private var lastFrame = 0L
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)
    private val normalMat = FloatArray(9)

    fun touch(down: Boolean) {
        touching = down
        if (down) velocity = 0f else lastTouch = SystemClock.uptimeMillis()
    }

    fun drag(dx: Float, dy: Float) {
        velocity = -dx * 0.35f
        yaw -= dx * 0.35f
        pitch = (pitch + dy * 0.35f).coerceIn(-85f, 85f)
    }

    fun zoom(factor: Float) {
        dist = (dist * factor).coerceIn(baseDist * 0.25f, baseDist * 4f)
    }

    fun reset() {
        scene?.let { fit(it) }
    }

    private fun fit(s: Scene) {
        baseDist = s.radius / sin(Math.toRadians(22.5)).toFloat() * 1.1f
        dist = baseDist
        yaw = -55f
        pitch = 14f
        velocity = 0f
        lastTouch = SystemClock.uptimeMillis()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.055f, 0.078f, 0.078f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        program = build()
        if (program != 0) {
            uMvp = GLES30.glGetUniformLocation(program, "uMvp")
            uNormal = GLES30.glGetUniformLocation(program, "uNormalMat")
            uTex = GLES30.glGetUniformLocation(program, "uTex")
            uHas = GLES30.glGetUniformLocation(program, "uHasTex")
        }
        // A lost context took the old buffers with it, so the scene is uploaded again.
        gpu = emptyList()
        if (scene != null) {
            pending = scene
            hasPending = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / maxOf(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (hasPending) {
            hasPending = false
            release()
            val s = pending
            scene = s
            if (s != null) {
                gpu = upload(s)
                fit(s)
            }
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = scene ?: return
        if (program == 0) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        if (!touching) {
            if (abs(velocity) > 0.02f) {
                yaw += velocity
                velocity *= 0.92f
            } else if (now - lastTouch > 2500) {
                yaw += 10f * dt
            }
        }

        val d = dist
        val p = Math.toRadians(pitch.toDouble())
        val y = Math.toRadians(yaw.toDouble())
        val ex = s.cx + (d * cos(p) * cos(y)).toFloat()
        val ey = s.cy + (d * cos(p) * sin(y)).toFloat()
        val ez = s.cz + (d * sin(p)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, s.cx, s.cy, s.cz, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 45f, aspect, maxOf(0.01f, s.radius * 0.05f), d + s.radius * 4f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        normalMat[0] = view[0]; normalMat[1] = view[1]; normalMat[2] = view[2]
        normalMat[3] = view[4]; normalMat[4] = view[5]; normalMat[5] = view[6]
        normalMat[6] = view[8]; normalMat[7] = view[9]; normalMat[8] = view[10]

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES30.glUniformMatrix3fv(uNormal, 1, false, normalMat, 0)
        GLES30.glUniform1i(uTex, 0)
        for (g in gpu) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            if (g.tex != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.tex)
                GLES30.glUniform1f(uHas, 1f)
            } else {
                GLES30.glUniform1f(uHas, 0f)
            }
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.count, GLES30.GL_UNSIGNED_INT, 0)
        }
    }

    private fun upload(s: Scene): List<Gpu> {
        val textures = HashMap<Textures.Image, Int>()
        return s.parts.map { part ->
            val n = part.positions.size / 3
            val data = FloatArray(n * 8)
            for (i in 0 until n) {
                data[i * 8] = part.positions[i * 3]
                data[i * 8 + 1] = part.positions[i * 3 + 1]
                data[i * 8 + 2] = part.positions[i * 3 + 2]
                data[i * 8 + 3] = part.normals[i * 3]
                data[i * 8 + 4] = part.normals[i * 3 + 1]
                data[i * 8 + 5] = part.normals[i * 3 + 2]
                data[i * 8 + 6] = part.uvs[i * 2]
                data[i * 8 + 7] = part.uvs[i * 2 + 1]
            }
            val vertexBuf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuf.put(data).position(0)
            val indexBuf = ByteBuffer.allocateDirect(part.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            indexBuf.put(part.indices).position(0)

            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, vertexBuf, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, part.indices.size * 4, indexBuf, GLES30.GL_STATIC_DRAW)

            val img = part.texture
            val tex = if (img == null) 0 else textures.getOrPut(img) { makeTexture(img) }
            Gpu(ids[0], ids[1], part.indices.size, tex)
        }
    }

    private fun makeTexture(img: Textures.Image): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val pixels = ByteBuffer.allocateDirect(img.rgba.size)
        pixels.put(img.rgba).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, img.w, img.h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return ids[0]
    }

    private fun release() {
        val done = HashSet<Int>()
        for (g in gpu) {
            GLES30.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0)
            if (g.tex != 0 && done.add(g.tex)) GLES30.glDeleteTextures(1, intArrayOf(g.tex), 0)
        }
        gpu = emptyList()
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun build(): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SRC)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }
}
