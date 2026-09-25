package dev.mtmux

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun DebugLogSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var result by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val bytes = payload; payload = null
        if (uri != null && bytes != null) scope.launch {
            busy = true
            result = try {
                withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) } }
                "日志已保存，可作为附件反馈问题"
            } catch (_: Exception) { "日志保存失败，请重试" }
            finally { busy = false }
        } else result = if(uri==null) "已取消保存" else "日志快照已失效，请重新生成"
    }
    HorizontalDivider()
    Text("调试日志",color=MaterialTheme.colorScheme.primary)
    Text("从应用启动起自动记录运行事件，最多保留最近 10 MiB，超出后覆盖最早记录。不包含密码、私钥、命令或终端正文。",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled=!busy && payload==null,onClick={
            scope.launch {
                busy=true
                try {
                    payload=withContext(Dispatchers.IO) { DebugLog.snapshot() }
                    save.launch("mtmux-debug-${System.currentTimeMillis()}.txt")
                } catch (_: Exception) { payload=null;result="日志生成失败，请重试" }
                finally {busy=false}
            }
        },modifier=Modifier.testTag("export-debug-log")) { Text(if(busy) "处理中…" else "生成日志") }
        TextButton(enabled=!busy && payload==null,onClick={scope.launch {
            busy=true
            result=try { withContext(Dispatchers.IO) {DebugLog.clear()};"历史日志已清空，后续事件继续记录" }
                catch (_: Exception) {"清空失败，请重试"}
            busy=false
        }},modifier=Modifier.testTag("clear-debug-log")) { Text("清空日志") }
    }
    if(result.isNotEmpty()) Text(result,style=MaterialTheme.typography.bodySmall)
}
