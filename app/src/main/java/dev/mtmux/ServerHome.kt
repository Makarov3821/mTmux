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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
            else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
                val settingsLabel=stringResource(R.string.settings_title); val addServerLabel=stringResource(R.string.home_add_server)
                val addFolderLabel=stringResource(R.string.home_new_folder); val refreshFolderLabel=stringResource(R.string.home_refresh_folder)
                val openSshLabel=stringResource(R.string.home_open_ssh); val refreshServerLabel=stringResource(R.string.home_refresh_server)
                val moreLabel=stringResource(R.string.home_more_actions); val ungroupedLabel=stringResource(R.string.home_ungrouped)
                // App bar: brand mark + name instead of a large page title.
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    BrandMark()
                    Text(stringResource(R.string.app_name),Modifier.weight(1f).testTag("home-title"),style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Bold))
                    AppIconButton(UiSymbol.SETTINGS,settingsLabel,{page="settings"},Modifier.testTag("settings"),tone=IconTone.PLAIN,iconSize=22.dp)
                    AppIconButton(UiSymbol.PLUS,addServerLabel,{edit=null;page="edit"},Modifier.testTag("add-server"),tone=IconTone.PROMINENT,size=38.dp,iconSize=20.dp)
                }
                Surface(Modifier.fillMaxWidth().padding(horizontal=4.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextField(query,{query=it},shape=RoundedCornerShape(14.dp),
                            leadingIcon={AppIcon(UiSymbol.SEARCH,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)},textStyle=MaterialTheme.typography.bodyMedium,
                            placeholder={Text(stringResource(R.string.home_search),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)},singleLine=true,
                            trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}){Text(stringResource(R.string.common_clear))}},
                            colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,
                                focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                            modifier=Modifier.weight(1f).testTag("server-search"))
                        Box {
                            AppIconButton(UiSymbol.SORT,stringResource(R.string.home_sort),{sortMenu=true},Modifier.testTag("server-sort"),tone=IconTone.PLAIN)
                            DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false},shape=RoundedCornerShape(14.dp)) {
                                ServerSort.entries.forEach { option -> DropdownMenuItem(text={Text(stringResource(option.label))},trailingIcon={if(sort==option) AppIcon(UiSymbol.CHECK,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)},onClick={runCatching {organization.sort(option);sort=option;sortMenu=false}.onFailure {message=uiText(R.string.home_sort_failed)}},modifier=Modifier.testTag("sort-${option.name}")) }
                            }
                        }
                        Box(Modifier.size(1.dp,20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        AppIconButton(UiSymbol.FOLDER_PLUS,addFolderLabel,{folderEdit(null)},Modifier.testTag("add-folder"),tone=IconTone.PLAIN)
                    }
                }
                message?.let { NoticeStrip(it.string(),Tone.DANGER,UiSymbol.ALERT_CIRCLE,Modifier.padding(start=4.dp,end=4.dp,top=8.dp)) }
                // Counts of tasks that need the user, from the same cached snapshots as the dots.
                val summaryRows = profiles.map { profile -> rows[profile.id] ?: HomeRow(cache.read(profile)) }
                // Same rule as the card dots: a fresh snapshot is stamped after the last minute tick,
                // so the clock must be re-read when any snapshot changes, or new readings count as gray.
                val summaryNow = remember(statusClock, summaryRows.map { it.snapshot }) { System.currentTimeMillis() }
                val summary = summaryRows.flatMap { row ->
                    if(row.error!=null) emptyList() else row.snapshot?.let { s -> s.tasks.map { s.taskState(it,summaryNow) } }.orEmpty() }
                val attentionCount = summary.count { it==TaskState.ATTENTION }; val runningCount = summary.count { it==TaskState.RUNNING }
                if(attentionCount+runningCount>0) Row(Modifier.padding(start=4.dp,top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(attentionCount>0) SummaryChip(stringResource(R.string.home_summary_attention,attentionCount),StatusColors.attention(),true)
                    if(runningCount>0) SummaryChip(stringResource(R.string.home_summary_running,runningCount),StatusColors.running(),false)
                }
                if(profiles.isEmpty() && folders.isEmpty()) Surface(Modifier.fillMaxWidth().padding(top=32.dp,start=4.dp,end=4.dp),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        BrandMark(56.dp)
                        Text(stringResource(R.string.home_empty),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold),textAlign=TextAlign.Center)
                        Text(stringResource(R.string.home_empty_detail),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
                        Button(onClick={edit=null;page="edit"},shape=RoundedCornerShape(14.dp),modifier=Modifier.padding(top=4.dp).height(46.dp)) { AppIcon(UiSymbol.PLUS,modifier=Modifier.size(18.dp)); Spacer(Modifier.width(6.dp));Text(stringResource(R.string.home_add_server)) }
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
                            if(folders.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled=folderId!=null && search.isEmpty()) {
                                    folderId?.let { runCatching {organization.collapse(it,!folderFolded);folderCollapsed[it]=!folderFolded}.onFailure {message=uiText(R.string.home_collapse_failed)} }
                                }.padding(horizontal=6.dp,vertical=12.dp).testTag("folder-${folderId ?: "ungrouped"}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    AppIcon(if(folderFolded) UiSymbol.NEXT else UiSymbol.DOWN,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(folderTitle,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface) {Text("${members.size}",Modifier.padding(horizontal=7.dp,vertical=1.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                }
                                val groupRefreshing = allMembers.any { rows[it.id]?.refreshing == true }
                                AppIconButton(UiSymbol.REFRESH,refreshFolderLabel,{allMembers.forEach { refresh(it) }},Modifier.testTag("refresh-folder-${folderId ?: "ungrouped"}"),
                                    enabled=allMembers.isNotEmpty() && !groupRefreshing,tone=IconTone.PLAIN,iconSize=17.dp) {
                                    if(groupRefreshing) CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp) else AppIcon(UiSymbol.REFRESH,modifier=Modifier.size(17.dp))
                                }
                                if(folderId!=null) Box {
                                    AppIconButton(UiSymbol.MORE,moreLabel,{folderMenu=folderId},Modifier.testTag("folder-menu-$folderId"),tone=IconTone.PLAIN,iconSize=17.dp)
                                    DropdownMenu(expanded=folderMenu==folderId,onDismissRequest={folderMenu=null},shape=RoundedCornerShape(14.dp)) {
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_rename_folder))},leadingIcon={AppIcon(UiSymbol.EDIT,modifier=Modifier.size(18.dp))},onClick={folderMenu=null;folderEdit(folders.first {it.id==folderId})})
                                        DropdownMenuItem(text={Text(stringResource(R.string.home_delete_folder),color=MaterialTheme.colorScheme.error)},leadingIcon={AppIcon(UiSymbol.CLOSE,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.error)},onClick={folderMenu=null;deletingFolder=folders.first {it.id==folderId}})
                                    }
                                }
                            } else Spacer(Modifier.height(8.dp))
                            if(!folderFolded && members.isEmpty()) Text(stringResource(R.string.home_folder_empty),Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(!folderFolded) members.forEach { profile -> key(profile.id) {

                        val state = rows[profile.id] ?: HomeRow(cache.read(profile))
                        val tmuxBlocked=doctorCache.blocked(profile.id,store.connectionRevision(profile),doctorClock)
                        // The minute tick invalidates expired colors; a new snapshot must use NOW,
                        // not the previous tick (which predates the snapshot and made it gray for up to 60s).
                        val stateNow = remember(statusClock, state.snapshot) { System.currentTimeMillis() }
                        val folded = search.isEmpty() && (collapsed[profile.id] ?: cache.collapsed(profile.id))
                        Surface(Modifier.fillMaxWidth().padding(vertical=5.dp).testTag("profile-${profile.id}"),
                            shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,
                            border=if(menu==profile.id) BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null) {
                        Box {
                        if(state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),trackColor=Color.Transparent)
                        Column {
                            Row(Modifier.padding(start=18.dp,end=2.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=search.isEmpty()) { collapsed[profile.id]=!folded;cache.collapse(profile.id,!folded) },verticalArrangement=Arrangement.Center) {
                                    val title = privacySettings.label(profile, privacyDisplay)
                                    Text(title,Modifier.testTag("server-title-${profile.id}"),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.SemiBold),maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                        val muted=MaterialTheme.colorScheme.onSurfaceVariant
                                        when {
                                            state.refreshing -> {CircularProgressIndicator(Modifier.size(10.dp),strokeWidth=1.5.dp);Text(state.progress.string(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)}
                                            state.error!=null && state.challenge==null -> {AppIcon(UiSymbol.ALERT_CIRCLE,modifier=Modifier.size(12.dp),tint=StatusColors.attention());Text(state.error.string()+if(state.snapshot!=null) stringResource(R.string.home_showing_cache) else "",style=MaterialTheme.typography.labelSmall,color=StatusColors.attention(),maxLines=2,overflow=TextOverflow.Ellipsis)}
                                            tmuxBlocked -> {AppIcon(UiSymbol.ALERT,modifier=Modifier.size(12.dp),tint=StatusColors.running());Text(stringResource(R.string.doctor_unavailable_short),style=MaterialTheme.typography.labelSmall,color=StatusColors.running(),maxLines=1)}
                                            else -> state.snapshot?.let { snapshot ->
                                                val time=java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                                val fullTime=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(java.util.Date(snapshot.updatedAt))
                                                val updatedLabel=stringResource(R.string.home_updated_at,fullTime)
                                                Text(time,Modifier.semantics {contentDescription=updatedLabel}.testTag("server-updated-${profile.id}"),style=MaterialTheme.typography.labelSmall,color=muted)
                                            } ?: Text(stringResource(R.string.home_no_cache),style=MaterialTheme.typography.labelSmall,color=muted,maxLines=1)
                                        }
                                    }
                                }
                                AppIconButton(UiSymbol.TERMINAL,openSshLabel,{open(profile,null)},Modifier.testTag("ssh-${profile.id}"),tone=IconTone.ACCENT)
                                AppIconButton(UiSymbol.PLUS,stringResource(R.string.home_new_tmux),{requestNewTmux(profile)},Modifier.testTag("new-tmux-${profile.id}"),enabled=!tmuxBlocked)
                                AppIconButton(UiSymbol.REFRESH,refreshServerLabel,{refresh(profile)},Modifier.testTag("refresh-${profile.id}"),enabled=!state.refreshing) {
                                    if(state.refreshing) CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp)
                                    else AppIcon(UiSymbol.REFRESH,modifier=Modifier.size(17.dp),tint=if(state.error!=null && state.challenge==null) StatusColors.attention() else LocalContentColor.current)
                                }
                                AppIconButton(UiSymbol.MORE,moreLabel,{menu=profile.id},Modifier.testTag("more-${profile.id}"),tone=IconTone.PLAIN,size=26.dp)
                            }
                            if(tmuxBlocked) Row(Modifier.fillMaxWidth().clickable {doctorForCreate=false;doctorProfile=profile}.testTag("tmux-unavailable-${profile.id}")
                                .padding(start=16.dp,end=12.dp,top=4.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                AppIcon(UiSymbol.SPARKLE,modifier=Modifier.size(18.dp),tint=StatusColors.running())
                                Text(stringResource(R.string.doctor_unavailable),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(shape=RoundedCornerShape(11.dp),color=MaterialTheme.colorScheme.surfaceVariant) {Text(stringResource(R.string.doctor_title_short),Modifier.padding(horizontal=12.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium)}
                            }
                            if(opening == profile.id) NoticeStrip(stringResource(R.string.home_opening_task),Tone.ACCENT,UiSymbol.TERMINAL,Modifier.padding(start=10.dp,end=10.dp,bottom=10.dp),
                                action=DialogAction(stringResource(R.string.common_cancel),{openingJob?.cancel()}))
                            if(state.challenge!=null) NoticeStrip(state.error?.string() ?: stringResource(R.string.home_needs_fingerprint),if(state.challenge.changed) Tone.DANGER else Tone.ACCENT,
                                if(state.challenge.changed) UiSymbol.SHIELD_ALERT else UiSymbol.SHIELD,Modifier.padding(start=10.dp,end=10.dp,bottom=10.dp).clickable{trust=profile to state.challenge},
                                action=DialogAction(stringResource(R.string.home_check_fingerprint),{trust=profile to state.challenge}))
                            if(!folded) {
                                val tasks = state.snapshot?.tasks.orEmpty().filter { search.isEmpty() || folderMatch || profile.matchesSearch(search) || it.matchesSearch(search) }
                                if(tasks.isEmpty() && state.snapshot!=null && !state.refreshing && state.error==null) {
                                    HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.06f))
                                    Text(stringResource(R.string.home_no_sessions),Modifier.padding(horizontal=16.dp,vertical=14.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val last = recent.firstOrNull { it.profileId==profile.id }
                                tasks.groupBy { it.binding!!.identity.split(':').slice(2..3).joinToString(":") }.forEach { (id, group) ->
                                    val expanded=search.isNotEmpty() || expandedWindows[profile.id+id]==true
                                    HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.06f))
                                    Row(Modifier.fillMaxWidth().heightIn(min=46.dp).clickable {if(group.size==1) open(profile,group.first()) else expandedWindows[profile.id+id]=!expanded}
                                        .testTag("task-${profile.id}-${group.first().binding!!.pane}").padding(start=16.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                        TaskStatusDot(TaskStates.aggregate(group.map { if(state.error==null) state.snapshot?.taskState(it,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN }),"task-status-${profile.id}-${group.first().binding!!.pane}")
                                        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                                        Text(group.first().sessionName,Modifier.weight(1f,fill=false),color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),maxLines=1,overflow=TextOverflow.Ellipsis)
                                        val window=group.first().windowName.removeSuffix(" · ${group.first().sessionName}").takeIf { it!=group.first().sessionName }
                                        if(window!=null) Text("  $window",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        if(group.any { it.identity==last?.identity }) Surface(Modifier.padding(start=8.dp),shape=RoundedCornerShape(5.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.14f)) {
                                            Text(stringResource(R.string.home_last_used),Modifier.padding(horizontal=6.dp,vertical=1.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                        }
                                        }
                                        if(group.size>1) Text("${group.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        AppIcon(if(group.size>1 && expanded) UiSymbol.DOWN else UiSymbol.NEXT,modifier=Modifier.size(16.dp).testTag("task-next-${profile.id}-${group.first().binding!!.pane}"),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val paneGuide=MaterialTheme.colorScheme.outlineVariant
                                    if(group.size>1 && expanded) Column(Modifier.padding(start=24.dp,bottom=6.dp).drawBehind { drawLine(paneGuide,Offset(0f,0f),Offset(0f,size.height),2.dp.toPx()) }) { group.forEach { task -> Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable{open(profile,task)}.testTag("pane-${profile.id}-${task.binding!!.pane}").padding(start=14.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){TaskStatusDot(if(state.error==null) state.snapshot?.taskState(task,stateNow) ?: TaskState.UNKNOWN else TaskState.UNKNOWN,"pane-status-${profile.id}-${task.binding!!.pane}");Text(stringResource(R.string.home_pane, task.binding!!.pane),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);AppIcon(UiSymbol.NEXT,modifier=Modifier.size(15.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)} } }
                                }
                            }
                        }
                        }
                        }
                        if(menu==profile.id) AppSheet(onDismiss={menu=null}) {
                            val lastDoctor=doctorCache.report(profile.id,store.connectionRevision(profile))?.checkedAt
                            SheetHeader(privacySettings.label(profile, privacyDisplay),listOfNotNull(memberships[profile.id]?.let { id -> folders.firstOrNull { it.id==id }?.name } ?: ungroupedLabel,
                                if(tmuxBlocked) stringResource(R.string.doctor_unavailable_short) else null).joinToString(" · "),UiSymbol.SERVER)
                            SheetAction(UiSymbol.SPARKLE,stringResource(R.string.doctor_title),{menu=null;doctorForCreate=false;doctorProfile=profile},Modifier.testTag("doctor-${profile.id}"),highlighted=true,
                                trailing=lastDoctor?.let { stringResource(R.string.doctor_last_checked,java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date(it))) })
                            SheetAction(UiSymbol.EDIT,stringResource(R.string.home_edit_server),{menu=null;edit=profile;page="edit"})
                            SheetAction(UiSymbol.SHIELD,stringResource(R.string.home_verify_host),{menu=null;state.challenge?.let { trust=profile to it } ?: refresh(profile,verifyFingerprint=true)},Modifier.testTag("verify-host-${profile.id}"))
                            SheetAction(UiSymbol.FOLDER,stringResource(R.string.home_move_to_folder),{menu=null;movingServer=profile},
                                trailing=memberships[profile.id]?.let { id -> folders.firstOrNull { it.id==id }?.name })
                            SheetDivider()
                            SheetAction(UiSymbol.CLOSE,stringResource(R.string.home_delete_server),{menu=null;delete=profile},danger=true)
                        }
                    } }
                        }
                    }
                    if(search.isNotEmpty() && !hasResults) Column(Modifier.fillMaxWidth().padding(vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        AppIcon(UiSymbol.SEARCH,modifier=Modifier.size(28.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.home_no_results),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if(folderDialog) AppDialog(onDismiss={folderDialog=false},title=stringResource(if(editingFolder==null) R.string.home_new_folder else R.string.home_rename_folder),icon=UiSymbol.FOLDER,
        confirm=DialogAction(stringResource(R.string.common_save),{runCatching {organization.saveFolder(editingFolder?.id,folderName);folders=organization.folders();folderDialog=false}.onFailure {folderError=errorText(it)}},"save-folder"),
        dismiss=DialogAction(stringResource(R.string.common_cancel),{folderDialog=false})) {
        OutlinedTextField(folderName,{folderName=it;folderError=null},label={Text(stringResource(R.string.folder_name))},singleLine=true,isError=folderError!=null,
            shape=appFieldShape,colors=appFieldColors(),modifier=Modifier.fillMaxWidth().testTag("folder-name"))
        folderError?.let { Text(it.string(),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    }
    deletingFolder?.let { folder -> AppDialog(onDismiss={deletingFolder=null},title=stringResource(R.string.folder_delete_title, folder.name),body=stringResource(R.string.folder_delete_body),
        icon=UiSymbol.FOLDER,tone=Tone.DANGER,
        confirm=DialogAction(stringResource(R.string.home_delete_folder),{runCatching {organization.deleteFolder(folder.id);folders=organization.folders();organizationRevision++;folderCollapsed.remove(folder.id);deletingFolder=null}.onFailure {message=uiText(R.string.folder_delete_failed)}},"confirm-delete-folder",danger=true),
        dismiss=DialogAction(stringResource(R.string.common_cancel),{deletingFolder=null})) }
    movingServer?.let { profile -> AppSheet(onDismiss={movingServer=null}) {
        SheetHeader(stringResource(R.string.move_title, privacySettings.label(profile, privacyDisplay)),icon=UiSymbol.FOLDER)
        val current=organization.folderOf(profile.id)
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            (listOf(null to stringResource(R.string.home_ungrouped))+folders.map {it.id to it.name}).forEach { (id,name) ->
                SheetAction(UiSymbol.FOLDER,name,{runCatching {organization.move(profile.id,id);organizationRevision++;movingServer=null}.onFailure {message=uiText(R.string.move_failed)}},
                    Modifier.testTag("move-folder-${id ?: "ungrouped"}"),highlighted=current==id)
            }
        }
        SheetDivider()
        SheetAction(UiSymbol.CLOSE,stringResource(R.string.common_cancel),{movingServer=null})
    } }
    delete?.let { profile -> AppDialog(onDismiss={delete=null},title=stringResource(R.string.delete_server_title, privacySettings.label(profile, privacyDisplay)),body=stringResource(R.string.delete_server_body),
        icon=UiSymbol.SERVER,tone=Tone.DANGER,
        confirm=DialogAction(stringResource(R.string.common_delete),{
            runCatching { store.delete(profile.id);revisions[profile.id]=(revisions[profile.id] ?: 0)+1;jobs.remove(profile.id)?.cancel();profiles=store.all();rows.remove(profile.id);delete=null }.onFailure { message=uiText(R.string.delete_server_failed) }
        },danger=true),dismiss=DialogAction(stringResource(R.string.common_cancel),{delete=null})) }
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
    trust?.let { (profile, item) -> ProtectSensitiveContent(); HostKeyDialog(item,onDismiss={trust=null},
        onTrust={pins.edit().putString(item.endpoint,item.key).apply();trust=null;refresh(profile)}) }
}

@Composable private fun TaskStatusDot(state: TaskState, tag: String) {
    val color=when(state) {
        TaskState.ATTENTION -> StatusColors.attention()
        TaskState.RUNNING -> StatusColors.running()
        TaskState.COMPLETED -> StatusColors.ready()
        TaskState.UNKNOWN -> StatusColors.unknown()
    }
    val stateLabel=stringResource(R.string.task_state_description, stringResource(state.text()))
    TaskStateDot(color,ring=state==TaskState.ATTENTION,modifier=Modifier.semantics {contentDescription=stateLabel}.testTag(tag))
    Spacer(Modifier.width(10.dp))
}

@Composable private fun SummaryChip(text: String, color: Color, emphasized: Boolean) {
    Surface(shape=CircleShape,color=if(emphasized) color.copy(alpha=.12f) else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start=9.dp,end=11.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(color,CircleShape))
            Text(text,style=MaterialTheme.typography.labelMedium,color=if(emphasized) color else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First-trust and changed-key prompt shared by home, editor, terminal and Doctor. */
@Composable internal fun HostKeyDialog(item: HostKeyChallenge, onDismiss: () -> Unit, onTrust: () -> Unit, trustLabel: String? = null, subtitle: String? = null,
    headerEnd: (@Composable () -> Unit)? = null, route: List<Pair<String,RouteState>>? = null, fingerprintTag: String = "editor-fingerprint", trustTag: String = "editor-trust-key") {
    AppDialog(onDismiss=onDismiss,properties=protectedDialogProperties,headerEnd=headerEnd,
        title=stringResource(if(item.changed) R.string.trust_changed_title else R.string.trust_verify_title),
        body=subtitle ?: stringResource(R.string.trust_verify_hint),
        icon=if(item.changed) UiSymbol.SHIELD_ALERT else UiSymbol.SHIELD,tone=if(item.changed) Tone.DANGER else Tone.ACCENT,stacked=true,
        confirm=if(!item.changed) DialogAction(trustLabel ?: stringResource(R.string.host_key_trust),onTrust,trustTag) else null,
        dismiss=DialogAction(stringResource(if(item.changed) R.string.common_close else R.string.common_cancel),onDismiss)) {
        route?.let { RouteChips(it) }
        FingerprintBlock(item.endpoint.substringBefore("|via:"),item.fingerprint,valueTag=fingerprintTag)
    }
}
