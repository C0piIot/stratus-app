package dev.stratus.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stratus.ui.ActionRow
import dev.stratus.ui.BUILD_DATE
import dev.stratus.ui.BUILD_VERSION
import dev.stratus.ui.Footnote
import dev.stratus.ui.Glyph
import dev.stratus.ui.NavRow
import dev.stratus.ui.Page
import dev.stratus.ui.Rule
import dev.stratus.ui.Section
import dev.stratus.ui.SwitchRow

/**
 * The menu: two things this app has, and the one switch that belongs to
 * neither (stratus-app#137).
 *
 * It used to be one screen called Server holding the address, which folders
 * are backed up, whether to spend mobile data and whether to send crash
 * reports -- four questions with nothing in common but having nowhere else to
 * go. **Where a setting lives is the only clue anybody has about what it
 * affects**, so a wifi switch under Server says the wifi is the server's.
 */
@Composable
fun SettingsScreen(
    /** The address, as the row's second line: the menu should answer "which server" without opening one. */
    serverAddress: String,
    /** One line about the backup, the same sentence the strip carries. */
    backupSummary: String,
    onServer: () -> Unit,
    onBackup: () -> Unit,
    /** Whether crash reports are on, or null in a build that cannot send any. */
    reporting: Boolean?,
    onReporting: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Page("Settings", onBack = onClose) {
        NavRow(Glyph.SERVER, "Server", serverAddress, onClick = onServer)
        NavRow(Glyph.BACKUP, "Backup", backupSummary, onClick = onBackup)

        // Neither the server's nor the backup's, and the third surface this
        // app has is this menu -- so it is a row here rather than a screen
        // holding one switch.
        if (reporting != null) {
            Rule()
            Section("Privacy") {
                SwitchRow(Glyph.REPORTS, "Send crash reports", checked = reporting, onChange = onReporting)
            }
            // What leaves the phone, said beside the switch rather than after it.
            Footnote(
                "When the app crashes, the error and which phone it was on go to " +
                    "Sentry, a service that is not your server. Never a photograph " +
                    "or a password, but an error can name a server address or a file.",
            )
        }

        Rule()
        Section("About") { Build() }
    }
}

/**
 * Which build this is, and tapping it copies the commit.
 *
 * The APK is replaced on every push, so "which one am I running" is a question
 * somebody has before reporting anything -- and the reason to show a commit is
 * to quote it, which is why this is a row that does something rather than a
 * line of text. The commit alone goes to the clipboard: the date is for
 * reading, the commit is for pasting.
 *
 * The confirmation has no timer. One that cleared itself would be a race the
 * end-to-end suite would have to sleep through, and leaving it until the
 * screen is left costs nothing.
 *
 * **`LocalClipboardManager` is deprecated and is still the one to use here.**
 * What replaces it, `LocalClipboard`, takes a `ClipEntry`, which is an expect
 * class with no common way to build one out of a string -- so the modern API
 * costs an expect/actual pair and a piece of iOS nobody here can run, to put
 * seven characters on a pasteboard. One warning instead, and a line to change
 * on the day Compose gives `ClipEntry` a common factory.
 */
@Composable
private fun Build() {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    ActionRow(
        Glyph.BUILD,
        if (copied) "Copied" else "Build",
        // The date and not the time: this answers how old the code is, and a
        // second-level timestamp belongs to a server that redeploys hourly.
        "$BUILD_VERSION · ${BUILD_DATE.substringBefore('T')}",
    ) {
        clipboard.setText(AnnotatedString(BUILD_VERSION))
        copied = true
    }
}
