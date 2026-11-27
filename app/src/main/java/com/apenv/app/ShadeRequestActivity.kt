package com.apenv.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// Invisible activity that only shows the screen-capture dialog and starts the shader service.
class ShadeRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == RESULT_OK && d != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ShadeService::class.java).putExtra(ShadeService.EXTRA_CODE, r.resultCode).putExtra(ShadeService.EXTRA_DATA, d)
                )
            }
            finish()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        l.launch(mpm.createScreenCaptureIntent())
    }
}
