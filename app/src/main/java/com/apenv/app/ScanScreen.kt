package com.apenv.app

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Scan game files: progress, the percentages by group, a file list for every row, and the exports.

private fun scanOutDir() = File(Environment.getExternalStorageDirectory(), "APEN V/Scans")

private fun scanColor(id: String): Color = when (id) {
    "unreadable", "encarch", "unkplat" -> Color(0xFFE57373)
    "android" -> GtaGreen
    "pc" -> Color(0xFF6DB6FF)
    else -> Accent
}

@Composable
internal fun GameScanScreen(ws: Workspace) {
    val sc = ws.scan
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var openTag by remember { mutableStateOf<ScanTag?>(null) }
    var exporting by remember { mutableStateOf(false) }

    BackHandler { if (openTag != null) openTag = null else ws.scanMode = false }
    LaunchedEffect(Unit) { sc.loadLast(ctx.filesDir) }
    androidx.compose.runtime.DisposableEffect(sc.running) {
        view.keepScreenOn = sc.running
        onDispose { view.keepScreenOn = false }
    }

    val tag = openTag
    if (tag != null) {
        ScanList(sc, tag) { openTag = null }
        return
    }

    val s = sc.summary
    val pr = sc.progress
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { ws.scanMode = false }.padding(end = 4.dp))
            Text("Scan game files", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Game folder", color = Dim, fontSize = 12.sp)
                Text(
                    ws.gameRoot?.absolutePath ?: "No folder chosen", color = Color.White, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
                if (!sc.running) {
                Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sc.running) Color(0xFF3A2424) else Accent)
                            .clickable(enabled = ws.gameRoot != null) {
                                if (sc.running) sc.stop()
                                else ws.gameRoot?.let {
                                    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    val mi = android.app.ActivityManager.MemoryInfo()
                                    am.getMemoryInfo(mi)
                                    sc.start(it, ws.aesKey, ctx.filesDir, ctx.cacheDir, mi.totalMem, mi.availMem)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(
                                if (sc.running) Ic.CLOSE else Ic.SCAN,
                                if (sc.running) Color(0xFFE57373) else Color.Black, 20.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (sc.running) "Stop scan" else if (s != null) "Scan again" else "Start scan",
                                color = if (sc.running) Color(0xFFE57373) else Color.Black,
                                fontSize = 15.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (sc.running && pr != null) {
                val frac = if (pr.total > 0) (pr.done.toFloat() / pr.total).coerceIn(0f, 1f) else 0f
                val secs = maxOf(1L, pr.elapsedMs / 1000)
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format(java.util.Locale.US, "%.1f%%", frac * 100f),
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${formatSize(pr.done)} of ${formatSize(pr.total)}", color = Dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Bar)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${pr.files} entries read · ${formatSize(pr.done / secs)}/s · ${secs / 60}m ${secs % 60}s",
                        color = Dim, fontSize = 12.sp
                    )
                    if (sc.info.isNotEmpty()) Text(sc.info, color = Dim, fontSize = 11.sp)
                    Text(pr.current, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            sc.message?.let {
                Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }

            if (s != null) {
                Text(
                    "${formatSize(s.total)} · ${s.files} entries · ${s.errors} unreadable",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
                for ((gid, gtitle) in ScanDefs.groups) {
                    val rows = ScanDefs.tags.filter { it.group == gid && s.counts[ScanDefs.index.getValue(it.id)] > 0L }
                    if (rows.isEmpty()) continue
                    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(vertical = 8.dp)) {
                        Text(
                            gtitle, color = Dim, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        for (t in rows) {
                            val i = ScanDefs.index.getValue(t.id)
                            val pct = s.pct(t.id)
                            Row(
                                Modifier.fillMaxWidth().clickable { openTag = t }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.label, color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Bar)) {
                                        Box(
                                            Modifier.fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                                                .fillMaxHeight().background(scanColor(t.id))
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("${formatSize(s.bytes[i])} · ${s.counts[i]} entries", color = Dim, fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    String.format(java.util.Locale.US, "%.1f%%", pct),
                                    color = scanColor(t.id), fontSize = 16.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(6.dp))
                                AppIcon(Ic.CHEVRON, Dim, 18.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (sc.running) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF3A2424))
                    .clickable { sc.stop() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Ic.CLOSE, Color(0xFFE57373), 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop scan", color = Color(0xFFE57373), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (s != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF17302D))
                        .clickable(enabled = !exporting && !sc.running) {
                            exporting = true
                            sc.message = "Writing the logs zip, this can take a minute..."
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { sc.extractLogs(scanOutDir()) } }
                                sc.message = r.fold(
                                    { "Saved: ${it.absolutePath}" },
                                    { "Could not save the logs: ${it.message ?: it.javaClass.simpleName}" }
                                )
                                exporting = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Ic.ARROW_R, Accent, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (exporting) "Writing zip..." else "Extract logs", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanList(sc: ScanController, tag: ScanTag, onBack: () -> Unit) {
    val s = sc.summary
    val rows = remember(tag) { mutableStateListOf<ScanRow>() }
    var finished by remember(tag) { mutableStateOf(false) }
    var loading by remember(tag) { mutableStateOf(false) }
    var saving by remember(tag) { mutableStateOf(false) }
    var note by remember(tag) { mutableStateOf<String?>(null) }
    val reader = remember(tag) { sc.reader(tag) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(tag) { onDispose { reader.close() } }

    val loadMore: () -> Unit = {
        if (!loading && !finished) {
            loading = true
            scope.launch {
                val chunk = withContext(Dispatchers.IO) { runCatching { reader.next(400) }.getOrDefault(emptyList()) }
                rows.addAll(chunk)
                if (chunk.size < 400) finished = true
                loading = false
            }
        }
    }
    LaunchedEffect(tag) { loadMore() }
    BackHandler { onBack() }

    val i = ScanDefs.index.getValue(tag.id)
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable(onClick = onBack).padding(end = 4.dp))
            Column(Modifier.weight(1f)) {
                Text(tag.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s != null) {
                    Text(
                        String.format(java.util.Locale.US, "%.1f%%", s.pct(tag.id)) + " · ${formatSize(s.bytes[i])} · ${s.counts[i]} entries",
                        color = Dim, fontSize = 12.sp
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF17302D))
                .clickable(enabled = !saving && s != null) {
                    saving = true
                    note = "Writing the list..."
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { sc.extractList(tag.id, scanOutDir()) } }
                        note = r.fold(
                            { "Saved: ${it.absolutePath}" },
                            { "Could not save: ${it.message ?: it.javaClass.simpleName}" }
                        )
                        saving = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Ic.ARROW_R, Accent, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Extract folder and file names to .txt", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        note?.let {
            Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows.size) { n ->
                val r = rows[n]
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)) {
                    Text(r.path.substringAfterLast('/'), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.path.substringBeforeLast('/', ""), color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatSize(r.stored)} · ${r.detail}", color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n == rows.size - 1 && !finished) LaunchedEffect(n) { loadMore() }
            }
            if (loading) item { Text("Loading...", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
        }
    }
}
