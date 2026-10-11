package dev.stratus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The furniture every screen behind the menu is built from.
 *
 * One file rather than a shape per screen, because what makes a settings
 * surface look like the system's is that its rows are all the same row: the
 * same height, the same place for the mark, the same place for the switch
 * (stratus-app#137).
 */

/** A bar with a back arrow in it, which is what every screen but the browser has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** False for a page holding a list of its own, which cannot scroll inside a scroll. */
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(Glyph.BACK, style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        Column(if (scroll) body.verticalScroll(rememberScrollState()) else body, content = content)
    }
}

/** A group of rows under a heading, the way a settings app groups them. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
    content()
}

/**
 * The paragraph under a group, where what a setting costs gets said.
 *
 * Under the rows rather than inside one: a row is a line and a sentence that
 * wraps to four of them stops the list being a list.
 */
@Composable
fun Footnote(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A row that opens something else. */
@Composable
fun NavRow(glyph: String, title: String, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Text(glyph) },
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Text(Glyph.MORE, style = MaterialTheme.typography.titleLarge) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A row that does something here and now, with nothing behind it. */
@Composable
fun ActionRow(glyph: String, title: String, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Text(glyph) },
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A row carrying a switch, where the whole row is the switch.
 *
 * `onCheckedChange = null` on the control and `toggleable` on the row, which is
 * the documented way round: a switch that handles its own clicks inside a
 * clickable row gives the row two targets with different sizes.
 */
@Composable
fun SwitchRow(glyph: String, title: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = { Text(glyph) },
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, onValueChange = onChange),
    )
}

/** A row that is only telling you something. */
@Composable
fun FactRow(glyph: String, title: String, detail: String) {
    ListItem(
        leadingContent = { Text(glyph) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
    )
}

@Composable
fun Rule() = HorizontalDivider(Modifier.padding(top = 8.dp))
