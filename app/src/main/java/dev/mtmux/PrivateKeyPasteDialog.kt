package dev.mtmux

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mtmux.core.PrivateKeyText

@Composable fun PrivateKeyPasteDialog(onDismiss: () -> Unit, onImport: (ByteArray) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    val context=LocalContext.current
    fun update(value: String) {
        if(value.length>65536 || value.toByteArray().size>65536) error="私钥须小于 64 KiB"
        else {draft=value;error=null}
    }
    ProtectSensitiveContent()
    AlertDialog(properties=protectedDialogProperties,containerColor=MaterialTheme.colorScheme.surfaceVariant,onDismissRequest=onDismiss,title={Text("粘贴私钥")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("粘贴完整私钥，包含 BEGIN 和 END 行。私钥口令在认证区填写。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft,::update,modifier=Modifier.fillMaxWidth().testTag("private-key-text"),minLines=4,maxLines=6,
                label={Text("私钥内容")},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace),
                visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            Row {
                TextButton(onClick={
                    val text=runCatching {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.let {if(it.itemCount>0) it.getItemAt(0).text?.toString() else null}}.getOrNull()
                    if(text.isNullOrBlank()) error="剪切板中没有文本" else update(text)
                },modifier=Modifier.testTag("paste-key-clipboard")) {Text("从剪切板粘贴")}
                TextButton(onClick={visible=!visible}) {Text(if(visible) "隐藏内容" else "显示内容")}
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={
        runCatching {PrivateKeyText.decode(draft)}.onSuccess { onImport(it);draft="" }
            .onFailure {error=it.message ?: "私钥格式不正确"}
    },enabled=draft.isNotBlank() && error==null,modifier=Modifier.testTag("confirm-key-paste")) {Text("导入")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
