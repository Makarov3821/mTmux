package dev.mtmux

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ServerManagementUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun failedConnectionStaysUnsavedUntilSaveAnywayAndTrustRemainsUnchanged() {
        val name = "offline-" + UUID.randomUUID().toString().take(8)
        val store = ServerProfiles(compose.activity)
        val pins = compose.activity.getSharedPreferences("host-pins",0)
        val endpoint = "[127.0.0.1]:9"
        val old = pins.getString(endpoint,null)
        try {
            compose.onNodeWithTag("add-server").performClick()
            compose.onNodeWithTag("profile-name").performTextInput(name)
            compose.onNodeWithTag("server-host").performTextInput("127.0.0.1")
            compose.onNodeWithTag("server-port").performTextReplacement("9")
            compose.onNodeWithTag("server-user").performTextInput("fixture")
            compose.onNodeWithText("密码",useUnmergedTree=true).performScrollTo().performClick()
            compose.onNodeWithTag("server-secret").performScrollTo().performTextInput("OFFLINE_FIXTURE_ONLY")
            compose.onNodeWithTag("save-server").performClick()
            compose.waitUntil(30000) { present("save-server-anyway") }
            assertTrue(store.all().none { it.name == name })
            assertEquals(old,pins.getString(endpoint,null))
            compose.onNodeWithTag("save-server-anyway").performClick()
            compose.waitUntil(10000) { store.all().any { it.name == name } }
            val profile = store.all().single { it.name == name }
            assertEquals(old,pins.getString(endpoint,null))
            compose.onNodeWithText(compose.activity.getString(R.string.home_saved_unverified)).assertExists()
            compose.onNodeWithTag("more-${profile.id}").performScrollTo().performClick()
            compose.onNodeWithTag("verify-host-${profile.id}").assertExists()
        } finally { store.all().filter { it.name == name }.forEach { store.delete(it.id) }; pins.edit().putString(endpoint,old).commit() }
    }

    @Test fun confirmingTheFixtureFingerprintFinishesSavingAndReturnsHome() {
        val args=InstrumentationRegistry.getArguments(); assumeTrue(args.containsKey("fixtureKey"))
        val store=ServerProfiles(compose.activity); val pins=compose.activity.getSharedPreferences("host-pins",0)
        val port=args.getString("fixturePort")!!.toInt(); val endpoint="[127.0.0.1]:$port"
        val old=pins.getString(endpoint,null); val key=args.getString("fixtureHostKey")!!
        val name="verified-"+UUID.randomUUID().toString().take(8)
        val fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)))
        pins.edit().remove(endpoint).commit()
        try {
            compose.onNodeWithTag("add-server").performClick()
            compose.onNodeWithTag("profile-name").performTextInput(name)
            compose.onNodeWithTag("server-host").performTextInput("127.0.0.1")
            compose.onNodeWithTag("server-port").performTextReplacement(port.toString())
            compose.onNodeWithTag("server-user").performTextInput(args.getString("fixtureUser")!!)
            compose.onNodeWithTag("server-key-paste").performScrollTo().performClick()
            compose.onNodeWithTag("private-key-text").performTextReplacement(String(Base64.getDecoder().decode(args.getString("fixtureKey")),Charsets.UTF_8))
            compose.onNodeWithTag("confirm-key-paste").performClick()
            compose.onNodeWithTag("advanced").performScrollTo().performClick()
            compose.onNodeWithTag("server-tmux-path").performScrollTo().performTextReplacement(args.getString("fixtureTmux")!!)
            compose.onNodeWithTag("save-server").performClick()
            compose.waitUntil(30000) { present("editor-fingerprint") }
            compose.onNodeWithTag("editor-fingerprint").assertTextEquals(fingerprint)
            assertTrue(store.all().none { it.name == name }); assertNull(pins.getString(endpoint,null))
            compose.onNodeWithTag("editor-trust-key").performClick()
            compose.waitUntil(30000) { store.all().any { it.name == name } }
            val profile=store.all().single { it.name == name }
            assertEquals(key,pins.getString(endpoint,null))
            compose.onNodeWithTag("run-tmux-doctor").assertExists()
            compose.onNodeWithTag("skip-tmux-doctor").performClick()
            compose.onNodeWithTag("profile-${profile.id}").assertExists()
            compose.onNodeWithTag("terminal-home").assertDoesNotExist()
        } finally { store.all().filter { it.name == name }.forEach { store.delete(it.id) }; pins.edit().putString(endpoint,old).commit() }
    }

    @Test fun namedSessionOpensItsNewTerminalAndDuplicateStaysInTheDialog() {
        val args=InstrumentationRegistry.getArguments(); assumeTrue(args.containsKey("fixtureKey"))
        val store=ServerProfiles(compose.activity); val pins=compose.activity.getSharedPreferences("host-pins",0)
        val key=Base64.getDecoder().decode(args.getString("fixtureKey"));val hostKey=args.getString("fixtureHostKey")!!
        val profile=ServerProfile(name="新建会话测试",host="127.0.0.1",port=args.getString("fixturePort")!!.toInt(),user=args.getString("fixtureUser")!!,path=args.getString("fixtureTmux")!!,keyAuthentication=true)
        val previous=pins.getString(profile.trustEndpoint(),null);val name="new-"+UUID.randomUUID().toString().take(8)
        store.save(profile,SavedCredentials("",key));pins.edit().putString(profile.trustEndpoint(),hostKey).commit();ServerCache(compose.activity).markAttempt(profile)
        val control=SshClient(object:PinStore { override fun get(endpoint:String)=hostKey })
        try {
            control.connect(store.login(profile));compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("new-tmux-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(30000) {present("new-tmux-name")}
            compose.onNodeWithTag("new-tmux-name").performTextInput(name)
            compose.onNodeWithTag("create-tmux").performClick()
            compose.waitUntil(30000) { store.recent().any { it.profileId==profile.id && it.sessionName==name } }
            compose.onNodeWithTag("terminal-home").assertExists().performClick()
            compose.waitUntil(30000) { ServerCache(compose.activity).read(profile)?.tasks?.any { it.sessionName==name } == true }
            compose.onNodeWithTag("new-tmux-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(30000) {present("new-tmux-name")}
            compose.onNodeWithTag("new-tmux-name").performTextInput(name)
            compose.onNodeWithTag("create-tmux").performClick()
            compose.waitUntil(30000) { present("new-tmux-error") }
            compose.onNodeWithTag("new-tmux-error").assertTextEquals(compose.activity.getString(R.string.tmux_name_exists))
        } finally {
            runCatching { control.exec(Tmux.quote(profile.path)+" -u kill-session -t "+Tmux.quote("=$name")) }
            control.close();store.delete(profile.id);pins.edit().putString(profile.trustEndpoint(),previous).commit()
        }
    }
}
