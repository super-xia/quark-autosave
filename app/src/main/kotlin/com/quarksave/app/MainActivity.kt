package com.quarksave.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.quarksave.app.ui.QuarkSaveApp
import com.quarksave.app.ui.QuarkSaveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuarkSaveTheme {
                Surface {
                    QuarkSaveApp()
                }
            }
        }
    }
}