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
        // Returning to the foreground cancels a pending background unload;
        // if the model was already released, the next AI call reloads it.
        NpuEngine.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // The screen going dark at a weighbridge is routine; only a sustained
        // absence (ResidencyPolicy grace) gives the ~4 GB of cDSP back.
        NpuEngine.onAppBackgrounded()
    }
}
