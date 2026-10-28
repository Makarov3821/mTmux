package dev.mtmux.audiotest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private var lab: LabViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: LabViewModel = viewModel(); lab = vm
            DisposableEffect(vm.busy, vm.recording, vm.preparingMic) {
                if (vm.busy || vm.recording || vm.preparingMic) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { LabScreen(vm) }
            }
        }
    }
    override fun onStop() { lab?.background(); super.onStop() }
}
@Composable private fun LabScreen(vm: LabViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.message(if (granted) "麦克风已允许，请重新按住说话" else "需要麦克风权限，也可以导入 WAV")
    }
    val wav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importWav { context.contentResolver.openInputStream(uri) } }
    val pack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModels { context.contentResolver.openInputStream(uri) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.export { context.contentResolver.openOutputStream(uri) } }
    var showModels by remember { mutableStateOf(true) }
    var showOtherModels by remember { mutableStateOf(false) }
    val idle = !vm.busy && !vm.recording && !vm.preparingMic
    val latestIdle by rememberUpdatedState(vm.canRecord)
    val latestRecording by rememberUpdatedState(vm.recording)
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        else vm.press()
    }
    Scaffold(bottomBar = {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vm.phase, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            val enabled = vm.canRecord || vm.recording || vm.preparingMic
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (vm.recording) MaterialTheme.colorScheme.tertiaryContainer else if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(64.dp).semantics {
                    role = Role.Button; contentDescription = "按住说话，松开比较"
                    onClick(label = "开始或结束录音") { if (latestRecording) vm.release() else if (latestIdle) start(); true }
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (latestIdle) {
                            down.consume(); start()
                            val up = waitForUpOrCancellation()
                            vm.release(cancel = up == null); up?.consume()
                        }
                    }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (vm.recording) "●  录音 ${vm.elapsedRecording}s · 松开比较" else if (vm.preparingMic) "准备麦克风…" else if (vm.busy) "比较 / 准备中…" else "按住说话，松开比较", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("语音对照实验室", style = MaterialTheme.typography.headlineMedium)
                Text("X-ASR × Paraformer · 标点专项对照 · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("同一份录音；Paraformer 只识别一次，CT / SRF 使用完全相同的原文。比较长句断句、问号和技术术语，原字与简繁保持不变。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选 ${vm.selected.size} 组 · ${vm.chosen.distinctBy { it.engine to it.group }.size} 次 ASR · 去重模型 ${mib(vm.chosenBytes)}", style = MaterialTheme.typography.titleSmall)
                        Text("APK ${mib(vm.apkBytes)} · 已保存模型 ${mib(vm.storedModelBytes)}", style = MaterialTheme.typography.bodySmall)
                        Text("模型不在 APK 中；首次联网准备，之后离线使用。下载中请保持页面打开，也可导入电脑准备的模型 ZIP。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::prepare, enabled = idle && vm.selected.isNotEmpty()) { Text("准备选中模型") }
                            OutlinedButton(onClick = { showModels = !showModels }) { Text(if (showModels) "收起清单" else "选择方案") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.selectFocus(); showOtherModels = false }, enabled = idle) { Text("仅选本轮三组") }
                            TextButton(onClick = { showOtherModels = !showOtherModels; showModels = true }) { Text(if (showOtherModels) "收起其它候选" else "其它候选") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pack.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = idle) { Text("导入模型 ZIP") }
                            TextButton(onClick = vm::stopWork, enabled = vm.busy) { Text("停止") }
                        }
                    }
                }
            }
            if (showModels) items(vm.catalog.schemes.filter { showOtherModels || it.focused || it.id in vm.selected }, key = { "scheme-${it.id}" }) { scheme ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scheme.id in vm.selected, onCheckedChange = { vm.select(scheme.id) }, enabled = idle)
                    Column(Modifier.weight(1f)) {
                        Text(scheme.title, style = MaterialTheme.typography.titleSmall)
                        Text("${mib(vm.catalog.bytes(scheme))} · ${if (scheme.id in vm.ready) "已准备" else "待准备"} · ${scheme.note}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                if (vm.chosen.any { it.engine == "whisper" || it.engine == "sensevoice" }) {
                Text("识别语言（Whisper / SenseVoice）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zh" to "中文", "auto" to "自动", "en" to "英文").forEach { (code, title) ->
                        FilterChip(selected = vm.language == code, onClick = { vm.language = code }, enabled = idle, label = { Text(title) })
                    }
                }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.rotateOrder, onCheckedChange = { vm.rotateOrder = it }, enabled = idle)
                    Text("重跑时轮换首个模型，减小固定顺序偏差", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = vm.reference, onValueChange = { vm.reference = it }, label = { Text("参考文本（选填，随结果导出）") }, enabled = idle, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { wav.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = idle, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("导入 WAV") }
                    OutlinedButton(onClick = vm::replay, enabled = vm.canReplay, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("重跑录音") }
                    OutlinedButton(onClick = { export.launch("audio-lab-${System.currentTimeMillis()}.zip") }, enabled = idle && vm.rows.any { it.status == "完成" || it.status == "标点失败" }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("导出") }
                }
                if (vm.audioSeconds > 0) Text("本次录音：%.2f 秒 · 16 kHz / 单声道 / PCM16".format(vm.audioSeconds), style = MaterialTheme.typography.bodySmall)
            }
            items(vm.rows, key = { "result-${it.id}" }) { row ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text("${row.status} · 模型 ${mib(row.modelBytes)}", style = MaterialTheme.typography.labelMedium)
                        if (row.status == "运行中") LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (row.totalMs > 0) {
                            Text("${if (row.asrReused) "等效总计" else "总计"} %.2fs · RTF %.2f".format(row.totalMs / 1000.0, row.totalMs / 1000.0 / vm.audioSeconds), style = MaterialTheme.typography.titleSmall)
                            Text("加载 ${row.loadMs} ms / ASR ${row.decodeMs} ms / 标点 ${row.punctuationMs} ms", style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.asrReused) Text("ASR 原文与耗时复用自「${vm.catalog.schemes.firstOrNull { it.id == row.asrSourceId }?.title ?: row.asrSourceId}」。本卡额外用时 ${row.observedRowMs} ms；等效总计 = 同一次 ASR + 本次标点处理。", style = MaterialTheme.typography.bodySmall)
                        if (row.raw.isNotEmpty() || row.status == "完成") {
                            Text("ASR 原始输出${if (row.language.isNotEmpty()) " · ${row.language}" else ""}", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(row.raw.ifEmpty { "（空文本）" }) }
                            Text("最终输出", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(row.text.ifEmpty { "（空文本）" }) }
                        }
                        row.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
            item { Text("ASR 4 线程 / 标点 1 线程，按新建识别器计时。同一模型的标点方案共享 ASR；复用卡显示等效总计。流式额外补 1 秒静音。空间不含共享运行库。导出包含录音、原文、最终文本及复用关系。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
