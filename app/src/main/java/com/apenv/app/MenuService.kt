package com.apenv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// Sends text commands to libshv inside the game: TCP 127.0.0.1:<port>, one line out, one line back
object Shv {
    @Volatile var port = 27015
    val trail = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun log(m: String) {
        trail.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + m)
        while (trail.size > 80) trail.removeAt(0)
    }

    private fun talk(p: Int, cmd: String): String {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", p), 600)
            s.soTimeout = 3000
            s.getOutputStream().apply { write((cmd + "\n").toByteArray(Charsets.UTF_8)); flush() }
            return s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()?.trim()?.ifEmpty { null } ?: "err empty reply"
        }
    }

    // Cheap alive check for the status dot: no logging, no discovery, safe to repeat every 50 ms
    fun probe(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 150)
            s.soTimeout = 400
            s.getOutputStream().apply { write("ping\n".toByteArray(Charsets.UTF_8)); flush() }
            s.getInputStream().read() != -1
        }
    } catch (e: Exception) { false }

    fun discover(): List<Int> {
        val out = linkedSetOf<Int>()
        try {
            val uid = Regex("uid:(\\d+)").find(ModLoader.sh("pm list packages -U ${ModLoader.PKG}"))?.groupValues?.get(1) ?: ""
            log("game uid=$uid")
            ModLoader.sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null").lines().forEach { l ->
                val f = l.trim().split(Regex("\\s+"))
                if (f.size > 7 && f[3] == "0A" && f[7] == uid) f[1].substringAfterLast(':').toIntOrNull(16)?.let { out += it }
            }
            val tcp = ModLoader.sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null").lines()
            log("tcp table: ${tcp.size} lines")
            val P = ModLoader.PKG
            fun chk(title: String, cmd: String): String {
                val raw = ModLoader.sh(cmd)
                val r = raw.lines().filter { it.isNotBlank() }
                log("$title: " + (if (r.isEmpty()) "(nothing)" else r.joinToString(" | ") { it.trim().takeLast(110) }))
                return raw
            }
            chk("wrap property", "getprop wrap.$P:game")
            chk("game processes", "pidof $P $P:game")
            chk("loaded in game", "pid=\$(pidof $P:game); run-as $P cat /proc/\$pid/maps 2>&1 | grep -E 'libshv|libapenv|libopeniv' | awk '{print \$6}' | sort -u | head -4")
            chk("game files", "run-as $P ls /data/user/0/$P/files 2>&1 | head -12")
            chk("menu marker", "ls /sdcard/Android/data/$P/files/shv.enabled 2>&1")
            chk("daemon", "pidof apenv_daemon openiv_daemon")
            val lc = chk("shv log", "logcat -d -s SHV OpenIV-Mods 2>&1 | tail -n 8")
            Regex("127\\.0\\.0\\.1:(\\d+)").findAll(lc).forEach { out += it.groupValues[1].toInt() }
        } catch (e: Throwable) { log("discover failed: ${e.message}") }
        log("listening ports: " + (if (out.isEmpty()) "none" else out.joinToString()))
        return out.toList()
    }

    @Synchronized fun send(cmd: String): String {
        try {
            val r = talk(port, cmd); log("$cmd -> $r"); return r
        } catch (e: Exception) { log("port $port: ${e.javaClass.simpleName}: ${e.message}") }
        for (p in discover()) {
            try { val r = talk(p, cmd); port = p; log("connected on port $p. $cmd -> $r"); return r }
            catch (e: Exception) { log("port $p: ${e.javaClass.simpleName}: ${e.message}") }
        }
        return "err no connection to the game (see Log tab)"
    }
}

private class Owner : LifecycleOwner, SavedStateRegistryOwner {
    val reg = LifecycleRegistry(this)
    val ctl = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ctl.savedStateRegistry
    init { ctl.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
}

class MenuService : Service() {
    private lateinit var wm: WindowManager
    private val owner = Owner()
    private var icon: View? = null
    private var panel: View? = null

    // Toggle states — stored at service level so they survive panel close/reopen
    private var godMode  by mutableStateOf(false)
    private var noWanted by mutableStateOf(false)
    private var infAmmo  by mutableStateOf(false)

