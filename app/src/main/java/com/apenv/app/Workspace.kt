package com.apenv.app

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class Item(val key: String, val name: String, val dir: Boolean, val info: String)

private val textExt = setOf(
    "xml", "txt", "ini", "meta", "lua", "json", "log", "dat", "cfg", "gxt2", "csv"
)
private val textureExt = setOf("ytd")
private val modelExt = setOf("ydr", "yft", "ydd")

enum class ViewKind { TEXT, TEXTURE, MODEL, ARCHIVE, OTHER }

fun viewKindOf(name: String): ViewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "rpf" -> ViewKind.ARCHIVE
        ext in textExt -> ViewKind.TEXT
        ext in textureExt -> ViewKind.TEXTURE
        ext in modelExt -> ViewKind.MODEL
        else -> ViewKind.OTHER
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1L shl 20 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1L shl 30 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

class Workspace(private val app: Application) : AndroidViewModel(app) {
    private val keyFile = File(app.filesDir, "aes.key")
    private val storageRoot = Environment.getExternalStorageDirectory()
    private val prefs = app.getSharedPreferences("apenv", 0)

    var granted by mutableStateOf(false)
    var editMode by mutableStateOf(false)
    var viewMode by mutableStateOf(false)
    var convertMode by mutableStateOf(false)
    var scanMode by mutableStateOf(false)
    val scan = ScanController()
    val aesKey: ByteArray? get() = key

    // Independent browse state for the Viewer screen, separate from the Editor's
    // gameDir/archive so opening the viewer never disturbs an in-progress edit session.
    var viewArchive by mutableStateOf<RpfArchive?>(null)
    var viewRpfPath by mutableStateOf(listOf<String>())
    var viewDir by mutableStateOf<File?>(null)
    var viewItems by mutableStateOf(listOf<File>())
    var viewSel by mutableStateOf(setOf<String>())
    private val viewArchiveStack = mutableListOf<ArchiveFrame>()
    private val viewTempFiles = mutableListOf<File>()
    var archive by mutableStateOf<RpfArchive?>(null)
    var rpfPath by mutableStateOf(listOf<String>())
    var rev by mutableIntStateOf(0)
    var gameRoot by mutableStateOf<File?>(
        prefs.getString("gameRootPath", null)?.let { File(it) }?.takeIf { it.isDirectory }
    )
    var gameDir by mutableStateOf<File?>(gameRoot)
    var gameItems by mutableStateOf(listOf<File>())
    var deviceDir by mutableStateOf(storageRoot)
    var deviceItems by mutableStateOf(listOf<File>())

    // Stack of nested archives: index 0 is the archive opened from disk,
    // each entry after that is an .rpf opened from inside the previous one.
    // archive always mirrors stack.last() when the stack is non-empty.
    private class ArchiveFrame(
        val arc: RpfArchive,
        val rpfPath: List<String>,
        val tempFile: File?,
        val leafName: String = ""
    )
    private val archiveStack = mutableListOf<ArchiveFrame>()

    // .zip browsing, separate from the .rpf archive stack since it's a different format.
    var zipFile by mutableStateOf<java.util.zip.ZipFile?>(null)
    var zipPath by mutableStateOf("")
    private var zipSourceFile: File? = null

    fun openZip(file: File) = work("Opening ${file.name}") {
        zipFile?.close()
        zipFile = java.util.zip.ZipFile(file)
        zipSourceFile = file
        zipPath = ""
        selRpf = emptySet()
    }

    fun closeZip() {
        zipFile?.close()
        zipFile = null
        zipPath = ""
        selRpf = emptySet()
    }

