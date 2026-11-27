package com.apenv.app

import android.graphics.Bitmap
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Plain-language names for the ASTC block sizes: a bigger block means smaller files and a softer picture.
internal val BlockNames = listOf(
    "Sharpest", "Very sharp", "Sharp", "Good", "Good", "Medium", "Medium",
    "Small files", "Small files", "Smaller files", "Smaller files", "Very small files", "Very small files", "Smallest files"
)

internal fun blockTitle(i: Int) = blockLabel(ASTC_BLOCKS[i])

@Composable
internal fun SettingCard(
    icon: Ic, label: String, value: String, onClick: () -> Unit,
    trailing: @Composable () -> Unit = { AppIcon(Ic.CHEVRON, Dim, 20.dp) }
) {
    Row(
        Modifier.fillMaxWidth()
            .appear(0, 10f)
            .pressScale(0.985f)
            .background(Panel, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeIcon(icon, Accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (label.isNotEmpty()) Text(label, color = Dim, fontSize = 12.sp)
            Text(
                value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

internal fun effortName(v: Float) = when {
    v < 0.15f -> "Fastest"
    v < 0.45f -> "Fast"
    v < 0.8f -> "Balanced"
    v < 0.95f -> "Thorough"
    else -> "Exhaustive"
}

// Speed on the left, accuracy on the right. The value is the encoder search effort (0 to 100).
@Composable
internal fun EffortSlider(value: Float, onChange: (Float) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val pad = with(LocalDensity.current) { 12.dp.toPx() }
    val report by rememberUpdatedState(onChange)
    var widthPx by remember { mutableStateOf(1f) }
    var pressed by remember { mutableStateOf(false) }
    var lastStep by remember { mutableStateOf((value * 20).roundToInt()) }
    val shown by animateFloatAsState(value, tween(140, easing = FastOutSlowInEasing), label = "effort")
    val grow by animateFloatAsState(
        if (pressed) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 380f), label = "grow"
    )

    fun move(x: Float) {
        val v = ((x - pad) / maxOf(1f, widthPx - 2 * pad)).coerceIn(0f, 1f)
        val step = (v * 20).roundToInt()
        if (step != lastStep) {
            lastStep = step
            haptic.performHapticFeedback(
                if (step == 0 || step == 20) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
            )
        }
        report(step / 20f)
    }

    Column(
        Modifier.fillMaxWidth().appear(0, 10f).background(Panel, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", color = lerp(Color.White, Dim, shown.coerceIn(0f, 1f)), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Crossfade(targetState = effortName(value), animationSpec = tween(160), label = "name") {
                Text(it, color = Accent, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Accuracy", color = lerp(Dim, Color.White, shown.coerceIn(0f, 1f)), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier.fillMaxWidth().height(40.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        try {
                            move(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                move(change.position.x)
                            }
                        } catch (_: Exception) {
                            // A cancelled gesture (e.g. the system or a parent taking over
                            // the pointer) should just stop the drag, not crash the screen.
                        } finally {
                            pressed = false
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val x0 = pad
                val x1 = size.width - pad
                val tx = x0 + (x1 - x0) * shown
                val th = 4.dp.toPx()
                drawRoundRect(Bar, Offset(x0, cy - th / 2), Size(x1 - x0, th), CornerRadius(th / 2))
                drawRoundRect(Accent, Offset(x0, cy - th / 2), Size(tx - x0, th), CornerRadius(th / 2))
                for (i in 0..4) {
                    val px = x0 + (x1 - x0) * i / 4f
                    drawCircle(if (px <= tx) Bg else Dim.copy(alpha = 0.6f), 2.dp.toPx(), Offset(px, cy))
                }
                val gk = grow.coerceIn(0f, 1.2f)
                drawCircle(Accent.copy(alpha = (0.18f * gk).coerceIn(0f, 1f)), pad + 8.dp.toPx() * gk, Offset(tx, cy))
                drawCircle(Accent, pad * (1f + 0.12f * gk), Offset(tx, cy))
                drawCircle(Bg, pad * 0.38f, Offset(tx, cy))
            }
        }
    }
}

@Composable
internal fun PreviewTile(item: PreviewItem) {
    var visible by remember { mutableStateOf(false) }
    var zoomOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(240), label = "tile")
    Column(
        Modifier.width(180.dp).graphicsLayer {
            alpha = a
            scaleX = 0.94f + 0.06f * a
            scaleY = 0.94f + 0.06f * a
        }
    ) {
        Box(
            Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)).background(Bar)
                .clickable { zoomOpen = true },
            contentAlignment = Alignment.Center
        ) {
            item.bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            item.title, color = Color.White, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
        )
        Text(item.detail, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (zoomOpen) {
        ZoomableImageOverlay(item) { zoomOpen = false }
    }
}

// Full-screen viewer. It fades and scales in, loads the sharp picture in the background (the
// small one is shown meanwhile), and zooming is animated: pinch follows your fingers around the
// pinch point, double-tap glides in on the spot you tapped or back out, and closing eases out.
@Composable
internal fun ZoomableImageOverlay(item: PreviewItem, onDismiss: () -> Unit) {
    val full by androidx.compose.runtime.produceState<Bitmap?>(null, item) {
        value = withContext(Dispatchers.IO) { item.full?.invoke() }
    }
    val shown = full ?: item.bitmap

    var appear by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val p by animateFloatAsState(
        if (appear && !closing) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "zoomIn"
    )
    LaunchedEffect(closing) {
        if (closing) {
            kotlinx.coroutines.delay(260)
            onDismiss()
        }
    }
    val close = { closing = true }

    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f * p))) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { box = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            job?.cancel()
                            val s0 = scale
                            val s1 = (s0 * zoom).coerceIn(1f, 6f)
                            val cx = centroid.x - box.width / 2f
                            val cy = centroid.y - box.height / 2f
                            val k = s1 / s0
                            val mx = box.width * (s1 - 1f) / 2f
                            val my = box.height * (s1 - 1f) / 2f
                            scale = s1
                            offX = (cx - (cx - offX) * k + pan.x).coerceIn(-mx, mx)
                            offY = (cy - (cy - offY) * k + pan.y).coerceIn(-my, my)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                job?.cancel()
                                job = scope.launch {
                                    val target = if (scale > 1.05f) 1f else 2.5f
                                    val mx = box.width * (target - 1f) / 2f
                                    val my = box.height * (target - 1f) / 2f
                                    val tx = if (target == 1f) 0f else (-(tap.x - box.width / 2f) * (target - 1f)).coerceIn(-mx, mx)
                                    val ty = if (target == 1f) 0f else (-(tap.y - box.height / 2f) * (target - 1f)).coerceIn(-my, my)
                                    val s0 = scale
                                    val x0 = offX
                                    val y0 = offY
                                    androidx.compose.animation.core.animate(
                                        0f, 1f, animationSpec = tween(320, easing = FastOutSlowInEasing)
                                    ) { f, _ ->
                                        scale = s0 + (target - s0) * f
                                        offX = x0 + (tx - x0) * f
                                        offY = y0 + (ty - y0) * f
                                    }
                                }
                            },
                            onTap = { if (scale <= 1.01f) close() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                shown?.let {
                    Image(
                        bitmap = it.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val open = 0.9f + 0.1f * p
                            scaleX = scale * open
                            scaleY = scale * open
                            translationX = offX
                            translationY = offY
                            alpha = p
                        }
                    )
                }
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(16.dp).graphicsLayer { alpha = p },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(Ic.CLOSE, Color.White, 26.dp, Modifier.clickable(onClick = close).padding(6.dp))
                Spacer(Modifier.width(10.dp))
                Text(item.title, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (full == null) "Loading full size…" else "Pinch to zoom · double-tap to zoom in or out · tap to close",
                color = Dim, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).graphicsLayer { alpha = p }
            )
        }
    }
}

