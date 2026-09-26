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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mtmux.core.PrivateKeyText

@Composable fun PrivateKeyPasteDialog(onDismiss: () -> Unit, onImport: (ByteArray) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    var visible by remember { mutableStateOf(false) }
    val context=LocalContext.current
    fun update(value: String) {
        if(value.length>65536 || value.toByteArray().size>65536) error=uiText(R.string.err_private_key_too_large)
        else {draft=value;error=null}
    }
    ProtectSensitiveContent()
    AlertDialog(properties=protectedDialogProperties,containerColor=MaterialTheme.colorScheme.surfaceVariant,onDismissRequest=onDismiss,title={Text(stringResource(R.string.editor_key_paste))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.key_paste_help),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft,::update,modifier=Modifier.fillMaxWidth().testTag("private-key-text"),minLines=4,maxLines=6,
                label={Text(stringResource(R.string.key_paste_label))},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace),
                visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            Row {
                TextButton(onClick={
                    val text=runCatching {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.let {if(it.itemCount>0) it.getItemAt(0).text?.toString() else null}}.getOrNull()
                    if(text.isNullOrBlank()) error=uiText(R.string.key_paste_clipboard_empty) else update(text)
                },modifier=Modifier.testTag("paste-key-clipboard")) {Text(stringResource(R.string.key_paste_from_clipboard))}
                TextButton(onClick={visible=!visible}) {Text(stringResource(if(visible) R.string.key_paste_hide else R.string.key_paste_show))}
            }
            error?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={
        runCatching {PrivateKeyText.decode(draft)}.onSuccess { onImport(it);draft="" }
            .onFailure {error=errorText(it)}
    },enabled=draft.isNotBlank() && error==null,modifier=Modifier.testTag("confirm-key-paste")) {Text(stringResource(R.string.key_paste_import))}},
        dismissButton={TextButton(onClick=onDismiss) {Text(stringResource(R.string.common_cancel))}})
}
