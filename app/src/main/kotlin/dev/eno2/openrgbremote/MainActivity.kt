package dev.eno2.openrgbremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.eno2.openrgbremote.core.designsystem.theme.OpenRgbTheme
import dev.eno2.openrgbremote.ui.AppRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenRgbTheme {
                AppRoot()
            }
        }
    }
}
