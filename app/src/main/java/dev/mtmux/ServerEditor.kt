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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mtmux.core.Login
import dev.mtmux.core.Tmux
import dev.mtmux.core.SshClient
import dev.mtmux.core.WarmConnectionPool
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

@Composable private fun AuthEditor(fields: AuthFields, changed: () -> Unit, prefix: String, alias: @Composable () -> Unit = {}) {
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
    BentoCard(title=stringResource(R.string.editor_target)) {
        alias()
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(fields.host,{fields.host=it;changed()},label={Text(stringResource(R.string.editor_host))},singleLine=true,shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.weight(2.2f).testTag("$prefix-host"))
            OutlinedTextField(fields.port,{fields.port=it;changed()},label={Text(stringResource(R.string.editor_port))},singleLine=true,shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.weight(1f).testTag("$prefix-port"))
        }
        OutlinedTextField(fields.user,{fields.user=it;changed()},label={Text(stringResource(R.string.editor_user))},singleLine=true,shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.fillMaxWidth().testTag("$prefix-user"))
    }
    BentoCard(title=stringResource(R.string.editor_auth)) {
    SegmentedChoice(listOf(true,false),fields.key,{stringResource(if(it) R.string.editor_auth_key else R.string.editor_auth_password)},{"$prefix-auth-${if(it) "key" else "password"}"},{fields.key=it;changed()})
    if (fields.key) {
        Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ToneBadge(UiSymbol.KEY,if(fields.privateKey==null) Tone.NEUTRAL else Tone.ACCENT,36.dp,18.dp)
                    Text(stringResource(if(fields.privateKey==null) R.string.editor_key_missing else R.string.editor_key_ready),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={picker.launch(arrayOf("*/*"))},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.weight(1f).testTag("$prefix-key-file")) {AppIcon(UiSymbol.FILE,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(stringResource(R.string.editor_key_file))}
                    FilledTonalButton(onClick={pasteKey=true},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.weight(1f).testTag("$prefix-key-paste")) {AppIcon(UiSymbol.COPY,modifier=Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(stringResource(R.string.editor_key_paste))}
                }
            }
        }
    }
    OutlinedTextField(if (fields.key) fields.passphrase else fields.password,
        { if (fields.key) fields.passphrase = it else fields.password = it; changed() },
        label = { Text(stringResource(if (fields.key) R.string.editor_passphrase else R.string.editor_auth_password)) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        shape = appFieldShape, colors = appFieldColors(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.common_hide else R.string.common_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("$prefix-secret"))
    fields.error?.let { NoticeStrip(stringResource(it), Tone.DANGER, UiSymbol.ALERT_CIRCLE) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ServerEditor(store: ServerProfiles, initial: ServerProfile?, warmConnections: WarmConnectionPool<SshClient>, onClose: () -> Unit, onSaved: (ServerProfile, ServerSaveOutcome) -> Unit) {
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
    var verification by remember { mutableStateOf<ServerDraft?>(null) }
    fun close() { if (dirty) abandon = true else onClose() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIconButton(UiSymbol.CLOSE, stringResource(R.string.common_cancel), { close() }, Modifier.testTag("cancel-edit"), tone = IconTone.PLAIN, iconSize = 22.dp)
            Text(stringResource(if (initial == null) R.string.home_add_server else R.string.home_edit_server), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Button(shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp), onClick = {
                val targetOk = target.validate()
                val hopsOk = if (jumpsEnabled) hops.map { it.validate() }.all { it } && hops.isNotEmpty() else true
                if (!hopsOk) { advanced = true; error = R.string.editor_err_jumps }
                else if (targetOk) runCatching {
                    Tmux.version(path)
                    val profile = ServerProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim(),
                        target.host.trim(), target.port.toInt(), target.user.trim(), path, target.key, mouse,
                        if (jumpsEnabled) hops.map { JumpHost(it.id,it.host.trim(),it.port.toInt(),it.user.trim(),it.key) } else emptyList())
                    val credentials = SavedCredentials(target.password,target.privateKey,target.passphrase,
                        if (jumpsEnabled) hops.associate { it.id to it.secret() } else emptyMap())
                    if (needsServerVerification(initial, profile, loaded.getOrNull(), credentials)) verification = ServerDraft(profile, credentials)
                    else {
                        store.save(profile, credentials)
                        if (folderId != organization.folderOf(profile.id)) runCatching { organization.move(profile.id, folderId) }
                        onSaved(profile, ServerSaveOutcome.LOCAL)
                    }
                }.onFailure { error = R.string.editor_err_save }
            }, modifier = Modifier.testTag("save-server")) { Text(stringResource(R.string.common_save)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { NoticeStrip(stringResource(it), Tone.DANGER, UiSymbol.ALERT_CIRCLE, Modifier.padding(top = 4.dp)) }
            AuthEditor(target, {dirty=true}, "server", alias={
                OutlinedTextField(name,{name=it;dirty=true},label={Text(stringResource(R.string.editor_alias))},singleLine=true,shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.fillMaxWidth().testTag("profile-name"))
            })
            BentoCard(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { advanced = !advanced }.heightIn(min = 44.dp).testTag("advanced"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.editor_advanced) + if (jumpsEnabled) " · " + pluralStringResource(R.plurals.editor_jump_count, hops.size, hops.size) else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                AppIcon(if (advanced) UiSymbol.DOWN else UiSymbol.NEXT, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (advanced) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(UiSymbol.FOLDER, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.editor_folder), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Box {
                        TextButton(onClick = { folderMenu = true }, enabled = folders.isNotEmpty(), modifier = Modifier.testTag("editor-folder")) {
                            Text(folders.firstOrNull { it.id == folderId }?.name ?: stringResource(R.string.home_ungrouped), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppIcon(UiSymbol.NEXT, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }, shape = RoundedCornerShape(14.dp)) {
                            (listOf<ServerFolder?>(null) + folders).forEach { folder ->
                                DropdownMenuItem(text = { Text((if (folder?.id == folderId) "✓ " else "") + (folder?.name ?: stringResource(R.string.home_ungrouped))) },
                                    onClick = { folderId = folder?.id; folderMenu = false; dirty = true },
                                    modifier = Modifier.testTag("editor-folder-${folder?.id ?: "ungrouped"}"))
                            }
                        }
                    }
                }
                if (folders.isEmpty()) Text(stringResource(R.string.editor_folder_none_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(UiSymbol.ROUTE, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Jump Host", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = jumpsEnabled, onCheckedChange = { jumpsEnabled = it; dirty = true; if (it && hops.isEmpty()) hops.add(AuthFields()) }, modifier = Modifier.testTag("jump-enabled"))
                }
                if (jumpsEnabled) {
                    RouteChips((listOf(stringResource(R.string.editor_route_phone)) + hops.indices.map { stringResource(R.string.hop_jump, it+1) } + stringResource(R.string.hop_target)).map { it to RouteState.NORMAL })
                    hops.toList().forEachIndexed { index, hop -> key(hop.id) {
                        InsetPanel {
                            TextButton(onClick = { hop.expanded = !hop.expanded },modifier=Modifier.fillMaxWidth()) {
                                AppIcon(if(hop.expanded) UiSymbol.DOWN else UiSymbol.NEXT)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.hop_jump,index+1),Modifier.weight(1f))
                            }
                            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                                if (index > 0) TextButton(onClick = { hops.removeAt(index); hops.add(index-1,hop); dirty = true }) { Text(stringResource(R.string.editor_move_up)) }
                                if (index < hops.lastIndex) TextButton(onClick = { hops.removeAt(index); hops.add(index+1,hop); dirty = true }) { Text(stringResource(R.string.editor_move_down)) }
                                TextButton(onClick = { hops.remove(hop); dirty = true }) { Text(stringResource(R.string.common_delete)) }
                            }
                            if (hop.expanded) AuthEditor(hop, { dirty = true }, "jump-${index+1}")
                            else Text("${hop.user}@${hop.host}:${hop.port}", style = MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

                        }
                    } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { hops.add(AuthFields()); dirty = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.editor_add_jump), textAlign = TextAlign.Center) }
                        FilledTonalButton(onClick = { pickJump = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).testTag("jump-from-saved")) { Text(stringResource(R.string.editor_jump_from_saved), textAlign = TextAlign.Center) }
                    }
                }
                OutlinedTextField(path, { path = it; dirty = true }, label = { Text(stringResource(R.string.editor_tmux_path)) }, shape = appFieldShape, colors = appFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().testTag("server-tmux-path"))
                SettingRow(stringResource(R.string.editor_tmux_mouse), icon = UiSymbol.HAND) { Switch(mouse, { mouse = it; dirty = true }) }
            }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppIcon(UiSymbol.LOCK, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.editor_keystore_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    verification?.let { draft ->
        ServerVerification(draft, store, warmConnections, onDismiss = { verification = null }, onSaved = { profile, outcome ->
            if (folderId != (initial?.let { organization.folderOf(it.id) })) runCatching { organization.move(profile.id, folderId) }
            verification = null
            onSaved(profile, outcome)
        })
    }
    if (pickJump) {
        val privacy = remember { PrivacyDisplay(context) }
        val candidates = remember { store.all().filter { it.id != initial?.id } }
        AppSheet(onDismiss = { pickJump = false }, secure = true) {
            SheetHeader(stringResource(R.string.editor_jump_pick_title), stringResource(R.string.editor_jump_pick_help), UiSymbol.ROUTE)
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text(stringResource(R.string.editor_jump_pick_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                candidates.forEach { candidate ->
                    SheetAction(UiSymbol.SERVER, privacy.label(candidate, privacy.enabled()), {
                        if (!importJump(candidate)) error = R.string.editor_jump_pick_failed
                        pickJump = false
                    }, Modifier.testTag("jump-pick-${candidate.id}"),
                        trailing = if (candidate.jumps.isNotEmpty()) pluralStringResource(R.plurals.editor_jump_count, candidate.jumps.size, candidate.jumps.size) else null)
                }
            }
            SheetDivider()
            SheetAction(UiSymbol.CLOSE, stringResource(R.string.common_cancel), { pickJump = false })
        }
    }
    if (abandon) AppDialog(onDismiss = { abandon = false }, properties = protectedDialogProperties, title = stringResource(R.string.editor_abandon_title),
        icon = UiSymbol.ALERT, tone = Tone.WARNING,
        confirm = DialogAction(stringResource(R.string.editor_abandon), onClose, danger = true),
        dismiss = DialogAction(stringResource(R.string.editor_keep_editing), { abandon = false }))
}
