package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuickReplySheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context=LocalContext.current
    val store=remember { QuickReplies(context) }
    var replies by remember { mutableStateOf(store.all()) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun save(next: List<String>) { runCatching { store.save(next);replies=next;editIndex=null;error="" }.onFailure {error=it.message.orEmpty()} }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=550.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("快捷回复",style=MaterialTheme.typography.titleLarge)
            Text("点击填入草稿，编辑后再发送。不会自动确认或执行。",style=MaterialTheme.typography.bodySmall)
            replies.forEachIndexed { index, text ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick={onPick(text)},modifier=Modifier.weight(1f).testTag("quick-reply-$index")) { Text(text,Modifier.fillMaxWidth()) }
                    TextButton(onClick={editIndex=index;value=text;error=""},modifier=Modifier.testTag("edit-quick-reply-$index")) { Text("编辑") }
                }
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick={editIndex=replies.size;value="";error=""},enabled=replies.size<12,modifier=Modifier.testTag("add-quick-reply")) { Text("＋ 添加回复") }
                TextButton(onClick=onDismiss) { Text("关闭") }
            }
        }
    }
    editIndex?.let { index ->
        AlertDialog(onDismissRequest={editIndex=null},title={Text(if(index<replies.size) "编辑快捷回复" else "添加快捷回复")},text={
            Column {
                OutlinedTextField(value,{value=it},label={Text("回复内容")},maxLines=6,modifier=Modifier.testTag("quick-reply-editor"))
                Text("${value.length}/1000 字",style=MaterialTheme.typography.bodySmall)
                if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={TextButton(onClick={save(replies.toMutableList().apply {if(index<size) this[index]=value.trim() else add(value.trim())})},modifier=Modifier.testTag("save-quick-reply")){Text("保存")}},dismissButton={
            Row {
                if(index<replies.size) TextButton(onClick={save(replies.filterIndexed { i,_ -> i!=index })}) { Text("删除") }
                TextButton(onClick={editIndex=null}) { Text("取消") }
            }
        })
    }
}
