package com.apenv.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    // The intro only plays when the app starts, not every time the home screen comes back
    val intro = remember { !ws.introPlayed }
    LaunchedEffect(Unit) { ws.introPlayed = true }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("APEN V", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.appear(0, enabled = intro))
            Spacer(Modifier.weight(1f))
            if (!ws.granted) {
                Row(
                    Modifier
                        .appear(750, enabled = intro)
                        .background(Panel, RoundedCornerShape(10.dp))
                        .clickable {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow storage access", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                    AppIcon(Ic.CHEVRON, Dim, 18.dp)
                }
            }
        }

        val hasFolder = ws.gameRoot != null
        val editReady = hasFolder && ws.granted
        // Muted tint (desaturated accent over the panel color) when disabled, instead of flat grey.
        val editBg = if (editReady) Color(0xFF17302D) else lerp(Panel, Color(0xFF17302D), 0.35f)
        val editTextColor = if (editReady) Accent else lerp(Dim, Accent, 0.35f)
        // Half the width of one of the square tiles below
        val tileW = ((LocalConfiguration.current.screenWidthDp - 42) / 4).dp

        // The falling cards hit at HOME_IMPACT_MS and shove every tile; each tile moves by its own amount.
        val impact = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            if (intro) {
                delay(HOME_IMPACT_MS)
                impact.snapTo(1f)
                impact.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 220f))
            }
        }
        val hit = { impact.value }
        // OIV lands later, top right. Every button gets its own wobble: closer ones are hit first, and each
        // is pushed its own way depending on where it sits (the wave travels from the top right downward).
        val oivEdit = rememberImpact(OIV_IMPACT_MS, 0, intro)
        val oivView = rememberImpact(OIV_IMPACT_MS, 60, intro)
        val oivShade = rememberImpact(OIV_IMPACT_MS, 120, intro)
        val oivMods = rememberImpact(OIV_IMPACT_MS, 170, intro)
        val oivCar = rememberImpact(OIV_IMPACT_MS, 140, intro)
        val oivConvert = rememberImpact(OIV_IMPACT_MS, 240, intro)
        val oivFolder = rememberImpact(OIV_IMPACT_MS, 210, intro)

        // Edit mode's card is full width (it stretches under OIV). OIV is its own tile that falls in
        // separately, only after the three big cards have landed.
        Box(Modifier.fillMaxWidth().height(150.dp).dropFromTop(enabled = intro)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shock(oivEdit, -4f, 9f, -0.6f, enabled = intro)
                    .pressScale()
                    .background(editBg, RoundedCornerShape(18.dp))
                    .clickable(enabled = editReady) { ws.enterEditMode() }
                    // end padding keeps the arrow where it is, left of the OIV tile
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = tileW + 10.dp + 16.dp)
            ) {
                HomeIcon(Ic.RENAME, editTextColor, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, editTextColor, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "Edit mode", color = editTextColor, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(8.dp)
                    .width(tileW)
                    .fillMaxHeight()
                    .dropFromTop(delayMs = HOME_IMPACT_MS.toInt(), durationMs = OIV_DROP_MS, enabled = intro)
                    .pressScale()
                    .background(
                        if (editReady) Color(0xFF1F403B) else lerp(Panel, Color(0xFF1F403B), 0.35f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = editReady) { ws.oivMode = true }
                    .padding(12.dp)
            ) {
                HomeIcon(Ic.OIV, editTextColor, Modifier.align(Alignment.TopStart))
                Text(
                    "OIV", color = editTextColor, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val viewBg = if (editReady) Color(0xFF17302D) else lerp(Panel, Color(0xFF17302D), 0.35f)
        val viewColor = if (editReady) Accent else lerp(Dim, Accent, 0.35f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .dropFromTop(enabled = intro)
                .shock(oivView, -9f, 12f, -0.5f, enabled = intro)
                .pressScale()
                .background(viewBg, RoundedCornerShape(18.dp))
                .clickable(enabled = editReady) { ws.enterViewMode() }
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                HomeIcon(Ic.EYE, viewColor)
                Spacer(Modifier.width(14.dp))
                Text("View", color = viewColor, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            AppIcon(Ic.CHEVRON, viewColor, 20.dp, Modifier.align(Alignment.CenterEnd))
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .dropFromTop(enabled = intro)
                .shock(oivShade, -5f, 8f, 0.4f, enabled = intro)
                .pressScale()
                .background(Color(0xFF17302D), RoundedCornerShape(18.dp))
                .clickable { ws.shadeMode = true }
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                // Same 48dp tinted rounded box as the other HomeIcons, so the icon and text line up with View / Edit mode
                Box(
                    Modifier.size(48.dp).background(Accent.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.reshade_wheel), null,
                        Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text("ReShade", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            AppIcon(Ic.CHEVRON, Accent, 20.dp, Modifier.align(Alignment.CenterEnd))
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .slideTogether(true, enabled = intro)
                    .shock(hit, -12f, 16f, -3f, enabled = intro)
                    .shock(oivMods, -8f, 8f, -2f, enabled = intro)
                    .pressScale()
                    .background(Color(0xFF17302D), RoundedCornerShape(18.dp))
                    .clickable(enabled = ws.granted) { ws.modsMode = true }
                    .padding(16.dp)
            ) {
                HomeIcon(Ic.SCRIPT, Accent, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, Accent, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "Mods", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .slideTogether(false, enabled = intro)
                    .shock(hit, 12f, 16f, 3f, enabled = intro)
                    .shock(oivCar, -4f, 14f, -3f, enabled = intro)
                    .pressScale()
                    .background(Color(0xFF17302D), RoundedCornerShape(18.dp))
                    .clickable(enabled = ws.granted) { ws.carMode = true }
                    .padding(16.dp)
            ) {
                HomeIcon(Ic.CAR, Accent, Modifier.align(Alignment.TopStart))
                AppIcon(Ic.CHEVRON, Accent, 20.dp, Modifier.align(Alignment.TopEnd))
                Text(
                    "Add vehicle", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
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
                    .slideTogether(true, enabled = intro)
                    .shock(hit, -9f, 9f, -2f, enabled = intro)
                    .shock(oivConvert, -6f, 5f, -1.5f, enabled = intro)
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
            Spacer(Modifier.width(10.dp))
            Box(
            Modifier
                .weight(1f)
                .aspectRatio(1f)
                    .slideTogether(false, enabled = intro)
                    .shock(hit, 9f, 9f, 2f, enabled = intro)
                    .shock(oivFolder, -3f, 9f, -2f, enabled = intro)
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
        }

        Spacer(Modifier.weight(1f))
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
