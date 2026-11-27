package com.apenv.app

import android.os.Environment
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

// The 3D viewer. It shows one model at a time and lists the other models that sit beside it,
// so a skin, an outfit or a car can be switched without leaving the screen.
@Composable
internal fun ModelScreen(ws: Workspace, source: ModelSource, start: String, onClose: () -> Unit) {
    var current by remember { mutableStateOf(start) }
    var extra by remember { mutableStateOf<File?>(null) }
    var picking by remember { mutableStateOf(false) }
    var scene by remember { mutableStateOf<Scene?>(null) }
    var status by remember { mutableStateOf("Loading…") }
    var loading by remember { mutableStateOf(true) }
    val holder = remember { arrayOfNulls<ModelGlView>(1) }
    val owner = LocalLifecycleOwner.current

    BackHandler(enabled = !picking) { onClose() }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(current, extra) {
        loading = true
        status = "Loading…"
        val result = withContext(Dispatchers.IO) {
            ModelLoader.load(source, current, extra, ws.aesKey) { !isActive }
        }
        scene = result.scene
        status = result.info
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { onClose() }.padding(end = 4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Crossfade(targetState = status, animationSpec = tween(180), label = "status") {
                        Text(it, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                    .appear(40)
                    .clip(RoundedCornerShape(20.dp)).background(Panel)
            ) {
                AndroidView(
                    factory = { ctx -> ModelGlView(ctx).also { holder[0] = it } },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.visibility = if (picking) View.INVISIBLE else View.VISIBLE
                        if (view.tag !== scene) {
                            view.tag = scene
                            view.show(scene)
                        }
                    },
                    onRelease = { it.onPause() }
                )
                if (loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = Accent, trackColor = Panel
                    )
                }
                if (!loading && scene == null) {
                    Text(
                        status, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                }
            }

            if (source.models.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp).appear(100),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(source.models, key = { it }) { name ->
                        ModelChip(name, name == current) { current = name }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp).appear(160),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionPill(Ic.FOLDER, extra?.name ?: "Textures folder", Modifier.weight(1f)) { picking = true }
                ActionPill(Ic.REFRESH, "Reset view", Modifier.weight(1f)) { holder[0]?.resetView() }
            }
        }

        if (picking) {
            SourcePicker(
                start = extra?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory(),
                onPick = {
                    extra = it
                    picking = false
                },
                onClose = { picking = false }
            )
        }
    }
}

@Composable
private fun ModelChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color(0xFF17302D) else Panel, tween(200), label = "chip")
    val fg by animateColorAsState(if (selected) Accent else Color.White, tween(200), label = "chipText")
    Row(
        Modifier.pressScale().clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(Ic.MODEL, fg, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(name, color = fg, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ActionPill(icon: Ic, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.pressScale().clip(RoundedCornerShape(16.dp)).background(Panel)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon, Accent, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
