package com.apenv.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

// Does nothing by itself. Android treats overlay windows that belong to an enabled accessibility service
// as trusted, so touches (and controller input) go straight through the ReShade overlay to the game.
// Normal app overlays get their touches blocked on Android 12+.
class ShadeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: ShadeAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { it.contains("${ctx.packageName}/") && it.contains("ShadeAccessibilityService") }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
