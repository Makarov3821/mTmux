package dev.mtmux

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mtmux.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Behaviour introduced by the 0.9.x UI refresh; no network fixture needed. */
@RunWith(AndroidJUnit4::class)
class UiRedesignTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)

    private fun state(name: String, value: Any?) = compose.runOnUiThread {
        val field = MainActivity::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        when (val holder = field.get(compose.activity)) {
            is androidx.compose.runtime.MutableLongState -> holder.longValue = value as Long
            else -> (holder as androidx.compose.runtime.MutableState<Any?>).value = value
        }
    }
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun seed(states: Map<Int, TaskState>): ServerProfile {
        val store = ServerProfiles(compose.activity)
        store.all().forEach { store.delete(it.id) }
        val profile = ServerProfile(name = "改版测试", host = "127.0.0.1", port = 9, user = "fixture", path = "tmux", keyAuthentication = false)
        store.save(profile, SavedCredentials("fixture-only"))
        val tasks = states.keys.map { i -> RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path, "fixture-pin",
            "1:2:\$$i:@$i:%$i:${i + 10}", "s$i", "w$i") }
        ServerCache(compose.activity).apply {
            write(profile, tasks, tasks.mapIndexed { n, t -> t.identity!! to states.values.toList()[n] }.toMap()); markAttempt(profile)
        }
        return profile
    }
    private fun showTerminal() { state("showConfig", false); compose.waitForIdle() }

    @Test fun homeSummaryCountsMatchTaskDotsAndCardButtonsAreInOrder() {
        val profile = seed(mapOf(0 to TaskState.ATTENTION, 1 to TaskState.RUNNING, 2 to TaskState.RUNNING, 3 to TaskState.COMPLETED))
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("home-title").assertTextEquals("mTmux")
            compose.onNodeWithText("1 个等待回复").assertIsDisplayed()
            compose.onNodeWithText("2 个运行中").assertIsDisplayed()
            val xs = listOf("ssh", "new-tmux", "refresh", "more").map { compose.onNodeWithTag("$it-${profile.id}").fetchSemanticsNode().boundsInRoot.left }
            assertEquals("card buttons: terminal, new, refresh, more", xs.sorted(), xs)
            // Low-frequency actions live in the sheet; new session and refresh stay on the card.
            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithTag("doctor-${profile.id}").assertIsDisplayed()
            compose.onNodeWithText("编辑服务器").assertIsDisplayed()
            compose.onAllNodesWithText(compose.activity.getString(R.string.home_new_tmux)).assertCountEquals(0)
        } finally { ServerProfiles(compose.activity).delete(profile.id) }
    }

    /** 0.9.2 regression: after a refresh the summary used the previous minute tick and counted every new reading as gray. */
    @Test fun summaryKeepsCountingTasksAfterARefresh() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.containsKey("fixtureTmux"))
        val key = java.util.Base64.getDecoder().decode(args.getString("fixtureKey"))
        val profile = ServerProfile(name = "summary-refresh", host = "127.0.0.1", port = args.getString("fixturePort")!!.toInt(),
            user = args.getString("fixtureUser")!!, path = args.getString("fixtureTmux")!!, keyAuthentication = true)
        val client = SshClient(object : PinStore { override fun get(endpoint: String) = args.getString("fixtureHostKey") })
        val tmux = Tmux.quote(profile.path)
        var session = ""
        val store = ServerProfiles(compose.activity)
        try {
            client.connect(Login(profile.host, profile.port, profile.user, "", key))
            // A Codex composer with a live "esc to interrupt" control reads as running.
            session = client.exec("$tmux new-session -d -P -F '#{session_id}' -s summary-refresh-fixture 'printf \"• Working (3s • esc to interrupt)\\n› Ask Codex to do anything\\nGPT-6-Astra medium · ~/project\\n\"; sleep 120'").output.trim()
            assertTrue(session.startsWith("$"))
            Thread.sleep(250)
            store.all().forEach { store.delete(it.id) }
            store.save(profile, SavedCredentials("", key, ""))
            compose.activity.getSharedPreferences("host-pins", 0).edit().putString(profile.trustEndpoint(), args.getString("fixtureHostKey")).commit()
            ServerCache(compose.activity).apply { write(profile, emptyList()); markAttempt(profile) }
            compose.activityRule.scenario.recreate()
            compose.onAllNodesWithText("运行中", substring = true).assertCountEquals(0)
            compose.onNodeWithTag("refresh-${profile.id}").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("个运行中", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // A second refresh replaces the snapshot again; the count must stay.
            compose.waitUntil(15000) { compose.onNodeWithTag("refresh-${profile.id}").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
            compose.onNodeWithTag("refresh-${profile.id}").performClick()
            compose.waitUntil(15000) { compose.onNodeWithTag("refresh-${profile.id}").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
            compose.onNodeWithText("个运行中", substring = true).assertIsDisplayed()
        } finally {
            if (session.startsWith("$")) client.exec("$tmux kill-session -t ${Tmux.quote(session)}")
            client.close(); store.delete(profile.id)
        }
    }

    @Test fun summaryHidesWhenNothingNeedsTheUserAndUnavailableTmuxDisablesOnlyCreation() {
        val profile = seed(mapOf(0 to TaskState.COMPLETED))
        val store = ServerProfiles(compose.activity)
        TmuxDoctorStore(compose.activity).save(profile.id, store.connectionRevision(profile), TmuxDoctorReport(mapOf("path.status" to "missing")), false)
        try {
            compose.activityRule.scenario.recreate()
            compose.onAllNodesWithText("等待回复", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("运行中", substring = true).assertCountEquals(0)
            compose.onNodeWithTag("tmux-unavailable-${profile.id}").assertIsDisplayed()
            compose.onNodeWithTag("new-tmux-${profile.id}").assertIsNotEnabled()
            compose.onNodeWithTag("ssh-${profile.id}").assertIsEnabled()
            compose.onNodeWithTag("refresh-${profile.id}").assertIsEnabled()
        } finally { store.delete(profile.id) }
    }

    @Test fun connectionCardShowsTheFailedHopAndOffersRetryAndHome() {
        val profile = seed(mapOf(0 to TaskState.RUNNING))
        try {
            showTerminal()
            state("busy", true); state("wireToken", ""); state("renderedBytes", 0L)
            state("connectRoute", listOf(hopText(1), hopText(0)))
            state("connectSteps", listOf(ConnectStep(uiText(R.string.step_hop_auth, hopText(1)), StepState.DONE, 146),
                ConnectStep(uiText(R.string.step_hop_auth, hopText(0)), StepState.ACTIVE)))
            compose.waitUntil(5000) { present("connection-card") }
            compose.onNodeWithText("正在连接").assertIsDisplayed()
            compose.onNodeWithText("146 ms").assertIsDisplayed()
            compose.onNodeWithTag("cancel-connect").assertIsDisplayed()
            compose.onAllNodesWithTag("reconnect-recent").assertCountEquals(0)

            state("reconnectTarget", TaskConnection(profile, null))
            state("connectFailure", ConnectionFailure(0, FailureReason.AUTH, IllegalStateException("fixture")))
            state("connectSteps", listOf(ConnectStep(uiText(R.string.step_hop_auth, hopText(1)), StepState.DONE, 146),
                ConnectStep(uiText(R.string.step_hop_auth, hopText(0)), StepState.FAILED)))
            state("status", TerminalStatus(uiText(R.string.status_disconnected), alert = true))
            state("busy", false)
            compose.onNodeWithText(compose.activity.getString(FailureReason.AUTH.text())).assertIsDisplayed()
            compose.onNodeWithTag("reconnect-recent").assertIsDisplayed().assertTextEquals("重试")
            compose.onNodeWithText("返回首页").assertIsDisplayed()
            compose.onAllNodesWithTag("cancel-connect").assertCountEquals(0)
        } finally { state("connectFailure", null); state("connectSteps", emptyList<ConnectStep>()); ServerProfiles(compose.activity).delete(profile.id) }
    }

    @Test fun disconnectedCardOffersOnlyReconnectAndBecomesAPillWhileReadingHistory() {
        val profile = seed(mapOf(0 to TaskState.RUNNING))
        try {
            showTerminal()
            state("reconnectTarget", TaskConnection(profile, null))
            state("busy", false); state("wireToken", ""); state("connectFailure", null); state("renderedBytes", 500L)
            state("status", TerminalStatus(uiText(R.string.status_terminal_closed)))
            compose.onNodeWithTag("disconnected-history").assertIsDisplayed()
            compose.onNodeWithTag("reconnect-recent").assertIsDisplayed().assertTextContains("重连")
            compose.onAllNodesWithText(compose.activity.getString(R.string.terminal_reselect_task)).assertCountEquals(0)
            compose.onAllNodesWithTag("disconnected-pill").assertCountEquals(0)
            state("reading", true)
            compose.onNodeWithTag("disconnected-pill").assertIsDisplayed()
            compose.onAllNodesWithTag("reconnect-recent").assertCountEquals(1)
            state("reading", false)
            compose.onAllNodesWithTag("disconnected-pill").assertCountEquals(0)
        } finally { state("reading", false); ServerProfiles(compose.activity).delete(profile.id) }
    }

    @Test fun voiceCancelIsArmedByLeavingThePressAreaAndDisarmedByReturning() {
        val state = VoiceHoldState(); val id = state.begin()!!
        state.ready(id, android.os.SystemClock.elapsedRealtime())
        state.move(id, true); assertTrue(state.cancelArmed)
        state.move(id, false); assertFalse("moving back must resume recording", state.cancelArmed)
        state.move(id, true)
        assertEquals("release while armed discards", VoiceRelease.CANCEL, state.release(id))
        val next = state.begin()!!; state.ready(next, android.os.SystemClock.elapsedRealtime())
        assertEquals(VoiceRelease.TRANSCRIBE, state.release(next))
    }
}

/** Redesigned components rendered on their own, without MainActivity. */
@RunWith(AndroidJUnit4::class)
class UiRedesignComponentTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun doctorReportPutsProblemsFirstAndFoldsPassingChecks() {
        val facts = mapOf("path.status" to "ok", "path" to "/usr/bin/tmux", "version.client" to "tmux 3.4", "version.server" to "3.4",
            "server.status" to "ready", "server.frame" to "3:123", "sessions.sampled" to "2", "mouse" to "off", "history-limit" to "10000")
        compose.setContent { MaterialTheme { DoctorFindings(TmuxDoctorReport(facts), autoMouse = false, collapsePassing = true) } }
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_needs_attention)).assertIsDisplayed()
        val mouse = compose.onNodeWithTag("doctor-row-mouse").fetchSemanticsNode().boundsInRoot.top
        val ssh = compose.onNodeWithTag("doctor-row-ssh").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the warning must come before passing checks", mouse < ssh)
        // Folded passing checks show only the title, not the reason.
        compose.onAllNodesWithText(compose.activity.getString(R.string.doctor_reason_verified)).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_expand)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_reason_verified)).assertExists()
    }

    @Test fun fingerprintIsGroupedForEyesButExactForAssistiveTech() {
        val fp = "SHA256:ZgNwJk7mDemoFingerprintForUiCatalogOnly"
        compose.setContent { MaterialTheme { FingerprintBlock("[192.0.2.10]:22", fp, valueTag = "fp") } }
        compose.onNodeWithTag("fp").assertTextEquals(fp)
    }

    /** 0.9.2 phone feedback: when "Very many" wraps, the other options stay vertically centered. */
    @Test fun segmentedChoiceKeepsOptionsCenteredWhenOneLabelWraps() {
        val options = listOf("None", "Few", "Medium", "Many", "Very many indeed")
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) {
            SegmentedChoice(options, "Medium", { it }, { "seg-$it" }, {})
        } } }
        val boxes = options.map { compose.onNodeWithTag("seg-$it").fetchSemanticsNode().boundsInRoot }
        assertTrue("the long label wraps in this width", boxes.last().height > 40 * compose.density.density)
        boxes.forEach { assertEquals("every option fills the row height", boxes.last().height, it.height, 1f) }
        val centers = options.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.center.y }
        centers.forEach { assertEquals("labels are vertically centered", centers.last(), it, 1.5f) }
    }
}