@Composable
internal fun PreviewCard(source: File?, aesKey: ByteArray?) {
    val entries = remember { mutableStateListOf<PreviewItem>() }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        entries.clear()
        if (source == null) return@LaunchedEffect
        loading = true
        try {
            withContext(Dispatchers.IO) {
                Previews.load(source, aesKey, { !isActive }) { entries.add(it) }
            }
        } finally {
            loading = false
        }
    }

    val summary = when {
        source == null -> "Choose files to see them here"
        entries.isEmpty() && loading -> "Loading…"
        entries.isEmpty() -> "No textures found"
        loading -> "${entries.size} found so far…"
        else -> "${entries.size} textures"
    }

    Column(Modifier.fillMaxWidth().appear(0, 10f).background(Panel, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeIcon(Ic.IMAGE, Accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Preview Files", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(summary, color = Dim, fontSize = 12.sp)
            }
        }
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries.toList(), key = { it.id }) { PreviewTile(it) }
            }
        }
    }
}

@Composable
internal fun TextureConvert(ws: Workspace) {
    BackHandler { ws.convertMode = false }
    var source by remember { mutableStateOf<File?>(null) }
    var picking by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(true) }
    var block by remember { mutableStateOf(2) }
    var effort by remember { mutableStateOf(0.5f) }
    var blockMenu by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ConvertReport?>(null) }
    val cacheDir = LocalContext.current.cacheDir
    var cancel by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun start() {
        val src = source
        if (src == null) { picking = true; return }
        running = true; cancel = false; result = null; status = ""
        val key = ws.aesKey
        val bw = ASTC_BLOCKS[block].first
        val bh = ASTC_BLOCKS[block].second
        val doReplace = replace
        val quality = effort * 100f
        Thread {
            val r = try {
                Converter.run(src, key, bw, bh, doReplace, quality, cacheDir, { current = it }, { cancel })
            } catch (e: Throwable) {
                null
            }
            result = r
            if (r == null) status = "Something went wrong while converting."
            running = false
        }.start()
    }

    if (picking) {
        SourcePicker(
            start = source?.let { if (it.isDirectory) it else it.parentFile } ?: Environment.getExternalStorageDirectory(),
            onPick = { source = it; picking = false },
            onClose = { picking = false }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                Ic.BACK, Color.White, 24.dp,
                Modifier.clickable { ws.convertMode = false }.padding(end = 4.dp)
            )
            Column(Modifier.weight(1f)) {
                Text("Texture Format Conversion", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingCard(
                Ic.FOLDER, "Files to convert",
                source?.absolutePath ?: "Tap to choose a folder or file",
                { picking = true }
            )
            PreviewCard(source, ws.aesKey)
            Box {
                SettingCard(Ic.TEXTURE, "Output Quality", blockTitle(block), { blockMenu = true })
                DropdownMenu(expanded = blockMenu, onDismissRequest = { blockMenu = false }) {
                    ASTC_BLOCKS.indices.forEach { i ->
                        DropdownMenuItem(text = { Text(blockTitle(i)) }, onClick = { block = i; blockMenu = false })
                    }
                }
            }
            EffortSlider(effort) { effort = it }
            SettingCard(
                Ic.REFRESH, "",
                "Replace original files",
                { replace = !replace },
                trailing = {
                    androidx.compose.material3.Switch(checked = replace, onCheckedChange = { replace = it })
                }
            )

            if (running) {
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Working…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(current, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Cancel", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { cancel = true; Converter.abort() }.padding(top = 10.dp)
                    )
                }
            }

            result?.let { r ->
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text(
                        when {
                            r.cancelled -> "Stopped"
                            r.errors > 0 -> "Finished with problems"
                            else -> "Done"
                        },
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${r.converted} textures converted · ${r.files} texture files · ${r.archives} archives",
                        color = Color(0xFFE6E6EA), fontSize = 14.sp
                    )
                    if (r.embedded > 0) {
                        Text(
                            "Also found ${r.embedded} textures stored inside ${r.modelFiles} model files (.ydr, .ydd, .yft). " +
                                "They share memory with the model, so they are left as they are.",
                            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (r.converted > 0) {
                        Text(
                            "Size ${formatSize(r.before)} to ${formatSize(r.after)}",
                            color = Accent, fontSize = 14.sp
                        )
                    }
                    if (r.retainedBc > 0) {
                        Text(
                            "${r.retainedBc} textures in formats not supported yet were kept as they are",
                            color = Dim, fontSize = 13.sp
                        )
                    }
                    if (r.converted > 0 && replace) {
                        Text("Originals were backed up to the APEN V folder first.", color = Dim, fontSize = 13.sp)
                    }
                    if (r.errors > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("${r.errors} problems", color = Color(0xFFD98C8C), fontSize = 13.sp)
                        r.notes.take(6).forEach {
                            Text(it, color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (status.isNotEmpty()) Text(status, color = Dim, fontSize = 13.sp)
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .height(56.dp)
                .background(if (running) Picked else Accent, RoundedCornerShape(18.dp))
                .clickable(enabled = !running) { start() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Ic.CUBES, if (running) Dim else Bg, 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "Working…" else "Convert",
                    color = if (running) Dim else Bg, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
