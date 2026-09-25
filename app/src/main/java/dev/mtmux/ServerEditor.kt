package dev.mtmux

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mtmux.core.Login
import dev.mtmux.core.Tmux
import java.util.UUID

private class AuthFields(host: String = "", port: Int = 22, user: String = "", key: Boolean = true, secret: SavedCredentials? = null, val id: String = UUID.randomUUID().toString()) {
    var host by mutableStateOf(host); var port by mutableStateOf(port.toString()); var user by mutableStateOf(user)
    var key by mutableStateOf(key); var password by mutableStateOf(secret?.password.orEmpty())
    var privateKey by mutableStateOf(secret?.privateKey); var passphrase by mutableStateOf(secret?.passphrase.orEmpty())
    var expanded by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    fun validate(): Boolean {
        error = when {
            host.trim().isEmpty() || host.any { it.isWhitespace() } -> "请填写有效的 IP 或域名"
            port.toIntOrNull() !in 1..65535 -> "端口须为 1–65535"
            user.trim().isEmpty() -> "请填写用户名"
            key && privateKey == null -> "请选择文件或粘贴私钥"
            !key && password.isEmpty() -> "请填写密码"
            else -> null
        }
        if (error != null) expanded = true
        return error == null
    }
    fun secret() = SavedCredentials(password, privateKey, passphrase)
}

