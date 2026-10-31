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
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class UiSymbol { BACK, DOWN, NEXT, PLUS, SETTINGS, FOLDER, SORT, REFRESH, MORE, TERMINAL, SEARCH, LATEST, COPY, MIC, CLOSE, CHECK, SHIELD, KEY, TOOLS, REPLY, KEYBOARD, EDIT, SHIELD_ALERT, UNPLUG, LOCK, ALERT, ALERT_CIRCLE, INFO, SPARKLE, SERVER, ROUTE, CLOCK, EYE, FOLDER_PLUS, WAVE, GLOBE, LINK, HAND, LOG, FILE, ARROW_UP, ARROW_LEFT, PALETTE, STOP, SEND }
/** Line icons shared with docs/plan-ui/shared/icons.js: SVG path data on a 24-unit grid, stroke 1.8. */
private val iconPaths: Map<UiSymbol, List<Pair<String, Boolean>>> = mapOf(
    UiSymbol.BACK to listOf("M 15 18 l -6 -6 6 -6" to false),
    UiSymbol.DOWN to listOf("M 6 9 l 6 6 6 -6" to false),
    UiSymbol.NEXT to listOf("M 9 6 l 6 6 -6 6" to false),
    UiSymbol.PLUS to listOf("M 12 5 v 14 M 5 12 h 14" to false),
    UiSymbol.SETTINGS to listOf("M 9.0 12.0 a 3.0 3.0 0 1 0 6.0 0 a 3.0 3.0 0 1 0 -6.0 0" to false, "M 19.4 15 a 1.7 1.7 0 0 0 .3 1.8 l .1 .1 a 2 2 0 1 1 -2.8 2.8 l -.1 -.1 a 1.7 1.7 0 0 0 -1.8 -.3 1.7 1.7 0 0 0 -1 1.5 V 21 a 2 2 0 1 1 -4 0 v -.1 a 1.7 1.7 0 0 0 -1.1 -1.5 1.7 1.7 0 0 0 -1.8 .3 l -.1 .1 a 2 2 0 1 1 -2.8 -2.8 l .1 -.1 a 1.7 1.7 0 0 0 .3 -1.8 1.7 1.7 0 0 0 -1.5 -1 H 3 a 2 2 0 1 1 0 -4 h .1 a 1.7 1.7 0 0 0 1.5 -1.1 1.7 1.7 0 0 0 -.3 -1.8 l -.1 -.1 a 2 2 0 1 1 2.8 -2.8 l .1 .1 a 1.7 1.7 0 0 0 1.8 .3 H 9 a 1.7 1.7 0 0 0 1 -1.5 V 3 a 2 2 0 1 1 4 0 v .1 a 1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8 -.3 l .1 -.1 a 2 2 0 1 1 2.8 2.8 l -.1 .1 a 1.7 1.7 0 0 0 -.3 1.8 V 9 a 1.7 1.7 0 0 0 1.5 1 H 21 a 2 2 0 1 1 0 4 h -.1 a 1.7 1.7 0 0 0 -1.5 1 z" to false),
    UiSymbol.FOLDER to listOf("M 3 7 a 2 2 0 0 1 2 -2 h 4 l 2 2 h 8 a 2 2 0 0 1 2 2 v 8 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z" to false),
    UiSymbol.SORT to listOf("M 7 4 v 16 M 3.5 16.5 L 7 20 l 3.5 -3.5 M 17 20 V 4 M 13.5 7.5 L 17 4 l 3.5 3.5" to false),
    UiSymbol.REFRESH to listOf("M 20 11 a 8 8 0 0 0 -14.8 -3.5 M 4 4 v 4 h 4 M 4 13 a 8 8 0 0 0 14.8 3.5 M 20 20 v -4 h -4" to false),
    UiSymbol.MORE to listOf("M 11.1 5.5 a 0.9 0.9 0 1 0 1.8 0 a 0.9 0.9 0 1 0 -1.8 0" to true, "M 11.1 12.0 a 0.9 0.9 0 1 0 1.8 0 a 0.9 0.9 0 1 0 -1.8 0" to true, "M 11.1 18.5 a 0.9 0.9 0 1 0 1.8 0 a 0.9 0.9 0 1 0 -1.8 0" to true),
    UiSymbol.TERMINAL to listOf("M 5 8 l 4 4 -4 4 M 12 17 h 7" to false),
    UiSymbol.SEARCH to listOf("M 4.5 11.0 a 6.5 6.5 0 1 0 13.0 0 a 6.5 6.5 0 1 0 -13.0 0" to false, "M 20 20 l -4.2 -4.2" to false),
    UiSymbol.LATEST to listOf("M 12 4 v 11 M 7 10.5 l 5 5 5 -5 M 5 20 h 14" to false),
    UiSymbol.COPY to listOf("M 10.0 8.0 h 8.0 a 2.0 2.0 0 0 1 2.0 2.0 v 8.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -8.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -8.0 a 2.0 2.0 0 0 1 2.0 -2.0 z" to false, "M 16 8 V 6 a 2 2 0 0 0 -2 -2 H 6 a 2 2 0 0 0 -2 2 v 8 a 2 2 0 0 0 2 2 h 2" to false),
    UiSymbol.MIC to listOf("M 12.0 3.0 h 0.0 a 3.0 3.0 0 0 1 3.0 3.0 v 5.0 a 3.0 3.0 0 0 1 -3.0 3.0 h -0.0 a 3.0 3.0 0 0 1 -3.0 -3.0 v -5.0 a 3.0 3.0 0 0 1 3.0 -3.0 z" to false, "M 5.5 11 a 6.5 6.5 0 0 0 13 0 M 12 17.5 V 21" to false),
    UiSymbol.CLOSE to listOf("M 18 6 L 6 18 M 6 6 l 12 12" to false),
    UiSymbol.CHECK to listOf("M 5 12.5 l 4.5 4.5 L 19 7.5" to false),
    UiSymbol.SHIELD to listOf("M 12 3 l 7.5 3 v 5.5 c 0 4.6 -3.2 8.3 -7.5 9.5 -4.3 -1.2 -7.5 -4.9 -7.5 -9.5 V 6 z" to false, "M 8.8 12 l 2.2 2.2 4.3 -4.4" to false),
    UiSymbol.KEY to listOf("M 4.0 15.0 a 4.0 4.0 0 1 0 8.0 0 a 4.0 4.0 0 1 0 -8.0 0" to false, "M 11 12 l 9 -9 M 16 7 l 3 3 M 14 9 l 2 2" to false),
    UiSymbol.TOOLS to listOf("M 14.5 6.5 a 4 4 0 0 0 -5.3 5.2 L 4 17 l 3 3 5.3 -5.2 a 4 4 0 0 0 5.2 -5.3 l -2.6 2.6 -2.4 -.6 -.6 -2.4 z" to false),
    UiSymbol.REPLY to listOf("M 9 7 L 4 12 l 5 5" to false, "M 4 12 h 10 a 6 6 0 0 1 6 6 v 1" to false),
    UiSymbol.KEYBOARD to listOf("M 4.5 6.0 h 15.0 a 2.0 2.0 0 0 1 2.0 2.0 v 8.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -15.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -8.0 a 2.0 2.0 0 0 1 2.0 -2.0 z" to false, "M 6 10 h .01 M 9.5 10 h .01 M 13 10 h .01 M 16.5 10 h .01 M 7 14 h 10" to false),
    UiSymbol.EDIT to listOf("M 4 20 h 4 L 19 9 a 2.8 2.8 0 0 0 -4 -4 L 4 16 z" to false, "M 13.5 6.5 l 4 4" to false),
    UiSymbol.SHIELD_ALERT to listOf("M 12 3 l 7.5 3 v 5.5 c 0 4.6 -3.2 8.3 -7.5 9.5 -4.3 -1.2 -7.5 -4.9 -7.5 -9.5 V 6 z" to false, "M 9.5 9.5 l 5 5 M 14.5 9.5 l -5 5" to false),
    UiSymbol.UNPLUG to listOf("M 9 7 V 3 M 15 7 V 3 M 7 7 h 10 v 4 a 5 5 0 0 1 -10 0 z M 12 16 v 5" to false, "M 3 3 l 18 18" to false),
    UiSymbol.LOCK to listOf("M 7.0 11.0 h 10.0 a 2.0 2.0 0 0 1 2.0 2.0 v 5.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -10.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -5.0 a 2.0 2.0 0 0 1 2.0 -2.0 z" to false, "M 8 11 V 8 a 4 4 0 0 1 8 0 v 3" to false),
    UiSymbol.ALERT to listOf("M 12 3.5 L 2.5 20 h 19 z" to false, "M 12 10 v 4.5 M 12 17.5 h .01" to false),
    UiSymbol.ALERT_CIRCLE to listOf("M 3.0 12.0 a 9.0 9.0 0 1 0 18.0 0 a 9.0 9.0 0 1 0 -18.0 0" to false, "M 12 7.5 v 5.5 M 12 16.5 h .01" to false),
    UiSymbol.INFO to listOf("M 3.0 12.0 a 9.0 9.0 0 1 0 18.0 0 a 9.0 9.0 0 1 0 -18.0 0" to false, "M 12 11 v 5.5 M 12 7.5 h .01" to false),
    UiSymbol.SPARKLE to listOf("M 12 3 v 4 M 12 17 v 4 M 3 12 h 4 M 17 12 h 4 M 6 6 l 2.5 2.5 M 15.5 15.5 L 18 18 M 6 18 l 2.5 -2.5 M 15.5 8.5 L 18 6" to false),
    UiSymbol.SERVER to listOf("M 5.3 4.0 h 13.4 a 1.8 1.8 0 0 1 1.8 1.8 v 3.4 a 1.8 1.8 0 0 1 -1.8 1.8 h -13.4 a 1.8 1.8 0 0 1 -1.8 -1.8 v -3.4 a 1.8 1.8 0 0 1 1.8 -1.8 z" to false, "M 5.3 13.0 h 13.4 a 1.8 1.8 0 0 1 1.8 1.8 v 3.4 a 1.8 1.8 0 0 1 -1.8 1.8 h -13.4 a 1.8 1.8 0 0 1 -1.8 -1.8 v -3.4 a 1.8 1.8 0 0 1 1.8 -1.8 z" to false, "M 7.5 7.5 h .01 M 7.5 16.5 h .01" to false),
    UiSymbol.ROUTE to listOf("M 3.8 18.0 a 2.2 2.2 0 1 0 4.4 0 a 2.2 2.2 0 1 0 -4.4 0" to false, "M 15.8 6.0 a 2.2 2.2 0 1 0 4.4 0 a 2.2 2.2 0 1 0 -4.4 0" to false, "M 8.2 18 H 15 a 3 3 0 0 0 0 -6 H 9 a 3 3 0 0 1 0 -6 h 6.8" to false),
    UiSymbol.CLOCK to listOf("M 3.0 12.0 a 9.0 9.0 0 1 0 18.0 0 a 9.0 9.0 0 1 0 -18.0 0" to false, "M 12 7.5 V 12 l 3 2" to false),
    UiSymbol.EYE to listOf("M 2.5 12 S 6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12 z" to false, "M 9.2 12.0 a 2.8 2.8 0 1 0 5.6 0 a 2.8 2.8 0 1 0 -5.6 0" to false),
    UiSymbol.FOLDER_PLUS to listOf("M 3 7 a 2 2 0 0 1 2 -2 h 4 l 2 2 h 8 a 2 2 0 0 1 2 2 v 8 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z" to false, "M 12 10.5 v 5 M 9.5 13 h 5" to false),
    UiSymbol.WAVE to listOf("M 4 10 v 4 M 8 7 v 10 M 12 4 v 16 M 16 7 v 10 M 20 10 v 4" to false),
    UiSymbol.GLOBE to listOf("M 3.0 12.0 a 9.0 9.0 0 1 0 18.0 0 a 9.0 9.0 0 1 0 -18.0 0" to false, "M 3 12 h 18 M 12 3 a 14 14 0 0 1 0 18 M 12 3 a 14 14 0 0 0 0 18" to false),
    UiSymbol.LINK to listOf("M 10 14 a 4 4 0 0 0 5.7 0 l 3 -3 a 4 4 0 0 0 -5.7 -5.7 l -1 1 M 14 10 a 4 4 0 0 0 -5.7 0 l -3 3 a 4 4 0 0 0 5.7 5.7 l 1 -1" to false),
    UiSymbol.HAND to listOf("M 8 13 V 5.5 a 1.5 1.5 0 0 1 3 0 V 11 M 11 10 V 4 a 1.5 1.5 0 0 1 3 0 v 6 M 14 10 V 5.5 a 1.5 1.5 0 0 1 3 0 V 14 a 7 7 0 0 1 -7 7 h -.5 a 6 6 0 0 1 -4.8 -2.5 L 3 15.5 a 1.6 1.6 0 0 1 2.5 -2 L 8 15" to false),
    UiSymbol.LOG to listOf("M 6 3 h 12 v 18 H 6 z" to false, "M 9 8 h 6 M 9 12 h 6 M 9 16 h 4" to false),
    UiSymbol.FILE to listOf("M 6 3 h 8 l 5 5 v 13 H 6 z" to false, "M 14 3 v 5 h 5" to false),
    UiSymbol.ARROW_UP to listOf("M 12 19 V 5 M 6 11 l 6 -6 6 6" to false),
    UiSymbol.ARROW_LEFT to listOf("M 19 12 H 5 M 11 6 l -6 6 6 6" to false),
    UiSymbol.PALETTE to listOf("M 12 3 a 9 9 0 1 0 0 18 c 1.2 0 1.8 -.8 1.8 -1.7 0 -1.3 -1.2 -1.6 -1.2 -2.8 0 -1 .8 -1.6 1.8 -1.6 H 17 a 4 4 0 0 0 4 -4 C 21 6.6 17 3 12 3 z" to false, "M 6.5 11.0 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0" to false, "M 9.5 7.5 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0" to false, "M 14.0 7.5 a 1.0 1.0 0 1 0 2.0 0 a 1.0 1.0 0 1 0 -2.0 0" to false),
    UiSymbol.STOP to listOf("M 6 6 h 12 v 12 h -12 z" to false),
    UiSymbol.SEND to listOf("M 3 3 L 22 12 L 3 21 L 7 12 Z M 7 12 H 21" to false),
)
private val vectors = UiSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name,24.dp,24.dp,24f,24f,autoMirror = symbol in setOf(UiSymbol.BACK,UiSymbol.NEXT,UiSymbol.REPLY)).apply {
        iconPaths.getValue(symbol).forEach { (data, filled) ->
            addPath(addPathNodes(data), fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}

@Composable fun AppIcon(symbol: UiSymbol, label: String? = null, modifier: Modifier = Modifier.size(20.dp), tint: Color = LocalContentColor.current) {
    Icon(vectors.getValue(symbol), label, modifier, tint)
}

enum class IconTone { NEUTRAL, ACCENT, PROMINENT, PLAIN, DANGER }

@Composable fun AppIconButton(symbol: UiSymbol, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, prominent: Boolean = false, tone: IconTone = if (prominent) IconTone.PROMINENT else IconTone.NEUTRAL,
    size: Dp = 34.dp, iconSize: Dp = 18.dp, content: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val (container, fg) = when (tone) {
        IconTone.PROMINENT -> scheme.primary to scheme.onPrimary
        IconTone.ACCENT -> scheme.primary.copy(alpha = .14f) to scheme.primary
        IconTone.DANGER -> scheme.error.copy(alpha = .14f) to scheme.error
        IconTone.PLAIN -> Color.Transparent to scheme.onSurfaceVariant
        IconTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(maxOf(size, 44.dp)).semantics { contentDescription = label }) {
        Surface(shape = RoundedCornerShape(size * .32f), color = if (enabled) container else container.copy(alpha = container.alpha * .5f),
            contentColor = fg.copy(alpha = if (enabled) 1f else .38f)) {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) { content?.invoke() ?: AppIcon(symbol, modifier = Modifier.size(iconSize)) }
        }
    }
}

/** Semantic state colors kept apart from the palette accent. */
object StatusColors {
    @Composable fun attention() = if (isDarkScheme()) Color(0xFFF7768E) else Color(0xFFC0263D)
    @Composable fun running() = if (isDarkScheme()) Color(0xFFE0AF68) else Color(0xFF946200)
    @Composable fun ready() = if (isDarkScheme()) Color(0xFF9ECE6A) else Color(0xFF2E7D32)
    @Composable fun unknown() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
}
@Composable fun isDarkScheme() = MaterialTheme.colorScheme.background.luminance() < .4f

enum class Tone { ACCENT, SUCCESS, WARNING, DANGER, NEUTRAL }
@Composable fun Tone.color(): Color = when (this) {
    Tone.ACCENT -> MaterialTheme.colorScheme.primary
    Tone.SUCCESS -> StatusColors.ready()
    Tone.WARNING -> StatusColors.running()
    Tone.DANGER -> StatusColors.attention()
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Rounded square badge with a tinted background, used by dialogs, sheets and notices. */
@Composable fun ToneBadge(symbol: UiSymbol, tone: Tone, size: Dp = 44.dp, iconSize: Dp = size * .5f) {
    val c = tone.color()
    Box(Modifier.size(size).background(c.copy(alpha = .15f), RoundedCornerShape(size * .32f)), contentAlignment = Alignment.Center) {
        AppIcon(symbol, modifier = Modifier.size(iconSize), tint = c)
    }
}

@Composable fun BentoCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionLabel(title)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun InsetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Filled field: soft container, accent outline only when focused. */
@Composable fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent)
val appFieldShape = RoundedCornerShape(12.dp)

data class DialogAction(val text: String, val onClick: () -> Unit, val tag: String? = null, val enabled: Boolean = true, val danger: Boolean = false)

@Composable private fun actionModifier(action: DialogAction, base: Modifier) = if (action.tag != null) base.testTag(action.tag) else base

@Composable fun PrimaryActionButton(action: DialogAction, modifier: Modifier = Modifier) {
    Button(onClick = action.onClick, enabled = action.enabled, shape = RoundedCornerShape(14.dp),
        colors = if (action.danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = .16f), contentColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        modifier = actionModifier(action, modifier.heightIn(min = 46.dp))) { Text(action.text, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}
@Composable fun TonalActionButton(action: DialogAction, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = action.onClick, enabled = action.enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
        modifier = actionModifier(action, modifier.heightIn(min = 46.dp))) { Text(action.text, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}
@Composable fun QuietActionButton(action: DialogAction, modifier: Modifier = Modifier) {
    TextButton(onClick = action.onClick, enabled = action.enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = actionModifier(action, modifier.heightIn(min = 44.dp))) { Text(action.text, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Shared dialog: badge → title → body → content → actions. Two actions sit side by side
 * (secondary left, primary right); [extra] is a quiet text action underneath.
 */
@Composable fun AppDialog(onDismiss: () -> Unit, title: String, body: String? = null, icon: UiSymbol? = null, tone: Tone = Tone.ACCENT,
    confirm: DialogAction? = null, dismiss: DialogAction? = null, extra: DialogAction? = null, stacked: Boolean = false,
    properties: DialogProperties = DialogProperties(), headerEnd: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside, securePolicy = properties.securePolicy, usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 18.dp).widthIn(max = 460.dp).fillMaxWidth(), shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 12.dp) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp).heightIn(max = 640.dp)) {
                if (icon != null || headerEnd != null) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
                    if (icon != null) ToneBadge(icon, tone, 48.dp, 24.dp)
                    Spacer(Modifier.weight(1f)); headerEnd?.invoke()
                }
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (body != null) Text(body, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (content != null) Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                }
                Spacer(Modifier.height(18.dp))
                when {
                    stacked -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        confirm?.let { PrimaryActionButton(it, Modifier.fillMaxWidth()) }
                        dismiss?.let { QuietActionButton(it, Modifier.fillMaxWidth()) }
                    }
                    confirm != null && dismiss != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TonalActionButton(dismiss, Modifier.weight(1f)); PrimaryActionButton(confirm, Modifier.weight(1f))
                    }
                    confirm != null -> PrimaryActionButton(confirm, Modifier.fillMaxWidth())
                    dismiss != null -> TonalActionButton(dismiss, Modifier.fillMaxWidth())
                }
                extra?.let { QuietActionButton(it, Modifier.fillMaxWidth().padding(top = 2.dp)) }
            }
        }
    }
}

