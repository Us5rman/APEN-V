package com.apenv.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private val STEPS = listOf(
    "Read the car file",
    "Find the model name",
    "Adapt to Android",
    "Save dlc.rpf in the pack folder",
    "Add it to dlclist.xml"
)

@Composable
internal fun CarScreen(ws: Workspace) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !ws.carPicker) { ws.carMode = false }
    BackHandler(enabled = ws.carPicker) { ws.carPicker = false }

    // ── state ──────────────────────────────────────────────────────────────────
    var pickedFile  by remember { mutableStateOf<File?>(null) }
    var folder      by remember { mutableStateOf("") }
    var busy        by remember { mutableStateOf(false) }
    var cur         by remember { mutableIntStateOf(-1) }
    var failed      by remember { mutableStateOf(false) }
    var detail      by remember { mutableStateOf("") }
    var manualModel by remember { mutableStateOf("") }
    var needsManual by remember { mutableStateOf(false) }
    val log         = remember { mutableStateListOf<String>() }

    // ── in-app file picker overlay ─────────────────────────────────────────────
    if (ws.carPicker) {
        SourcePicker(
            start   = ws.deviceDir,
            onPick  = { f ->
                if (f.isFile && f.extension.equals("rpf", true)) {
                    pickedFile  = f
                    needsManual = false
                    manualModel = ""
                }
                ws.carPicker = false
            },
            onClose = { ws.carPicker = false }
        )
        return
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    @Composable fun Field(value: String, hint: String, onChange: (String) -> Unit) {
        BasicTextField(
            value = value, onValueChange = { if (!busy) onChange(it) }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Accent),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(Bar, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (value.isEmpty()) Text(hint, color = Dim, fontSize = 14.sp)
                    inner()
                }
            }
        )
    }

    fun launch() {
        val f = pickedFile ?: return
        busy = true; failed = false; cur = 0; detail = ""; log.clear()
        val override = if (needsManual) manualModel else ""
        scope.launch(Dispatchers.IO) {
            val result = CarPack.addFromFile(
                ctx, f, override, folder, ws.gameRoot, ws.aesKey,
                { cur = it }, { detail = it },
                onNeedModel = { needsManual = true; busy = false; cur = -1 }
            ) { log.add(it) }
            if (result != null) {
                failed = !result
                // A new vehicle was added — mods need to be re-enabled before launching,
                // so clear the mods_done flag so the Launch game button is disabled.
                if (result) {
                    ctx.getSharedPreferences("apenv", 0).edit().putBoolean("mods_done", false).apply()
                }
                busy = false
            }
        }
    }

    val canRun = pickedFile != null && !busy && (!needsManual || manualModel.isNotBlank())

    // ── layout ─────────────────────────────────────────────────────────────────
    Column(Modifier.fillMaxSize().background(Bg)) {

        // Top bar — same pattern as every other screen
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                Ic.BACK, Color.White, 24.dp,
                Modifier.clickable(enabled = !busy) { ws.carMode = false }.padding(end = 4.dp)
            )
            Text("Add Vehicle", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── File picker card ───────────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Label
                Text("Car RPF", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                // Pick-file row — mirrors the style of items in Editor/ModsScreen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Bar, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy) { ws.carPicker = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(Ic.FILE, if (pickedFile != null) Accent else Dim, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pickedFile?.name ?: "Choose a .rpf file",
                            color = if (pickedFile != null) Color.White else Dim,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        if (pickedFile != null)
                            Text(
                                formatSize(pickedFile!!.length()),
                                color = Dim, fontSize = 11.sp
                            )
                    }
                    AppIcon(Ic.CHEVRON, Dim, 18.dp)
                }

                // Folder name field
                Text("Folder name (optional)", color = Dim, fontSize = 12.sp)
                Field(folder, "blank = same as model name") { folder = it }

                // Manual model name — only when auto-detect fails
                AnimatedVisibility(
                    visible = needsManual,
                    enter = fadeIn() + expandVertically(),
                    exit  = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Model name not found — enter it manually",
                            color = Color(0xFFE5534B), fontSize = 12.sp
                        )
                        Field(manualModel, "e.g. m3ghe") { manualModel = it }
                    }
                }

                // Add button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(
                            if (canRun) Accent else Picked,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable(enabled = canRun) { launch() },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy)
                        CircularProgressIndicator(
                            color = Bg, strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    else
                        Text(
                            "Add vehicle",
                            color = if (canRun) Bg else Dim,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                }
            }

            // ── Progress card ──────────────────────────────────────────────────
            AnimatedVisibility(
                visible = cur >= 0,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Text("Progress", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp))

                    STEPS.forEachIndexed { i, label ->
                        val done   = cur > i
                        val active = cur == i && busy
                        val bad    = cur == i && failed
                        Row(
                            Modifier.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                if (active)
                                    CircularProgressIndicator(
                                        color = Accent, strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                else
                                    Box(
                                        Modifier.size(if (done || bad) 14.dp else 8.dp)
                                            .background(
                                                when { bad -> Color(0xFFE5534B); done -> GtaGreen; else -> Dim },
                                                CircleShape
                                            )
                                    )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    label,
                                    color = when { active || done -> Color.White; bad -> Color(0xFFE5534B); else -> Dim },
                                    fontSize = 14.sp
                                )
                                if (active && detail.isNotEmpty())
                                    Text(detail, color = Dim, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    when {
                        cur == STEPS.size ->
                            Text("Done. Start the game to test.",
                                color = GtaGreen, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        failed ->
                            Text("Stopped — see the log below.",
                                color = Color(0xFFE5534B), fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // ── Log card ───────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = log.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Log", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp))
                    log.forEach { Text(it, color = Dim, fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
