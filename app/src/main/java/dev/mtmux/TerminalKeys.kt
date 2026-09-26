package dev.mtmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val keyPages = listOf(
    listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl-C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "Enter" to "\r"),
    listOf("F1" to "\u001bOP", "F2" to "\u001bOQ", "F3" to "\u001bOR", "F4" to "\u001bOS", "F5" to "\u001b[15~", "F6" to "\u001b[17~"),
    listOf("F7" to "\u001b[18~", "F8" to "\u001b[19~", "F9" to "\u001b[20~", "F10" to "\u001b[21~", "F11" to "\u001b[23~", "F12" to "\u001b[24~")
)

@Composable fun TerminalKeys(enabled: Boolean, onKey: (String, String) -> Unit) {
    val pager = rememberPagerState(pageCount = { keyPages.size })
    val pageLabel = stringResource(R.string.keys_page, pager.currentPage + 1, keyPages.size)
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        HorizontalPager(pager, modifier = Modifier.fillMaxSize().testTag("terminal-key-pages")) { page ->
            Row(Modifier.fillMaxSize()) {
                keyPages[page].forEach { (label, data) ->
                    TextButton(onClick = { onKey(label, data) }, enabled = enabled,
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).fillMaxHeight().testTag("terminal-key-$label")) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).semantics { contentDescription = pageLabel }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { page ->
                Box(Modifier.size(3.dp).background(if (page == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
    }
}
