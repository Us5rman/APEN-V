package com.apenv.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ModsScreen(ws: Workspace) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { ws.modsMode = false }
    var state by remember { mutableStateOf(ModLoader.state(ctx)) }
    var menu by remember { mutableStateOf(true) }
    fun needOverlay(): Boolean {
        if (Settings.canDrawOverlays(ctx)) return false
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
    val prefs = remember { ctx.getSharedPreferences("apenv", 0) }
    var done by remember { mutableStateOf(prefs.getBoolean("mods_done", false)) }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var mods by remember { mutableStateOf(ModLoader.status(null)) }

    suspend fun openGame() {
        if (menu) try {
            ModLoader.sh("pkill -f 'logcat -v time -s SHV' 2>/dev/null; " +
                "nohup sh -c 'logcat -v time -s SHV OpenIV-Mods' > /data/local/tmp/apenv_shv.log 2>&1 &")
        } catch (e: Throwable) { }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (menu && Settings.canDrawOverlays(ctx)) ctx.startForegroundService(Intent(ctx, MenuService::class.java))
            ctx.packageManager.getLaunchIntentForPackage(ModLoader.PKG)?.let { ctx.startActivity(it) }
            ShadeStore.onGameLaunched(ctx)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            state = ModLoader.state(ctx)
            val root = ws.gameRoot
            mods = kotlinx.coroutines.withContext(Dispatchers.IO) { ModLoader.status(root) }
            delay(1500)
        }
    }

    val ready = state == 3 && !busy && ws.gameRoot != null
    val statusText = when (state) {
        0 -> "Shizuku is not installed. Install it from its website or the Play Store, then start it."
        1 -> "Shizuku is installed but not running. Open it and start it."
        2 -> "Shizuku is running. Tap to give APEN V permission."
        else -> "Shizuku is ready."
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { ws.modsMode = false }.padding(end = 4.dp))
            Text("Mods", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
                    .clickable(enabled = state == 2) { ModLoader.requestPermission() }
                    .padding(16.dp)
            ) {
                Text("Access", color = Dim, fontSize = 12.sp)
                Text(statusText, color = if (state == 3) Accent else Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }

            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mod menu", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Switch(checked = menu, onCheckedChange = { menu = it; if (it) needOverlay() })
            }

            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Mods folder", color = Dim, fontSize = 12.sp)
                Text(
                    ws.gameRoot?.let { ModLoader.modsFolder(it).absolutePath } ?: "Choose the game folder first",
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    if (!mods.folder) "Not created yet. Enable mods creates it."
                    else (if (mods.common) "common.rpf ready" else "common.rpf missing") + " · " +
                        (if (mods.update) "update folder ready" else "update folder missing"),
                    color = if (mods.folder && mods.common && mods.update) Accent else Dim,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }

            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Mod packs (mods/update/android/dlcpacks)", color = Dim, fontSize = 12.sp)
                Text(
                    if (mods.packs.isEmpty()) "None yet. Put each pack's folder there with Edit mode."
                    else mods.packs.joinToString("\n"),
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (log.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Log", color = Dim, fontSize = 12.sp)
                    Text(
                        log.joinToString("\n"), color = Color(0xFFE6E6EA), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(
                Modifier.weight(1f).height(56.dp).background(if (ready) Color(0xFF3A1D1D) else Panel, RoundedCornerShape(18.dp))
                    .clickable(enabled = ready) {
                        busy = true
                        log.clear()
                        scope.launch(Dispatchers.IO) {
                            ModLoader.disable(ws.gameRoot) { log.add(it) }
                            ctx.stopService(Intent(ctx, MenuService::class.java))
                            prefs.edit().putBoolean("mods_done", false).apply()
                            done = false
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("Disable mods", color = if (ready) Color(0xFFFF7A72) else Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.weight(1f).height(56.dp).background(if (ready || done || !Settings.canDrawOverlays(ctx)) Accent else Picked, RoundedCornerShape(18.dp))
                    .clickable(enabled = !busy) {
                        if (done) {
                            busy = true
                            log.clear()
                            scope.launch(Dispatchers.IO) {
                                if (!ModLoader.isActive(menu)) {
                                    log.add("The loader was off (phone restart?). Turning it on again.")
                                    ModLoader.activate(menu) { log.add(it) }
                                }
                                openGame()
                                busy = false
                            }
                            return@clickable
                        }
                        if (!Settings.canDrawOverlays(ctx)) { needOverlay(); return@clickable }
                        if (!ready) return@clickable
                        busy = true
                        log.clear()
                        scope.launch(Dispatchers.IO) {
                            ModLoader.enable(ctx, ws.gameRoot, menu) { log.add(it) }
                            if (log.any { it.startsWith("Done") }) {
                                prefs.edit().putBoolean("mods_done", true).apply()
                                done = true
                            }
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "Working…" else if (done) "Launch game" else "Enable mods",
                    color = if (ready || done || !Settings.canDrawOverlays(ctx)) Bg else Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
