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
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=550.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.terminal_quick_replies),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.quick_reply_help),style=MaterialTheme.typography.bodySmall)
            replies.forEachIndexed { index, text ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick={onPick(text)},modifier=Modifier.weight(1f).testTag("quick-reply-$index")) { Text(text,Modifier.fillMaxWidth()) }
                    TextButton(onClick={editIndex=index;value=text;error=null},modifier=Modifier.testTag("edit-quick-reply-$index")) { Text(stringResource(R.string.common_edit)) }
                }
            }
            error?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick={editIndex=replies.size;value="";error=null},enabled=replies.size<12,modifier=Modifier.testTag("add-quick-reply")) { Text(stringResource(R.string.quick_reply_add)) }
                TextButton(onClick=onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
    editIndex?.let { index ->
        AlertDialog(onDismissRequest={editIndex=null},title={Text(stringResource(if(index<replies.size) R.string.quick_reply_edit_title else R.string.quick_reply_add_title))},text={
            Column {
                OutlinedTextField(value,{value=it},label={Text(stringResource(R.string.quick_reply_content))},maxLines=6,modifier=Modifier.testTag("quick-reply-editor"))
                Text(stringResource(R.string.quick_reply_counter, value.length),style=MaterialTheme.typography.bodySmall)
                error?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
            }
        },confirmButton={TextButton(onClick={save(replies.toMutableList().apply {if(index<size) this[index]=value.trim() else add(value.trim())})},modifier=Modifier.testTag("save-quick-reply")){Text(stringResource(R.string.common_save))}},dismissButton={
            Row {
                if(index<replies.size) TextButton(onClick={save(replies.filterIndexed { i,_ -> i!=index })}) { Text(stringResource(R.string.common_delete)) }
                TextButton(onClick={editIndex=null}) { Text(stringResource(R.string.common_cancel)) }
            }
        })
    }
}
