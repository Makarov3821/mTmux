package dev.mtmux

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType

internal fun savedPreviewLimits(context:Context):PreviewLimits {
    val preferences=context.getSharedPreferences("terminal-settings",0)
    return PreviewLimits.normalized(preferences.getInt(PreviewLimits.FILE_KEY,10),preferences.getInt(PreviewLimits.CACHE_KEY,50))
}

@Composable internal fun PreviewSettings() {
    val context=LocalContext.current
    var limits by remember {mutableStateOf(savedPreviewLimits(context))}
    var editing by remember {mutableStateOf<String?>(null)}
    var input by remember {mutableStateOf("")}
    fun edit(key:String,value:Int) {input=value.toString();editing=key}
    SettingRow(stringResource(R.string.preview_file_limit),icon=UiSymbol.FILE) {
        TextButton(onClick={edit(PreviewLimits.FILE_KEY,limits.fileMiB)},modifier=Modifier.testTag("preview-file-limit")) {Text("${limits.fileMiB} MiB")}
    }
    SettingRow(stringResource(R.string.preview_cache_limit),icon=UiSymbol.FOLDER) {
        TextButton(onClick={edit(PreviewLimits.CACHE_KEY,limits.cacheMiB)},modifier=Modifier.testTag("preview-cache-limit")) {Text("${limits.cacheMiB} MiB")}
    }
    editing?.let {key ->
        val file=key==PreviewLimits.FILE_KEY
        val value=input.toIntOrNull()
        val maximum=if(file) 100 else 1024
        val inRange=value!=null && value in 1..maximum
        val ordered=value!=null && if(file) value<=limits.cacheMiB else value>=limits.fileMiB
        val valid=inRange && ordered
        AppDialog(onDismiss={editing=null},title=stringResource(if(file) R.string.preview_file_limit else R.string.preview_cache_limit),icon=UiSymbol.FILE,
            confirm=DialogAction(stringResource(R.string.common_save),{
                limits=if(file) limits.copy(fileMiB=value!!) else limits.copy(cacheMiB=value!!)
                context.getSharedPreferences("terminal-settings",0).edit().putInt(PreviewLimits.FILE_KEY,limits.fileMiB)
                    .putInt(PreviewLimits.CACHE_KEY,limits.cacheMiB).apply()
                editing=null
            },tag="preview-limit-save",enabled=valid),dismiss=DialogAction(stringResource(R.string.common_cancel),{editing=null})) {
            OutlinedTextField(input,{input=it},singleLine=true,label={Text("MiB")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                shape=appFieldShape,colors=appFieldColors(),isError=!valid,
                supportingText=if(valid) null else {{Text(if(!inRange) stringResource(R.string.preview_limit_range,maximum)
                    else stringResource(R.string.preview_cache_below_file))}},
                modifier=Modifier.fillMaxWidth().testTag("preview-limit-input"))
        }
    }
}
