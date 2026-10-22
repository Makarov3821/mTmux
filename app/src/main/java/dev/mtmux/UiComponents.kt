package dev.mtmux

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class UiSymbol { BACK, DOWN, NEXT, PLUS, SETTINGS, FOLDER, SORT, REFRESH, MORE, TERMINAL, SEARCH, LATEST, COPY, MIC, STOP, CLOSE, CHECK, SHIELD, KEY, TOOLS, REPLY, SEND, KEYBOARD }
private val vectors = UiSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name,24.dp,24.dp,24f,24f,autoMirror = symbol in setOf(UiSymbol.BACK,UiSymbol.NEXT)).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when(symbol) {
                UiSymbol.BACK -> {moveTo(14f,5f);lineTo(7f,12f);lineTo(14f,19f)}
                UiSymbol.NEXT -> {moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f)}
                UiSymbol.DOWN -> {moveTo(5f,9f);lineTo(12f,16f);lineTo(19f,9f)}
                UiSymbol.PLUS -> {moveTo(12f,5f);lineTo(12f,19f);moveTo(5f,12f);lineTo(19f,12f)}
                UiSymbol.CLOSE -> {moveTo(6f,6f);lineTo(18f,18f);moveTo(18f,6f);lineTo(6f,18f)}
                UiSymbol.CHECK -> {moveTo(4f,12f);lineTo(9f,17f);lineTo(20f,6f)}
                UiSymbol.TERMINAL -> {moveTo(4f,6f);lineTo(10f,12f);lineTo(4f,18f);moveTo(12f,18f);lineTo(20f,18f)}
                UiSymbol.FOLDER -> {moveTo(3f,7f);lineTo(3f,19f);lineTo(21f,19f);lineTo(21f,7f);lineTo(12f,7f);lineTo(9f,4f);lineTo(3f,4f);close()}
                UiSymbol.SORT -> {moveTo(7f,4f);lineTo(7f,20f);moveTo(3f,8f);lineTo(7f,4f);lineTo(11f,8f);moveTo(17f,4f);lineTo(17f,20f);moveTo(13f,16f);lineTo(17f,20f);lineTo(21f,16f)}
                UiSymbol.REFRESH -> {moveTo(20f,10f);curveTo(19f,4f,11f,1f,6f,6f);curveTo(1f,11f,4f,20f,11f,21f);curveTo(15f,22f,19f,19f,20f,16f);moveTo(20f,4f);lineTo(20f,10f);lineTo(14f,10f)}
                UiSymbol.MORE -> {moveTo(12f,5f);lineTo(12f,5.1f);moveTo(12f,12f);lineTo(12f,12.1f);moveTo(12f,19f);lineTo(12f,19.1f)}
                UiSymbol.SEARCH -> {moveTo(16f,10f);curveTo(16f,18f,3f,18f,3f,10f);curveTo(3f,2f,16f,2f,16f,10f);moveTo(15f,15f);lineTo(21f,21f)}
                UiSymbol.LATEST -> {moveTo(12f,3f);lineTo(12f,16f);moveTo(7f,11f);lineTo(12f,16f);lineTo(17f,11f);moveTo(5f,21f);lineTo(19f,21f)}
                UiSymbol.COPY -> {moveTo(8f,8f);lineTo(20f,8f);lineTo(20f,21f);lineTo(8f,21f);close();moveTo(16f,4f);lineTo(4f,4f);lineTo(4f,17f)}
                UiSymbol.MIC -> {moveTo(9f,5f);curveTo(9f,1f,15f,1f,15f,5f);lineTo(15f,11f);curveTo(15f,15f,9f,15f,9f,11f);close();moveTo(5f,10f);curveTo(5f,20f,19f,20f,19f,10f);moveTo(12f,18f);lineTo(12f,22f);moveTo(8f,22f);lineTo(16f,22f)}
                UiSymbol.STOP -> {moveTo(6f,6f);lineTo(18f,6f);lineTo(18f,18f);lineTo(6f,18f);close()}
                UiSymbol.SHIELD -> {moveTo(12f,2f);lineTo(21f,6f);lineTo(20f,14f);curveTo(19f,18f,15f,21f,12f,22f);curveTo(9f,21f,5f,18f,4f,14f);lineTo(3f,6f);close();moveTo(8f,12f);lineTo(11f,15f);lineTo(17f,9f)}
                UiSymbol.KEY -> {moveTo(14f,7f);curveTo(14f,1f,23f,1f,23f,7f);curveTo(23f,13f,14f,13f,14f,7f);moveTo(15f,10f);lineTo(3f,22f);lineTo(1f,20f);lineTo(4f,17f);lineTo(7f,20f);moveTo(18f,6f);lineTo(19f,6f)}
                UiSymbol.REPLY -> {moveTo(8f,5f);lineTo(2f,11f);lineTo(8f,17f);moveTo(3f,11f);lineTo(14f,11f);curveTo(19f,11f,21f,14f,21f,20f)}
                UiSymbol.SEND -> {moveTo(3f,3f);lineTo(22f,12f);lineTo(3f,21f);lineTo(7f,12f);close();moveTo(7f,12f);lineTo(21f,12f)}
                UiSymbol.KEYBOARD -> {moveTo(2f,5f);lineTo(22f,5f);lineTo(22f,19f);lineTo(2f,19f);close();moveTo(6f,9f);lineTo(6.1f,9f);moveTo(10f,9f);lineTo(10.1f,9f);moveTo(14f,9f);lineTo(14.1f,9f);moveTo(18f,9f);lineTo(18.1f,9f);moveTo(6f,14f);lineTo(18f,14f)}
                UiSymbol.TOOLS -> {moveTo(14f,3f);curveTo(20f,1f,23f,8f,19f,12f);lineTo(9f,22f);lineTo(3f,16f);lineTo(13f,6f);lineTo(14f,3f);lineTo(17f,7f);lineTo(20f,4f)}
                UiSymbol.SETTINGS -> {moveTo(9f,3f);lineTo(15f,3f);lineTo(16f,6f);lineTo(20f,7f);lineTo(22f,12f);lineTo(19f,15f);lineTo(19f,19f);lineTo(14f,22f);lineTo(11f,19f);lineTo(7f,19f);lineTo(3f,15f);lineTo(5f,11f);lineTo(4f,7f);lineTo(8f,6f);close();moveTo(15f,12f);curveTo(15f,16f,9f,16f,9f,12f);curveTo(9f,8f,15f,8f,15f,12f)}
            }
        }
    }.build()
}

