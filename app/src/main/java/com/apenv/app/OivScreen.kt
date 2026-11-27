package com.apenv.app

import androidx.activity.compose.BackHandler
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun OivScreen(ws: Workspace) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { ws.oivMode = false }

    var oiv by remember { mutableStateOf<OivPackage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var mods by remember { mutableStateOf(ModLoader.status(null)) }

    LaunchedEffect(Unit) {
        while (true) {
            val root = ws.gameRoot
            mods = withContext(Dispatchers.IO) { ModLoader.status(root) }
            delay(1500)
        }
    }

    if (picking) {
        val storage = Environment.getExternalStorageDirectory()
        SourcePicker(
            start = File(storage, "Download").takeIf { it.isDirectory } ?: storage,
            onPick = { f ->
                picking = false
                scope.launch(Dispatchers.IO) {
                    log.clear()
                    try {
                        oiv = Oiv.read(f)
                    } catch (e: Exception) {
                        oiv = null
                        log.add("Could not read the package: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            },
            onClose = { picking = false },
            heading = "Choose a package",
            accepts = { it.extension.equals("oiv", true) },
            visible = { it.isDirectory || it.extension.equals("oiv", true) },
            pickFolder = false
        )
        return
    }

    val pkg = oiv
    val can = pkg != null && !busy && ws.gameRoot != null && mods.folder

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { ws.oivMode = false }.padding(end = 4.dp))
            Text("OIV installer", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Package", color = Dim, fontSize = 12.sp)
                if (pkg == null) {
                    Text(
                        "Pick an OpenIV package (.oiv) to install into the mods folder.",
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        pkg.name + if (pkg.version.isNotEmpty()) "  " + pkg.version else "",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (pkg.author.isNotEmpty()) Text("by " + pkg.author, color = Dim, fontSize = 12.sp)
                    Text(Oiv.summary(pkg), color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }

            if (ws.gameRoot == null) {
                Text("Choose the game folder on the home screen first.", color = Color(0xFFFF7A72), fontSize = 12.sp)
            } else if (!mods.folder) {
                Text("Press Enable mods first, it creates the mods folder.", color = Color(0xFFFF7A72), fontSize = 12.sp)
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
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(
                Modifier.weight(1f).height(56.dp).background(Picked, RoundedCornerShape(18.dp))
                    .clickable(enabled = !busy) { picking = true },
                contentAlignment = Alignment.Center
            ) { Text("Choose .oiv", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Box(Modifier.padding(start = 10.dp).weight(1f).height(56.dp)
                .background(if (can) Accent else Panel, RoundedCornerShape(18.dp))
                .clickable(enabled = can) {
                    val root = ws.gameRoot ?: return@clickable
                    val job = pkg ?: return@clickable
                    busy = true
                    log.clear()
                    scope.launch(Dispatchers.IO) {
                        val res = Oiv.install(job, root, ws.aesKey, ctx.cacheDir) { log.add(it) }
                        if (res.ok && res.updateChanged) {
                            if (ModLoader.state(ctx) == 3) ModLoader.applyModdedUpdate(root) { log.add(it) }
                            else log.add("update.rpf changed. Start Shizuku and press Enable mods again to apply it.")
                        }
                        busy = false
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "Installing…" else "Install",
                    color = if (can || busy) (if (busy) Dim else Bg) else Dim,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