    // Connection state: 0=game not loaded (gray), 1=not connected (red), 2=connected (green), 3=issue (yellow)
    private var connState by mutableStateOf(0)

    // Controller: invisible 1×1 focused view that catches gamepad buttons without blocking game touch
    private var ctrlCatcher: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun lp(focus: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focus)
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun compose(c: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setContent(c)
    }

    private fun startFg() {
        val ch = "apenv_menu"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(NotificationChannel(ch, "APEN V Menu", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("APEN V")
            .setContentText("Mod menu active")
            .build()
        startForeground(1, n)
    }

    // Dot color based on connection state: 0=gray, 1=red, 2=green, 3=yellow
    private val dotColorFor: (Int) -> Color = { state ->
        when (state) {
            2    -> GtaGreen                // connected
            1    -> Color(0xFFE5534B)       // not connected to game
            3    -> Color(0xFFE5A93B)       // issue / partial
            else -> Color(0xFF5A6866)       // game not loaded
        }
    }

    override fun onCreate() {
        super.onCreate()
        startFg()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ip = lp(false).apply { x = 20; y = 300 }
        val iv = compose {
            val animDotColor by animateColorAsState(
                targetValue = dotColorFor(connState),
                animationSpec = tween(500), label = "dot"
            )
            Box(Modifier.size(56.dp)) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0xCC0E1414), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(Ic.GTAV, GtaGreen, 32.dp)
                }
                // Status dot — top-left of bubble
                Box(
                    Modifier.size(18.dp).align(Alignment.TopStart)
                        .background(Color(0xFF0A1010), CircleShape)
                        .padding(4.dp)
                        .background(animDotColor, CircleShape)
                )
            }
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        iv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = ip.x; oy = ip.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    ip.x = ox + dx; ip.y = oy + dy; wm.updateViewLayout(iv, ip)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        icon = iv
        wm.addView(iv, ip)
        watch()
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var alive = true

    private fun watch() {
        val t0 = System.currentTimeMillis()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        Thread {
            var seen = false; var gone = 0
            while (alive) {
                var wait = 3000L
                if (!pm.isInteractive) wait = 10000L
                else {
                    val st = try { ModLoader.gameState() } catch (e: Throwable) { -1 }
                    if (st >= 1) { seen = true; gone = 0 }
                    if (st == 0) gone++
                    if (gone >= 2 && (seen || System.currentTimeMillis() - t0 > 90_000)) { ui.post { stopSelf() }; break }
                    if (st >= 0) ui.post {
                        val vis = if (st == 2) View.VISIBLE else View.GONE
                        icon?.visibility = vis; panel?.visibility = vis
                        onScreen = st == 2
                        if (st != 2) connState = 0
                        else if (connState <= 1) pollUntilGreen()
                    }
                    wait = if (st == 2) 4000L else 2500L
                }
                try { Thread.sleep(wait) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    @Volatile private var onScreen = false
    @Volatile private var polling = false

    // Tries every 50 ms until the game answers, then stops so it costs the game nothing.
    private fun pollUntilGreen() {
        if (polling) return
        polling = true
        Thread {
            var shown = -1
            try {
                while (alive && connState <= 1) {
                    if (!onScreen) { Thread.sleep(300); continue }
                    if (Shv.probe()) { ui.post { connState = 2 }; break }
                    if (shown != 1) { shown = 1; ui.post { connState = 1 } }
                    Thread.sleep(50)
                }
            } catch (e: InterruptedException) {
            } finally { polling = false }
        }.start()
    }

    // One check when the panel opens. If the default port is dead it also tries to find the real one.
    private fun pingConnection() {
        Thread {
            val ok = Shv.probe() || !Shv.send("ping").contains("err no connection", ignoreCase = true)
            ui.post { connState = if (ok) 2 else 1 }
            if (!ok) pollUntilGreen()
        }.start()
    }

    private var panelX = 0
    private var panelY = 0

    private fun panelLp() = lp(true).apply {
        gravity = Gravity.CENTER; x = panelX; y = panelY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private fun togglePanel() {
        if (panel != null) { wm.removeView(panel); panel = null; return }
        val p = compose { Panel() }
        panel = p; wm.addView(p, panelLp())
    }

    private fun translateReply(r: String): String = r
        .replace(Regex("неизвестная модель",        RegexOption.IGNORE_CASE), "unknown model")
        .replace(Regex("неизвестная команда",        RegexOption.IGNORE_CASE), "unknown command")
        .replace(Regex("нет подключения к игре",     RegexOption.IGNORE_CASE), "no connection to game")
        .replace(Regex("не найден",                  RegexOption.IGNORE_CASE), "not found")
        .replace(Regex("не удалось",                 RegexOption.IGNORE_CASE), "failed")
        .replace(Regex("ошибка",                     RegexOption.IGNORE_CASE), "error")
        .replace(Regex("недоступно",                 RegexOption.IGNORE_CASE), "unavailable")
        .replace(Regex("загрузка\\.\\.\\.?",         RegexOption.IGNORE_CASE), "loading...")
        .replace(Regex("загружен",                   RegexOption.IGNORE_CASE), "loaded")
        .replace(Regex("загружено",                  RegexOption.IGNORE_CASE), "loaded")
        .replace(Regex("загрузка",                   RegexOption.IGNORE_CASE), "loading")
        .replace(Regex("успешно",                    RegexOption.IGNORE_CASE), "ok")
        .replace(Regex("выполнено",                  RegexOption.IGNORE_CASE), "done")
        .replace(Regex("готово",                     RegexOption.IGNORE_CASE), "done")
        .replace(Regex("включено",                   RegexOption.IGNORE_CASE), "enabled")
        .replace(Regex("выключено",                  RegexOption.IGNORE_CASE), "disabled")
        .replace(Regex("удалено",                    RegexOption.IGNORE_CASE), "deleted")
        .replace(Regex("починено",                   RegexOption.IGNORE_CASE), "repaired")
        .replace(Regex("перевёрнуто",                RegexOption.IGNORE_CASE), "flipped")
        .replace(Regex("перевернуто",                RegexOption.IGNORE_CASE), "flipped")
        .replace(Regex("двигатель остановлен",        RegexOption.IGNORE_CASE), "engine stopped")
        .replace(Regex("взорвано",                   RegexOption.IGNORE_CASE), "exploded")
        .replace(Regex("транспорт заспавнен",        RegexOption.IGNORE_CASE), "vehicle spawned")
        .replace(Regex("транспортное средство",      RegexOption.IGNORE_CASE), "vehicle")
        .replace(Regex("транспорт",                  RegexOption.IGNORE_CASE), "vehicle")
        .replace(Regex("игрок",                      RegexOption.IGNORE_CASE), "player")
        .replace(Regex("телепортация",               RegexOption.IGNORE_CASE), "teleporting")
        .replace(Regex("телепортирован",             RegexOption.IGNORE_CASE), "teleported")
        .replace(Regex("розыск снят",                RegexOption.IGNORE_CASE), "wanted cleared")
        .replace(Regex("розыск",                     RegexOption.IGNORE_CASE), "wanted")
        .replace(Regex("бессмертие",                 RegexOption.IGNORE_CASE), "god mode")
        .replace(Regex("невидимость",                RegexOption.IGNORE_CASE), "invisibility")
        .replace(Regex("скорость бега",              RegexOption.IGNORE_CASE), "run speed")
        .replace(Regex("скорость плавания",          RegexOption.IGNORE_CASE), "swim speed")
        .replace(Regex("суперпрыжок",                RegexOption.IGNORE_CASE), "super jump")
        .replace(Regex("бесконечные патроны",        RegexOption.IGNORE_CASE), "infinite ammo")
        .replace(Regex("оружие выдано",              RegexOption.IGNORE_CASE), "weapon given")
        .replace(Regex("погода",                     RegexOption.IGNORE_CASE), "weather")
        .replace(Regex("время",                      RegexOption.IGNORE_CASE), "time")

    // Every command doubles as a connection check, so there is no extra traffic after the dot is green
    private fun run(cmd: String) {
        Thread {
            val r = translateReply(Shv.send(cmd))
            val lost = r.contains("err no connection", ignoreCase = true)
            ui.post {
                connState = when {
                    lost -> 1
                    r.contains("err", ignoreCase = true) -> 3
                    else -> 2
                }
            }
            if (lost) pollUntilGreen()
        }.start()
    }

    private fun toggle(current: Boolean, cmdOn: String, cmdOff: String, set: (Boolean) -> Unit) {
        val next = !current
        set(next)
        run(if (next) cmdOn else cmdOff)
    }

    // ── Composables ──────────────────────────────────────────────────────────

    @androidx.compose.runtime.Composable
    private fun SectionLabel(text: String) {
        Text(
            text, color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp).fillMaxWidth()
        )
    }

    @androidx.compose.runtime.Composable
    private fun Btn(label: String, cmd: String, mod: Modifier = Modifier, accent: Boolean = false) {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(100), label = "btn_scale")
        val bg by animateColorAsState(
            if (accent) {
                if (pressed) Accent.copy(alpha = 0.75f) else Accent
            } else {
                if (pressed) Bar.copy(alpha = 0.6f) else Bar
            },
            tween(100), label = "btn_bg"
        )
        Box(
            mod.height(46.dp).scale(scale)
                .background(bg, RoundedCornerShape(14.dp))
                .clickable {
                    pressed = true
                    run(cmd)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pressed = false }, 150)
                }
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (accent) Bg else Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Grid(items: List<Pair<String, String>>, cols: Int = 3) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (l, c) -> Btn(l, c, Modifier.weight(1f)) }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }

    // Toggle matching the ModsScreen / app style
    @androidx.compose.runtime.Composable
    private fun Toggle(label: String, sublabel: String, state: Boolean, onToggle: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .clickable { onToggle() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (sublabel.isNotEmpty())
                    Text(sublabel, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(
                checked = state,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor    = Bg,
                    checkedTrackColor    = Accent,
                    uncheckedThumbColor  = Dim,
                    uncheckedTrackColor  = Bar
                )
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Panel() {
        val tabs = listOf("Player", "Teleport", "Vehicle", "World", "Log")
        var tab by remember { mutableStateOf(2) }
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels / dm.density
        val screenH = dm.heightPixels / dm.density
        val wDp = screenW - 8f
        val hDp = minOf(screenH - 8f, screenH)

        // Ping on first open
        androidx.compose.runtime.LaunchedEffect(Unit) { pingConnection() }

        val animDotColor by animateColorAsState(
            targetValue = dotColorFor(connState),
            animationSpec = tween(600, easing = FastOutSlowInEasing), label = "panel_dot"
        )

        Column(
            Modifier.size(width = wDp.dp, height = hDp.dp)
                .background(Color(0xF2101818), RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            // ── Header ──
            Row(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectDragGestures { ch, d ->
                        ch.consume()
                        panelX += d.x.toInt(); panelY += d.y.toInt()
                        panel?.let { v -> wm.updateViewLayout(v, panelLp()) }
                    }
                }.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(animDotColor, CircleShape))
                Text("APEN V", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp))
                Box(Modifier.weight(1f))
                Text("✕", color = Dim, fontSize = 20.sp,
                    modifier = Modifier.clickable { togglePanel() }.padding(start = 4.dp))
            }

            // ── Tab bar ──
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { i, t ->
                    val selAnim by animateColorAsState(
                        if (tab == i) Accent else Bar, tween(200), label = "tab_$i"
                    )
                    val textAnim by animateColorAsState(
                        if (tab == i) Bg else Color.White, tween(200), label = "tabtxt_$i"
                    )
                    Box(
                        Modifier.height(36.dp)
                            .background(selAnim, RoundedCornerShape(18.dp))
                            .clickable { tab = i }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(t, color = textAnim, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                    }
                }
            }

            // ── Tab content ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> PlayerTab()
                    1 -> TeleportTab()
                    2 -> VehiclesTab()
                    3 -> WorldTab()
                    4 -> LogTab()
                }
            }
        }
    }

    // ── Player tab ───────────────────────────────────────────────────────────
    @androidx.compose.runtime.Composable
    private fun PlayerTab() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("PROTECTION")
            Toggle("Invincible", "Infinite health, immune to all damage", godMode) {
                toggle(godMode, "invincible 1", "invincible 0") { godMode = it }
            }
            Toggle("No Wanted Level", "Police ignore you", noWanted) {
                toggle(noWanted, "neverwanted 1", "neverwanted 0") { noWanted = it }
            }
            Toggle("Infinite Ammo", "Never run out of bullets", infAmmo) {
                toggle(infAmmo, "giveweapons ammo", "giveweapons ammo") { infAmmo = it }
            }

            SectionLabel("HEALTH")
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Full Health", "heal",   Modifier.weight(1f))
                Btn("Full Armor",  "armour", Modifier.weight(1f))
            }

