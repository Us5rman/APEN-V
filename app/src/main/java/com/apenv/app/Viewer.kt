package com.apenv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
internal fun Viewer(ws: Workspace) {
    var textView by remember { mutableStateOf<String?>(null) }
    var textContent by remember { mutableStateOf("") }
    var infoView by remember { mutableStateOf<Pair<String, Leaf>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var modelSource by remember { mutableStateOf<ModelSource?>(null) }
    var modelStart by remember { mutableStateOf("") }
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    val inArchive = ws.viewArchive != null
    val items: List<Item> = remember(ws.viewArchive, ws.viewRpfPath, ws.viewDir, ws.viewItems) {
        if (inArchive) ws.viewArchiveItems()
        else ws.viewItems.map {
            Item(it.absolutePath, it.name, it.isDirectory, if (it.isDirectory) "Folder" else formatSize(it.length()))
        }
    }

    modelSource?.let { src ->
        ModelScreen(ws, src, modelStart) { modelSource = null }
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                Ic.BACK, Color.White, 24.dp,
                Modifier.clickable { ws.exitViewMode() }.padding(end = 4.dp)
            )
            Column(Modifier.weight(1f)) {
                Text("View models", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Read-only preview", color = Dim, fontSize = 11.sp)
            }
        }

        SearchBar(
            open = searchOpen,
            query = query,
            onOpen = { searchOpen = true },
            onClose = { searchOpen = false; query = "" },
            onQuery = { query = it }
        )

        Pane(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            title = if (inArchive) ws.viewArchiveBreadcrumb() else ws.viewTitle(),
            onUp = {
                if (inArchive) {
                    if (!ws.viewArchiveUp()) ws.viewUp()
                } else ws.viewUp()
            },
            items = items,
            query = query,
            selected = ws.viewSel,
            onTap = { item ->
                if (inArchive) {
                    when {
                        item.dir -> ws.viewArchiveInto(item.name)
                        viewKindOf(item.name) == ViewKind.MODEL -> {
                            ws.viewArchive?.let { modelSource = ModelSources.fromArchive(it, ws.viewRpfPath) }
                            modelStart = item.name
                        }
                        viewKindOf(item.name) == ViewKind.ARCHIVE -> ws.openNestedViewArchive(item.name)
                        viewKindOf(item.name) == ViewKind.TEXT -> {
                            textContent = ws.readViewLeafText(item.name)
                            textView = item.name
                        }
                        else -> {
                            ws.viewLeafInfo(item.name)?.let { infoView = item.name to it }
                        }
                    }
                } else {
                    val f = File(item.key)
                    when {
                        item.dir -> ws.goView(f)
                        viewKindOf(item.name) == ViewKind.MODEL -> {
                            modelSource = ModelSources.fromDir(f.parentFile ?: f)
                            modelStart = item.name
                        }
                        viewKindOf(item.name) == ViewKind.ARCHIVE -> ws.openViewArchive(f)
                        viewKindOf(item.name) == ViewKind.TEXT -> {
                            textContent = f.readText(Charsets.UTF_8)
                            textView = item.name
                        }
                        else -> {
                            infoView = item.name to Leaf(item.name, false, 0, 0, f.length(), false, 0, 0, f.length())
                        }
                    }
                }
            },
            onToggle = { },
            empty = { Text("Nothing here.", color = Dim) }
        )
    }

    textView?.let { name ->
        AlertDialog(
            onDismissRequest = { textView = null },
            title = { Text(name, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = textContent,
                    onValueChange = { textContent = it },
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ws.viewArchive != null) {
                        ws.writeLeafTextInView(name, textContent)
                    } else {
                        ws.viewDir?.let { File(it, name).writeText(textContent, Charsets.UTF_8) }
                    }
                    textView = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { textView = null }) { Text("Close") } }
        )
    }

    infoView?.let { (name, leaf) ->
        val kind = viewKindOf(name)
        AlertDialog(
            onDismissRequest = { infoView = null },
            title = { Text(name, fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        when (kind) {
                            ViewKind.TEXTURE -> "Texture file"
                            ViewKind.MODEL -> "Model file"
                            else -> "File"
                        },
                        color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Size: ${formatSize(leaf.shownSize)}", color = Dim, fontSize = 12.sp)
                    if (leaf.resource) Text("Resource (RSC7) version data present", color = Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (kind) {
                            ViewKind.TEXTURE -> "Pixel preview for .ytd is not supported yet — only file info is shown."
                            ViewKind.MODEL -> "3D preview for this model format is not supported yet — only file info is shown."
                            else -> "No preview available for this file type."
                        },
                        color = Dim, fontSize = 11.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { infoView = null }) { Text("Close") } }
        )
    }
}
