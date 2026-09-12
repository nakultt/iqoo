package com.veritransit.inspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.ui.AppRoot
import com.veritransit.inspector.ui.theme.VeriTransitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        Repo.init(System.currentTimeMillis())
        // Brings GenieX up and reports whether the NPU bundle is already on
        // disk. Cheap, off the main thread, and every AI path is optional.
        NpuEngine.initialize(applicationContext)
        // Warms the Kokoro voice engine so the first tamper verdict speaks
        // without a cold-start pause. Offline, silent until needed.
        com.veritransit.inspector.ai.KokoroVoice.initialize(applicationContext)
        setContent {
            VeriTransitTheme {
                AppRoot()
            }
        }
    }
}