/** Floating rounded bottom panel used for actions and short flows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, floating: Boolean = true,
    secure: Boolean = false, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != SheetValue.Hidden }),
        shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        properties = ModalBottomSheetProperties(securePolicy = if (secure) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit, shouldDismissOnBackPress = dismissible),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) },
        modifier = modifier.then(if (floating) Modifier.padding(horizontal = 8.dp).navigationBarsPadding().padding(bottom = 8.dp) else Modifier)) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp), content = content)
    }
}

@Composable fun SheetHeader(title: String, subtitle: String? = null, icon: UiSymbol? = null, tone: Tone = Tone.NEUTRAL, end: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) {
            if (tone == Tone.NEUTRAL) Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { AppIcon(icon) }
            else ToneBadge(icon, tone, 42.dp, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        end?.invoke()
    }
}

@Composable fun SheetAction(symbol: UiSymbol, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    danger: Boolean = false, highlighted: Boolean = false, trailing: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (danger) scheme.error else scheme.onSurface
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
        .background(if (highlighted) scheme.surfaceVariant else Color.Transparent).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp).alpha(if (enabled) 1f else .4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(when { danger -> scheme.error.copy(alpha = .14f); highlighted -> scheme.primary.copy(alpha = .14f); else -> scheme.surfaceVariant }, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { AppIcon(symbol, modifier = Modifier.size(18.dp), tint = when { danger -> scheme.error; highlighted -> scheme.primary; else -> scheme.onSurface }) }
        Text(text, Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable fun SheetDivider() { HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant) }

enum class StepState { DONE, ACTIVE, PENDING, FAILED, WARNING }

/** One row of a staged progress list (connection, verification, Doctor). */
@Composable fun StepRow(text: String, state: StepState, trailing: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.DONE -> Box(Modifier.size(22.dp).background(StatusColors.ready().copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { AppIcon(UiSymbol.CHECK, modifier = Modifier.size(13.dp), tint = StatusColors.ready()) }
                StepState.FAILED -> Box(Modifier.size(22.dp).background(StatusColors.attention().copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { AppIcon(UiSymbol.CLOSE, modifier = Modifier.size(12.dp), tint = StatusColors.attention()) }
                StepState.WARNING -> Box(Modifier.size(22.dp).background(StatusColors.running().copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) { AppIcon(UiSymbol.ALERT, modifier = Modifier.size(12.dp), tint = StatusColors.running()) }
                StepState.ACTIVE -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                StepState.PENDING -> Box(Modifier.size(20.dp).border(1.5.dp, scheme.outlineVariant, CircleShape))
            }
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (state == StepState.ACTIVE || state == StepState.FAILED) FontWeight.SemiBold else FontWeight.Normal),
            color = when (state) { StepState.PENDING -> scheme.onSurfaceVariant.copy(alpha = .7f); StepState.FAILED -> StatusColors.attention(); else -> scheme.onSurface })
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

enum class RouteState { NORMAL, DONE, CURRENT, FAILED }
/** Phone → jump → target chips. */
@Composable fun RouteChips(nodes: List<Pair<String, RouteState>>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        nodes.forEachIndexed { index, (label, state) ->
            if (index > 0) AppIcon(UiSymbol.NEXT, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val color = when (state) { RouteState.DONE -> StatusColors.ready(); RouteState.CURRENT -> MaterialTheme.colorScheme.primary; RouteState.FAILED -> StatusColors.attention(); RouteState.NORMAL -> MaterialTheme.colorScheme.onSurface }
            Surface(shape = CircleShape, color = if (state == RouteState.NORMAL) MaterialTheme.colorScheme.surfaceVariant else color.copy(alpha = .14f),
                border = if (state == RouteState.CURRENT) BorderStroke(1.dp, color.copy(alpha = .4f)) else null) {
                Text(label + if (state == RouteState.DONE) " ✓" else "", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
            }
        }
    }
}

/** Translucent floating card placed over the terminal. */
@Composable fun FloatingCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .96f), shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)), content = content)
}

