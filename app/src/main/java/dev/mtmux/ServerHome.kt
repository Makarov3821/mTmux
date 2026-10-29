package dev.mtmux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
private data class PendingHomeRefresh(val profile: ServerProfile, val automatic: Boolean, val verifyFingerprint: Boolean, val forceInterval: Boolean)

private class HomeRefresh {
    val opening = java.util.concurrent.atomic.AtomicBoolean(false)
    val finished = CompletableDeferred<Unit>()
    var key: String? = null
    var handedOff: SshClient? = null // Main-thread ownership after all probe workers have drained.
    var verifyFingerprint = false
}

@Composable fun ServerHome(warmConnections: WarmConnectionPool<SshClient>, openSettings: Boolean = false, privacyDisplay: Boolean, onPrivacyDisplay: (Boolean) -> Unit, refreshOnEntry: Boolean, refreshProfileOnEntry: String? = null, onEntryHandled: () -> Unit, onOpen: (ServerProfile, RecentTask?, SshClient?) -> Unit,
                          onPreferences: (Int,Float,Boolean) -> Unit,
                          appearance: AppAppearance, onAppearance: (AppAppearance) -> Unit, palette: AppPalette, onPalette: (AppPalette) -> Unit) {
    val context = LocalContext.current
    val privacySettings = remember { PrivacyDisplay(context) }
    val store = remember { ServerProfiles(context) }; val cache = remember { ServerCache(context) }
    val doctorCache = remember { TmuxDoctorStore(context) }
    var doctorRevision by remember { mutableIntStateOf(0) }
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pendingRefreshes = remember { linkedMapOf<String, PendingHomeRefresh>() }
    val clients = remember { mutableSetOf<SshClient>() }
    val jobs = remember { mutableMapOf<String, Job>() }
    val revisions = remember { mutableMapOf<String, Int>() }
    val refreshes = remember { mutableMapOf<String, HomeRefresh>() }
    var opening by remember { mutableStateOf<String?>(null) }
    var openingJob by remember { mutableStateOf<Job?>(null) }
    // Keep the settings page across an in-app language switch (Activity recreation).
    // After an in-app language switch the Activity is recreated; return to Settings.
    var page by remember { mutableStateOf(if (openSettings) "settings" else "home") }
    var edit by remember { mutableStateOf<ServerProfile?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<ServerProfile?>(null) }
    var trust by remember { mutableStateOf<Pair<ServerProfile,HostKeyChallenge>?>(null) }
    var creatingTmux by remember { mutableStateOf<ServerProfile?>(null) }
    var doctorInvitation by remember { mutableStateOf<ServerProfile?>(null) }
    var doctorProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var doctorForCreate by remember { mutableStateOf(false) }
    val currentPage by rememberUpdatedState(page)
    var message by remember { mutableStateOf<UiText?>(null) }
    var font by remember { mutableIntStateOf(settings.getInt("fontSize",14)) }
    var speed by remember { mutableFloatStateOf(savedScrollSpeed(settings)) }
    var touch by remember { mutableStateOf(settings.getBoolean("remoteTouch",true)) }
    fun preferences() { settings.edit().putInt("fontSize",font).putFloat("scrollSensitivity",speed).putBoolean("remoteTouch",touch).apply(); onPreferences(font,speed,touch) }
    fun refresh(profile: ServerProfile, automatic: Boolean = false, verifyFingerprint: Boolean = false, forceInterval: Boolean = false) {
        if (automatic && (doctorInvitation?.id == profile.id || doctorProfile?.id == profile.id)) return
        if (automatic && doctorCache.blocked(profile.id,store.connectionRevision(profile),System.currentTimeMillis())) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            val previous = pendingRefreshes[profile.id]?.takeIf { it.profile == profile }
            pendingRefreshes[profile.id] = PendingHomeRefresh(profile,
                automatic && (previous?.automatic ?: true), verifyFingerprint || previous?.verifyFingerprint == true,
                forceInterval || previous?.forceInterval == true)
            return
        }
        if (automatic && !AutomaticRefreshPolicy.allowsRefresh(
                folderCollapsed = organization.folderOf(profile.id)?.let { organization.collapsed(it) } == true,
                intervalElapsed = cache.shouldRefresh(profile), forceInterval = forceInterval)) return
        if (rows[profile.id]?.refreshing == true) {
            if (verifyFingerprint) refreshes[profile.id]?.verifyFingerprint = true
            return
        }
        rows[profile.id] = HomeRow(rows[profile.id]?.snapshot ?: cache.read(profile), refreshing = true)
        val revision = (revisions[profile.id] ?: 0) + 1
        revisions[profile.id] = revision
        val traceAttempt = DebugLog.nextAttempt()
        val refresh = HomeRefresh()
        refresh.verifyFingerprint = verifyFingerprint
        refreshes[profile.id] = refresh
        jobs[profile.id] = scope.launch {
            try {
                limit.withPermit {
                    val connectionKey = store.connectionRevision(profile)
                    refresh.key = connectionKey
                    val pooled = warmConnections.take(connectionKey)
                    val connection = pooled ?: SshClient(object : PinStore { override fun get(endpoint: String) = pins.getString(endpoint,null) })
                    connection.observe { DebugLog.connectionTrace(DebugLog.Owner.REFRESH, profile.id.hashCode(), it, traceAttempt) }
                    var retain = false
                    var partial: ServerSnapshot? = rows[profile.id]?.snapshot
                    clients.add(connection)
                    try {
                        DebugLog.event(DebugLog.Event.REFRESH,profile.id.hashCode(),if(automatic) 1 else 0)
                        cache.markAttempt(profile)
                        val discovered = withContext(Dispatchers.IO) {
                            if (pooled != null) connection.reportReuse()
                            else connection.connect(store.login(profile)) { progress ->
                                DebugLog.stage(progress,profile.id.hashCode())
                                scope.launch { if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true) rows[profile.id] = rows[profile.id]!!.copy(progress=progressText(progress)) }
                            }
                            scope.launch { if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true) rows[profile.id] = rows[profile.id]!!.copy(progress=uiText(R.string.home_reading_tmux)) }
                            val hostKey = pins.getString(profile.trustEndpoint(),null) ?: throw AppError(R.string.home_verify_fingerprint)
                            val collected = linkedMapOf<String, TaskProbe.Snapshot>()
                            fun publish(progress: UiText) {
                                val tasks = collected.values.toList().recentTasks(profile, hostKey)
                                // Only completed reads replace colors; listing never renews their age.
                                val snapshot = mergeTaskSnapshot(partial, tasks, emptyMap())
                                partial = snapshot
                                scope.launch {
                                    if (revisions[profile.id] == revision && rows[profile.id]?.refreshing == true &&
                                        store.all().firstOrNull { it.id == profile.id } == profile) {
                                        rows[profile.id] = rows[profile.id]!!.copy(snapshot = snapshot, progress = progress)
                                    }
                                }
                            }
                            var completed = 0
                            val snapshots = connection.discoverTaskSnapshots(profile.path, stopRequested = { refresh.opening.get() }, onListed = { listed ->
                                DebugLog.event(DebugLog.Event.REFRESH_LIST, profile.id.hashCode(), listed.size)
                                listed.forEach { collected[it.binding.identity] = it }
                                publish(uiText(R.string.home_reading_tmux))
                            }) { batch ->
                                DebugLog.event(DebugLog.Event.REFRESH_BATCH,profile.id.hashCode(),batch.size)
                                batch.forEach { snapshot ->
                                    collected.entries.removeAll { it.key != snapshot.binding.identity && it.value.binding.target == snapshot.binding.target }
                                    collected[snapshot.binding.identity] = snapshot
                                }
                                partial = mergeTaskSnapshot(partial, collected.values.toList().recentTasks(profile, hostKey),
                                    batch.associate { it.binding.identity to it.state })
                                completed += batch.size
                                publish(UiText.Plural(R.plurals.home_read_states, completed))
                            }
                            mergeTaskSnapshot(partial, snapshots.recentTasks(profile, hostKey), emptyMap())
                        }
                        ensureActive()
                        if (revisions[profile.id] != revision) return@withPermit
                        if (store.all().firstOrNull { it.id == profile.id } == profile) {
                            DebugLog.event(DebugLog.Event.REFRESH_DONE,profile.id.hashCode(),discovered.tasks.size)
                            rows[profile.id] = HomeRow(cache.write(profile,discovered))
                            if(store.connectionRevision(profile)==connectionKey) {
                                doctorCache.save(profile.id,connectionKey,TmuxDoctorReport(mapOf("path.status" to "ok")),false)
                                doctorRevision++
                            }
                            retain = store.connectionRevision(profile) == connectionKey
                        } else { rows.remove(profile.id) }
                    } catch (error: Exception) {
                        if (error is CancellationException || (error is CodedError && error.code == ErrorCode.CANCELLED)) {
                            DebugLog.event(DebugLog.Event.REFRESH_CANCELLED, profile.id.hashCode())
                            throw CancellationException("refresh cancelled", error)
                        }
                        if (revisions[profile.id] != revision) return@withPermit
                        DebugLog.event(DebugLog.Event.REFRESH_FAILED,profile.id.hashCode(),error=error)
                        if(error is CodedError && error.code==ErrorCode.TMUX_UNAVAILABLE && store.connectionRevision(profile)==connectionKey) {
                            doctorCache.save(profile.id,connectionKey,TmuxDoctorReport(mapOf("path.status" to "failed")),false)
                            doctorRevision++
                        }
                        val challenge = (error as? HostKeyRejected)?.challenge
                        if (refresh.verifyFingerprint && challenge != null && currentPage == "home") trust = profile to challenge
                        rows[profile.id] = HomeRow(runCatching {cache.clearTaskStates(profile)}.getOrNull() ?: rows[profile.id]?.snapshot, error = when {
                            challenge != null -> uiText(if (challenge.changed) R.string.home_host_key_changed else R.string.home_needs_fingerprint)
                            else -> errorText(error)
                        }, challenge = challenge)
                    } finally {
                        val active = isActive && revisions[profile.id] == revision
                        withContext(NonCancellable) {
                            val transfer = retain && active && refresh.opening.get() && connection.isReusable()
                            if (transfer) {
                                // No suspension between deciding ownership and publishing the handoff.
                                refresh.handedOff = connection
                            } else {
                                withContext(Dispatchers.IO) {
                                    if (retain && active) warmConnections.put(connectionKey, connection)
                                    else connection.close()
                                }
                            }
                            clients.remove(connection)
                        }
                    }
                }
            } finally {
                refresh.finished.complete(Unit)
                if (!refresh.opening.get() && refreshes[profile.id] === refresh) refreshes.remove(profile.id)
            }
        }
    }
    fun open(profile: ServerProfile, task: RecentTask?) {
        if (opening != null) return
        val refresh = refreshes[profile.id]
        if (refresh == null) { onOpen(profile, task, null); return }
        opening = profile.id
        refresh.opening.set(true)
        DebugLog.event(DebugLog.Event.REFRESH_HANDOFF_REQUEST, profile.id.hashCode())
        openingJob = scope.launch {
            try {
                refresh.finished.await()
                val connection = refresh.handedOff
                if (store.all().firstOrNull { it.id == profile.id } != profile ||
                    store.connectionRevision(profile) != refresh.key) {
                    message = uiText(R.string.err_profile_changed)
                    return@launch
                }
                DebugLog.event(DebugLog.Event.REFRESH_HANDOFF, profile.id.hashCode(), if (connection == null) 0 else 1)
                refresh.handedOff = null
                onOpen(profile, task, connection)
            } finally {
                refresh.opening.set(false)
                refresh.handedOff?.close(); refresh.handedOff = null
                if (refresh.finished.isCompleted && refreshes[profile.id] === refresh) refreshes.remove(profile.id)
                opening = null
            }
        }
    }
    BackHandler(enabled = opening != null) { openingJob?.cancel() }
    DisposableEffect(Unit) { onDispose {
        clients.toList().forEach { it.cancel() }
        refreshes.values.forEach { it.handedOff?.close(); it.handedOff = null }
    } }
    fun refreshVisibleFolders(returnedProfileId: String? = null) {
        store.all().forEach { refresh(it, automatic = true, forceInterval = it.id == returnedProfileId) }
    }
    val refreshOnResume by rememberUpdatedState({ if (currentPage == "home") {
        refreshVisibleFolders()
        val pending = pendingRefreshes.values.toList()
        pendingRefreshes.clear()
        val current = store.all()
        pending.filter { it.profile in current }.forEach { refresh(it.profile,it.automatic,it.verifyFingerprint,it.forceInterval) }
    } })
    LaunchedEffect(Unit) {
        if (refreshOnEntry) {
            refreshVisibleFolders(refreshProfileOnEntry)
        }
        onEntryHandled()
    }
    val statusClock by produceState(System.currentTimeMillis(),page,lifecycle) {
        if(page=="home") lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { value=System.currentTimeMillis(); delay(60_000) }
        }
    }
    // Expiry changes only local presentation; it never launches background SSH diagnostics.
    val doctorClock by produceState(System.currentTimeMillis(),doctorRevision,profiles,page,lifecycle) {
        if(page=="home") lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                val now=System.currentTimeMillis();value=now
                val next=profiles.map {doctorCache.expiresAt(it.id)}.filter {it>now}.minOrNull()
                delay((next?.minus(now) ?: 60_000L).coerceAtLeast(1))
            }
        }
    }
    fun requestNewTmux(profile: ServerProfile) {
        when(doctorCache.availability(profile.id,store.connectionRevision(profile),System.currentTimeMillis())) {
            TmuxAvailability.UNAVAILABLE -> message=uiText(R.string.doctor_blocked_help)
            TmuxAvailability.AVAILABLE -> creatingTmux=profile
            TmuxAvailability.UNKNOWN -> {doctorForCreate=true;doctorProfile=profile}
        }
    }
    DisposableEffect(lifecycle) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopped = true; openingJob?.cancel(); jobs.values.toList().forEach { it.cancel() }; clients.toList().forEach { it.cancel() }; refreshes.values.forEach { it.handedOff?.close(); it.handedOff = null }; rows.keys.toList().forEach { id -> rows[id]?.let { rows[id] = it.copy(refreshing=false) } } }
            if (event == Lifecycle.Event.ON_RESUME && (stopped || pendingRefreshes.isNotEmpty())) { stopped = false; refreshOnResume() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(page) {
        if (page == "home") { profiles = store.all(); profiles.forEach { collapsed.putIfAbsent(it.id,cache.collapsed(it.id)) } }
        else openingJob?.cancel()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            "edit" -> ServerEditor(store, edit, warmConnections, onClose = { page = "home" }, onSaved = { saved, outcome ->
                val newlyAdded=edit==null
                jobs.remove(saved.id)?.cancel()
                revisions[saved.id] = (revisions[saved.id] ?: 0) + 1
                profiles = store.all(); organizationRevision++; rows.remove(saved.id); message = null; page = "home"
                when (outcome) {
                    ServerSaveOutcome.VERIFIED -> if(newlyAdded) doctorInvitation=saved else refresh(saved,automatic=true)
                    ServerSaveOutcome.LOCAL -> refresh(saved, automatic = true)
                    ServerSaveOutcome.UNVERIFIED -> {
                        val snapshot = runCatching { cache.clearTaskStates(saved) }.getOrNull() ?: cache.read(saved)
                        rows[saved.id] = HomeRow(snapshot, error = uiText(R.string.home_saved_unverified))
                        // Respect the user's offline save: retry only through an explicit action
                        // or the normal later refresh policy, not immediately after closing the editor.
                        runCatching { cache.markAttempt(saved) }
                    }
                }
            })
            "settings" -> {
                BackHandler { page = "home" }
                AppSettings(onBack={page="home"},pool=warmConnections,appearance=appearance,onAppearance=onAppearance,
                    palette=palette,onPalette=onPalette,privacy=privacyDisplay,onPrivacy=onPrivacyDisplay,
                    font=font,onFont={font=it;preferences()},speed=speed,onSpeed={speed=it;preferences()},
                    touch=touch,onTouch={touch=it;preferences()})
            }
            else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
                val settingsLabel=stringResource(R.string.settings_title); val addServerLabel=stringResource(R.string.home_add_server)
                val addFolderLabel=stringResource(R.string.home_new_folder); val refreshFolderLabel=stringResource(R.string.home_refresh_folder)
                val openSshLabel=stringResource(R.string.home_open_ssh); val refreshServerLabel=stringResource(R.string.home_refresh_server)
                val moreLabel=stringResource(R.string.home_more_actions); val ungroupedLabel=stringResource(R.string.home_ungrouped)
                Row(Modifier.fillMaxWidth().height(68.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_title),Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                    AppIconButton(UiSymbol.SETTINGS,settingsLabel,{page="settings"},Modifier.testTag("settings"))
                    AppIconButton(UiSymbol.PLUS,addServerLabel,{edit=null;page="edit"},Modifier.testTag("add-server"),prominent=true)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(query,{query=it},shape=MaterialTheme.shapes.medium,
                        leadingIcon={AppIcon(UiSymbol.SEARCH)},textStyle=MaterialTheme.typography.bodyMedium,placeholder={Text(stringResource(R.string.home_search),maxLines=1,overflow=TextOverflow.Ellipsis)},singleLine=true,
                        trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}){Text(stringResource(R.string.common_clear))}},
                        modifier=Modifier.weight(1f).testTag("server-search"))
                    Box {
                        AppIconButton(UiSymbol.SORT,stringResource(R.string.home_sort),{sortMenu=true},Modifier.testTag("server-sort"))
                        DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                            ServerSort.entries.forEach { option -> DropdownMenuItem(text={Text((if(sort==option) "✓ " else "")+stringResource(option.label))},onClick={runCatching {organization.sort(option);sort=option;sortMenu=false}.onFailure {message=uiText(R.string.home_sort_failed)}},modifier=Modifier.testTag("sort-${option.name}")) }
                        }
                    }
                    AppIconButton(UiSymbol.FOLDER,addFolderLabel,{folderEdit(null)},Modifier.testTag("add-folder"))
                }
                message?.let { Text(it.string(),color=MaterialTheme.colorScheme.error) }
                if(profiles.isEmpty() && folders.isEmpty()) BentoCard(Modifier.padding(top=36.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        AppIcon(UiSymbol.TERMINAL,modifier=Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.home_empty),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.home_empty_detail),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick={edit=null;page="edit"}) { AppIcon(UiSymbol.PLUS); Spacer(Modifier.width(6.dp));Text(stringResource(R.string.home_add_server)) }
                    }
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
                                    if(groupRefreshing) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp) else AppIcon(UiSymbol.REFRESH)
                                }
                                if(folderId!=null) Box {
                                    AppIconButton(UiSymbol.MORE,moreLabel,{folderMenu=folderId},Modifier.testTag("folder-menu-$folderId"))
                                    DropdownMenu(expanded=folderMenu==folderId,onDismissRequest={folderMenu=null}) {
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_rename_folder))},onClick={folderMenu=null;folderEdit(folders.first {it.id==folderId})})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_delete_folder))},onClick={folderMenu=null;deletingFolder=folders.first {it.id==folderId}})
                                    }
                                }
                            }
                            if(!folderFolded && members.isEmpty()) Text(stringResource(R.string.home_folder_empty),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
                            if(!folderFolded) members.forEach { profile -> key(profile.id) {

                        val state = rows[profile.id] ?: HomeRow(cache.read(profile))
                        val tmuxBlocked=doctorCache.blocked(profile.id,store.connectionRevision(profile),doctorClock)
                        // The minute tick invalidates expired colors; a new snapshot must use NOW,
                        // not the previous tick (which predates the snapshot and made it gray for up to 60s).
                        val stateNow = remember(statusClock, state.snapshot) { System.currentTimeMillis() }
                        val folded = search.isEmpty() && (collapsed[profile.id] ?: cache.collapsed(profile.id))
                        Surface(Modifier.fillMaxWidth().padding(vertical=6.dp).testTag("profile-${profile.id}"),
                            shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,
                            border=BorderStroke(1.dp,if(state.error!=null) MaterialTheme.colorScheme.error.copy(alpha=.35f) else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=search.isEmpty()) { collapsed[profile.id]=!folded;cache.collapse(profile.id,!folded) }, verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                    AppIcon(if(folded) UiSymbol.NEXT else UiSymbol.DOWN,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        val title = privacySettings.label(profile, privacyDisplay)
                                        Text(title,Modifier.testTag("server-title-${profile.id}"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        state.snapshot?.let { snapshot ->
                                            val time=java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                            val fullTime=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                            val updatedLabel=stringResource(R.string.home_updated_at,fullTime)
                                            Text(time,Modifier.semantics {contentDescription=updatedLabel}.testTag("server-updated-${profile.id}"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                AppIconButton(UiSymbol.TERMINAL,openSshLabel,{open(profile,null)},Modifier.testTag("ssh-${profile.id}"))
                                AppIconButton(UiSymbol.PLUS,stringResource(R.string.home_new_tmux),{requestNewTmux(profile)},Modifier.testTag("new-tmux-${profile.id}"),enabled=!tmuxBlocked)
                                TextButton(onClick={refresh(profile)},enabled=!state.refreshing,contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=44.dp,minHeight=48.dp).semantics{contentDescription=refreshServerLabel}.testTag("refresh-${profile.id}")){
                                    if(state.refreshing) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp) else AppIcon(UiSymbol.REFRESH)
                                }
                                Box {
                                    AppIconButton(UiSymbol.MORE,moreLabel,{menu=profile.id},Modifier.testTag("more-${profile.id}"))
                                    DropdownMenu(expanded=menu==profile.id,onDismissRequest={menu=null}) {
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_new_tmux))},enabled=!tmuxBlocked,onClick={menu=null;requestNewTmux(profile)})
                                        DropdownMenuItem(text={Text(stringResource(R.string.doctor_title))},onClick={menu=null;doctorForCreate=false;doctorProfile=profile},modifier=Modifier.testTag("doctor-${profile.id}"))
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_verify_host))},onClick={menu=null;state.challenge?.let { trust=profile to it } ?: refresh(profile,verifyFingerprint=true)},modifier=Modifier.testTag("verify-host-${profile.id}"))
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_edit_server))},onClick={menu=null;edit=profile;page="edit"})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_move_to_folder))},onClick={menu=null;movingServer=profile})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_delete_server))},onClick={menu=null;delete=profile})
                                    }
                                }
                            }
                            if(tmuxBlocked) Text(stringResource(R.string.doctor_unavailable),Modifier.padding(vertical=4.dp).clickable {doctorForCreate=false;doctorProfile=profile}.testTag("tmux-unavailable-${profile.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                            val note = when { opening == profile.id -> stringResource(R.string.home_opening_task); state.refreshing -> state.progress.string(); state.error != null -> state.error.string() + if(state.snapshot!=null) stringResource(R.string.home_showing_cache) else "";state.snapshot==null -> stringResource(R.string.home_no_cache);else -> null }
                            if (opening == profile.id) TextButton(onClick = { openingJob?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
                            if (note != null) Surface(Modifier.fillMaxWidth().padding(vertical=6.dp).clickable(enabled=state.challenge!=null){trust=profile to state.challenge!!},
                                shape=MaterialTheme.shapes.small,color=if(state.error!=null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                Text(note,Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=if(state.error!=null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(!folded) {
                                val tasks = state.snapshot?.tasks.orEmpty().filter { search.isEmpty() || folderMatch || profile.matchesSearch(search) || it.matchesSearch(search) }
                                if(tasks.isEmpty() && state.snapshot!=null && !state.refreshing && state.error==null) Text(stringResource(R.string.home_no_sessions),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
                                val last = recent.firstOrNull { it.profileId==profile.id }
                                tasks.groupBy { it.binding!!.identity.split(':').slice(2..3).joinToString(":") }.forEach { (id, group) ->
                                    val title="${group.first().sessionName} / ${group.first().windowName}"
                                    val expanded=search.isNotEmpty() || expandedWindows[profile.id+id]==true
                                    TextButton(onClick={if(group.size==1) open(profile,group.first()) else expandedWindows[profile.id+id]=!expanded},shape=MaterialTheme.shapes.medium,colors=ButtonDefaults.textButtonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("task-${profile.id}-${group.first().binding!!.pane}"),contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp)) {
                                        TaskStatusDot(TaskStates.aggregate(group.map { if(state.error==null) state.snapshot?.taskState(it,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN }),"task-status-${profile.id}-${group.first().binding!!.pane}")
                                        Text(title,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        if(group.any { it.identity==last?.identity }) Surface(shape=MaterialTheme.shapes.extraSmall,color=MaterialTheme.colorScheme.primaryContainer) {
                                            Text(stringResource(R.string.home_last_used),Modifier.padding(horizontal=6.dp,vertical=3.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                        }
                                        if(group.size>1) Text(" ${group.size}",style=MaterialTheme.typography.labelMedium); AppIcon(if(group.size>1 && expanded) UiSymbol.DOWN else UiSymbol.NEXT,modifier=Modifier.size(16.dp))
                                    }
                                    val paneGuide=MaterialTheme.colorScheme.outlineVariant
                                    if(group.size>1 && expanded) Column(Modifier.padding(start=12.dp).drawBehind { drawLine(paneGuide,Offset(0f,0f),Offset(0f,size.height),1.dp.toPx()) }) { group.forEach { task -> TextButton(onClick={open(profile,task)},modifier=Modifier.fillMaxWidth().padding(start=8.dp).testTag("pane-${profile.id}-${task.binding!!.pane}")){TaskStatusDot(if(state.error==null) state.snapshot?.taskState(task,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN,"pane-status-${profile.id}-${task.binding!!.pane}");Text(stringResource(R.string.home_pane, task.binding!!.pane),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);AppIcon(UiSymbol.NEXT,modifier=Modifier.size(16.dp))} } }
                                }
                            }
                        }
                        }
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
    doctorInvitation?.let { profile -> TmuxDoctorInvitation(
        onRun={doctorInvitation=null;doctorForCreate=false;doctorProfile=profile},
        onSkip={doctorInvitation=null;refresh(profile,automatic=true)}) }
    doctorProfile?.let { profile -> key(profile.id) {TmuxDoctorDialog(profile,warmConnections,doctorForCreate,
        remoteTouch=touch,onEnableTouch={touch=true;preferences()},
        beforeConnect={refreshes[profile.id]?.finished?.await()},onChanged={doctorRevision++},
        onDismiss={doctorProfile=null;doctorRevision++;refresh(profile,automatic=true)},
        onCreateReady={doctorProfile=null;doctorRevision++;creatingTmux=profile})} }
    creatingTmux?.let { profile -> NewTmuxDialog(profile, warmConnections, onDismiss={mayHaveChanged -> creatingTmux=null;doctorRevision++;if(mayHaveChanged) refresh(profile)},
        onTrustRequired={trust=profile to it},onCreated={task,client -> creatingTmux=null;doctorRevision++;onOpen(profile,task,client)}) }
    trust?.let { (profile, item) -> ProtectSensitiveContent(); AlertDialog(icon={AppIcon(UiSymbol.SHIELD,modifier=Modifier.size(28.dp),tint=if(item.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)},properties=protectedDialogProperties,onDismissRequest={trust=null},title={Text(stringResource(if(item.changed) R.string.trust_changed_title else R.string.trust_verify_title))},text={Text("${item.endpoint.substringBefore("|via:")}\n${item.fingerprint}\n${stringResource(R.string.trust_verify_hint)}")},confirmButton={if(!item.changed) TextButton(onClick={pins.edit().putString(item.endpoint,item.key).apply();trust=null;refresh(profile)}){Text(stringResource(R.string.host_key_trust))}},dismissButton={TextButton(onClick={trust=null}){Text(stringResource(R.string.common_close))}}) }
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
