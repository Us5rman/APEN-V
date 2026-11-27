package com.apenv.app

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import rikka.shizuku.Shizuku

// Turns the mods on and off for the game. Mirrors the reference app's ShellExecutor pipeline:
//   1. deploy libs into every location the game process can dlopen
//   2. swap update.rpf with the modded version
//   3. setprop wrap.PKG:game "LD_PRELOAD=<path>"  ← primary injection vector
//   4. cmd activity attach-agent                   ← secondary / fallback
//   5. background retry daemon (25 attempts / 90 s) matching reference runBoundedAttach()
object ModLoader {
    const val PKG  = "com.thegwchr.fuzzy"
    private const val TMP = "/data/local/tmp"
    private const val EXT = "/sdcard/Android/data/$PKG/files"
    private const val INT = "/data/user/0/$PKG/files"
    // Port libshv opens inside the game process (matches Bridge.PORT in reference)
    private const val SHV_PORT = 0x6987  // 27015

    val modsDir = File("/sdcard/APEN V/Mods")

    // ── Shizuku shell ────────────────────────────────────────────────────────

    fun sh(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun step(log: (String) -> Unit, label: String, cmd: String): String {
        log("• $label")
        val out = try { sh(cmd) } catch (e: Throwable) { "failed: ${e.message ?: e.javaClass.simpleName}" }
        if (out.isNotEmpty()) log("  " + out.lines().take(6).joinToString("\n  "))
        return out
    }

    // ── Shizuku / state ──────────────────────────────────────────────────────

    fun state(ctx: Context): Int {
        val installed = try {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (e: Exception) { false }
        val alive = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!alive) return if (installed) 1 else 0
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 3 else 2
    }

    fun requestPermission() { try { Shizuku.requestPermission(1) } catch (e: Throwable) { } }

    // 0=not running, 1=background, 2=foreground
    fun gameState(): Int {
        val o = sh("pidof $PKG $PKG:game >/dev/null || { echo OFF; exit; }; " +
            "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
        return when {
            o.startsWith("OFF") -> 0
            o.contains(PKG)     -> 2
            else                -> 1
        }
    }

    // ── Port probe (mirrors ShellExecutor.isPortListening) ───────────────────

    fun isPortListening(port: Int = SHV_PORT): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 250); true }
    } catch (e: Throwable) { false }

    // ── Auto-inject daemon (mirrors ShellExecutor.runBoundedAttach) ──────────
    // 25 attempts, 2.5 s apart, 90 s window — identical constants to reference.
    // Checks the port before each attempt: backs off when libshv is already up.

    @Volatile private var autoInjectRunning = false

    fun startAutoInjectDaemon(libPath: String = "$INT/libshv.so") {
        if (autoInjectRunning) return
        autoInjectRunning = true
        Thread({
            val start   = System.currentTimeMillis()
            var attempts = 0
            val maxAttempts = 25
            val windowMs    = 90_000L
            val pollMs      = 2_500L

            while (autoInjectRunning
                   && attempts < maxAttempts
                   && (System.currentTimeMillis() - start) < windowMs) {

                try { Thread.sleep(pollMs) } catch (e: InterruptedException) { break }

                if (isPortListening()) {
                    // Port open — lib is running; keep watching in case game restarts
                    try { Thread.sleep(2_000L) } catch (e: InterruptedException) { break }
                    continue
                }

                attempts++
                try {
                    val r = sh("cmd activity attach-agent $PKG:game $libPath 2>/dev/null; echo exitcode:$?")
                    if (r.contains("exitcode:0")) {
                        // Attached successfully — wait a moment then verify port opened
                        try { Thread.sleep(1_000L) } catch (e: InterruptedException) { break }
                        if (isPortListening()) break   // success, stop hammering
                    }
                } catch (e: Throwable) { /* Shizuku may not be ready yet — just retry */ }
            }
            autoInjectRunning = false
        }, "ApenvAutoInject").apply { isDaemon = true; start() }
    }

    fun stopAutoInjectDaemon() { autoInjectRunning = false }

    // ── Library deployment (mirrors ShellExecutor.deployAssetLibrary) ────────
    // Copies into 4 locations with the same fallback chain the reference uses,
    // then fixes SELinux label with restorecon (critical on Android 12+ stock).

    private fun deployLib(log: (String) -> Unit, name: String, tmpPath: String) {
        // 1. /data/user/0/<pkg>/files/ via run-as (most reliable — same SELinux domain as game)
        step(log, "Deploy $name → INT (run-as)",
            "run-as $PKG sh -c '" +
                "mkdir -p $INT && " +
                "cp $tmpPath $INT/$name 2>/dev/null && " +
                "chmod 755 $INT/$name' 2>&1 " +
            "|| echo 'run-as failed — will try shell fallback'"
        )

        // 2. Shell fallback (non-debuggable builds where run-as is blocked)
        step(log, "Deploy $name → INT (shell fallback)",
            "if [ ! -f $INT/$name ]; then " +
                "mkdir -p $INT && " +
                "cp $tmpPath $INT/$name 2>/dev/null && " +
                "chmod 755 $INT/$name && " +
                "echo 'shell fallback used'; " +
            "else echo 'run-as succeeded'; fi"
        )

        // 3. External storage path the daemon/game can also read
        step(log, "Deploy $name → EXT",
            "mkdir -p $EXT && cp $tmpPath $EXT/$name && chmod 777 $EXT/$name"
        )

        // 4. /data/local/tmp (world-readable, used as last-resort dlopen source)
        step(log, "chmod $name in TMP", "chmod 777 $tmpPath")

        // 5. SELinux label fix — THE most common silent failure on stock Android 12+
        //    Without this the linker cannot dlopen the file even with correct permissions.
        step(log, "restorecon $name",
            "restorecon $INT/$name 2>&1 || echo 'restorecon unavailable (non-fatal on older kernels)'"
        )

        // 6. Copy SELinux context from an existing file in INT (belt-and-suspenders)
        step(log, "chcon $name",
            "ctx=\$(ls -Z $INT 2>/dev/null | grep -v '^total' | grep -v '$name' | head -1 | awk '{print \$1}'); " +
            "[ -n \"\$ctx\" ] && chcon \"\$ctx\" $INT/$name 2>&1 && echo \"chcon ok: \$ctx\" || echo 'chcon skipped'"
        )

        // 7. Verification — same LOC:IN_APP check the reference uses
        step(log, "Verify $name",
            "if run-as $PKG [ -f $INT/$name ]; then echo 'LOC:IN_APP'; " +
            "elif [ -f $INT/$name ]; then echo 'LOC:IN_APP (shell)'; " +
            "else echo 'LOC:MISSING — injection will likely fail'; fi"
        )
    }

    // ── Staging (unpack assets → TMP) ────────────────────────────────────────

    private fun stage(ctx: Context, log: (String) -> Unit): Boolean {
        return try {
            // Write to loader's own filesDir (correct SELinux label out of the box)
            val loaderFiles = ctx.filesDir
            loaderFiles.setExecutable(true, false)
            loaderFiles.setReadable(true, false)
            for (n in listOf("libapenv.so", "libshv.so")) {
                val dst = File(loaderFiles, n)
                ctx.assets.open("mods/$n").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
            }

            // Also write to TMP for shell-side operations
            val dir = File(modsDir, "stage").also { it.mkdirs() }
            for (n in listOf("libapenv.so", "libshv.so", "apenv_daemon")) {
                ctx.assets.open("mods/$n").use { i ->
                    // Write to stage dir
                    File(dir, n).outputStream().use { o -> i.copyTo(o) }
                }
                // Copy from stage to TMP via shell (Shizuku has the right permissions)
                step(log, "Stage $n to TMP",
                    "cp '${dir.absolutePath}/$n' $TMP/$n && chmod 755 $TMP/$n && echo 'ok'"
                )
            }
            true
        } catch (e: Exception) {
            log("Could not unpack mod files: ${e.message}")
            false
        }
    }

    // ── wrap property (setprop) ───────────────────────────────────────────────
    // Mirror of ShellExecutor lines setting wrap.PKG:game.
    // Priority: INT path (game's own dir, correct SELinux) → TMP → loader filesDir

    private fun resolveLibPath(ctx: Context, name: String): String {
        val intPath    = "$INT/$name"
        val tmpPath    = "$TMP/$name"
        val loaderPath = "${ctx.filesDir.absolutePath}/$name"
        return try {
            when {
                sh("run-as $PKG test -f $intPath && echo y") == "y" -> intPath
                sh("test -f $tmpPath && echo y") == "y" -> tmpPath
                else                                     -> loaderPath
            }
        } catch (e: Throwable) { intPath }
    }

    private fun setWrapProperty(log: (String) -> Unit, libPath: String) {
        step(log, "Clear old wrap property",
            "setprop wrap.$PKG:game \"\" 2>/dev/null || true"
        )
        step(log, "Set LD_PRELOAD wrap",
            "setprop wrap.$PKG:game \"LD_PRELOAD=$libPath\""
        )
        val now = try { sh("getprop wrap.$PKG:game") } catch (e: Throwable) { "" }
        if (now.contains("LD_PRELOAD")) log("  wrap set: $now")
        else log("  wrap not set, this Shizuku mode is not allowed to. Going on without it.")
    }

    private fun clearWrapProperty(log: (String) -> Unit) {
        step(log, "Clear wrap property",
            "setprop wrap.$PKG:game \"\" 2>/dev/null || true"
        )
    }

    // ── update.rpf swap (mirrors ShellExecutor.enableModsAndLaunch [2/5]) ────

    private fun swapUpdateRpf(gameRoot: File, log: (String) -> Unit) {
        val gameUpdate = File(gameRoot, "update/update.rpf").absolutePath
        val gameOrig   = File(gameRoot, "update/update.rpf.orig").absolutePath
        val modUpdate  = File(gameRoot, "mods/update/update.rpf").absolutePath

        step(log, "Backup original update.rpf",
            "if [ ! -f '$gameOrig' ] && [ -f '$gameUpdate' ]; then " +
                "cp '$gameUpdate' '$gameOrig' && echo 'backed up'; " +
            "else echo 'backup already exists or update.rpf missing'; fi"
        )
        step(log, "Apply modded update.rpf",
            "if [ -f '$modUpdate' ]; then " +
                "cp '$modUpdate' '$gameUpdate' && echo 'update.rpf swapped' || echo 'swap failed'; " +
            "else echo 'no mods/update/update.rpf — skipping (DLC-only packs do not need this)'; fi"
        )
        step(log, "Permissions on update.rpf",
            "chmod 777 '$gameUpdate' 2>/dev/null || true"
        )
    }

    fun applyModdedUpdate(gameRoot: File, log: (String) -> Unit) = swapUpdateRpf(gameRoot, log)

    private fun restoreUpdateRpf(gameRoot: File, log: (String) -> Unit) {
        val gameUpdate = File(gameRoot, "update/update.rpf").absolutePath
        val gameOrig   = File(gameRoot, "update/update.rpf.orig").absolutePath
        step(log, "Restore original update.rpf",
            "if [ -f '$gameOrig' ]; then " +
                "cp '$gameOrig' '$gameUpdate' && echo 'restored' || echo 'restore failed'; " +
            "else echo 'no backup found'; fi"
        )
    }

    // ── mods/ folder permissions (matches reference chmod -R 777 / chown) ────

    private fun fixModsPermissions(modsPath: String, log: (String) -> Unit) {
        step(log, "mods/ permissions",
            "chown -R media_rw:media_rw '$modsPath' 2>/dev/null; " +
            "chmod -R 777 '$modsPath'; " +
            "find '$modsPath' -type d -exec chmod 777 {} + 2>/dev/null; " +
            "find '$modsPath' -type f -exec chmod 777 {} + 2>/dev/null"
        )
    }

    // ── apenv_daemon (openiv_daemon equivalent) ───────────────────────────────

    private fun startFileDaemon(log: (String) -> Unit) {
        // Kill any stale instance first (reference kills openiv_daemon the same way)
        step(log, "Stop stale file-redirect daemon",
            "pkill -f apenv_daemon 2>/dev/null; pkill -f openiv_daemon 2>/dev/null; true"
        )
        // Copy from TMP to a persistent location and launch
        step(log, "Start file-redirect daemon",
            "chmod 755 $TMP/apenv_daemon 2>/dev/null && " +
            "(nohup $TMP/apenv_daemon </dev/null >/dev/null 2>&1 &) && " +
            "sleep 0.4 && " +
            "pidof apenv_daemon && echo 'daemon running' || echo 'daemon NOT started'"
        )
    }

    // ── isActive / checkActive ────────────────────────────────────────────────

    fun isActive(menu: Boolean): Boolean = try {
        val daemonOk = sh("pidof apenv_daemon").let { it.isNotBlank() && it.trim() != "0" }
        daemonOk
    } catch (e: Throwable) { false }

    private fun checkActive(menu: Boolean, log: (String) -> Unit): Boolean {
        val pid   = try { sh("pidof apenv_daemon") } catch (e: Throwable) { "" }
        val wrap  = try { sh("getprop wrap.$PKG:game") } catch (e: Throwable) { "" }
        val port  = isPortListening()
        val daemonOk = pid.isNotBlank() && pid.trim() != "0"
        val wrapOk   = wrap.contains("LD_PRELOAD")
        log("  file-redirect daemon: ${if (daemonOk) "running pid=$pid" else "NOT running"}")
        if (menu) {
            log("  wrap property:        ${if (wrapOk) wrap.trim() else "not set (optional)"}")
            log("  port $SHV_PORT:          ${if (port) "OPEN (libshv running)" else "closed (game not started yet)"}")
        }
        return daemonOk
    }

    // ── Mods folder helpers ───────────────────────────────────────────────────

    class ModsStatus(val folder: Boolean, val common: Boolean, val update: Boolean, val packs: List<String>)
    fun modsFolder(root: File) = File(root, "mods")

    fun status(root: File?): ModsStatus {
        if (root == null) return ModsStatus(false, false, false, emptyList())
        val mf = modsFolder(root)
        val packs = File(mf, "update/android/dlcpacks").listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        return ModsStatus(mf.isDirectory, File(mf, "common.rpf").isFile, File(mf, "update").isDirectory, packs)
    }

    private fun findCommon(root: File): File? {
        val direct = File(root, "common.rpf")
        if (direct.isFile) return direct
        return root.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.equals("common.rpf", true) }
    }

    private fun copyTree(src: File, dst: File, log: (String) -> Unit): Int {
        var n = 0
        if (src.isDirectory) {
            dst.mkdirs()
            for (c in src.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (c.name.equals("update2.rpf", true) || c.name.endsWith(".orig", true)) continue
                n += copyTree(c, File(dst, c.name), log)
            }
        } else if (!dst.exists()) {
            if (src.length() > (64L shl 20)) log("  copying ${src.name} (${src.length() shr 20} MB)")
            src.copyTo(dst, overwrite = true, bufferSize = 4 shl 20)
            n = 1
        }
        return n
    }

    fun setupFolder(root: File, log: (String) -> Unit): Boolean {
        return try {
            val mf = modsFolder(root)
            mf.mkdirs()
            log("• Mods folder: ${mf.absolutePath}")
            val common = findCommon(root)
            if (common == null) log("  common.rpf not found in game folder") else {
                val c = copyTree(common, File(mf, "common.rpf"), log)
                log("  common.rpf: " + if (c == 0) "already there" else "copied")
            }
            val upd = File(root, "update")
            if (!upd.isDirectory) log("  update folder not found") else {
                val c = copyTree(upd, File(mf, "update"), log)
                log("  update: $c files copied")
            }
            // Use update.rpf.orig as the clean base if it exists
            val orig = File(upd, "update.rpf.orig")
            val mark = File(mf, "update/.base_from_orig")
            if (orig.isFile && !mark.exists()) {
                orig.copyTo(File(mf, "update/update.rpf"), overwrite = true, bufferSize = 4 shl 20)
                mark.writeText("1")
                log("  update.rpf: stock copy (update.rpf.orig) used as base")
            }
            File(mf, "update/android/dlcpacks").mkdirs()
            true
        } catch (e: Exception) {
            log("Could not set up mods folder: ${e.message}")
            false
        }
    }

    // ── enable() — full first-time setup ─────────────────────────────────────
    // Mirrors ShellExecutor.enableModsAndLaunch steps 1-5.

    fun enable(ctx: Context, gameRoot: File?, menu: Boolean, log: (String) -> Unit) {
        if (gameRoot == null) { log("Choose the game folder first"); return }

        log("• [1/6] Unpacking mod files…")
        if (!stage(ctx, log)) return

        log("• [2/6] Deploying libopeniv equivalent (libapenv.so)…")
        deployLib(log, "libapenv.so", "$TMP/libapenv.so")

        log("• [3/6] Setting up mods/ folder…")
        if (!setupFolder(gameRoot, log)) return
        val mf = modsFolder(gameRoot).absolutePath
        fixModsPermissions(mf, log)

        log("• [4/6] Swapping update.rpf to modded version…")
        swapUpdateRpf(gameRoot, log)

        // Remove the disabled marker (reference: rm openiv.disabled / apenv.disabled)
        step(log, "Enable mods marker",
            "rm -f $EXT/apenv.disabled 2>/dev/null; " +
            "run-as $PKG rm -f $INT/apenv.disabled 2>/dev/null; true"
        )

        log("• [5/6] Configuring trainer overlay (libshv.so)…")
        if (menu) {
            deployLib(log, "libshv.so", "$TMP/libshv.so")
            step(log, "shv.enabled marker",
                "touch $EXT/shv.enabled && chmod 666 $EXT/shv.enabled; " +
                "run-as $PKG sh -c 'touch $INT/shv.enabled && chmod 666 $INT/shv.enabled' 2>/dev/null; true"
            )
            step(log, "Overlay permission",
                "appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null || true"
            )
            val libPath = resolveLibPath(ctx, "libshv.so")
            log("  wrap lib path: $libPath")
            setWrapProperty(log, libPath)
        } else {
            step(log, "shv.enabled marker off",
                "rm -f $EXT/shv.enabled; run-as $PKG rm -f $INT/shv.enabled 2>/dev/null; true"
            )
            clearWrapProperty(log)
        }

        log("• [6/6] Starting daemons…")
        startFileDaemon(log)
        if (menu) startAutoInjectDaemon()

        step(log, "Stop game (apply changes)",
            "run-as $PKG kill -9 \$(pidof $PKG:game 2>/dev/null) 2>/dev/null; " +
            "am force-stop $PKG"
        )

        log("Done. libshv.so loads through LD_PRELOAD when the game starts, then opens port $SHV_PORT.")
    }

    // ── activate() — re-arm after reboot / Shizuku restart ───────────────────
    // Called by the UI when mods were already set up but the daemon is gone.

    fun activate(menu: Boolean, log: (String) -> Unit): Boolean {
        // Ensure TMP files are still present (they survive reboot on most ROMs, but not always)
        step(log, "Check/restore TMP files",
            "for f in apenv_daemon libapenv.so libshv.so; do " +
                "[ -f $TMP/\$f ] || { cp '${modsDir.absolutePath}/stage/'\$f $TMP/\$f 2>/dev/null && " +
                "chmod 755 $TMP/\$f && echo \"restored \$f to TMP\"; }; " +
            "done; ls -l $TMP/apenv_daemon $TMP/libapenv.so $TMP/libshv.so 2>&1"
        )

        // Re-deploy libs if missing from INT (reference re-checks this on every activate)
        for (name in listOf("libapenv.so") + if (menu) listOf("libshv.so") else emptyList()) {
            step(log, "Re-deploy $name if missing",
                "if run-as $PKG test -f $INT/$name 2>/dev/null; then echo '$name present'; " +
                "elif run-as $PKG sh -c 'mkdir -p $INT; cp $TMP/$name $INT/$name && chmod 755 $INT/$name' 2>/dev/null; then " +
                    "restorecon $INT/$name 2>/dev/null; echo 're-deployed $name'; " +
                "else echo 'could not copy $name into the game folder (run-as failed)'; fi"
            )
        }

        // Re-enable markers
        step(log, "Enable mods marker",
            "rm -f $EXT/apenv.disabled 2>/dev/null; " +
            "run-as $PKG rm -f $INT/apenv.disabled 2>/dev/null; true"
        )
        if (menu) {
            step(log, "shv.enabled marker",
                "touch $EXT/shv.enabled && chmod 666 $EXT/shv.enabled; " +
                "run-as $PKG sh -c 'touch $INT/shv.enabled && chmod 666 $INT/shv.enabled' 2>/dev/null; true"
            )
            // Re-apply wrap property — it doesn't survive reboot on most ROMs
            val libPath = if (sh("run-as $PKG test -f $INT/libshv.so && echo y") == "y") "$INT/libshv.so"
                          else "$TMP/libshv.so"
            log("• wrap lib path: $libPath")
            setWrapProperty(log, libPath)
        } else {
            step(log, "shv.enabled marker off",
                "rm -f $EXT/shv.enabled; run-as $PKG rm -f $INT/shv.enabled 2>/dev/null; true"
            )
            clearWrapProperty(log)
        }

        startFileDaemon(log)
        if (menu) startAutoInjectDaemon()

        step(log, "Stop game (apply changes)",
            "run-as $PKG kill -9 \$(pidof $PKG:game 2>/dev/null) 2>/dev/null; am force-stop $PKG"
        )

        log("• Verifying…")
        try { sh("sleep 1") } catch (e: Throwable) { }
        val ok = checkActive(menu, log)
        log(if (ok) "Loader is on. The wrap property is set — libshv will load via LD_PRELOAD on game start."
            else "Could not turn the loader on (see status above).")
        return ok
    }

    // ── disable() — mirrors ShellExecutor.disableMods ────────────────────────

    fun disable(gameRoot: File?, log: (String) -> Unit) {
        stopAutoInjectDaemon()

        // Disable markers (reference: touch openiv.disabled, rm shv.enabled)
        step(log, "Disable mods markers",
            "touch $EXT/apenv.disabled 2>/dev/null; " +
            "run-as $PKG sh -c 'touch $INT/apenv.disabled' 2>/dev/null; " +
            "rm -f $EXT/shv.enabled; " +
            "run-as $PKG rm -f $INT/shv.enabled 2>/dev/null; true"
        )

        // Clear LD_PRELOAD so the game starts clean (reference: setprop wrap.PKG:game "")
        clearWrapProperty(log)

        // Restore original update.rpf (reference does this in disableMods)
        if (gameRoot != null) restoreUpdateRpf(gameRoot, log)

        // Kill daemons
        step(log, "Stop daemons",
            "pkill -f apenv_daemon 2>/dev/null; pkill -f openiv_daemon 2>/dev/null; " +
            "pkill -f apenv_watch 2>/dev/null; true"
        )

        step(log, "Stop game", "am force-stop $PKG")
        log("Done. Mods disabled. Original update.rpf restored.")
    }
}