/** Inline notice inside cards or sheets: tinted strip with icon, title, optional detail and action. */
@Composable fun NoticeStrip(title: String, tone: Tone, symbol: UiSymbol, modifier: Modifier = Modifier, detail: String? = null,
    action: DialogAction? = null) {
    val c = tone.color()
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.background(c.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(symbol, modifier = Modifier.size(18.dp), tint = c)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) FilledTonalButton(onClick = action.onClick, enabled = action.enabled, shape = RoundedCornerShape(11.dp),
            contentPadding = PaddingValues(horizontal = 12.dp), modifier = actionModifier(action, Modifier.height(34.dp)),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Text(action.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Fingerprint shown in 4-character groups so it can be compared piece by piece. */
@Composable fun FingerprintBlock(endpoint: String, fingerprint: String, modifier: Modifier = Modifier, valueTag: String = "fingerprint-groups") {
    val (algo, value) = fingerprint.substringBefore(':', "").let { if (it.isEmpty()) "" to fingerprint else it to fingerprint.substringAfter(':') }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOf(endpoint, algo).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Screen readers and tests get the exact fingerprint; the visual grouping is only for comparing by eye.
            Text(value.chunked(4).joinToString(" "), Modifier.clearAndSetSemantics { text = AnnotatedString(fingerprint); testTag = valueTag }, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = .6.sp, lineHeight = 24.sp))
        }
    }
}

