package com.apenv.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Saves crash details into the APEN V folder so they can be shared. Nothing leaves the phone.
object CrashLog {
    private fun folder(): File = File(Environment.getExternalStorageDirectory(), "APEN V").apply { mkdirs() }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(folder(), "crash-${stamp()}.txt")
                    .writeText("Thread: ${thread.name}\n" + error.stackTraceToString())
            }
            previous?.uncaughtException(thread, error)
        }
        reportLastExit(context)
    }

    // Native crashes and hangs never reach the handler above, so they are read back on the next start.
    private fun reportLastExit(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            if (last.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && last.reason != ApplicationExitInfo.REASON_ANR) return
            val marker = File(context.filesDir, "last_exit_reported")
            if (marker.exists() && marker.readText() == last.timestamp.toString()) return
            marker.writeText(last.timestamp.toString())
            val name = "crash-${stamp()}-native"
            File(folder(), "$name.txt").writeText("reason=${last.reason}\n${last.description}\n")
            last.traceInputStream?.use { input ->
                File(folder(), "$name.trace").outputStream().use { input.copyTo(it) }
            }
        }
    }
}
