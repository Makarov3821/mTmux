package dev.mtmux

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun DebugLogSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val bytes = payload; payload = null
        if (uri != null && bytes != null) scope.launch {
            busy = true
            result = try {
                withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) } }
                R.string.log_saved
            } catch (_: Exception) { R.string.log_save_failed }
            finally { busy = false }
        } else result = if(uri==null) R.string.log_save_cancelled else R.string.log_snapshot_stale
    }
    HorizontalDivider()
    Text(stringResource(R.string.log_title),color=MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.log_help),style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled=!busy && payload==null,onClick={
            scope.launch {
                busy=true
                try {
                    payload=withContext(Dispatchers.IO) { DebugLog.snapshot() }
                    save.launch("mtmux-debug-${System.currentTimeMillis()}.txt")
                } catch (_: Exception) { payload=null;result=R.string.log_generate_failed }
                finally {busy=false}
            }
        },modifier=Modifier.testTag("export-debug-log")) { Text(stringResource(if(busy) R.string.log_busy else R.string.log_generate)) }
        TextButton(enabled=!busy && payload==null,onClick={scope.launch {
            busy=true
            result=try { withContext(Dispatchers.IO) {DebugLog.clear()};R.string.log_cleared }
                catch (_: Exception) {R.string.log_clear_failed}
            busy=false
        }},modifier=Modifier.testTag("clear-debug-log")) { Text(stringResource(R.string.log_clear)) }
    }
    result?.let { Text(stringResource(it),style=MaterialTheme.typography.bodySmall) }
}
