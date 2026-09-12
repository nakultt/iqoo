package com.veritransit.inspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
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
        setContent {
            VeriTransitTheme {
                AppRoot()
            }
        }
    }
}
