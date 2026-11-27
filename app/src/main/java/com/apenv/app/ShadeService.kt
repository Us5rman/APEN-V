package com.apenv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf

// Captures the game, runs the preset's shaders over it and shows the result as a full-screen overlay.
// Pick "A single app" (the game) in the capture dialog so the overlay isn't captured itself.
class ShadeService : Service() {
    companion object {
        val running = mutableStateOf(false)
        const val ACTION_STOP = "com.apenv.app.SHADE_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "shade"
    }

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var view: SurfaceView? = null
    private var overlayWm: WindowManager? = null
    private var renderer: ShadeRenderer? = null
    private val main = Handler(Looper.getMainLooper())
    private var curW = 0
    private var curH = 0
    private var dpiNow = 0
    private var listener: DisplayManager.DisplayListener? = null

    // Real size of the screen in its current rotation (the game is usually landscape, this app portrait).
    private fun displaySize(): Pair<Int, Int> {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "ReShade overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ShadeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("APEN V shaders running")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val preset = ShadeStore.current(this)
        if (data == null || preset == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        proj = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = displaySize()
        curW = w
        curH = h
        val dpi = resources.displayMetrics.densityDpi
        dpiNow = dpi
        val lutPath = ShadeStore.lutFile(this).takeIf { it.isFile }?.absolutePath
        val passes = preset.techniques.mapNotNull { (_, file) ->
            if (ShadeFx.status(file) != null) null
            else ShadeFx.def(file)?.let { fx -> ShadePass(fx, preset.sections[file] ?: emptyMap(), preset.defines) { preset.isOn(file) } }
        }

        val acc = ShadeAccessibilityService.instance
        val sv = SurfaceView(acc ?: this)
        view = sv
        var started = false
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (started) return
                started = true
                val r = ShadeRenderer(holder.surface, w, h, passes, lutPath, { src ->
                    vd = mp.createVirtualDisplay("apenv-shade", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, src, null, null)
                }, { msg -> main.post { android.widget.Toast.makeText(this@ShadeService, msg, android.widget.Toast.LENGTH_LONG).show() } })
                renderer = r
                r.start()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        // With the accessibility service on, the overlay is a trusted accessibility overlay: touches pass
        // through to the game and it can stay fully opaque. Without it Android 12+ blocks touches under a
        // normal overlay unless it is at most 80% opaque.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (acc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (acc == null) {
            lp.alpha = 0.79f
            main.post { android.widget.Toast.makeText(this, "Turn on the APEN V accessibility service in ReShade for reliable touch input", android.widget.Toast.LENGTH_LONG).show() }
        }
        // Cover the notch / cutout too, otherwise the overlay is inset and the picture shifts in landscape.
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val owm = acc?.getSystemService(WindowManager::class.java) ?: wm
        overlayWm = owm
        owm.addView(sv, lp)

        // Rotation: resize the capture and the shader targets to match, or the image comes out
        // rotated, squashed or tiled.
        val dl = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val (nw, nh) = displaySize()
                if (nw == curW && nh == curH) return
                curW = nw
                curH = nh
                runCatching { vd?.resize(nw, nh, dpiNow) }
                renderer?.resize(nw, nh)
            }
        }
        listener = dl
        getSystemService(DisplayManager::class.java).registerDisplayListener(dl, main)
        running.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.value = false
        listener?.let { runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } }
        runCatching { renderer?.stop() }
        runCatching { vd?.release() }
        runCatching { proj?.stop() }
        view?.let { v -> runCatching { (overlayWm ?: getSystemService(WindowManager::class.java)).removeView(v) } }
        super.onDestroy()
    }
}
