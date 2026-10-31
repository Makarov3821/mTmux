package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuickReplySheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context=LocalContext.current
    val store=remember { QuickReplies(context) }
    var replies by remember { mutableStateOf(store.all()) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    fun save(next: List<String>) { runCatching { store.save(next);replies=next;editIndex=null;error=null }.onFailure {error=errorText(it)} }
    AppSheet(onDismiss=onDismiss) {
        SheetHeader(stringResource(R.string.terminal_quick_replies),stringResource(R.string.quick_reply_help),UiSymbol.REPLY)
        Column(Modifier.fillMaxWidth().heightIn(max=460.dp).verticalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            replies.forEachIndexed { index, text ->
                Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick={onPick(text)},shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("quick-reply-$index")) {
                            Text(text,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        AppIconButton(UiSymbol.EDIT,stringResource(R.string.common_edit),{editIndex=index;value=text;error=null},Modifier.testTag("edit-quick-reply-$index"),tone=IconTone.PLAIN,iconSize=17.dp)
                    }
                }
            }
            error?.let { NoticeStrip(it.string(),Tone.DANGER,UiSymbol.ALERT_CIRCLE) }
        }
        Row(Modifier.padding(start=8.dp,end=8.dp,top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TonalActionButton(DialogAction(stringResource(R.string.common_close),onDismiss),Modifier.weight(1f))
            PrimaryActionButton(DialogAction(stringResource(R.string.quick_reply_add),{editIndex=replies.size;value="";error=null},"add-quick-reply",enabled=replies.size<12),Modifier.weight(1f))
        }
    }
    editIndex?.let { index ->
        AppDialog(onDismiss={editIndex=null},title=stringResource(if(index<replies.size) R.string.quick_reply_edit_title else R.string.quick_reply_add_title),icon=UiSymbol.REPLY,
            confirm=DialogAction(stringResource(R.string.common_save),{save(replies.toMutableList().apply {if(index<size) this[index]=value.trim() else add(value.trim())})},"save-quick-reply"),
            dismiss=DialogAction(stringResource(R.string.common_cancel),{editIndex=null}),
            extra=if(index<replies.size) DialogAction(stringResource(R.string.common_delete),{save(replies.filterIndexed { i,_ -> i!=index })},danger=true) else null) {
            OutlinedTextField(value,{value=it},label={Text(stringResource(R.string.quick_reply_content))},maxLines=6,shape=appFieldShape,colors=appFieldColors(),
                modifier=Modifier.fillMaxWidth().testTag("quick-reply-editor"))
            Text(stringResource(R.string.quick_reply_counter, value.length),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it.string(),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        }
    }
}
