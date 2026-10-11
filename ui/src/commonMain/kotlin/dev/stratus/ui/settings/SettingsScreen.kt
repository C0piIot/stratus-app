package dev.stratus.ui.settings

import androidx.compose.runtime.Composable
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
    }
}
