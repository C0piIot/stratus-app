package dev.stratus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.stratus.core.signin.signInController
import dev.stratus.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = signInController(applicationContext, lifecycleScope)
        setContent { App(controller) }
    }
}
