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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    /** String resource of the validation error, if any. */
    var error by mutableStateOf<Int?>(null)
    fun validate(): Boolean {
        error = when {
            host.trim().isEmpty() || host.any { it.isWhitespace() } -> R.string.editor_err_host
            port.toIntOrNull() !in 1..65535 -> R.string.editor_err_port
            user.trim().isEmpty() -> R.string.editor_err_user
            key && privateKey == null -> R.string.editor_err_key
            !key && password.isEmpty() -> R.string.editor_err_password
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
        }.onFailure { DebugLog.event(DebugLog.Event.KEY_IMPORT_FAILED); fields.error = R.string.editor_err_key_file }
    }
    OutlinedTextField(fields.host, { fields.host = it; changed() }, label = { Text(stringResource(R.string.editor_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("$prefix-host"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(fields.port, { fields.port = it; changed() }, label = { Text(stringResource(R.string.editor_port)) }, singleLine = true, modifier = Modifier.weight(1f).testTag("$prefix-port"))
        OutlinedTextField(fields.user, { fields.user = it; changed() }, label = { Text(stringResource(R.string.editor_user)) }, singleLine = true, modifier = Modifier.weight(2f).testTag("$prefix-user"))
    }
    Text(stringResource(R.string.editor_auth), style = MaterialTheme.typography.labelMedium)
    Row {
        FilterChip(selected = fields.key, onClick = { fields.key = true; changed() }, label = { Text(stringResource(R.string.editor_auth_key)) })
        Spacer(Modifier.width(12.dp))
        FilterChip(selected = !fields.key, onClick = { fields.key = false; changed() }, label = { Text(stringResource(R.string.editor_auth_password)) })
    }
    if (fields.key) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if(fields.privateKey==null) R.string.editor_key_missing else R.string.editor_key_ready),style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},modifier=Modifier.weight(1f).testTag("$prefix-key-file")) {Text(stringResource(R.string.editor_key_file))}
                    OutlinedButton(onClick={pasteKey=true},modifier=Modifier.weight(1f).testTag("$prefix-key-paste")) {Text(stringResource(R.string.editor_key_paste))}
                }
            }
        }
    }
    OutlinedTextField(if (fields.key) fields.passphrase else fields.password,
        { if (fields.key) fields.passphrase = it else fields.password = it; changed() },
        label = { Text(stringResource(if (fields.key) R.string.editor_passphrase else R.string.editor_auth_password)) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.common_hide else R.string.common_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("$prefix-secret"))
    fields.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val organization = remember { ServerOrganization(context) }
    val folders = remember { organization.folders() }
    // Presentation only: stored separately from the SSH target and credentials.
    var folderId by remember { mutableStateOf(initial?.let { organization.folderOf(it.id) }) }
    var folderMenu by remember { mutableStateOf(false) }
    var pickJump by remember { mutableStateOf(false) }
    /** Copies a saved server (and its own jump chain, in order) into this route as new hops. */
    fun importJump(source: ServerProfile): Boolean {
        val saved = runCatching { store.credentials(source) }.getOrNull() ?: return false
        val chain = source.jumps.map { j -> AuthFields(j.host, j.port, j.user, j.keyAuthentication, saved.jumps[j.id] ?: return false) } +
            AuthFields(source.host, source.port, source.user, source.keyAuthentication, SavedCredentials(saved.password, saved.privateKey, saved.passphrase))
        chain.forEach { it.expanded = false }
        // Replace the blank hop added when Jump Host was switched on.
        if (hops.size == 1 && hops[0].host.isBlank() && hops[0].user.isBlank()) hops.clear()
        hops.addAll(chain); jumpsEnabled = true; dirty = true
        return true
    }
    var error by remember { mutableStateOf(if (loaded.isFailure) R.string.editor_err_credentials_unreadable else null) }
    fun close() { if (dirty) abandon = true else onClose() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { close() }) { Text(stringResource(R.string.common_cancel)) }
            Text(stringResource(if (initial == null) R.string.home_add_server else R.string.home_edit_server), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                val targetOk = target.validate()
                val hopsOk = if (jumpsEnabled) hops.map { it.validate() }.all { it } && hops.isNotEmpty() else true
                if (!hopsOk) { advanced = true; error = R.string.editor_err_jumps }
                else if (targetOk) runCatching {
                    Tmux.version(path)
                    val profile = ServerProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim(),
                        target.host.trim(), target.port.toInt(), target.user.trim(), path, target.key, mouse,
                        if (jumpsEnabled) hops.map { JumpHost(it.id,it.host.trim(),it.port.toInt(),it.user.trim(),it.key) } else emptyList())
                    store.save(profile,SavedCredentials(target.password,target.privateKey,target.passphrase,
                        if (jumpsEnabled) hops.associate { it.id to it.secret() } else emptyMap()))
                    if (folderId != (initial?.let { organization.folderOf(it.id) })) runCatching { organization.move(profile.id, folderId) }
                    onSaved()
                }.onFailure { error = R.string.editor_err_save }
            }, modifier = Modifier.testTag("save-server")) { Text(stringResource(R.string.common_save)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it; dirty = true }, label = { Text(stringResource(R.string.editor_alias)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("profile-name"))
            AuthEditor(target, { dirty = true }, "server")
            HorizontalDivider(Modifier.padding(top = 12.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().testTag("advanced")) {
                Text("${if (advanced) "⌄" else "›"} " + stringResource(R.string.editor_advanced) + if (jumpsEnabled) " · " + pluralStringResource(R.plurals.editor_jump_count, hops.size, hops.size) else "")
            }
            if (advanced) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.editor_folder))
                    Box {
                        TextButton(onClick = { folderMenu = true }, enabled = folders.isNotEmpty(), modifier = Modifier.testTag("editor-folder")) {
                            Text((folders.firstOrNull { it.id == folderId }?.name ?: stringResource(R.string.home_ungrouped)) + " ▾")
                        }
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            (listOf<ServerFolder?>(null) + folders).forEach { folder ->
                                DropdownMenuItem(text = { Text((if (folder?.id == folderId) "✓ " else "") + (folder?.name ?: stringResource(R.string.home_ungrouped))) },
                                    onClick = { folderId = folder?.id; folderMenu = false; dirty = true },
                                    modifier = Modifier.testTag("editor-folder-${folder?.id ?: "ungrouped"}"))
                            }
                        }
                    }
                }
                if (folders.isEmpty()) Text(stringResource(R.string.editor_folder_none_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Jump Host")
                    Switch(checked = jumpsEnabled, onCheckedChange = { jumpsEnabled = it; dirty = true; if (it && hops.isEmpty()) hops.add(AuthFields()) }, modifier = Modifier.testTag("jump-enabled"))
                }
                if (jumpsEnabled) {
                    Text((listOf(stringResource(R.string.editor_route_phone)) + hops.indices.map { stringResource(R.string.hop_jump, it+1) } + stringResource(R.string.hop_target)).joinToString(" → "), style = MaterialTheme.typography.bodySmall)
                    hops.toList().forEachIndexed { index, hop -> key(hop.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { hop.expanded = !hop.expanded }) { Text("${if(hop.expanded) "⌄" else "›"} " + stringResource(R.string.hop_jump, index+1)) }
                                Spacer(Modifier.weight(1f))
                                if (index > 0) TextButton(onClick = { hops.removeAt(index); hops.add(index-1,hop); dirty = true }) { Text(stringResource(R.string.editor_move_up)) }
                                if (index < hops.lastIndex) TextButton(onClick = { hops.removeAt(index); hops.add(index+1,hop); dirty = true }) { Text(stringResource(R.string.editor_move_down)) }
                                TextButton(onClick = { hops.remove(hop); dirty = true }) { Text(stringResource(R.string.common_delete)) }
                            }
                            if (hop.expanded) AuthEditor(hop, { dirty = true }, "jump-${index+1}")
                            else Text("${hop.user}@${hop.host}:${hop.port}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { hops.add(AuthFields()); dirty = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_add_jump)) }
                        OutlinedButton(onClick = { pickJump = true }, modifier = Modifier.weight(1f).testTag("jump-from-saved")) { Text(stringResource(R.string.editor_jump_from_saved)) }
                    }
                }
                OutlinedTextField(path, { path = it; dirty = true }, label = { Text(stringResource(R.string.editor_tmux_path)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(mouse, { mouse = it; dirty = true }); Text(stringResource(R.string.editor_tmux_mouse), style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickJump) {
        val privacy = remember { PrivacyDisplay(context) }
        val candidates = remember { store.all().filter { it.id != initial?.id } }
        AlertDialog(properties = protectedDialogProperties, onDismissRequest = { pickJump = false },
            title = { Text(stringResource(R.string.editor_jump_pick_title)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.editor_jump_pick_help), style = MaterialTheme.typography.bodySmall)
                if (candidates.isEmpty()) Text(stringResource(R.string.editor_jump_pick_empty), Modifier.padding(top = 12.dp))
                candidates.forEach { candidate ->
                    TextButton(onClick = {
                        if (!importJump(candidate)) error = R.string.editor_jump_pick_failed
                        pickJump = false
                    }, modifier = Modifier.fillMaxWidth().testTag("jump-pick-${candidate.id}")) {
                        Text(privacy.label(candidate, privacy.enabled()) + if (candidate.jumps.isNotEmpty()) " · " + pluralStringResource(R.plurals.editor_jump_count, candidate.jumps.size, candidate.jumps.size) else "",
                            Modifier.fillMaxWidth())
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { pickJump = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (abandon) AlertDialog(properties=protectedDialogProperties,onDismissRequest = { abandon = false }, title = { Text(stringResource(R.string.editor_abandon_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.editor_abandon)) } }, dismissButton = { TextButton(onClick = { abandon = false }) { Text(stringResource(R.string.editor_keep_editing)) } })
}
