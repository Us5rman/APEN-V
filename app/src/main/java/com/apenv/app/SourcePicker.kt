package com.apenv.app

import android.os.Environment
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// The same list panel as the editor, on its own: browse folders, no editing actions.
// Tap a folder to go in; tap a .ytd, .rpf or .zip file to pick just that file.
@Composable
internal fun SourcePicker(
    start: File,
    onPick: (File) -> Unit,
    onClose: () -> Unit,
    heading: String = "Choose files",
    accepts: (File) -> Boolean = {
        it.extension.equals("ytd", true) || it.extension.equals("rpf", true) || it.extension.equals("zip", true)
    },
    visible: (File) -> Boolean = { true },
    pickFolder: Boolean = true
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(start) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val atRoot = dir.absolutePath == root.absolutePath || dir.parentFile == null
    val goUp: () -> Unit = { if (!atRoot) dir.parentFile?.let { dir = it } }

    BackHandler { if (atRoot) onClose() else goUp() }
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    val items = remember(dir) {
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { !it.name.startsWith(".") && visible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { Item(it.absolutePath, it.name, it.isDirectory, if (it.isDirectory) "Folder" else formatSize(it.length())) }
    }
    val title = "Internal storage" + dir.absolutePath.removePrefix(root.absolutePath)

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ic.BACK, Color.White, 24.dp, Modifier.clickable { onClose() }.padding(end = 4.dp))
            Text(heading, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
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
            title = title,
            onUp = goUp,
            items = items,
            query = query,
            selected = emptySet(),
            onTap = {
                val f = File(it.key)
                when {
                    it.dir -> { dir = f; query = ""; searchOpen = false }
                    accepts(f) -> onPick(f)
                    else -> Unit
                }
            },
            onToggle = { },
            empty = { Text("Nothing here.", color = Dim) }
        )
        if (pickFolder) Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .height(56.dp)
                .background(Accent, RoundedCornerShape(18.dp))
                .clickable { onPick(dir) },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Ic.CHECK, Bg, 22.dp)
                Spacer(Modifier.width(10.dp))
                Text("Use this folder", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
