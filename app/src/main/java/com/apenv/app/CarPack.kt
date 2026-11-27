package com.apenv.app

import android.content.Context
import android.net.Uri
import java.io.File

// Adds a car: saves it as mods/update/android/dlcpacks/<folderName>/dlc.rpf and
// registers <modelName> in mods/update/common/data/dlclist.xml.
// Model name is detected automatically from vehicles.meta inside the RPF.
// If detection fails, onNeedModel is called and the function returns null so the
// caller can ask the user for the name and retry with overrideModel set.
object CarPack {
    // Set once at startup to the app's private files folder
    lateinit var store: File

    // The list of added cars lives in private storage. An old copy in the mods folder is merged in and removed.
    private fun addedList(mods: File): File {
        val mine = File(store, "cars_added.txt")
        val old = File(mods, "update/cars_added.txt")
        if (old.isFile) runCatching {
            val merged = ((if (mine.isFile) mine.readLines() else emptyList()) + old.readLines())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            mine.writeText(merged.joinToString("\n"))
            old.delete()
        }
        return mine
    }

    // Strips leading/trailing whitespace and illegal chars; spaces become spaces (not underscores).
    fun cleanName(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

    // Folder names never use spaces — replace them with nothing (compact) so "my m3 pack" → "mym3pack".
    private fun folderName(s: String) = cleanName(s).replace(" ", "")

    // Model names are single tokens — strip spaces entirely.
    private fun modelName(s: String) = cleanName(s).replace(" ", "")

    /**
     * Returns true on success, false on failure, null when the model name could not be detected
     * and [onNeedModel] was invoked (the caller should surface a text field and retry with
     * [overrideModel] set to the user-supplied name).
     *
     * @param overrideModel  When non-blank, skips auto-detection and uses this as the model name.
     * @param onNeedModel    Called (on the IO thread) when auto-detection fails and [overrideModel]
     *                       is blank. The caller must ask the user for the name and retry.
     */
    fun add(
        ctx: Context, uri: Uri, overrideModel: String, folderHint: String, gameRoot: File?, key: ByteArray?,
        step: (Int) -> Unit, detail: (String) -> Unit,
        onNeedModel: () -> Unit,
        log: (String) -> Unit
    ): Boolean? {
        if (gameRoot == null) { log("Choose the game folder first."); return false }
        val mods = ModLoader.modsFolder(gameRoot)
        if (!mods.isDirectory) { log("Run Enable mods first (the mods folder is missing)."); return false }

        val cache = ctx.cacheDir
        val src   = File(cache, "car_src.rpf")
        try {
            // ── Step 0: read the car file ──────────────────────────────────────────
            step(0)
            log("• Reading the car file")
            ctx.contentResolver.openInputStream(uri)?.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
                ?: run { log("Could not open the file."); return false }

            // ── Step 1: find the model name ────────────────────────────────────────
            step(1)
            val model: String
            if (overrideModel.isNotBlank()) {
                model = modelName(overrideModel)
                log("• Using model name: $model")
            } else {
                log("• Looking for model name in vehicles.meta…")
                val detected = detectModelName(src, key)
                if (detected == null) {
                    log("• Could not find the model name automatically.")
                    onNeedModel()
                    return null   // signal: caller must ask the user and retry
                }
                model = detected
                log("• Found model name: $model")
            }

            val folder = folderName(folderHint).ifEmpty { model }
            if (model.isEmpty()) { log("Model name is empty after cleanup."); return false }

            // ── Step 2: Adapt to Android ───────────────────────────────────────────
            step(2)
            log("• Renaming x64 → android inside the RPF")
            detail("Patching directory names…")
            val adapted = File(cache, "car_android.rpf")
            adapted.delete()
            src.copyTo(adapted, overwrite = true)
            val ok = try {
                adaptToAndroid(adapted, key, log)
            } catch (e: Exception) {
                log("• Could not rename x64 folders: ${e.message}")
                false
            }
            if (!ok) {
                log("Stopped: the pack has no android/ folder, so the game would crash on it. Nothing was added.")
                return false
            }
            val result = adapted

            // ── Step 3: save dlc.rpf ───────────────────────────────────────────────
            step(3)
            val dir = File(mods, "update/android/dlcpacks/$folder").apply { mkdirs() }
            result.copyTo(File(dir, "dlc.rpf"), overwrite = true)
            log("• Saved dlcpacks/$folder/dlc.rpf")

            // ── Step 4: register in dlclist.xml ───────────────────────────────────
            step(4)
            if (!register(mods, key, model, folder, log)) return false
            step(STEPS_COUNT)
            log("Done. Start the game to test.")
            return true
        } catch (e: Exception) {
            log("Failed: ${e.message}")
            return false
        } finally {
            src.delete()
            File(cache, "car_android.rpf").delete()
        }
    }

    fun addFromFile(
        ctx: Context, file: File, overrideModel: String, folderHint: String,
        gameRoot: File?, key: ByteArray?,
        step: (Int) -> Unit, detail: (String) -> Unit,
        onNeedModel: () -> Unit, log: (String) -> Unit
    ): Boolean? = add(ctx, Uri.fromFile(file), overrideModel, folderHint, gameRoot, key, step, detail, onNeedModel, log)

    // Total number of steps (used by the UI to show "Finished").
    const val STEPS_COUNT = 5

    // ── Auto-detect model name from vehicles.meta inside the RPF ─────────────────────────────────
    private fun detectModelName(rpf: File, key: ByteArray?): String? {
        return try {
            val a = RpfArchive(rpf, key, false, rpf.name)
            a.load()
            var found: String? = null
            fun walk(dir: Dir) {
                for (child in dir.kids) {
                    if (found != null) return
                    when (child) {
                        is Leaf -> if (child.name.equals("vehicles.meta", ignoreCase = true)) {
                            val bytes = runCatching { a.extractToBytes(child) }.getOrNull() ?: return
                            val xml = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                            // <modelName>xxx</modelName>  — grab the first occurrence
                            val m = Regex("<modelName>\\s*([^<\\s]+)\\s*</modelName>", RegexOption.IGNORE_CASE)
                                .find(xml)
                            if (m != null) found = m.groupValues[1].trim().lowercase()
                        }
                        is Dir -> walk(child)
                    }
                }
            }
            walk(a.root)
            found
        } catch (e: Exception) {
            null
        }
    }

    // ── Adapt to Android: rename every directory named x64 (any case) → android ──────────────────
    private fun adaptToAndroid(rpf: File, key: ByteArray?, log: (String) -> Unit): Boolean {
        val a = RpfArchive(rpf, key, false, rpf.name)
        a.load()
        val renamed = a.renameDirsMatchingName("x64", "android")
        if (renamed == 0) log("  • No x64 directories found in the RPF")
        else log("  • Renamed $renamed x64 director${if (renamed == 1) "y" else "ies"} → android")
        // Re-open and check: the game looks for <pack>:/android/vehicles.rpf at the top level.
        val b = RpfArchive(rpf, key, false, rpf.name)
        b.load()
        log("  • Top level: " + b.root.kids.joinToString(", ") { it.name })
        return b.root.kids.any { it is Dir && it.name.equals("android", ignoreCase = true) }
    }

    // dlclist.xml entry uses modelName; folder layout uses folderName.
    private fun register(mods: File, key: ByteArray?, model: String, folder: String, log: (String) -> Unit): Boolean {
        val item = "<Item>dlcpacks:/$folder/</Item>"
        fun patch(xml: String): String? {
            if (xml.contains(item, ignoreCase = true)) return xml
            val at = xml.lastIndexOf("</Paths>")
            if (at < 0) return null
            return xml.substring(0, at) + "\t" + item + "\r\n\t" + xml.substring(at)
        }
        val loose = File(mods, "update/common/data/dlclist.xml")
        if (loose.isFile) {
            val bak = File(loose.parentFile, "dlclist.xml.bak")
            if (!bak.exists()) loose.copyTo(bak)
            val out = patch(loose.readText()) ?: run { log("dlclist.xml has no </Paths>, not changed."); return false }
            loose.writeText(out)
            log("• Added $item to dlclist.xml")
            return true
        }
        val rpf = File(mods, "update/update.rpf").takeIf { it.isFile }
            ?: mods.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals("update.rpf", true) }
        if (rpf == null) { log("dlclist.xml not found: no loose file in mods/update/common/data and no update.rpf in mods/update."); return false }
        log("• dlclist.xml is inside ${rpf.name}, editing it there")
        val path = listOf("common", "data")
        val tmp = File.createTempFile("dlclist", ".xml")
        try {
            val a = RpfArchive(rpf, key, true, rpf.name)
            val leaf = a.dirAt(path).kids.filterIsInstance<Leaf>().firstOrNull { it.name.equals("dlclist.xml", true) }
                ?: run { log("dlclist.xml is not in common/data inside ${rpf.name}."); return false }
            val side = addedList(mods)
            val added = (if (side.isFile) side.readLines() else emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!added.contains(item)) added += item
            val read = try { String(a.extractToBytes(leaf), Charsets.UTF_8).removePrefix("\uFEFF") } catch (e: Exception) {
                log("• Could not read it (${e.message}). Rebuilding it from the stock list instead.")
                null
            }
            val out: String
            if (read != null) {
                out = patch(read) ?: run { log("dlclist.xml has no </Paths>, not changed."); return false }
                if (out == read) { log("• dlclist.xml already lists it"); return true }
            } else {
                val all = DLCLIST_BASE.map { "<Item>$it</Item>" } + added
                out = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n\r\n<SMandatoryPacksData>\r\n\t<Paths>\r\n" +
                    all.joinToString("") { "\t\t$it\r\n" } + "\t</Paths>\t\r\n</SMandatoryPacksData>\r\n"
            }
            side.writeText(added.joinToString("\n"))
            val named = File(tmp.parentFile, "dlclist.xml")
            named.writeText(out)
            a.replaceMany(listOf(RpfArchive.Replacement(path, "dlclist.xml", named)))
            named.delete()
            log("• Added $item to dlclist.xml inside ${rpf.name}")
            return true
        } catch (e: Exception) {
            log("Could not edit dlclist.xml in ${rpf.name}: ${e.message}")
            return false
        } finally { tmp.delete() }
    }
}
