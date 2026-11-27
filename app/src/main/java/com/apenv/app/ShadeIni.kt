package com.apenv.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// A parsed ReShade preset: ordered techniques (technique name to fx file, lowercase, no .fx)
// plus every [Section.fx] key/value. Values are live: edits are picked up by the renderer.
internal class ShadePreset(
    val name: String,
    val techniques: List<Pair<String, String>>,
    val sections: ConcurrentHashMap<String, ConcurrentHashMap<String, String>>,
    val defines: Map<String, String> = emptyMap()
) {
    val original: Map<String, Map<String, String>> = sections.mapValues { it.value.toMap() }
    val enabled = ConcurrentHashMap<String, Boolean>()
    fun isOn(file: String) = enabled[file] ?: true
}

internal object ShadeIni {
    // Older presets use other key names for the same setting.
    private val ALIAS = mapOf(
        "monochrome" to mapOf("Coefficients" to "Monochrome_conversion_values", "ColorSaturation" to "Monochrome_color_saturation")
    )

    fun parse(text: String, name: String): ShadePreset {
        val sections = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
        var cur: ConcurrentHashMap<String, String>? = null
        var curKey = ""
        var defLine = ""
        var techLine = ""
        var sortLine = ""
        var effects = listOf<String>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                val key = line.substring(1, line.length - 1).lowercase().removeSuffix(".fx")
                curKey = key
                cur = sections.getOrPut(key) { ConcurrentHashMap() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            if (cur == null) {
                if (k.equals("Techniques", true)) techLine = v
                else if (k.equals("TechniqueSorting", true)) sortLine = v
                else if (k.equals("PreprocessorDefinitions", true)) defLine = v
                else if (k.equals("Effects", true)) effects = v.split(',').map { it.trim().lowercase().removeSuffix(".fx") }.filter { it.isNotEmpty() }
            } else cur[ALIAS[curKey]?.get(k) ?: k] = v
        }
        val order = sortLine.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val rawTechs = techLine.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .sortedBy { t -> order.indexOf(t.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
        val techs = rawTechs.map { it.trim() }.map { t ->
            val at = t.indexOf('@')
            if (at > 0) t.substring(0, at) to t.substring(at + 1).lowercase().removeSuffix(".fx")
            else {
                // Older presets list bare technique names; the file comes from Effects= or the name.
                val l = t.lowercase()
                val file = when {
                    sections.containsKey(l) || ShadeFx.def(l) != null -> l
                    else -> effects.firstOrNull { it == l } ?: effects.firstOrNull { it.contains(l) } ?: l
                }
                t to file
            }
        }
        for ((_, f) in techs) sections.getOrPut(f) { ConcurrentHashMap() }
        val defines = defLine.split(',').mapNotNull { d ->
            val e = d.indexOf('=')
            if (e > 0) d.substring(0, e).trim() to d.substring(e + 1).trim() else null
        }.toMap()
        return ShadePreset(name, techs, sections, defines)
    }
}

internal object ShadeStore {
    private var cached: ShadePreset? = null
    private fun ini(ctx: Context) = File(ctx.filesDir, "shade_active.ini")
    private fun nameFile(ctx: Context) = File(ctx.filesDir, "shade_name.txt")
    private fun over(ctx: Context) = File(ctx.filesDir, "shade_over.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shade_prefs", Context.MODE_PRIVATE)

    fun save(ctx: Context, text: String, name: String) {
        ini(ctx).writeText(text)
        nameFile(ctx).writeText(name)
        over(ctx).delete()
        cached = null
    }

    fun lutFile(ctx: Context) = File(ctx.filesDir, "shade_lut.img")
    fun lutName(ctx: Context): String? = File(ctx.filesDir, "shade_lut_name.txt").takeIf { it.isFile }?.readText()
    fun saveLut(ctx: Context, bytes: ByteArray, name: String) {
        lutFile(ctx).writeBytes(bytes)
        File(ctx.filesDir, "shade_lut_name.txt").writeText(name)
        ShadeFx.lutReady = true
    }

    fun current(ctx: Context): ShadePreset? {
        ShadeFx.lutReady = lutFile(ctx).isFile
        cached?.let { return it }
        val f = ini(ctx)
        if (!f.isFile) return null
        val n = nameFile(ctx).takeIf { it.isFile }?.readText() ?: "preset.ini"
        val p = ShadeIni.parse(f.readText(), n)
        runCatching {
            over(ctx).takeIf { it.isFile }?.forEachLine { l ->
                val a = l.split('|', limit = 4)
                if (a[0] == "E" && a.size >= 3) p.enabled[a[1]] = a[2] == "1"
                if (a[0] == "V" && a.size == 4) p.sections.getOrPut(a[1]) { java.util.concurrent.ConcurrentHashMap() }[a[2]] = a[3]
            }
        }
        cached = p
        return p
    }

    fun saveOverrides(ctx: Context, p: ShadePreset) {
        val sb = StringBuilder()
        for ((file, on) in p.enabled) if (!on) sb.append("E|").append(file).append("|0\n")
        for ((file, m) in p.sections) for ((k, v) in m) {
            if (p.original[file]?.get(k) != v) sb.append("V|").append(file).append('|').append(k).append('|').append(v).append('\n')
        }
        over(ctx).writeText(sb.toString())
    }

    fun withGta(ctx: Context) = prefs(ctx).getBoolean("withGta", false)
    fun setWithGta(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("withGta", on).apply() }

    // Called when GTA V is launched from the Mods button. The capture dialog has to be answered by
    // the user (pick the game under "A single app"), so it is raised once the game is on screen.
    fun onGameLaunched(ctx: Context) {
        val app = ctx.applicationContext
        if (!withGta(app) || ShadeService.running.value) return
        val p = current(app) ?: return
        if (p.techniques.none { ShadeFx.status(it.second) == null }) return
        if (!Settings.canDrawOverlays(app)) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                app.startActivity(Intent(app, ShadeRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, 6000)
    }
}
