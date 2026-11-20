package com.example.lightrhythm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.lightrhythm.ui.theme.LightRhythmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightRhythmTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    PhotoImportRoute(Modifier.padding(padding))
                }
            }
        }
    }
}
