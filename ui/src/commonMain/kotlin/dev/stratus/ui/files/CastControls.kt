package dev.stratus.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.cast.CastController
import dev.stratus.core.cast.CastState
import dev.stratus.core.cast.clockOf

/**
 * What is playing on a screen, and the controls for it (stratus-app#153).
 *
 * Each slider keeps where a finger has it only while it is being dragged, and
 * acts when it is let go: the screen reports its position once a second, so a
 * slider that followed the report would jump back under the finger, and one
 * that sought on every step would send a renderer a dozen seeks per drag.
 */
@Composable
internal fun CastControls(cast: CastController, playing: CastState.Playing) {
    val playback by cast.playback.collectAsState()
    var seeking by remember { mutableStateOf<Float?>(null) }
    var turning by remember { mutableStateOf<Float?>(null) }

    Column {
        ListItem(
            headlineContent = { Text("Playing on ${playing.device.name}") },
            supportingContent = { Text(playing.item.title) },
            trailingContent = {
                Row {
                    val now = playback
                    if (now != null) {
                        if (now.paused) {
                            TextButton(onClick = cast::resume) { Text("Play") }
                        } else {
                            TextButton(onClick = cast::pause) { Text("Pause") }
                        }
                    }
                    TextButton(onClick = cast::stop) { Text("Stop") }
                }
            },
        )
        val now = playback
        if (now != null) {
            val length = now.durationSeconds
            if (length != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val at = seeking?.toLong() ?: now.positionSeconds
                    Text(clockOf(at), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = (seeking ?: now.positionSeconds.toFloat()).coerceIn(0f, length.toFloat()),
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            seeking?.let { cast.seekTo(it.toLong()) }
                            seeking = null
                        },
                        valueRange = 0f..length.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(clockOf(length), style = MaterialTheme.typography.labelSmall)
                }
            }
            val volume = now.volume
            if (volume != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = turning ?: volume.toFloat(),
                        onValueChange = { turning = it },
                        onValueChangeFinished = {
                            turning?.let { cast.setVolume(it.toInt()) }
                            turning = null
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
