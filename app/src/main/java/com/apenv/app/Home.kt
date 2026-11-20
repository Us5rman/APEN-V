package com.apenv.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeIcon(ic: Ic, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(ic, tint, 28.dp, strokeWidth = 1.6f)
    }
}

@Composable
internal fun Home(ws: Workspace) {
    val ctx = LocalContext.current
    var showRoots by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            Text("APEN V", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }

        val hasFolder = ws.gameRoot != null
        val editReady = hasFolder && ws.granted
        // Muted tint (desaturated accent over the panel color) when disabled, instead of flat grey.
        val editBg = if (editReady) Color(0xFF17302D) else lerp(Panel, Color(0xFF17302D), 0.35f)
        val editTextColor = if (editReady) Accent else lerp(Dim, Accent, 0.35f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .appear(0)
                .pressScale()
                .background(editBg, RoundedCornerShape(18.dp))
                .clickable(enabled = editReady) { ws.enterEditMode() }
                .padding(16.dp)
        ) {
            HomeIcon(Ic.RENAME, editTextColor, Modifier.align(Alignment.TopStart))
            AppIcon(Ic.CHEVRON, editTextColor, 20.dp, Modifier.align(Alignment.TopEnd))
            Text(
                "Edit mode", color = editTextColor, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }

        Spacer(Modifier.height(10.dp))

        val viewBg = if (editReady) Color(0xFF17302D) else Panel
        val viewColor = if (editReady) Accent else Dim
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .appear(70)
                    .pressScale()
                    .background(viewBg, RoundedCornerShape(18.dp))
                    .clickable(enabled = editReady) { ws.enterViewMode() }
                    .padding(16.dp)
            ) {
                HomeIcon(Ic.PERSON, viewColor, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, viewColor, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "View models", color = viewColor, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .appear(120)
                    .pressScale()
                    .background(Color(0xFF17302D), RoundedCornerShape(18.dp))
                    .clickable(enabled = ws.granted) { ws.convertMode = true }
                    .padding(16.dp)
            ) {
                HomeIcon(Ic.CUBES, Accent, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, Accent, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "Texture Format Conversion", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            Box(
            Modifier
                .weight(1f)
                .aspectRatio(1f)
                    .appear(170)
                    .pressScale()
                .background(Panel, RoundedCornerShape(18.dp))
                .clickable(enabled = ws.granted) { showRoots = true }
                .padding(16.dp)
        ) {
            HomeIcon(Ic.GTAV, if (ws.granted) GtaGreen else Dim, Modifier.align(Alignment.TopStart))
            AppIcon(Ic.CHEVRON, Dim, 20.dp, Modifier.align(Alignment.TopEnd))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    ws.gameRoot?.name ?: "No folder chosen",
                    color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Choose game folder",
                    color = if (ws.granted) Color.White else Dim,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }
        }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .appear(220)
                    .pressScale()
                    .background(if (editReady) Color(0xFF17302D) else Panel, RoundedCornerShape(18.dp))
                    .clickable(enabled = editReady) { ws.scanMode = true }
                    .padding(16.dp)
            ) {
                val scanTint = if (editReady) Accent else Dim
                HomeIcon(Ic.SCAN, scanTint, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, scanTint, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "Scan game files", color = scanTint, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (!ws.granted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(10.dp))
                    .clickable {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Allow storage access", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                AppIcon(Ic.CHEVRON, Dim, 18.dp)
            }
        }
    }

    if (showRoots) {
        var browseDir by remember { mutableStateOf(ws.deviceRoots().first()) }
        val browseItems = remember(browseDir) {
            (browseDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
                .sortedBy { it.name.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { showRoots = false },
            title = { Text("Choose game folder", fontSize = 15.sp) },
            text = {
                Column(Modifier.height(430.dp)) {
                    // The device roots are the top of the line: you can't climb above them.
                    val roots = remember { ws.deviceRoots() }
                    val atRoot = browseDir in roots

                    ws.gtaRoot()?.let { gta ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                showRoots = false
                                ws.chooseGameFolder(gta)
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(Ic.REFRESH, Accent, 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("GTA V (auto-detected: ${gta.name})", color = Accent, fontSize = 13.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            showRoots = false
                            ws.chooseGameFolder(browseDir)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(Ic.CHECK, Accent, 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Use this folder", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bar)
                            .clickable(enabled = !atRoot) { browseDir.parentFile?.let { browseDir = it } }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(Ic.UP, if (atRoot) Dim.copy(alpha = 0.35f) else Color.White, 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (browseDir == roots.first()) "Internal storage" else browseDir.name,
                            color = if (atRoot) Dim else Color.White,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(browseItems, key = { it.absolutePath }) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable { browseDir = f }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(Ic.FOLDER, tintFor(Ic.FOLDER), 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoots = false }) { Text("Cancel") } }
        )
    }
}