@Composable fun TaskStateDot(color: Color, ring: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(if (ring) 16.dp else 8.dp).then(if (ring) Modifier.border(1.5.dp, color.copy(alpha = .45f), CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** Brand mark: rounded tile with the terminal glyph. */
@Composable fun BrandMark(size: Dp = 30.dp) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(primary.copy(alpha = .78f), primary)), RoundedCornerShape(size * .3f)), contentAlignment = Alignment.Center) {
        AppIcon(UiSymbol.TERMINAL, modifier = Modifier.size(size * .6f), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable fun ScrollSpeedControl(value: Float, onChange: (Float) -> Unit, tag: String) {
    Column {
        SettingRow(stringResource(R.string.scroll_speed_title), icon = UiSymbol.SORT) {
            Text("${ScrollSpeed.normalize(value).toInt()}×", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
        }
        Slider(value = ScrollSpeed.normalize(value), onValueChange = { onChange(ScrollSpeed.normalize(it)) },
            valueRange = 1f..10f, steps = 8, modifier = Modifier.padding(start = 31.dp).testTag(tag))
        Text(stringResource(R.string.scroll_speed_help), Modifier.padding(start = 31.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
    // Tools and replies read at about 70% of the 52dp voice button (36dp tall, 22dp icon, 16sp label);
    // the voice button stays centered and each label is centered in its half, so they sit symmetrically.
    val label=MaterialTheme.typography.labelLarge.copy(fontSize=16.sp,lineHeight=20.sp)
    Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        TextButton(onClick=onTools,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(48.dp).testTag("terminal-tools")) {
            AppIcon(UiSymbol.TOOLS,modifier=Modifier.size(22.dp));Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.terminal_tools_compact),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=label)
        }
        }
        Box(contentAlignment=Alignment.Center) {voice()}
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
        TextButton(onClick=onReplies,enabled=enabled,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding=PaddingValues(horizontal=10.dp),modifier=Modifier.height(48.dp)
                .semantics {contentDescription=repliesDescription}.testTag("quick-replies")) {
            AppIcon(UiSymbol.REPLY,modifier=Modifier.size(22.dp));Spacer(Modifier.width(8.dp))
            Text(stringResource(if(compact) R.string.terminal_replies_short else R.string.terminal_replies_compact),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=label)
        }
        }
    }
}

/** Segmented control; each segment is selectable for accessibility and tests. */
@Composable fun <T> SegmentedChoice(options: List<T>, selected: T, label: @Composable (T) -> String, tag: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Same height for every option: when one label wraps, the others stay vertically centered.
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).background(scheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 38.dp).clip(RoundedCornerShape(9.dp))
                .background(if (on) scheme.primary.copy(alpha = .16f) else Color.Transparent)
                .selectable(on, role = Role.Tab) { onSelect(option) }.testTag(tag(option)).padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Text(label(option), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) scheme.primary else scheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Settings row: optional leading icon, title + one-line help, trailing control. */
@Composable fun SettingRow(title: String, modifier: Modifier = Modifier, icon: UiSymbol? = null, help: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) AppIcon(icon, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
