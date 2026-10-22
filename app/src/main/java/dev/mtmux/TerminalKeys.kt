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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private val keyPages = listOf(
    listOf("Esc" to "Escape", "Tab" to "Tab", "Ctrl-C" to "Ctrl-C", "↑" to "ArrowUp", "↓" to "ArrowDown", "Enter" to "Enter"),
    (1..6).map { "F$it" to "F$it" },
    (7..12).map { "F$it" to "F$it" },
    listOf("Ctrl" to "Ctrl", "Shift" to "Shift", "Alt" to "Alt", "←" to "ArrowLeft", "→" to "ArrowRight", "⌫" to "Backspace")
)

@Composable fun TerminalKeys(enabled: Boolean, modifiers: Int, onModifier: (Int) -> Unit, onKey: (String) -> Unit) {
    val pager = rememberPagerState(initialPage=TERMINAL_KEY_PAGER_START,pageCount={Int.MAX_VALUE})
    val pageLabel = stringResource(R.string.keys_page,terminalKeyPage(pager.currentPage)+1,TERMINAL_KEY_PAGE_COUNT)
    val pending = if(modifiers == 0) "" else stringResource(R.string.keys_modifier_pending,TerminalKeyStroke.label(modifiers))
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        HorizontalPager(pager,reverseLayout=true,overscrollEffect=null,
            modifier=Modifier.fillMaxSize().testTag("terminal-key-pages").semantics { stateDescription=pageLabel }) { page ->
            Row(Modifier.fillMaxSize().padding(bottom=4.dp)) {
                keyPages[terminalKeyPage(page)].forEach { (label,key) ->
                    val bit = when(key) {"Ctrl" -> TerminalKeyStroke.CTRL;"Shift" -> TerminalKeyStroke.SHIFT;"Alt" -> TerminalKeyStroke.ALT;else -> 0}
                    val latched = bit != 0 && modifiers and bit != 0
                    TextButton(onClick={if(bit!=0) onModifier(bit) else onKey(key)},enabled=enabled,
                        shape=MaterialTheme.shapes.small,
                        colors=ButtonDefaults.textButtonColors(containerColor=if(latched) MaterialTheme.colorScheme.primary.copy(alpha=.16f) else Color.Transparent),
                        contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1f).fillMaxHeight().testTag("terminal-key-$label")
                            .semantics { if(bit!=0) selected=latched }) {
                        Text(label,maxLines=1,style=MaterialTheme.typography.labelMedium,
                            color=if(enabled && (latched || modifiers!=0 && bit==0)) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom=1.dp).semantics {contentDescription=pageLabel;stateDescription=pending},
            horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            repeat(TERMINAL_KEY_PAGE_COUNT) { page ->
                Box(Modifier.size(3.dp).background(if(page==terminalKeyPage(pager.currentPage)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,CircleShape))
            }
        }
    }
}
