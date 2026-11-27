package com.apenv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
private fun NumField(value: String, modifier: Modifier, onValue: (String) -> Unit) {
    var t by remember { mutableStateOf(value) }
    BasicTextField(
        value = t,
        onValueChange = { t = it; onValue(it) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(Accent),
        modifier = modifier.background(Color(0xFF263033), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun ParamRow(prm: FxParam, map: java.util.concurrent.ConcurrentHashMap<String, String>, onChange: () -> Unit) {
    val cur = map[prm.key] ?: prm.def
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(prm.key, color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        if (prm.vec) {
            val parts = cur.split(',').map { it.trim() }.let { l -> List(3) { i -> l.getOrElse(i) { l.firstOrNull() ?: "0" } } }.toMutableList()
            Row {
                for (i in 0..2) {
                    NumField(parts[i], Modifier.weight(1f)) { v ->
                        parts[i] = v
                        if (parts.all { it.toFloatOrNull() != null }) { map[prm.key] = parts.joinToString(","); onChange() }
                    }
                    if (i < 2) Spacer(Modifier.width(8.dp))
                }
            }
        } else {
            NumField(cur, Modifier.fillMaxWidth()) { v ->
                if (v.toFloatOrNull() != null) { map[prm.key] = v; onChange() }
            }
        }
    }
}

@Composable
internal fun ShadeScreen(ws: Workspace) {
    val ctx = LocalContext.current
    BackHandler { ws.shadeMode = false }
    var preset by remember { mutableStateOf(ShadeStore.current(ctx)) }
    val running by ShadeService.running
    var withGta by remember { mutableStateOf(ShadeStore.withGta(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    var lutName by remember { mutableStateOf(ShadeStore.lutName(ctx)) }
    var rev by remember { mutableStateOf(0) }
    var accOn by remember { mutableStateOf(ShadeAccessibilityService.isEnabled(ctx)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { accOn = ShadeAccessibilityService.isEnabled(ctx); kotlinx.coroutines.delay(1000) }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "preset.ini"
            ShadeStore.save(ctx, text, name)
            preset = ShadeStore.current(ctx)
            open = null
        }.onFailure { ws.notice = "Could not read that file" }
    }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "lut.png"
            ShadeStore.saveLut(ctx, bytes, name)
            lutName = name
            rev++
        }.onFailure { ws.notice = "Could not read that image" }
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ShadeService::class.java).putExtra(ShadeService.EXTRA_CODE, r.resultCode).putExtra(ShadeService.EXTRA_DATA, data)
            )
        } else ws.notice = "Screen capture was not allowed"
    }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { ws.shadeMode = false }.padding(end = 8.dp))
            Text("ReShade", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("Preset", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(preset?.name ?: "No ReShade .ini loaded", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().background(Color(0xFF263033), RoundedCornerShape(12.dp))
                    .clickable { pick.launch(arrayOf("*/*")) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Load ReShade .ini", color = Accent, fontSize = 14.sp) }
        }

        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("LUT image", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(lutName ?: "None loaded (used by the LUT effect)", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().background(Color(0xFF263033), RoundedCornerShape(12.dp))
                    .clickable { pickLut.launch(arrayOf("image/*")) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Load LUT image", color = Accent, fontSize = 14.sp) }
        }

        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("Touch and controller input", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (accOn) "On: input goes straight through to the game" else "Off: taps may not reach the game",
                color = if (accOn) GtaGreen else Color(0xFFE5574E), fontSize = 15.sp
            )
            if (!accOn) {
                Spacer(Modifier.height(4.dp))
                Text("Turn on \"APEN V ReShade touch\" in Accessibility settings. It only lets input pass through the overlay; it does not read your screen.", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().background(Color(0xFF263033), RoundedCornerShape(12.dp))
                        .clickable { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Open Accessibility settings", color = Accent, fontSize = 14.sp) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Start With GTA V", color = Color.White, fontSize = 16.sp)
                Text("Turns ReShade on when you launch GTA V from the Mods button", color = Dim, fontSize = 12.sp)
            }
            Switch(
                checked = withGta,
                onCheckedChange = { withGta = it; ShadeStore.setWithGta(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF0E1A18))
            )
        }

        preset?.let { p ->
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Effects in this preset", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (p.techniques.isEmpty()) Text("No active techniques found.", color = Dim, fontSize = 14.sp)
                for ((tech, file) in p.techniques) {
                    val why = ShadeFx.status(file)
                    val def = ShadeFx.def(file)
                    var on by remember(file, rev) { mutableStateOf(p.isOn(file)) }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = def != null) { open = if (open == file) null else file }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(10.dp).background(if (why == null) GtaGreen else Color(0xFFE5574E), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tech, color = if (why == null) Color.White else Color(0xFFE5574E), fontSize = 15.sp)
                                if (why != null) Text("Skipped: $why", color = Dim, fontSize = 12.sp)
                                else Text((if (open == file) "Tap to close" else "Tap to adjust") + (def?.note?.let { " · $it" } ?: ""), color = Dim, fontSize = 12.sp)
                            }
                            if (def != null && why == null) Switch(
                                checked = on,
                                onCheckedChange = { on = it; p.enabled[file] = it; ShadeStore.saveOverrides(ctx, p) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color(0xFF0E1A18))
                            )
                        }
                        if (def != null && open == file) key(rev) {
                            val map = p.sections.getOrPut(file) { java.util.concurrent.ConcurrentHashMap() }
                            Column(Modifier.padding(start = 22.dp, bottom = 6.dp)) {
                                for (prm in def.params) ParamRow(prm, map) { ShadeStore.saveOverrides(ctx, p) }
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier.background(Color(0xFF263033), RoundedCornerShape(10.dp)).clickable {
                                        map.clear(); p.original[file]?.let { map.putAll(it) }
                                        p.enabled.remove(file)
                                        ShadeStore.saveOverrides(ctx, p)
                                        rev++
                                    }.padding(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text("Reset to preset values", color = Accent, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val ready = preset != null && preset!!.techniques.any { ShadeFx.status(it.second) == null }
        Box(
            Modifier.fillMaxWidth().background(if (running || ready) Accent else Panel, RoundedCornerShape(16.dp))
                .clickable(enabled = running || ready) {
                    if (running) {
                        ctx.startService(Intent(ctx, ShadeService::class.java).setAction(ShadeService.ACTION_STOP))
                    } else if (!Settings.canDrawOverlays(ctx)) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } else {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        capture.launch(mpm.createScreenCaptureIntent())
                    }
                }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (running) "Stop ReShade" else "Start ReShade", color = if (running || ready) Color(0xFF0E1A18) else Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "When Android pops up asking what to share, just pick \"A single app\" and select the game — don't tap \"Entire screen\" or you'll get a weird infinite tunnel effect. Any tweaks you make here show up instantly while ReShade is already running.",
            color = Dim, fontSize = 12.sp
        )
    }
}
