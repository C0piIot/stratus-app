package dev.stratus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import dev.stratus.core.appContainer
import dev.stratus.ui.App
import dev.stratus.ui.BackRequests

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer(applicationContext)
        val back = BackRequests()

        // Compose Multiplatform has no common BackHandler at the version this
        // project is pinned to, so the system gesture is wired here. Walking up
        // the tree is what back means while browsing; anywhere else it leaves.
        onBackPressedDispatcher.addCallback(this) {
            if (back.onBack?.invoke() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent { App(container, back) }
    }
}
