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
    AppDialog(onDismiss=onDismiss,properties=protectedDialogProperties,title=stringResource(R.string.editor_key_paste),body=stringResource(R.string.key_paste_help),icon=UiSymbol.KEY,
        confirm=DialogAction(stringResource(R.string.key_paste_import),{
            runCatching {PrivateKeyText.decode(draft)}.onSuccess { onImport(it);draft="" }.onFailure {error=errorText(it)}
        },"confirm-key-paste",enabled=draft.isNotBlank() && error==null),
        dismiss=DialogAction(stringResource(R.string.common_cancel),onDismiss)) {
        OutlinedTextField(draft,::update,modifier=Modifier.fillMaxWidth().testTag("private-key-text"),minLines=4,maxLines=6,
            label={Text(stringResource(R.string.key_paste_label))},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace),
            shape=appFieldShape,colors=appFieldColors(),isError=error!=null,
            visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick={
                val text=runCatching {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.let {if(it.itemCount>0) it.getItemAt(0).text?.toString() else null}}.getOrNull()
                if(text.isNullOrBlank()) error=uiText(R.string.key_paste_clipboard_empty) else update(text)
            },shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),modifier=Modifier.testTag("paste-key-clipboard")) {AppIcon(UiSymbol.COPY,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(stringResource(R.string.key_paste_from_clipboard))}
            TextButton(onClick={visible=!visible}) {AppIcon(UiSymbol.EYE,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(stringResource(if(visible) R.string.key_paste_hide else R.string.key_paste_show))}
        }
        error?.let { NoticeStrip(it.string(),Tone.DANGER,UiSymbol.ALERT_CIRCLE) }
    }
}
