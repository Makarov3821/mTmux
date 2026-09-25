package dev.mtmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class TerminalSnapshot(val text: String, val clipped: Boolean, val viewportOffset: Int)

/** Native selection handles operate on an immutable local copy, never on the SSH input surface. */
@Composable fun TerminalCopyDialog(snapshot: TerminalSnapshot, fontSize: Int, dark: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val textColor=MaterialTheme.colorScheme.onSurface.toArgb()
    val selectionColor=MaterialTheme.colorScheme.primaryContainer.toArgb()
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val dialogView=androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if(dark) 0xFF111916.toInt() else 0xFFF7FAF8.toInt()))
                androidx.core.view.WindowCompat.getInsetsController(window,dialogView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick=onClose,modifier=Modifier.testTag("close-terminal-copy")) { Text("‹ 返回终端") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("终端文字",snapshot.text))
                        copied=true
                    },enabled=snapshot.text.isNotEmpty(),modifier=Modifier.testTag("copy-all-terminal")) { Text(if(copied) "已复制" else "复制全部") }
                }
                Text("选择复制",style=MaterialTheme.typography.titleLarge)
                Text("长按文字并拖动选区，使用系统菜单复制。这里是已加载内容的静态副本；更早的 tmux 历史需返回终端滚动后再打开。",style=MaterialTheme.typography.bodySmall)
                if(snapshot.clipped) Text("内容较多，仅保留最近 20 万字符。",style=MaterialTheme.typography.bodySmall)
                if(snapshot.text.isEmpty()) Text("暂无可复制的终端文字",Modifier.padding(top=24.dp))
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
                            setPadding(8,24,8,24)
                            setTextIsSelectable(true)
                            // Keep selection local: no editable input, links or remote actions.
                            autoLinkMask=0
                        }
                        addView(textView)
                        post { textView.layout?.let { layout -> scrollTo(0,layout.getLineTop(layout.getLineForOffset(snapshot.viewportOffset.coerceIn(0,snapshot.text.length)))) } }
                    }
                },update={scroll -> (scroll.getChildAt(0) as TextView).apply {setTextColor(textColor);highlightColor=selectionColor}},modifier=Modifier.weight(1f).fillMaxWidth().testTag("terminal-copy-content"))
            }
        }
    }
}
