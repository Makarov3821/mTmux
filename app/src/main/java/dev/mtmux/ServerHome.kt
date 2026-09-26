package dev.mtmux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class HomeRow(val snapshot: ServerSnapshot? = null, val refreshing: Boolean = false,
                           val progress: UiText = uiText(R.string.home_refreshing), val error: UiText? = null, val challenge: HostKeyChallenge? = null)

@Composable fun ServerHome(openSettings: Boolean = false, privacyDisplay: Boolean, onPrivacyDisplay: (Boolean) -> Unit, refreshOnEntry: Boolean, onEntryHandled: () -> Unit, onOpen: (ServerProfile, RecentTask?) -> Unit,
                          onPreferences: (Int,Float,Boolean) -> Unit,
                          appearance: AppAppearance, onAppearance: (AppAppearance) -> Unit) {
    val context = LocalContext.current
    val privacySettings = remember { PrivacyDisplay(context) }
    val store = remember { ServerProfiles(context) }; val cache = remember { ServerCache(context) }
    val organization = remember { ServerOrganization(context) }
    var folders by remember { mutableStateOf(organization.folders()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(organization.sort()) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var folderDialog by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<ServerFolder?>(null) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<UiText?>(null) }
    var deletingFolder by remember { mutableStateOf<ServerFolder?>(null) }
    var movingServer by remember { mutableStateOf<ServerProfile?>(null) }
    var organizationRevision by remember { mutableIntStateOf(0) }
    val folderCollapsed = remember { mutableStateMapOf<String, Boolean>() }
    fun folderEdit(folder: ServerFolder?) { editingFolder=folder;folderName=folder?.name.orEmpty();folderError=null;folderDialog=true }
    val pins = remember { context.getSharedPreferences("host-pins",0) }
    val settings = remember { context.getSharedPreferences("terminal-settings",0) }
    var profiles by remember { mutableStateOf(store.all()) }
    val rows = remember { mutableStateMapOf<String, HomeRow>() }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val expandedWindows = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope(); val limit = remember { Semaphore(2) }
    val clients = remember { mutableSetOf<SshClient>() }
    val jobs = remember { mutableMapOf<String, Job>() }
    val revisions = remember { mutableMapOf<String, Int>() }
    // Keep the settings page across an in-app language switch (Activity recreation).
    // After an in-app language switch the Activity is recreated; return to Settings.
    var page by remember { mutableStateOf(if (openSettings) "settings" else "home") }
    var edit by remember { mutableStateOf<ServerProfile?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<ServerProfile?>(null) }
    var trust by remember { mutableStateOf<Pair<ServerProfile,HostKeyChallenge>?>(null) }
    val currentPage by rememberUpdatedState(page)
    var message by remember { mutableStateOf<UiText?>(null) }
    var font by remember { mutableIntStateOf(settings.getInt("fontSize",14)) }
    var speed by remember { mutableFloatStateOf(settings.getFloat("scrollSensitivity",1f)) }
    var touch by remember { mutableStateOf(settings.getBoolean("remoteTouch",true)) }
    fun preferences() { settings.edit().putInt("fontSize",font).putFloat("scrollSensitivity",speed).putBoolean("remoteTouch",touch).apply(); onPreferences(font,speed,touch) }
    fun refresh(profile: ServerProfile, automatic: Boolean = false) {
        if (automatic && (!cache.shouldRefresh(profile) || organization.folderOf(profile.id)?.let { organization.collapsed(it) } == true)) return
        if (rows[profile.id]?.refreshing == true) return
        rows[profile.id] = HomeRow(rows[profile.id]?.snapshot ?: cache.read(profile), refreshing = true)
        val revision = (revisions[profile.id] ?: 0) + 1
        revisions[profile.id] = revision
        jobs[profile.id] = scope.launch {
            limit.withPermit {
                val connection = SshClient(object : PinStore { override fun get(endpoint: String) = pins.getString(endpoint,null) })
                clients.add(connection)
                try {
                    DebugLog.event(DebugLog.Event.REFRESH,profile.id.hashCode(),if(automatic) 1 else 0)
                    cache.markAttempt(profile)
                    val discovered = withContext(Dispatchers.IO) {
                        connection.connect(store.login(profile)) { progress ->
                            DebugLog.stage(progress,profile.id.hashCode())
                            scope.launch { if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true) rows[profile.id] = rows[profile.id]!!.copy(progress=progressText(progress)) }
                        }
                        scope.launch { if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true) rows[profile.id] = rows[profile.id]!!.copy(progress=uiText(R.string.home_reading_tmux)) }
                        val hostKey = pins.getString(profile.trustEndpoint(),null) ?: throw AppError(R.string.home_verify_fingerprint)
                        val collected = mutableListOf<TaskProbe.Snapshot>()
                        val snapshots = connection.discoverTaskSnapshots(profile.path) { batch ->
                            DebugLog.event(DebugLog.Event.REFRESH_BATCH,profile.id.hashCode(),batch.size)
                            collected.addAll(batch)
                            val tasks = collected.toList().recentTasks(profile, hostKey)
                            val states = collected.associate { it.binding.identity to it.state }
                            scope.launch {
                                if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true &&
                                    store.all().firstOrNull { it.id == profile.id } == profile) {
                                    val row = rows[profile.id]!!
                                    val pending = row.snapshot?.tasks.orEmpty().filter { old -> tasks.none { it.identity == old.identity } }
                                    rows[profile.id] = row.copy(snapshot = ServerSnapshot(tasks + pending, System.currentTimeMillis(), states),
                                        progress = UiText.Plural(R.plurals.home_read_states, tasks.size))
                                }
                            }
                        }
                        snapshots.recentTasks(profile, hostKey) to snapshots.associate { it.binding.identity to it.state }
                    }
                    ensureActive()
                    if (revisions[profile.id] != revision) return@withPermit
                    if (store.all().firstOrNull { it.id == profile.id } == profile) {
                        DebugLog.event(DebugLog.Event.REFRESH_DONE,profile.id.hashCode(),discovered.first.size)
                        rows[profile.id] = HomeRow(cache.write(profile,discovered.first,discovered.second))
                    } else { rows.remove(profile.id) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (revisions[profile.id] != revision) return@withPermit
                    DebugLog.event(DebugLog.Event.REFRESH_FAILED,profile.id.hashCode(),error=error)
                    val challenge = (error as? HostKeyRejected)?.challenge
                    rows[profile.id] = HomeRow(runCatching {cache.clearTaskStates(profile)}.getOrNull() ?: rows[profile.id]?.snapshot, error = when {
                        challenge != null -> uiText(if (challenge.changed) R.string.home_host_key_changed else R.string.home_needs_fingerprint)
                        else -> errorText(error)
                    }, challenge = challenge)
                } finally { clients.remove(connection); withContext(NonCancellable + Dispatchers.IO) { connection.close() } }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { clients.toList().forEach { it.close() } } }
    fun refreshVisibleFolders() { store.all().forEach { refresh(it, automatic = true) } }
    val refreshOnResume by rememberUpdatedState({ if (currentPage == "home") refreshVisibleFolders() })
    LaunchedEffect(Unit) {
        if (refreshOnEntry) refreshVisibleFolders()
        onEntryHandled()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val statusClock by produceState(System.currentTimeMillis(),page,lifecycle) {
        if(page=="home") lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { value=System.currentTimeMillis(); delay(60_000) }
        }
    }
    DisposableEffect(lifecycle) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopped = true; jobs.values.toList().forEach { it.cancel() }; clients.toList().forEach { it.close() }; rows.keys.toList().forEach { id -> rows[id]?.let { rows[id] = it.copy(refreshing=false) } } }
            if (event == Lifecycle.Event.ON_RESUME && stopped) { stopped = false; refreshOnResume() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(page) {
        if (page == "home") { profiles = store.all(); profiles.forEach { collapsed.putIfAbsent(it.id,cache.collapsed(it.id)) } }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            "edit" -> ServerEditor(store, edit, onClose = { page = "home" }, onSaved = {
                profiles = store.all(); rows.clear(); message = null; page = "home"
            })
            "settings" -> {
                BackHandler { page = "home" }
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { page = "home" }) { Text(stringResource(R.string.common_back)) }; Text(stringResource(R.string.settings_title),style = MaterialTheme.typography.titleLarge) }
                    Text(stringResource(R.string.settings_appearance),color=MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AppAppearance.entries.forEach { option -> FilterChip(selected=appearance==option,onClick={onAppearance(option)},label={Text(stringResource(option.label))},modifier=Modifier.testTag("theme-${option.name}")) }
                    }
                    LanguageSettings()
                    Text(stringResource(R.string.settings_privacy),color=MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_privacy_display),Modifier.weight(1f))
                        Switch(checked=privacyDisplay,onCheckedChange=onPrivacyDisplay,modifier=Modifier.testTag("privacy-display"))
                    }
                    Text(stringResource(R.string.settings_privacy_help),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_terminal_display),color=MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.settings_font_size),Modifier.weight(1f)); TextButton(onClick={font=(font-1).coerceAtLeast(8);preferences()}){Text("−")}; Text("$font"); TextButton(onClick={font=(font+1).coerceAtMost(28);preferences()}){Text("＋")} }
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_terminal_input),color=MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.settings_touch),Modifier.weight(1f)); Switch(touch,{touch=it;preferences()}) }
                    Text(stringResource(R.string.scroll_speed,"%.1f".format(java.util.Locale.ROOT,speed)))
                    Slider(speed,{speed=it;preferences()},valueRange=0.5f..2f,steps=2,modifier=Modifier.testTag("settings-speed"))
                    Text(stringResource(R.string.settings_touch_help),style=MaterialTheme.typography.bodySmall)
                    DebugLogSettings()
                }
            }
            else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
                val settingsLabel=stringResource(R.string.settings_title); val addServerLabel=stringResource(R.string.home_add_server)
                val addFolderLabel=stringResource(R.string.home_new_folder); val refreshFolderLabel=stringResource(R.string.home_refresh_folder)
                val openSshLabel=stringResource(R.string.home_open_ssh); val refreshServerLabel=stringResource(R.string.home_refresh_server)
                val moreLabel=stringResource(R.string.home_more_actions); val ungroupedLabel=stringResource(R.string.home_ungrouped)
                Row(Modifier.fillMaxWidth().height(68.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_title),Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                    TextButton(onClick={page="settings"},modifier=Modifier.semantics{contentDescription=settingsLabel}.testTag("settings")){Text("⚙",style=MaterialTheme.typography.headlineSmall)}
                    TextButton(onClick={edit=null;page="edit"},modifier=Modifier.semantics{contentDescription=addServerLabel}.testTag("add-server")){Text("＋",style=MaterialTheme.typography.headlineMedium)}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(query,{query=it},placeholder={Text(stringResource(R.string.home_search),maxLines=1,overflow=TextOverflow.Ellipsis)},singleLine=true,
                        trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}){Text(stringResource(R.string.common_clear))}},
                        modifier=Modifier.weight(1f).testTag("server-search"))
                    Box {
                        TextButton(onClick={sortMenu=true},modifier=Modifier.testTag("server-sort")){Text(stringResource(R.string.home_sort))}
                        DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                            ServerSort.entries.forEach { option -> DropdownMenuItem(text={Text((if(sort==option) "✓ " else "")+stringResource(option.label))},onClick={runCatching {organization.sort(option);sort=option;sortMenu=false}.onFailure {message=uiText(R.string.home_sort_failed)}},modifier=Modifier.testTag("sort-${option.name}")) }
                        }
                    }
                    TextButton(onClick={folderEdit(null)},modifier=Modifier.semantics {contentDescription=addFolderLabel}.testTag("add-folder")){Text("▱＋")}
                }
                message?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
                if(profiles.isEmpty() && folders.isEmpty()) Column(Modifier.fillMaxWidth().padding(top=100.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.home_empty))
                    Spacer(Modifier.height(16.dp)); OutlinedButton(onClick={edit=null;page="edit"}){Text(stringResource(R.string.home_add_server))}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // Search is local and does not trigger discovery. Expanded search results
                    // never overwrite saved folder/server collapse preferences.
                    val search = query.trim()
                    val recent = store.recent()
                    val ordered = organization.ordered(profiles,sort,recent)
                    val memberships = remember(profiles,folders,organizationRevision) { profiles.associate { it.id to organization.folderOf(it.id) } }
                    val groups = folders.map { it.id to it.name } + listOf(null to ungroupedLabel)
                    var hasResults = false
                    groups.forEach { (folderId,folderTitle) ->
                        val folderMatch = search.isNotEmpty() && folderId != null && folderTitle.contains(search,ignoreCase=true)
                        val allMembers = ordered.filter { memberships[it.id] == folderId }
                        val members = allMembers.filter { profile ->
                            search.isEmpty() || folderMatch || profile.matchesSearch(search) ||
                                (rows[profile.id]?.snapshot ?: cache.read(profile))?.tasks?.any { it.matchesSearch(search) } == true
                        }
                        val showGroup = if(search.isEmpty()) folderId != null || members.isNotEmpty() else members.isNotEmpty() || folderMatch
                        if(showGroup) hasResults = true
                        if(showGroup) key(folderId ?: "ungrouped") {
                            val folderFolded = search.isEmpty() && folderId != null && (folderCollapsed[folderId] ?: organization.collapsed(folderId))
                            if(folders.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("${if(folderFolded) "›" else "⌄"} $folderTitle  · ${members.size}",Modifier.weight(1f).clickable(enabled=folderId!=null && search.isEmpty()) {
                                    folderId?.let { runCatching {organization.collapse(it,!folderFolded);folderCollapsed[it]=!folderFolded}.onFailure {message=uiText(R.string.home_collapse_failed)} }
                                }.padding(vertical=12.dp).testTag("folder-${folderId ?: "ungrouped"}"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleSmall)
                                val groupRefreshing = allMembers.any { rows[it.id]?.refreshing == true }
                                TextButton(onClick={allMembers.forEach { refresh(it) }},enabled=allMembers.isNotEmpty() && !groupRefreshing,
                                    contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=44.dp,minHeight=48.dp)
                                        .semantics {contentDescription=refreshFolderLabel}.testTag("refresh-folder-${folderId ?: "ungrouped"}")) {
                                    if(groupRefreshing) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp) else Text("⟳",style=MaterialTheme.typography.titleLarge)
                                }
                                if(folderId!=null) Box {
                                    TextButton(onClick={folderMenu=folderId},modifier=Modifier.testTag("folder-menu-$folderId")){Text("⋮")}
                                    DropdownMenu(expanded=folderMenu==folderId,onDismissRequest={folderMenu=null}) {
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_rename_folder))},onClick={folderMenu=null;folderEdit(folders.first {it.id==folderId})})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_delete_folder))},onClick={folderMenu=null;deletingFolder=folders.first {it.id==folderId}})
                                    }
                                }
                            }
                            if(!folderFolded && members.isEmpty()) Text(stringResource(R.string.home_folder_empty),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
                            if(!folderFolded) members.forEach { profile -> key(profile.id) {

                        val state = rows[profile.id] ?: HomeRow(cache.read(profile))
                        // The minute tick invalidates expired colors; a new snapshot must use NOW,
                        // not the previous tick (which predates the snapshot and made it gray for up to 60s).
                        val stateNow = remember(statusClock, state.snapshot) { System.currentTimeMillis() }
                        val folded = search.isEmpty() && (collapsed[profile.id] ?: cache.collapsed(profile.id))
                        Column(Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("profile-${profile.id}")) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable(enabled=search.isEmpty()) { collapsed[profile.id]=!folded;cache.collapse(profile.id,!folded) }, verticalAlignment=Alignment.CenterVertically) {
                                    val title = privacySettings.label(profile, privacyDisplay)
                                    Text("${if(folded) "›" else "⌄"} $title", Modifier.weight(1f,fill=false).testTag("server-title-${profile.id}"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    state.snapshot?.let { snapshot ->
                                        val time = java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                        val fullTime = java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                        val updatedLabel=stringResource(R.string.home_updated_at, fullTime)
                                        Text(time,Modifier.padding(start=6.dp).semantics { contentDescription=updatedLabel }.testTag("server-updated-${profile.id}"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                                    }
                                }
                                TextButton(onClick={onOpen(profile,null)},contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=44.dp,minHeight=48.dp).semantics{contentDescription=openSshLabel}.testTag("ssh-${profile.id}")){Text(">_")}
                                TextButton(onClick={refresh(profile)},enabled=!state.refreshing,contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=44.dp,minHeight=48.dp).semantics{contentDescription=refreshServerLabel}.testTag("refresh-${profile.id}")){
                                    if(state.refreshing) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp) else Text("⟳",style=MaterialTheme.typography.titleLarge)
                                }
                                Box {
                                    TextButton(onClick={menu=profile.id},contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=36.dp,minHeight=48.dp).semantics{contentDescription=moreLabel}.testTag("more-${profile.id}")){Text("⋮",style=MaterialTheme.typography.titleLarge)}
                                    DropdownMenu(expanded=menu==profile.id,onDismissRequest={menu=null}) {
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_edit_server))},onClick={menu=null;edit=profile;page="edit"})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_move_to_folder))},onClick={menu=null;movingServer=profile})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_delete_server))},onClick={menu=null;delete=profile})
                                    }
                                }
                            }
                            val note = when { state.refreshing -> state.progress.string(); state.error != null -> state.error.string() + if(state.snapshot!=null) stringResource(R.string.home_showing_cache) else "";state.snapshot==null -> stringResource(R.string.home_no_cache);else -> null }
                            if (note != null) Text(note,Modifier.padding(start=16.dp).clickable(enabled=state.challenge!=null){trust=profile to state.challenge!!},style=MaterialTheme.typography.labelSmall,color=if(state.error!=null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            if(!folded) {
                                val tasks = state.snapshot?.tasks.orEmpty().filter { search.isEmpty() || folderMatch || profile.matchesSearch(search) || it.matchesSearch(search) }
                                if(tasks.isEmpty() && state.snapshot!=null && !state.refreshing && state.error==null) Text(stringResource(R.string.home_no_sessions),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
                                val last = recent.firstOrNull { it.profileId==profile.id }
                                tasks.groupBy { it.binding!!.identity.split(':').slice(2..3).joinToString(":") }.forEach { (id, group) ->
                                    val title="${group.first().sessionName} / ${group.first().windowName}"
                                    val expanded=search.isNotEmpty() || expandedWindows[profile.id+id]==true
                                    TextButton(onClick={if(group.size==1) onOpen(profile,group.first()) else expandedWindows[profile.id+id]=!expanded},modifier=Modifier.fillMaxWidth().testTag("task-${profile.id}-${group.first().binding!!.pane}"),contentPadding=PaddingValues(start=24.dp,end=8.dp,top=12.dp,bottom=12.dp)) {
                                        TaskStatusDot(TaskStates.aggregate(group.map { if(state.error==null) state.snapshot?.taskState(it,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN }),"task-status-${profile.id}-${group.first().binding!!.pane}")
                                        Text(title,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface)
                                        if(group.any { it.identity==last?.identity }) Text(stringResource(R.string.home_last_used),style=MaterialTheme.typography.labelSmall)
                                        Text(if(group.size==1) "  ›" else if(expanded) "  ⌄" else "  › ${group.size}")
                                    }
                                    if(group.size>1 && expanded) group.forEach { task -> TextButton(onClick={onOpen(profile,task)},modifier=Modifier.fillMaxWidth().padding(start=36.dp).testTag("pane-${profile.id}-${task.binding!!.pane}")){TaskStatusDot(if(state.error==null) state.snapshot?.taskState(task,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN,"pane-status-${profile.id}-${task.binding!!.pane}");Text(stringResource(R.string.home_pane, task.binding!!.pane),Modifier.weight(1f));Text("›")} }
                                }
                            }
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    } }
                        }
                    }
                    if(search.isNotEmpty() && !hasResults) Text(stringResource(R.string.home_no_results),Modifier.padding(vertical=24.dp))
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if(folderDialog) AlertDialog(onDismissRequest={folderDialog=false},title={Text(stringResource(if(editingFolder==null) R.string.home_new_folder else R.string.home_rename_folder))},text={Column {
        OutlinedTextField(folderName,{folderName=it;folderError=null},label={Text(stringResource(R.string.folder_name))},singleLine=true,modifier=Modifier.testTag("folder-name"))
        folderError?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton(onClick={runCatching {organization.saveFolder(editingFolder?.id,folderName);folders=organization.folders();folderDialog=false}.onFailure {folderError=errorText(it)}},modifier=Modifier.testTag("save-folder")){Text(stringResource(R.string.common_save))}},dismissButton={TextButton(onClick={folderDialog=false}){Text(stringResource(R.string.common_cancel))}})
    deletingFolder?.let { folder -> AlertDialog(onDismissRequest={deletingFolder=null},title={Text(stringResource(R.string.folder_delete_title, folder.name))},text={Text(stringResource(R.string.folder_delete_body))},confirmButton={TextButton(onClick={runCatching {organization.deleteFolder(folder.id);folders=organization.folders();organizationRevision++;folderCollapsed.remove(folder.id);deletingFolder=null}.onFailure {message=uiText(R.string.folder_delete_failed)}},modifier=Modifier.testTag("confirm-delete-folder")){Text(stringResource(R.string.home_delete_folder))}},dismissButton={TextButton(onClick={deletingFolder=null}){Text(stringResource(R.string.common_cancel))}}) }
    movingServer?.let { profile -> AlertDialog(onDismissRequest={movingServer=null},title={Text(stringResource(R.string.move_title, privacySettings.label(profile, privacyDisplay)))},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
        (listOf(null to stringResource(R.string.home_ungrouped))+folders.map {it.id to it.name}).forEach { (id,name) ->
            TextButton(onClick={runCatching {organization.move(profile.id,id);organizationRevision++;movingServer=null}.onFailure {message=uiText(R.string.move_failed)}},modifier=Modifier.fillMaxWidth().testTag("move-folder-${id ?: "ungrouped"}")){Text(name)}
        }
    }},confirmButton={TextButton(onClick={movingServer=null}){Text(stringResource(R.string.common_cancel))}}) }
    delete?.let { profile -> AlertDialog(onDismissRequest={delete=null},title={Text(stringResource(R.string.delete_server_title, privacySettings.label(profile, privacyDisplay)))},text={Text(stringResource(R.string.delete_server_body))},confirmButton={TextButton(onClick={
        runCatching { store.delete(profile.id);revisions[profile.id]=(revisions[profile.id] ?: 0)+1;jobs.remove(profile.id)?.cancel();profiles=store.all();rows.remove(profile.id);delete=null }.onFailure { message=uiText(R.string.delete_server_failed) }
    }){Text(stringResource(R.string.common_delete))}},dismissButton={TextButton(onClick={delete=null}){Text(stringResource(R.string.common_cancel))}}) }
    trust?.let { (profile, item) -> ProtectSensitiveContent(); AlertDialog(properties=protectedDialogProperties,onDismissRequest={trust=null},title={Text(stringResource(if(item.changed) R.string.trust_changed_title else R.string.trust_verify_title))},text={Text("${item.endpoint.substringBefore("|via:")}\n${item.fingerprint}\n${stringResource(R.string.trust_verify_hint)}")},confirmButton={if(!item.changed) TextButton(onClick={pins.edit().putString(item.endpoint,item.key).apply();trust=null;refresh(profile)}){Text(stringResource(R.string.host_key_trust))}},dismissButton={TextButton(onClick={trust=null}){Text(stringResource(R.string.common_close))}}) }
}

@Composable private fun TaskStatusDot(state: TaskState, tag: String) {
    val dark=MaterialTheme.colorScheme.background.red<0.5f
    val color=when(state) {
        TaskState.ATTENTION -> if(dark) Color(0xFFFF8A80) else Color(0xFFBA1A1A)
        TaskState.RUNNING -> if(dark) Color(0xFFF2CF66) else Color(0xFF8B6500)
        TaskState.COMPLETED -> if(dark) Color(0xFF79D5A0) else Color(0xFF176B39)
        TaskState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stateLabel=stringResource(R.string.task_state_description, stringResource(state.text()))
    Box(Modifier.size(8.dp).background(color,CircleShape).semantics {contentDescription=stateLabel}.testTag(tag))
    Spacer(Modifier.width(8.dp))
}