    fun zipItems(): List<Item> {
        val zf = zipFile ?: return emptyList()
        val prefix = zipPath
        val seen = LinkedHashMap<String, Item>()
        zf.entries().asSequence().forEach { e ->
            if (!e.name.startsWith(prefix)) return@forEach
            val rest = e.name.removePrefix(prefix)
            if (rest.isEmpty()) return@forEach
            val slash = rest.indexOf('/')
            if (slash < 0) {
                seen[rest] = Item(prefix + rest, rest, false, formatSize(e.size))
            } else {
                val folder = rest.substring(0, slash)
                seen.putIfAbsent(folder, Item(prefix + folder + "/", folder, true, "Folder"))
            }
        }
        return seen.values.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    }

    fun zipInto(folderName: String) {
        zipPath += "$folderName/"
        selRpf = emptySet()
    }

    fun zipUp() {
        if (zipPath.isEmpty()) return
        zipPath = zipPath.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        selRpf = emptySet()
    }

    fun zipTitle(): String = (zipSourceFile?.name ?: "zip") + (if (zipPath.isEmpty()) "" else " / " + zipPath.trimEnd('/'))

    fun extractZipEntry(entryName: String, destDir: File) = work("Extracting") {
        val zf = zipFile ?: return@work
        val entry = zf.getEntry(entryName) ?: return@work
        val out = File(destDir, entryName.substringAfterLast('/'))
        zf.getInputStream(entry).use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
    }
    var selRpf by mutableStateOf(setOf<String>())
    var selDev by mutableStateOf(setOf<String>())
    var activePane by mutableIntStateOf(1)
    var busy by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    private var key: ByteArray? = if (keyFile.exists()) keyFile.readBytes() else null

    init {
        val oldBackups = File(storageRoot, "RPFManager/backups")
        val newBackups = File(storageRoot, "APEN V")
        if (oldBackups.isDirectory && !newBackups.exists()) oldBackups.renameTo(newBackups)
        Backups.init(newBackups)
    }

