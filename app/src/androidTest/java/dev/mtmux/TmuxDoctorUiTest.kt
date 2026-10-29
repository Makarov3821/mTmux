package dev.mtmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TmuxDoctorUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun verifiedSaveOffersDoctorAndReusesItsAuthenticatedChain() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Run scripts/test_ssh.py --android",args.containsKey("fixtureKey"))
        val context=compose.activity;val store=ServerProfiles(context)
        val pins=context.getSharedPreferences("host-pins",0)
        val endpoint="[127.0.0.1]:${args.getString("fixturePort")}"
        val previous=pins.getString(endpoint,null)
        val name="Doctor flow "+java.util.UUID.randomUUID().toString().take(8)
        val pool=WarmConnectionPool<SshClient>(isReusable={it.isReusable()})
        pins.edit().remove(endpoint).commit()
        fun click(tag:String) {
            runCatching {compose.onNodeWithTag(tag).performScrollTo()}
            compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        }
        try {
            compose.setContent {MaterialTheme {
                ServerHome(pool,privacyDisplay=true,onPrivacyDisplay={},refreshOnEntry=false,onEntryHandled={},onOpen={_,_,_->},
                    onPreferences={_,_,_->},appearance=AppAppearance.DARK,onAppearance={},palette=AppPalette.TOKYO,onPalette={})
            }}
            click("add-server")
            compose.onNodeWithTag("profile-name").performTextReplacement(name)
            compose.onNodeWithTag("server-host").performTextReplacement("127.0.0.1")
            compose.onNodeWithTag("server-port").performTextReplacement(args.getString("fixturePort")!!)
            compose.onNodeWithTag("server-user").performTextReplacement(args.getString("fixtureUser")!!)
            click("server-key-paste")
            compose.onNodeWithTag("private-key-text").performTextReplacement(String(java.util.Base64.getDecoder().decode(args.getString("fixtureKey")),Charsets.UTF_8))
            click("confirm-key-paste");click("advanced")
            compose.onNodeWithTag("server-tmux-path").performScrollTo().performTextReplacement(args.getString("fixtureTmux")!!)
            click("save-server")
            compose.waitUntil(90000) {compose.onAllNodesWithTag("editor-fingerprint").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(store.all().none {it.name==name})
            click("editor-trust-key")
            compose.waitUntil(90000) {compose.onAllNodesWithTag("run-tmux-doctor").fetchSemanticsNodes().isNotEmpty()}
            val profile=store.all().single {it.name==name}
            assertEquals(args.getString("fixtureHostKey"),pins.getString(endpoint,null))
            click("run-tmux-doctor")
            compose.waitUntil(90000) {compose.onAllNodesWithTag("doctor-recheck").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("doctor-error").assertDoesNotExist()
            val report=TmuxDoctorStore(context).report(profile.id,store.connectionRevision(profile))!!.report
            assertEquals(DoctorGrade.PASS,report.findings().single {it.check==DoctorCheck.PROTOCOL}.grade)
            val lines=DebugLog.snapshot().toString(Charsets.UTF_8).lineSequence().filter {"a=${profile.id.hashCode()} " in it}.toList()
            assertEquals(1,lines.count {"phase=TCP_CONNECT result=OK" in it})
            assertTrue(lines.any {"source=DOCTOR" in it && "phase=SSH_REUSED result=OK" in it})
            assertTrue(store.all().any {it.id==profile.id})
        } finally {
            store.all().filter {it.name==name}.forEach {store.delete(it.id)}
            pool.close();pins.edit().putString(endpoint,previous).commit()
        }
    }
    @Test fun invitationCanBeSkippedWithoutRunningDiagnosis() {
        var runs=0;var skips=0
        compose.setContent {MaterialTheme {TmuxDoctorInvitation({runs++},{skips++})}}
        compose.onNodeWithTag("skip-tmux-doctor").performClick()
        assertEquals(0,runs);assertEquals(1,skips)
    }
    @Test fun versionWarningAndNoServerAreIndependentFindings() {
        var version by mutableStateOf("tmux 3.1c")
        var touch by mutableStateOf(false)
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
            DoctorFindings(TmuxDoctorReport(mapOf("path.status" to "ok","version.client" to version,"server.status" to "none")),true,remoteTouch=touch)
        }}}
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_reason_version_old)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_touch_off)).assertExists()
        compose.onNodeWithTag("doctor-row-sessions").assertExists()
        compose.runOnUiThread {version="tmux 3.2a";touch=true}
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_reason_version_old)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_reason_version_ok)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.doctor_touch_on)).assertExists()
    }
    @Test fun unavailableTmuxBlocksOnlyCreationAndAnExpiredRecordPermitsCheckingAgain() {
        val context=compose.activity
        val store=ServerProfiles(context);val cache=TmuxDoctorStore(context)
        val profile=ServerProfile(name="Doctor UI fixture",host="127.0.0.1",port=9,user="fixture",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("LOCAL_FIXTURE_ONLY"))
        val revision=store.connectionRevision(profile)
        val pool=WarmConnectionPool<SshClient>(maxSize=0,isReusable={it.isReusable()})
        var generation by mutableIntStateOf(0)
        fun content() {compose.setContent {key(generation) {MaterialTheme {
            ServerHome(pool,privacyDisplay=true,onPrivacyDisplay={},refreshOnEntry=false,onEntryHandled={},onOpen={_,_,_->},
                onPreferences={_,_,_->},appearance=AppAppearance.DARK,onAppearance={},palette=AppPalette.TOKYO,onPalette={})
        }}}}
        try {
            cache.save(profile.id,revision,TmuxDoctorReport(mapOf("path.status" to "missing")),true)
            content()
            compose.onNodeWithTag("new-tmux-${profile.id}").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("ssh-${profile.id}").assertIsEnabled()
            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithTag("doctor-${profile.id}").assertIsEnabled()
            cache.save(profile.id,revision,TmuxDoctorReport(mapOf("path.status" to "missing")),true,System.currentTimeMillis()-TmuxDoctorStore.TTL)
            compose.runOnUiThread {generation++}
            compose.onNodeWithTag("new-tmux-${profile.id}").performScrollTo().assertIsEnabled()
            store.delete(profile.id)
            assertNull(cache.report(profile.id,revision))
        } finally {store.delete(profile.id);pool.close()}
    }
    @Test fun offlineDiagnosisKeepsServerSavedAndDoesNotClaimTmuxIsMissing() {
        val context=compose.activity;val store=ServerProfiles(context);val cache=TmuxDoctorStore(context)
        val profile=ServerProfile(name="Offline Doctor fixture",host="127.0.0.1",port=9,user="fixture",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("LOCAL_FIXTURE_ONLY"))
        val revision=store.connectionRevision(profile)
        val pool=WarmConnectionPool<SshClient>(maxSize=0,isReusable={it.isReusable()})
        try {
            compose.setContent {MaterialTheme {TmuxDoctorDialog(profile,pool,onChanged={},onDismiss={})}}
            compose.waitUntil(30000) {compose.onAllNodesWithTag("doctor-error").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(store.all().any {it.id==profile.id})
            assertEquals(TmuxAvailability.UNKNOWN,cache.availability(profile.id,revision,System.currentTimeMillis()))
        } finally {store.delete(profile.id);pool.close()}
    }
}