@Composable private fun AuthEditor(fields: AuthFields, changed: () -> Unit, prefix: String) {
    var visible by remember { mutableStateOf(false) }
    var pasteKey by remember { mutableStateOf(false) }
    if(pasteKey) PrivateKeyPasteDialog(onDismiss={pasteKey=false},onImport={
        fields.privateKey=it;fields.key=true;fields.error=null;changed();pasteKey=false
        DebugLog.event(DebugLog.Event.KEY_PASTE)
    })
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream(); val block = ByteArray(4096)
                while (true) { val n = input.read(block); if (n < 0) break; require(out.size() + n <= 65536); out.write(block, 0, n) }
                out.toByteArray()
            }
            require(bytes.isNotEmpty()); DebugLog.event(DebugLog.Event.KEY_FILE); fields.privateKey = bytes; fields.key = true; fields.error = null; changed()
        }.onFailure { DebugLog.event(DebugLog.Event.KEY_IMPORT_FAILED); fields.error = "无法读取私钥，文件须小于 64 KiB" }
    }
    OutlinedTextField(fields.host, { fields.host = it; changed() }, label = { Text("IP / 域名") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("$prefix-host"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(fields.port, { fields.port = it; changed() }, label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f).testTag("$prefix-port"))
        OutlinedTextField(fields.user, { fields.user = it; changed() }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(2f).testTag("$prefix-user"))
    }
    Text("认证方式", style = MaterialTheme.typography.labelMedium)
    Row {
        FilterChip(selected = fields.key, onClick = { fields.key = true; changed() }, label = { Text("私钥") })
        Spacer(Modifier.width(12.dp))
        FilterChip(selected = !fields.key, onClick = { fields.key = false; changed() }, label = { Text("密码") })
    }
    if (fields.key) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(fields.privateKey==null) "尚未导入私钥" else "✓ 私钥已就绪",style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},modifier=Modifier.weight(1f).testTag("$prefix-key-file")) {Text("选择文件")}
                    OutlinedButton(onClick={pasteKey=true},modifier=Modifier.weight(1f).testTag("$prefix-key-paste")) {Text("粘贴私钥")}
                }
            }
        }
    }
    OutlinedTextField(if (fields.key) fields.passphrase else fields.password,
        { if (fields.key) fields.passphrase = it else fields.password = it; changed() },
        label = { Text(if (fields.key) "私钥口令（可选）" else "密码") }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } },
        modifier = Modifier.fillMaxWidth().testTag("$prefix-secret"))
    fields.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable fun ServerEditor(store: ServerProfiles, initial: ServerProfile?, onClose: () -> Unit, onSaved: () -> Unit) {
    ProtectSensitiveContent()
    val loaded = remember { runCatching { initial?.let { store.credentials(it) } } }
    val target = remember { AuthFields(initial?.host.orEmpty(), initial?.port ?: 22, initial?.user.orEmpty(), initial?.keyAuthentication ?: true, loaded.getOrNull()) }
    var name by remember { mutableStateOf(initial?.explicitAlias().orEmpty()) }
    var path by remember { mutableStateOf(initial?.path ?: "tmux") }
    var mouse by remember { mutableStateOf(initial?.enableTmuxMouse ?: true) }
    var advanced by remember { mutableStateOf(false) }
    var jumpsEnabled by remember { mutableStateOf(initial?.jumps?.isNotEmpty() == true) }
    val hops = remember { mutableStateListOf<AuthFields>().apply { initial?.jumps?.forEach { add(AuthFields(it.host,it.port,it.user,it.keyAuthentication,loaded.getOrNull()?.jumps?.get(it.id),it.id)) } } }
    var dirty by remember { mutableStateOf(false) }; var abandon by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "已保存凭据无法读取，请重新填写后保存" else "") }
    fun close() { if (dirty) abandon = true else onClose() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { close() }) { Text("取消") }
            Text(if (initial == null) "添加服务器" else "编辑服务器", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                val targetOk = target.validate()
                val hopsOk = if (jumpsEnabled) hops.map { it.validate() }.all { it } && hops.isNotEmpty() else true
                if (!hopsOk) { advanced = true; error = "请补全每一级跳板" }
                else if (targetOk) runCatching {
                    Tmux.version(path)
                    val profile = ServerProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim(),
                        target.host.trim(), target.port.toInt(), target.user.trim(), path, target.key, mouse,
                        if (jumpsEnabled) hops.map { JumpHost(it.id,it.host.trim(),it.port.toInt(),it.user.trim(),it.key) } else emptyList())
                    store.save(profile,SavedCredentials(target.password,target.privateKey,target.passphrase,
                        if (jumpsEnabled) hops.associate { it.id to it.secret() } else emptyMap()))
                    onSaved()
                }.onFailure { error = "保存失败，请检查 tmux 路径、凭据和设备存储" }
            }, modifier = Modifier.testTag("save-server")) { Text("保存") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it; dirty = true }, label = { Text("别名") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("profile-name"))
            AuthEditor(target, { dirty = true }, "server")
            HorizontalDivider(Modifier.padding(top = 12.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().testTag("advanced")) {
                Text("${if (advanced) "⌄" else "›"} 高级选项" + if (jumpsEnabled) " · ${hops.size} 级跳板" else "")
            }
            if (advanced) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Jump Host")
                    Switch(checked = jumpsEnabled, onCheckedChange = { jumpsEnabled = it; dirty = true; if (it && hops.isEmpty()) hops.add(AuthFields()) }, modifier = Modifier.testTag("jump-enabled"))
                }
                if (jumpsEnabled) {
                    Text("手机 → " + hops.indices.joinToString(" → ") { "跳板 ${it+1}" } + " → 目标服务器", style = MaterialTheme.typography.bodySmall)
                    hops.toList().forEachIndexed { index, hop -> key(hop.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { hop.expanded = !hop.expanded }) { Text("${if(hop.expanded) "⌄" else "›"} 跳板 ${index+1}") }
                                Spacer(Modifier.weight(1f))
                                if (index > 0) TextButton(onClick = { hops.removeAt(index); hops.add(index-1,hop); dirty = true }) { Text("上移") }
                                if (index < hops.lastIndex) TextButton(onClick = { hops.removeAt(index); hops.add(index+1,hop); dirty = true }) { Text("下移") }
                                TextButton(onClick = { hops.remove(hop); dirty = true }) { Text("删除") }
                            }
                            if (hop.expanded) AuthEditor(hop, { dirty = true }, "jump-${index+1}")
                            else Text("${hop.user}@${hop.host}:${hop.port}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    } }
                    OutlinedButton(onClick = { hops.add(AuthFields()); dirty = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加一级跳板") }
                }
                OutlinedTextField(path, { path = it; dirty = true }, label = { Text("tmux 路径") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(mouse, { mouse = it; dirty = true }); Text("连接时开启鼠标", style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (abandon) AlertDialog(properties=protectedDialogProperties,onDismissRequest = { abandon = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = onClose) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { abandon = false }) { Text("继续编辑") } })
}