@Composable fun AppIcon(symbol: UiSymbol, label: String? = null, modifier: Modifier = Modifier.size(20.dp), tint: Color = LocalContentColor.current) {
    Icon(vectors.getValue(symbol), label, modifier, tint)
}

@Composable fun AppIconButton(symbol: UiSymbol, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, prominent: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        Surface(shape = RoundedCornerShape(11.dp), color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)
            prominent -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }, contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=if(enabled) 1f else .4f)) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { AppIcon(symbol) }
        }
    }
}

@Composable fun BentoCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape=MaterialTheme.shapes.large, color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(title != null) Text(title, color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable fun InsetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape=MaterialTheme.shapes.medium, color=MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

@Composable fun ScrollSpeedControl(value: Float, onChange: (Float) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Text(stringResource(R.string.scroll_speed_title), Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text("${ScrollSpeed.normalize(value).toInt()}×", color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleMedium)
    }
    Slider(value=ScrollSpeed.normalize(value), onValueChange={onChange(ScrollSpeed.normalize(it))},
        valueRange=1f..10f,steps=8,modifier=Modifier.testTag(tag))
    Text(stringResource(R.string.scroll_speed_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun DraftInput(value: String, onChange: (String) -> Unit, onSend: () -> Unit,
    sendEnabled: Boolean, sending: Boolean, pendingModifiers: Int, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val sendDescription=stringResource(R.string.terminal_send_enter)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(vertical=6.dp)) {
        Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(focused) 1f else .65f)) {
            Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                BasicTextField(value,onChange,modifier.weight(1f).fillMaxHeight(),maxLines=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.2f) 1 else 2,
                    interactionSource=interaction,cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface,fontFamily=FontFamily.Monospace),
                    decorationBox={ inner ->
                        Box(Modifier.fillMaxSize().padding(horizontal=12.dp),contentAlignment=Alignment.CenterStart) {
                            if(value.isEmpty()) Text(if(pendingModifiers==0) stringResource(R.string.terminal_input_placeholder)
                                else stringResource(R.string.keys_modifier_pending,TerminalKeyStroke.label(pendingModifiers)),
                                style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(pendingModifiers==0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            inner()
                        }
                    })
                Button(onClick=onSend,enabled=sendEnabled,shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(horizontal=12.dp),
                    modifier=Modifier.fillMaxHeight().widthIn(min=72.dp).testTag("send-enter").semantics {contentDescription=sendDescription}) {
                    Text(stringResource(if(sending) R.string.terminal_sending else R.string.terminal_send_short),maxLines=1,style=MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Flat utility row shared by both product flavors; the voice slot is empty in standard. */
@Composable fun TerminalActions(enabled: Boolean, onTools: () -> Unit,
    onReplies: () -> Unit, voice: @Composable () -> Unit) {
    val compact=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.15f || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp<360
    val repliesDescription=stringResource(R.string.terminal_quick_replies)
    Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        TextButton(onClick=onTools,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(48.dp).testTag("terminal-tools")) {
            AppIcon(UiSymbol.TOOLS,modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.terminal_tools_compact),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
        }
        }
        Box(contentAlignment=Alignment.Center) {voice()}
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        TextButton(onClick=onReplies,enabled=enabled,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(48.dp)
                .semantics {contentDescription=repliesDescription}.testTag("quick-replies")) {
            AppIcon(UiSymbol.REPLY,modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
            Text(stringResource(if(compact) R.string.terminal_replies_short else R.string.terminal_replies_compact),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
        }
        }
    }
}
