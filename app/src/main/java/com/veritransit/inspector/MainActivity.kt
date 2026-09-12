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
        setContent {
            VeriTransitTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NpuEngine.onHostForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // Lets the residency watchdog free the cDSP memory while nothing can
        // use it; the next inference reloads the cached bundle on demand.
        NpuEngine.onHostBackgrounded()
    }
}
