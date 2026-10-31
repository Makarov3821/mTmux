package dev.mtmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class TerminalSnapshot(val text: String, val clipped: Boolean, val viewportOffset: Int)

/** Native selection handles operate on an immutable local copy, never on the SSH input surface. */
@Composable fun TerminalCopyDialog(snapshot: TerminalSnapshot, fontSize: Int, dark: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val textColor=MaterialTheme.colorScheme.onSurface.toArgb()
    val selectionColor=MaterialTheme.colorScheme.primary.copy(alpha=.35f).toArgb()
    val backgroundColor=MaterialTheme.colorScheme.background.toArgb()
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val dialogView=androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundColor))
                androidx.core.view.WindowCompat.getInsetsController(window,dialogView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp,vertical=4.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    AppIconButton(UiSymbol.BACK,stringResource(R.string.copy_back),onClose,Modifier.testTag("close-terminal-copy"),tone=IconTone.PLAIN,iconSize=22.dp)
                    Text(stringResource(R.string.tools_select_copy),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge.copy(fontWeight=androidx.compose.ui.text.font.FontWeight.Bold))
                    FilledTonalButton(shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),onClick={
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_terminal_text),snapshot.text))
                        copied=true
                    },enabled=snapshot.text.isNotEmpty(),modifier=Modifier.testTag("copy-all-terminal")) { AppIcon(if(copied) UiSymbol.CHECK else UiSymbol.COPY,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(stringResource(if(copied) R.string.copy_done else R.string.copy_all)) }
                }
                Text(stringResource(R.string.copy_help),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(snapshot.clipped) NoticeStrip(stringResource(R.string.copy_clipped),Tone.WARNING,UiSymbol.INFO,Modifier.padding(top=8.dp))
                if(snapshot.text.isEmpty()) Text(stringResource(R.string.copy_empty),Modifier.padding(start=4.dp,top=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(Modifier.weight(1f).fillMaxWidth().padding(top=12.dp,bottom=8.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
                AndroidView(factory={ ctx ->
                    ScrollView(ctx).apply {
                        isFillViewport=true
                        val textView=TextView(android.view.ContextThemeWrapper(ctx,if(dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)).apply {
                            tag="terminal-copy-text"
                            text=snapshot.text
                            textSize=fontSize.toFloat()
                            typeface=Typeface.MONOSPACE
                            setTextColor(textColor)
                            highlightColor=selectionColor
                            val inset=(12*resources.displayMetrics.density).toInt();setPadding(inset,inset,inset,inset)
                            setTextIsSelectable(true)
                            // Keep selection local: no editable input, links or remote actions.
                            autoLinkMask=0
                        }
                        addView(textView)
                        post { textView.layout?.let { layout -> scrollTo(0,layout.getLineTop(layout.getLineForOffset(snapshot.viewportOffset.coerceIn(0,snapshot.text.length)))) } }
                    }
                },update={scroll -> (scroll.getChildAt(0) as TextView).apply {setTextColor(textColor);highlightColor=selectionColor}},modifier=Modifier.fillMaxSize().testTag("terminal-copy-content"))
                }
            }
        }
    }
}