            SectionLabel("WEAPONS")
            Btn("All Weapons", "giveweapons all", Modifier.fillMaxWidth())

            SectionLabel("WANTED")
            Grid(listOf(
                "Clear"   to "wanted 0",
                "1 Star"  to "wanted 1",
                "2 Stars" to "wanted 2",
                "3 Stars" to "wanted 3",
                "4 Stars" to "wanted 4",
                "5 Stars" to "wanted 5"
            ), cols = 3)
        }
    }

    // ── Teleport tab ─────────────────────────────────────────────────────────
    @androidx.compose.runtime.Composable
    private fun TeleportTab() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("QUICK TELEPORT")
            Grid(listOf(
                "Airport (LSIA)"  to "tpalias airport",
                "Mount Chiliad"   to "tpalias chilliad",
                "City Center"     to "tpalias city",
                "Del Pier"        to "tpalias pier",
                "Lighthouse"      to "tpalias lighthouse",
                "Vinewood Tower"  to "tpalias tower",
                "Island"          to "tpalias island",
                "Garage"          to "tpalias garage"
            ), cols = 2)

            SectionLabel("NAVIGATION")
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Go to Waypoint", "tpwaypoint", Modifier.weight(1f))
                Btn("Drop to Ground", "ground",      Modifier.weight(1f))
            }

            SectionLabel("POSITION")
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Save Position",    "savepos", Modifier.weight(1f))
                Btn("Restore Position", "loadpos", Modifier.weight(1f))
            }
        }
    }

    // ── World tab ────────────────────────────────────────────────────────────
    @androidx.compose.runtime.Composable
    private fun WorldTab() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("WEATHER")
            Grid(listOf(
                "Clear"       to "weather clear",
                "Extra Sunny" to "weather extrasunny",
                "Cloudy"      to "weather clouds",
                "Overcast"    to "weather overcast",
                "Rain"        to "weather rain",
                "Thunder"     to "weather thunder",
                "Foggy"       to "weather foggy",
                "Smog"        to "weather smog",
                "Snow"        to "weather snow",
                "Light Snow"  to "weather snowlight",
                "Xmas"        to "weather xmas"
            ), cols = 3)

            SectionLabel("TIME OF DAY")
            Grid(listOf(
                "Dawn"     to "time 6",
                "Morning"  to "time 9",
                "Noon"     to "time 12",
                "Sunset"   to "time 19",
                "Night"    to "time 23",
                "Midnight" to "time 0"
            ), cols = 3)


        }
    }

    // ── Vehicles tab ─────────────────────────────────────────────────────────

    private fun prettyModel(model: String): String {
        val uppers = setOf("bmw","m3","m5","rs6","rs","amg","suv","gtb","gt","gtr","rx","s15","s13","r34","r35","rx7","rx8","lsx","ls","v8","v10","v12","4wd","ii","iii","iv","vi","xl","sr","xf","xxr")
        return model.replace('_', ' ').split(' ').joinToString(" ") { word ->
            if (word.lowercase() in uppers) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun fuzzyMatch(query: String, name: String, model: String): Boolean {
        val hay = (name + " " + model.replace('_', ' ')).lowercase()
        val tokens = query.trim().lowercase().split(Regex("[\\s_]+")).filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.all { tok -> hay.contains(tok) }
    }

    private fun loadModPacks(): List<Pair<String, String>> = emptyList()

    private fun loadCustomVehicles(): MutableList<Pair<String, String>> {
        val prefs = getSharedPreferences("apenv", 0)
        val raw = prefs.getString("custom_vehicles_list", null) ?: return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
        }.toMutableList()
    }

    private fun saveCustomVehicles(list: List<Pair<String, String>>) {
        getSharedPreferences("apenv", 0).edit()
            .putString("custom_vehicles_list", list.joinToString("\n") { "${it.first}:${it.second}" })
            .apply()
    }

    @androidx.compose.runtime.Composable
    private fun VehiclesTab() {
        var cat by remember { mutableStateOf("") }
        var q   by remember { mutableStateOf("") }
        val modPacks = remember { loadModPacks() }
        val showCustom = cat == "CUSTOM"

        val allCategories = VEHICLES
        val everything = remember(modPacks) {
            if (modPacks.isEmpty()) VEHICLES.flatMap { it.second }
            else modPacks + VEHICLES.flatMap { it.second }
        }

        val list = when {
            q.trim().length >= 2 -> everything.filter { (n, m) -> fuzzyMatch(q, n, m) }
            cat.isEmpty()        -> everything
            else                 -> allCategories.firstOrNull { it.first == cat }?.second ?: emptyList()
        }

        Row(Modifier.fillMaxSize()) {
            // Category sidebar
            LazyColumn(Modifier.width(90.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    val sel = showCustom
                    val selBg by animateColorAsState(if (sel) Accent else Color(0xFF1E2E2C), tween(180), label = "cat_bg_CUSTOM")
                    val textColor by animateColorAsState(if (sel) Bg else Accent, tween(180), label = "cat_txt_CUSTOM")
                    Box(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(selBg, RoundedCornerShape(10.dp))
                            .clickable { cat = "CUSTOM"; q = "" },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("  CUSTOM", color = textColor, fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(listOf("ALL") + allCategories.map { it.first }) { c ->
                    val sel = (c == "ALL" && cat.isEmpty()) || c == cat
                    val selBg by animateColorAsState(if (sel) Accent else Bar, tween(180), label = "cat_bg_$c")
                    val textColor by animateColorAsState(if (sel) Bg else Color.White, tween(180), label = "cat_txt_$c")
                    Box(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(selBg, RoundedCornerShape(10.dp))
                            .clickable { cat = if (c == "ALL") "" else c; q = "" },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("  $c", color = textColor, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)) {
                if (showCustom) {
                    CustomVehiclesScreen()
                } else {
                    // Search bar
                    val focus = androidx.compose.ui.platform.LocalFocusManager.current
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = q, onValueChange = { q = it; cat = "" }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(Modifier.background(Bar, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    if (q.isEmpty()) Text("Search vehicles…", color = Dim, fontSize = 12.sp, maxLines = 1)
                                    inner()
                                }
                            }
                        )
                        if (q.isNotEmpty()) {
                            Text("✕", color = Dim, fontSize = 16.sp,
                                modifier = Modifier.clickable { q = "" }.padding(start = 8.dp))
                        }
                    }

                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list) { (n, m) ->
                            Row(
                                Modifier.fillMaxWidth().height(48.dp)
                                    .background(Bar, RoundedCornerShape(12.dp))
                                    .clickable { run("veh $m") }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(n, color = Color.White, fontSize = 13.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                Text(m.replace('_', ' '), color = Dim, fontSize = 10.sp)
                            }
                        }
                        val trimmed = q.trim().lowercase().replace(' ', '_')
                        if (q.trim().length >= 2 && list.none { it.second == trimmed }) item {
                            Row(
                                Modifier.fillMaxWidth().height(48.dp)
                                    .background(Accent, RoundedCornerShape(12.dp))
                                    .clickable { run("veh $trimmed"); focus.clearFocus() }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Spawn \"${prettyModel(trimmed)}\"", color = Bg,
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("Repair",  "vehfix",     Modifier.weight(1f))
                        Btn("Flip Up", "vehupright", Modifier.weight(1f))
                        Btn("Delete",  "vehdelete",  Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CustomVehiclesScreen() {
        var customVehicles by remember { mutableStateOf(loadCustomVehicles()) }
        var nameField  by remember { mutableStateOf("") }
        var modelField by remember { mutableStateOf("") }
        var quickSpawn by remember { mutableStateOf("") }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current

        Column(Modifier.fillMaxSize()) {
            // ── Quick Spawn (no save) ──
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = quickSpawn, onValueChange = { quickSpawn = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.background(Bar, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 10.dp)) {
                            if (quickSpawn.isEmpty()) Text("Spawn model (e.g. adder)", color = Dim, fontSize = 12.sp, maxLines = 1)
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.height(40.dp).background(Accent, RoundedCornerShape(10.dp))
                        .clickable {
                            val m = quickSpawn.trim().lowercase().replace(' ', '_')
                            if (m.isNotEmpty()) { run("veh $m"); quickSpawn = ""; focus.clearFocus() }
                        }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Spawn", color = Bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }

            // ── Divider ──
            Box(Modifier.fillMaxWidth().height(1.dp).background(Bar).padding(bottom = 8.dp))

            // ── Add to saved list ──
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = nameField, onValueChange = { nameField = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.background(Bar, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 10.dp)) {
                            if (nameField.isEmpty()) Text("Title", color = Dim, fontSize = 12.sp, maxLines = 1)
                            inner()
                        }
                    }
                )
                BasicTextField(
                    value = modelField, onValueChange = { modelField = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.background(Bar, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 10.dp)) {
                            if (modelField.isEmpty()) Text("Model", color = Dim, fontSize = 12.sp, maxLines = 1)
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.height(40.dp).background(Accent, RoundedCornerShape(10.dp))
                        .clickable {
                            val n = nameField.trim(); val m = modelField.trim().lowercase().replace(' ', '_')
                            if (n.isNotEmpty() && m.isNotEmpty()) {
                                val updated = (customVehicles + (n to m)).toMutableList()
                                saveCustomVehicles(updated)
                                customVehicles = updated
                                nameField = ""; modelField = ""
                                focus.clearFocus()
                            }
                        }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Add", color = Bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }

            // Saved list
            if (customVehicles.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No custom vehicles saved.\nAdd one above.", color = Dim, fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(customVehicles.size) { i ->
                        val (n, m) = customVehicles[i]
                        Row(
                            Modifier.fillMaxWidth().height(50.dp)
                                .background(Bar, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(n, color = Color.White, fontSize = 13.sp, maxLines = 1)
                                Text(m, color = Dim, fontSize = 10.sp, maxLines = 1)
                            }
                            Text("▶", color = Accent, fontSize = 14.sp,
                                modifier = Modifier.clickable { run("veh $m") }.padding(horizontal = 10.dp))
                            Text("✕", color = Dim, fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    val updated = customVehicles.toMutableList().also { it.removeAt(i) }
                                    saveCustomVehicles(updated)
                                    customVehicles = updated
                                })
                        }
                    }
                }
            }
        }
    }

    // ── Log tab ──────────────────────────────────────────────────────────────
    @androidx.compose.runtime.Composable
    private fun LogTab() {
        var tick by remember { mutableStateOf(0) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(46.dp).background(Bar, RoundedCornerShape(14.dp))
                    .clickable { Thread { Shv.log("--- diagnose (port ${Shv.port}) ---"); Shv.discover(); tick++ }.start() },
                    contentAlignment = Alignment.Center) {
                    Text("Diagnose", color = Color.White, fontSize = 12.sp)
                }
                Box(Modifier.weight(1f).height(46.dp).background(Bar, RoundedCornerShape(14.dp))
                    .clickable { Shv.trail.clear(); tick++ },
                    contentAlignment = Alignment.Center) {
                    Text("Clear", color = Color.White, fontSize = 12.sp)
                }
            }
            val lines = Shv.trail.toList().also { tick.hashCode() }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF0A1010), RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState(), reverseScrolling = true)
                    .padding(10.dp)
            ) {
                if (lines.isEmpty()) Text("Nothing yet — open the menu to auto-ping, or tap Diagnose.", color = Dim, fontSize = 11.sp)
                lines.forEach { Text(it, color = Dim, fontSize = 10.sp) }
            }
        }
    }

    override fun onDestroy() {
        alive = false
        panel?.let { wm.removeView(it) }
        icon?.let  { wm.removeView(it) }
        owner.reg.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
