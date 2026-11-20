package com.apenv.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.launch

internal class Prompt(val title: String, val initial: String, val ok: (String) -> Unit)

@Composable
internal fun Editor(ws: Workspace) {
    val ctx = LocalContext.current

    var menu by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var showBackups by remember { mutableStateOf(false) }
    var showRoots by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(4097)
                buf.copyOf(maxOf(s.read(buf), 0))
            }
            ws.setKey(bytes ?: ByteArray(0))
        }
    }

    val archive = ws.archive

    val gameItems: List<Item> = remember(ws.archive, ws.rpfPath, ws.rev, ws.gameDir, ws.gameItems) {
        if (archive != null) ws.rpfItems()
        else ws.gameItems.map {
            Item(it.absolutePath, it.name, it.isDirectory, if (it.isDirectory) "Folder" else formatSize(it.length()))
        }
    }
    val devItems = ws.deviceItems.map {
        Item(it.absolutePath, it.name, it.isDirectory, if (it.isDirectory) "Folder" else formatSize(it.length()))
    }

    var fabOpen by remember { mutableStateOf(false) }
    val fabRot by animateFloatAsState(if (fabOpen) 45f else 0f, tween(220), label = "fabRot")
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetTitle by remember { mutableStateOf("") }
    var sheetRename by remember { mutableStateOf(false) }
    val sheetProgress by animateFloatAsState(if (sheetOpen) 1f else 0f, tween(300), label = "sheet")
    BackHandler(enabled = archive != null) { ws.rpfUp() }
    BackHandler(enabled = sheetOpen) { sheetOpen = false }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    val selCount = if (ws.activePane == 0) ws.selRpf.size else ws.selDev.size
    val hasSelection = selCount > 0
    // Long-press while something is already selected opens the action sheet.
    val openSheet: () -> Unit = {
        sheetTitle = when {
            selCount != 1 -> "$selCount selected"
            ws.activePane == 0 -> ws.selRpf.firstOrNull()?.let { File(it).name } ?: ""
            else -> ws.selDev.firstOrNull()?.let { File(it).name } ?: ""
        }
        sheetRename = selCount == 1
        sheetOpen = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    Ic.BACK, Color.White, 24.dp,
                    Modifier.clickable { ws.editMode = false }.padding(end = 4.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text("APEN V", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text("File workspace", color = Dim, fontSize = 12.sp)
                }
                Box {
                    AppIcon(Ic.MORE, Color.White, 22.dp, Modifier.clickable { menu = true }.padding(8.dp))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Set AES key") }, onClick = {
                            menu = false
                            keyPicker.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("Backups") }, onClick = {
                            menu = false
                            if (archive == null) ws.notice = "Open a game archive first" else showBackups = true
                        })
                        DropdownMenuItem(text = { Text("Choose another folder") }, onClick = {
                            menu = false
                            showRoots = true
                        })
                        DropdownMenuItem(text = { Text("Close archive") }, onClick = {
                            menu = false
                            ws.closeRpf()
                        })
                    }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                PaneTab("Device Storage", devItems.size, ws.activePane == 1, Modifier.weight(1f)) { ws.activePane = 1 }
                PaneTab("Game files", gameItems.size, ws.activePane == 0, Modifier.weight(1f)) { ws.activePane = 0 }
            }

            // One search bar for both panes. Opening it animates the path row down.
            SearchBar(
                open = searchOpen,
                query = query,
                onOpen = { searchOpen = true },
                onClose = { searchOpen = false; query = "" },
                onQuery = { query = it },
                loading = ws.busy
            )

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Pane(
                    modifier = Modifier.weight(1f),
                    title = ws.deviceTitle(),
                    onUp = { ws.deviceUp() },
                    items = devItems,
                    query = if (ws.activePane == 1) query else "",
                    selected = ws.selDev,
                    onTap = {
                        val f = File(it.key)
                        when {
                            it.dir -> ws.goDevice(f)
                            f.extension.equals("zip", true) -> ws.openZip(f)
                            else -> Unit
                        }
                    },
                    onToggle = { ws.toggleDev(it.key) },
                    onSheet = openSheet,
                    empty = { Text("Nothing here.", color = Dim) }
                )
                Box(Modifier.width(1.dp).fillMaxSize().background(Bar))
                Pane(
                    modifier = Modifier.weight(1f),
                    title = if (archive != null) ws.rpfBreadcrumb() else ws.gameTitle(),
                    onUp = { if (archive != null) ws.rpfUp() else ws.gameUp() },
                    items = gameItems,
                    query = if (ws.activePane == 0) query else "",
                    selected = ws.selRpf,
                    onTap = { item ->
                        if (archive != null) {
                            if (item.dir) ws.rpfInto(item.name)
                            else if (item.name.substringAfterLast('.', "").equals("rpf", true)) ws.openRpfLeafByName(item.name)
                        } else {
                            val f = File(item.key)
                            if (item.dir) ws.goGame(f)
                            else if (f.extension.equals("rpf", true)) ws.openRpf(f)
                        }
                    },
                    onToggle = { ws.toggleRpf(it.key) },
                    onSheet = openSheet,
                    empty = {
                        Text(
                            if (archive == null) "No archive open here. Browse into a folder or tap a .rpf to open it."
                            else "This folder is empty.",
                            color = Dim
                        )
                    }
                )
            }
        }

        // Action sheet: one animation value drives both the dark tint and the slide, so they
        // move together. Content is snapshotted at open so it doesn't change while sliding out.
        if (sheetProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f * sheetProgress))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        sheetOpen = false
                    }
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = size.height * (1f - sheetProgress) }
            ) {
                ActionSheet(
                    title = sheetTitle,
                    extract = ws.activePane == 0,
                    onImport = { sheetOpen = false; ws.transferSelected() },
                    onRename = if (sheetRename) ({
                        sheetOpen = false
                        if (ws.activePane == 0) prompt = Prompt("Rename", File(ws.selRpf.first()).name) { ws.rpfRename(it) }
                        else prompt = Prompt("Rename", File(ws.selDev.first()).name) { ws.deviceRename(it) }
                    }) else null,
                    onDelete = {
                        sheetOpen = false
                        confirm = "Delete $selCount item(s)? ${if (ws.activePane == 0) "A backup is made first." else ""}" to {
                            if (ws.activePane == 0) ws.rpfDelete() else ws.deviceDelete()
                        }
                    }
                )
            }
        }

        // Backdrop blur sits at the top level so it truly fills the whole screen,
        // not just the small Box the FAB lives in (that was the stretched/oval bug).
        if (fabOpen) {
            BlurScrim(onDismiss = { fabOpen = false })
        }

        // Floating "+" menu: create a new file or folder in the currently active pane.
        if (sheetProgress < 0.01f) Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.animation.AnimatedVisibility(visible = fabOpen) {
                    Column(horizontalAlignment = Alignment.End) {
                        FabAction("New folder") {
                            fabOpen = false
                            prompt = if (ws.activePane == 0) {
                                Prompt("New folder", "") { ws.rpfNewFolder(it) }
                            } else {
                                Prompt("New folder", "") { ws.deviceNewFolder(it) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FabAction("New file") {
                            fabOpen = false
                            prompt = if (ws.activePane == 0) {
                                Prompt("New file", "") { ws.rpfNewFile(it) }
                            } else {
                                Prompt("New file", "") { ws.deviceNewFile(it) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Accent, androidx.compose.foundation.shape.CircleShape)
                        .clickable {
                            if (ws.activePane == 0 && archive == null) {
                                ws.notice = "Open a game archive first"
                            } else {
                                fabOpen = !fabOpen
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(Ic.PLUS, Color.Black, 24.dp, Modifier.rotate(fabRot), strokeWidth = 2.4f)
                }
            }
        }
    }

    prompt?.let { p ->
        var text by remember(p) { mutableStateOf(p.initial) }
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(p.title) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { prompt = null; p.ok(text.trim()) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } }
        )
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }

    ws.notice?.let {
        AlertDialog(
            onDismissRequest = { ws.notice = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { ws.notice = null }) { Text("OK") } }
        )
    }

    val backupFile = ws.backupTarget()
    if (showBackups && backupFile != null) {
        val backups = Backups.list(backupFile)
        AlertDialog(
            onDismissRequest = { showBackups = false },
            title = { Text("Backups") },
            text = {
                if (backups.isEmpty()) Text("No backups yet.")
                else Column {
                    backups.forEach { f ->
                        Text(
                            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(f.lastModified())) + " · " + formatSize(f.length()),
                            modifier = Modifier.fillMaxWidth().clickable {
                                showBackups = false
                                confirm = "Restore this backup over ${backupFile.name}?" to { ws.restoreBackup(f) }
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBackups = false }) { Text("Close") } }
        )
    }

    if (showRoots) {
        AlertDialog(
            onDismissRequest = { showRoots = false },
            title = { Text("Choose a location") },
            text = {
                Column {
                    ws.deviceRoots().forEachIndexed { i, f ->
                        Text(
                            if (i == 0) "Internal storage" else f.name,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showRoots = false
                                ws.goDevice(f)
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoots = false }) { Text("Close") } }
        )
    }

    if (ws.zipFile != null) {
        val zipItems = remember(ws.zipFile, ws.zipPath) {
            ws.zipItems()
        }
        AlertDialog(
            onDismissRequest = { ws.closeZip() },
            title = { Text(ws.zipTitle(), fontSize = 14.sp) },
            text = {
                Column(Modifier.height(380.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { ws.zipUp() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(Ic.UP, Dim, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Up", color = Dim, fontSize = 12.sp)
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(zipItems, key = { it.key }) { item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (item.dir) ws.zipInto(item.name)
                                        else ws.extractZipEntry(item.key, ws.deviceDir)
                                    }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FileIcon(item.name, item.dir)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (item.dir) "Folder" else item.info, color = Dim, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Text(
                        "Tap a file to extract it to the current Device Storage folder.",
                        color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { ws.closeZip(); ws.refreshDevice() }) { Text("Close") } }
        )
    }
}
