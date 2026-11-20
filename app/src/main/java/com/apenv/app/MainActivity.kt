package com.apenv.app

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val Bg = Color(0xFF0E1414)

internal val Panel = Color(0xFF17201F)

internal val Bar = Color(0xFF212C2B)

internal val Accent = Color(0xFF3FD6B8)

internal val Dim = Color(0xFF8E9A99)

internal val Picked = Color(0xFF123A35)

internal val GtaGreen = Color(0xFF7CC957)

class MainActivity : ComponentActivity() {
    private val ws: Workspace by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = Panel,
                    onSurface = Color(0xFFE6E6EA),
                    onBackground = Color(0xFFE6E6EA)
                )
            ) {
                Root(ws)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ws.granted = Environment.isExternalStorageManager()
        if (ws.granted) {
            ws.refreshDevice()
            ws.refreshGame()
            ws.refreshView()
        }
    }
}

@Composable
internal fun Root(ws: Workspace) {
    val ctx = LocalContext.current

    val screen = when {
        ws.scanMode -> 4
        ws.convertMode -> 1
        ws.editMode -> 2
        ws.viewMode -> 3
        else -> 0
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(220, delayMillis = 70), initialScale = 0.97f)) togetherWith
                fadeOut(tween(110))
        },
        label = "screen"
    ) { s ->
        when (s) {
            1 -> TextureConvert(ws)
            2 -> Editor(ws)
            3 -> Viewer(ws)
            4 -> GameScanScreen(ws)
            else -> Home(ws)
        }
    }
}