    private fun work(label: String, block: () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                notice = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
                refreshDevice()
                rev++
            }
        }
    }

    fun refreshDevice() {
        deviceItems = (deviceDir.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun goDevice(dir: File) {
        deviceDir = dir
        selDev = emptySet()
        refreshDevice()
    }

    fun deviceUp() {
        deviceDir.parentFile?.takeIf { it.canRead() }?.let { goDevice(it) }
    }

    fun deviceRoots(): List<File> {
        val roots = mutableListOf(storageRoot)
        app.getExternalFilesDirs(null).forEach { f ->
            val path = f?.absolutePath ?: return@forEach
            val cut = path.indexOf("/Android/data")
            if (cut > 0) File(path.substring(0, cut)).let { if (it !in roots) roots += it }
        }
        return roots
    }

    // GTA V has no official Android release, so there's no fixed package name to rely on.
    // Check a few common unofficial-port locations first, then fall back to scanning
    // top-level folders for anything named like a GTA V install.
    private val gtaCandidates = listOf(
        "Android/data/com.rockstargames.gtav/files",
        "Android/obb/com.rockstargames.gtav",
        "GTAV", "GTA5", "GTA V", "GTA 5"
    )

    fun gtaRoot(): File? {
        gtaCandidates.map { File(storageRoot, it) }.firstOrNull { it.isDirectory }?.let { return it }
        return listOf(storageRoot, File(storageRoot, "Android/data"), File(storageRoot, "Android/obb"))
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .firstOrNull { f ->
                f.isDirectory && Regex("gta.?v\\b|gta.?5", RegexOption.IGNORE_CASE).containsMatchIn(f.name)
            }
    }

    fun goGta() {
        goDevice(gtaRoot() ?: storageRoot)
    }

    fun refreshGame() {
        gameItems = (gameDir?.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun goGame(dir: File) {
        gameDir = dir
        selRpf = emptySet()
        refreshGame()
    }

    fun gameUp() {
        val root = gameRoot
        val parent = gameDir?.parentFile?.takeIf { it.canRead() } ?: return
        if (root != null && gameDir == root) return
        goGame(parent)
    }

    fun gameTitle(): String = gameDir?.name ?: "No folder chosen"

    // Called once from the home screen to pick which folder "Game files" browses.
    // This is the only place gameRoot/gameDir should be set from scratch.
    fun chooseGameFolder(dir: File) {
        archive = null
        rpfPath = emptyList()
        selRpf = emptySet()
        gameRoot = dir
        prefs.edit().putString("gameRootPath", dir.absolutePath).apply()
        goGame(dir)
    }

    fun enterEditMode() {
        if (gameRoot == null || !granted) return
        editMode = true
    }

    fun enterViewMode() {
        if (gameRoot == null || !granted) return
        viewMode = true
        if (viewDir == null) goView(gameRoot!!)
    }

    private fun closeViewArchive() {
        viewArchiveStack.clear()
        viewTempFiles.forEach { it.delete() }
        viewTempFiles.clear()
        viewArchive = null
        viewRpfPath = emptyList()
        viewSel = emptySet()
    }

    fun exitViewMode() {
        closeViewArchive()
        viewMode = false
    }

    fun refreshView() {
        viewItems = (viewDir?.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun goView(dir: File) {
        viewDir = dir
        viewSel = emptySet()
        refreshView()
    }

    fun viewUp() {
        val parent = viewDir?.parentFile?.takeIf { it.canRead() } ?: return
        if (viewDir == gameRoot) return
        goView(parent)
    }

    fun viewTitle(): String = viewDir?.name ?: "Game files"

    fun viewArchiveBreadcrumb(): String =
        (viewArchiveStack.map { f -> f.leafName.ifEmpty { f.arc.file.name } } + viewRpfPath).joinToString(" / ")

    fun openViewArchive(file: File) = work("Opening ${file.name}") {
        viewArchiveStack.clear()
        viewArchiveStack += ArchiveFrame(RpfArchive(file, key), emptyList(), null, file.name)
        viewArchive = viewArchiveStack.last().arc
        viewRpfPath = emptyList()
        viewSel = emptySet()
    }

    fun viewArchiveItems(): List<Item> {
        val a = viewArchive ?: return emptyList()
        return a.dirAt(viewRpfPath).kids.sortedWith(compareBy({ it is Leaf }, { it.name.lowercase() })).map {
            when (it) {
                is Dir -> Item(it.name, it.name, true, "Folder")
                is Leaf -> Item(
                    it.name, it.name, false,
                    "${it.name.substringAfterLast('.', "file").uppercase()} · ${formatSize(it.shownSize)}"
                )
            }
        }
    }

    fun viewArchiveInto(name: String) {
        viewRpfPath = viewRpfPath + name
        viewSel = emptySet()
    }

    fun viewArchiveUp(): Boolean {
        if (viewRpfPath.isNotEmpty()) {
            viewRpfPath = viewRpfPath.dropLast(1)
            viewSel = emptySet()
            return true
        }
        if (viewArchiveStack.size > 1) {
            val closing = viewArchiveStack.removeAt(viewArchiveStack.lastIndex)
            viewArchive = viewArchiveStack.last().arc
            viewRpfPath = closing.rpfPath
            viewSel = emptySet()
            return true
        }
        if (viewArchiveStack.isNotEmpty()) {
            closeViewArchive()
            return true
        }
        return false
    }

    fun openNestedViewArchive(name: String) {
        val a = viewArchive ?: return
        val leaf = a.dirAt(viewRpfPath).kids.filterIsInstance<Leaf>().firstOrNull { it.name == name } ?: return
        val pathAtOpen = viewRpfPath
        work("Opening ${leaf.name}") {
            val tmp = File(app.cacheDir, "view_${System.nanoTime()}_${leaf.name}")
            tmp.outputStream().buffered().use { a.extract(leaf, it) }
            viewTempFiles += tmp
            viewArchiveStack += ArchiveFrame(RpfArchive(tmp, key, false), pathAtOpen, tmp, leaf.name)
            viewArchive = viewArchiveStack.last().arc
            viewRpfPath = emptyList()
            viewSel = emptySet()
        }
    }

    fun readViewLeafText(name: String): String {
        val a = viewArchive ?: throw IllegalStateException("No archive open")
        val leaf = a.dirAt(viewRpfPath).kids.filterIsInstance<Leaf>().first { it.name == name }
        return String(a.extractToBytes(leaf), Charsets.UTF_8)
    }

    fun writeLeafTextInView(name: String, text: String) {
        val a = viewArchive ?: return
        val path = viewRpfPath
        work("Saving $name") {
            val tmp = File(app.cacheDir, "viewedit_${System.nanoTime()}")
            tmp.writeText(text, Charsets.UTF_8)
            try {
                a.importAs(path, listOf(name to tmp))
                syncStack(viewArchiveStack)
            } finally {
                tmp.delete()
            }
        }
    }

    fun viewLeafInfo(name: String): Leaf? {
        val a = viewArchive ?: return null
        return a.dirAt(viewRpfPath).kids.filterIsInstance<Leaf>().firstOrNull { it.name == name }
    }

    fun deviceTitle(): String = if (deviceDir == storageRoot) "Internal storage" else deviceDir.name

    fun toggleDev(path: String) {
        activePane = 1
        selDev = if (path in selDev) selDev - path else selDev + path
    }

    fun toggleRpf(name: String) {
        activePane = 0
        selRpf = if (name in selRpf) selRpf - name else selRpf + name
    }

    fun rpfInto(name: String) {
        activePane = 0
        rpfPath = rpfPath + name
        selRpf = emptySet()
    }

    fun rpfUp() {
        if (rpfPath.isNotEmpty()) {
            rpfPath = rpfPath.dropLast(1)
            selRpf = emptySet()
        } else if (archiveStack.size > 1) {
            val closing = archiveStack.removeAt(archiveStack.lastIndex)
            closing.tempFile?.delete()
            archive = archiveStack.last().arc
            rpfPath = closing.rpfPath
            selRpf = emptySet()
        } else if (archiveStack.isNotEmpty()) {
            closeRpf()
        }
    }

    fun rpfItems(): List<Item> {
        val dir = archive?.dirAt(rpfPath) ?: return emptyList()
        return dir.kids.sortedWith(compareBy({ it is Leaf }, { it.name.lowercase() })).map {
            when (it) {
                is Dir -> Item(it.name, it.name, true, "Folder")
                is Leaf -> Item(
                    it.name, it.name, false,
                    "${it.name.substringAfterLast('.', "file").uppercase()} · ${formatSize(it.shownSize)}"
                )
            }
        }
    }

    fun openRpf(file: File) = work("Opening ${file.name}") {
        archiveStack.clear()
        archiveStack += ArchiveFrame(RpfArchive(file, key), emptyList(), null, file.name)
        archive = archiveStack.last().arc
        rpfPath = emptyList()
        selRpf = emptySet()
    }

    // Reads a leaf's content as text for the text viewer/editor.
    fun readLeafText(name: String): String {
        val a = archive ?: throw IllegalStateException("No archive open")
        val leaf = a.dirAt(rpfPath).kids.filterIsInstance<Leaf>().first { it.name == name }
        return String(a.extractToBytes(leaf), Charsets.UTF_8)
    }

    // Writes edited text back into the archive at the current path, replacing the leaf.
    fun writeLeafText(name: String, text: String) {
        val a = archive ?: return
        val path = rpfPath
        work("Saving $name") {
            val tmp = File(app.cacheDir, "edit_${System.nanoTime()}")
            tmp.writeText(text, Charsets.UTF_8)
            try {
                a.importAs(path, listOf(name to tmp))
                syncNested()
            } finally {
                tmp.delete()
            }
        }
    }

    // Reads a leaf's raw content bytes (decompressed/decrypted but not RSC-stripped) for preview.
    fun readLeafBytes(name: String): ByteArray {
        val a = archive ?: throw IllegalStateException("No archive open")
        val leaf = a.dirAt(rpfPath).kids.filterIsInstance<Leaf>().first { it.name == name }
        return a.extractToBytes(leaf)
    }

    // Looks up a leaf by name at the current rpfPath and opens it as a nested archive
    // if it's an .rpf file. Called from the UI when a leaf item is tapped.
    fun openRpfLeafByName(name: String) {
        val a = archive ?: return
        val leaf = a.dirAt(rpfPath).kids.filterIsInstance<Leaf>().firstOrNull { it.name == name } ?: return
        if (name.substringAfterLast('.', "").equals("rpf", true)) openNestedRpf(leaf)
    }

    // Opens an .rpf that sits as a leaf inside the currently open archive.
    // Extracts it to a temp file first since RpfArchive reads from a real File.
    fun openNestedRpf(leaf: Leaf) {
        val parent = archive ?: return
        val pathAtOpen = rpfPath
        work("Opening ${leaf.name}") {
            val tmp = File(app.cacheDir, "nested_${System.nanoTime()}_${leaf.name}")
            tmp.outputStream().buffered().use { parent.extract(leaf, it) }
            val nested = RpfArchive(tmp, key, false)
            archiveStack += ArchiveFrame(nested, pathAtOpen, tmp, leaf.name)
            archive = nested
            rpfPath = emptyList()
            selRpf = emptySet()
        }
    }

    // True breadcrumb across every nested archive currently open, e.g.
    // "update.rpf / x64 / levels.rpf / common"
    fun rpfBreadcrumb(): String {
        val parts = archiveStack.map { f -> f.leafName.ifEmpty { f.arc.file.name } } + rpfPath
        return parts.joinToString(" / ")
    }

    fun closeRpf() {
        archiveStack.forEach { it.tempFile?.delete() }
        archiveStack.clear()
        archive = null
        rpfPath = emptyList()
        selRpf = emptySet()
        refreshGame()
    }

    fun setKey(bytes: ByteArray) {
        val parsed = if (bytes.size == 32) {
            bytes
        } else {
            String(bytes).filterNot { it.isWhitespace() }
                .takeIf { s -> s.length == 64 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        }
        if (parsed == null) {
            notice = "The key must be a 32-byte file or 64 hex characters"
            return
        }
        keyFile.writeBytes(parsed)
        key = parsed
        archiveStack.firstOrNull()?.let { openRpf(it.arc.file) }
    }

    private fun syncStack(stack: List<ArchiveFrame>) {
        for (i in stack.lastIndex downTo 1) {
            val frame = stack[i]
            stack[i - 1].arc.importAs(frame.rpfPath, listOf(frame.leafName to frame.arc.file))
        }
    }

    private fun syncNested() = syncStack(archiveStack)

    fun backupTarget(): File? = archiveStack.firstOrNull()?.arc?.file

    fun extractSelected() {
        val a = archive ?: return
        val chosen = a.dirAt(rpfPath).kids.filter { it.name in selRpf }
        val dest = deviceDir
        work("Extracting") {
            chosen.forEach { extractNode(a, it, dest) }
            selRpf = emptySet()
        }
    }

    private fun extractNode(a: RpfArchive, node: Node, dest: File) {
        when (node) {
            is Dir -> {
                val folder = File(dest, node.name).apply { mkdirs() }
                node.kids.forEach { extractNode(a, it, folder) }
            }
            is Leaf -> File(dest, node.name).outputStream().buffered().use { a.extract(node, it) }
        }
    }

    fun importSelected() {
        val a = archive ?: return
        val files = selDev.map { File(it) }.filter { it.isFile }
        if (files.isEmpty()) {
            notice = "Select one or more files in Device files first"
            return
        }
        val path = rpfPath
        work("Importing") {
            a.importFiles(path, files)
            syncNested()
            selDev = emptySet()
        }
    }

    fun rpfDelete() {
        val a = archive ?: return
        val path = rpfPath
        val names = selRpf
        work("Deleting") {
            a.delete(path, names)
            syncNested()
            selRpf = emptySet()
        }
    }

    fun rpfNewFolder(name: String) {
        val a = archive ?: return
        val path = rpfPath
        work("Creating folder") {
            a.newFolder(path, name)
            syncNested()
        }
    }

    fun rpfRename(name: String) {
        val a = archive ?: return
        val old = selRpf.singleOrNull() ?: return
        val path = rpfPath
        work("Renaming") {
            a.rename(path, old, name)
            syncNested()
            selRpf = emptySet()
        }
    }

    fun deviceRename(name: String) {
        val old = selDev.singleOrNull()?.let { File(it) } ?: return
        work("Renaming") {
            val target = File(old.parentFile, name)
            if (!old.renameTo(target)) notice = "Could not rename \"${old.name}\""
            selDev = emptySet()
        }
    }

    fun deviceNewFolder(name: String) = work("Creating folder") {
        if (!File(deviceDir, name).mkdirs()) notice = "Could not create \"$name\""
    }

    fun deviceNewFile(name: String) = work("Creating file") {
        val f = File(deviceDir, name)
        if (f.exists() || !f.createNewFile()) notice = "Could not create \"$name\""
    }

    fun rpfNewFile(name: String) {
        val a = archive ?: return
        val path = rpfPath
        work("Creating file") {
            val tmp = File(app.cacheDir, "newfile_${System.nanoTime()}")
            tmp.createNewFile()
            try {
                a.importAs(path, listOf(name to tmp))
                syncNested()
            } finally {
                tmp.delete()
            }
        }
    }

    // Copies the selection from the active pane into the folder the other pane is showing.
    // Nothing is removed from the source. Works folder to folder, archive to folder and
    // folder to archive.
    fun transferSelected() {
        val fromGame = activePane == 0
        val a = archive
        val path = rpfPath
        val gameDest = gameDir
        val devDest = deviceDir
        val gameSel = selRpf
        val devSel = selDev
        if (fromGame && gameSel.isEmpty()) return
        if (!fromGame && devSel.isEmpty()) return
        if (!fromGame && a == null && gameDest == null) {
            notice = "Choose a game folder first"
            return
        }
        work(if (fromGame) "Extracting" else "Importing") {
            if (fromGame) {
                if (a != null) {
                    a.dirAt(path).kids.filter { it.name in gameSel }.forEach { extractNode(a, it, devDest) }
                } else {
                    gameSel.map { File(it) }.forEach { copyInto(it, devDest) }
                }
                selRpf = emptySet()
            } else {
                val sources = devSel.map { File(it) }
                if (a != null) {
                    val files = sources.filter { it.isFile }
                    if (files.size < sources.size) notice = "Only files can go into an archive. Folders were skipped."
                    if (files.isNotEmpty()) {
                        a.importFiles(path, files)
                        syncNested()
                    }
                } else {
                    sources.forEach { copyInto(it, gameDest!!) }
                }
                selDev = emptySet()
            }
            refreshGame()
        }
    }

    private fun copyInto(src: File, destDir: File) {
        val target = File(destDir, src.name)
        if (src.absolutePath == target.absolutePath) return
        if (src.isDirectory && target.absolutePath.startsWith(src.absolutePath + "/")) return
        src.copyRecursively(target, overwrite = true)
    }

    fun deviceDelete() {
        val targets = selDev.map { File(it) }
        work("Deleting") {
            targets.forEach { it.deleteRecursively() }
            selDev = emptySet()
        }
    }

    fun restoreBackup(backup: File) {
        val top = archiveStack.firstOrNull()?.arc ?: return
        work("Restoring backup") {
            Backups.restore(top.file, backup)
            val fresh = RpfArchive(top.file, key)
            archiveStack.forEach { it.tempFile?.delete() }
            archiveStack.clear()
            archiveStack += ArchiveFrame(fresh, emptyList(), null, top.file.name)
            archive = fresh
            rpfPath = emptyList()
            selRpf = emptySet()
        }
    }
}
