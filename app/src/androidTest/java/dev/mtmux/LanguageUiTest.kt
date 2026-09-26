package dev.mtmux

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.TaskState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** English (default resources) smoke tests plus the in-app language switch. */
@RunWith(AndroidJUnit4::class)
class LanguageUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("en")).around(compose)

    // CJK and full-width punctuation; the full-width ＋ icon (U+FF0B) is a symbol, not text.
    private val cjk = Regex("[\\u3000-\\u303f\\u4e00-\\u9fff\\uff01-\\uff0a\\uff0c-\\uffef]")

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(750)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = File(output?.let { File(it) } ?: compose.activity.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Every visible text and accessibility label on screen, excluding user-entered data. */
    private fun visibleText(): List<String> = compose.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() } +
                node.children.flatMap { walk(it) }
        walk(root)
    }

    private fun assertNoChinese(screen: String) {
        val leaked = visibleText().filter { cjk.containsMatchIn(it) }
        assertTrue("$screen shows untranslated text: $leaked", leaked.isEmpty())
    }

    private fun resetStore(): ServerProfiles {
        val store = ServerProfiles(compose.activity)
        store.all().forEach { store.delete(it.id) }
        val organization = ServerOrganization(compose.activity)
        organization.folders().forEach { organization.deleteFolder(it.id) }
        return store
    }

    @Test fun englishIsTheDefaultAcrossHomeSettingsEditorAndTerminal() {
        val store = resetStore()
        val profile = ServerProfile(name = "", host = "127.0.0.1", port = 9, user = "test", path = "tmux", keyAuthentication = false)
        store.save(profile, SavedCredentials("fixture-password"))
        val task = RecentTask(profile.id, profile.host, 9, profile.user, "tmux", "fixture-pin", "1:2:$0:@0:%0:3", "work", "0 · agent")
        val cache = ServerCache(compose.activity)
        cache.write(profile, listOf(task), mapOf(task.identity!! to TaskState.RUNNING))
        cache.markAttempt(profile) // Keep the fixture offline: no automatic refresh on entry.
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Servers").assertIsDisplayed()
            compose.onNodeWithTag("server-title-${profile.id}", useUnmergedTree = true).assertTextContains("Server ", substring = true)
            compose.onNodeWithTag("task-status-${profile.id}-%0", useUnmergedTree = true).assertContentDescriptionEquals("Last refresh: running")
            compose.onNodeWithTag("settings").assertContentDescriptionEquals("Settings")
            assertNoChinese("Home")
            saveScreenshot("english-home")

            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithText("Language").assertExists()
            compose.onNodeWithTag("language-SYSTEM").assertIsDisplayed()
            compose.onNodeWithTag("language-CHINESE").assertTextContains("简体中文") // language names use their own script
            compose.onNodeWithTag("language-ENGLISH").assertIsSelected()
            assertTrue(visibleText().filter { cjk.containsMatchIn(it) }.all { it == "简体中文" })
            compose.onNodeWithTag("export-debug-log").performScrollTo().assertTextEquals("Generate log")
            saveScreenshot("english-settings")
            compose.onNodeWithText("‹ Back").performScrollTo().performClick()

            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithText("Edit server").performClick()
            compose.onNodeWithText("Authentication").assertExists()
            compose.onNodeWithTag("advanced").performScrollTo().performClick()
            compose.onNodeWithText("Enable mouse when connecting").performScrollTo().assertIsDisplayed()
            assertNoChinese("Editor")
            compose.onNodeWithText("Cancel").performClick()

            // Terminal: failed connection text, bottom bar labels on a 360dp-wide screen.
            compose.onNodeWithTag("ssh-${profile.id}").performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("connection-status").fetchSemanticsNodes().any { node ->
                    node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                        .any { it.text.startsWith("Target server: ") }
                }
            }
            listOf("Tools", "Hide ⌨", "Replies", "Send ⏎").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            assertNoChinese("Terminal")
            saveScreenshot("english-terminal-error")
        } finally { store.delete(profile.id) }
    }

    @Test fun switchingLanguageKeepsSettingsOpenAndDoesNotRefresh() {
        val store = resetStore()
        val profile = ServerProfile(name = "lang-fixture", host = "127.0.0.1", port = 9, user = "test", path = "tmux", keyAuthentication = false)
        store.save(profile, SavedCredentials("fixture-password"))
        val cache = ServerCache(compose.activity)
        val prefs = compose.activity.getSharedPreferences("server-profiles", 0)
        try {
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithText("Settings").assertExists()
            // Make the server due for an automatic refresh; a language switch must not trigger it.
            cache.markAttempt(profile, System.currentTimeMillis() - 31 * 60 * 1000L)
            val before = prefs.getString("probe:${profile.id}", null)

            compose.onNodeWithTag("language-CHINESE").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("语言").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("设置").assertExists()
            compose.onNodeWithTag("language-CHINESE").assertIsSelected()
            assertEquals(AppLanguage.CHINESE, AppLanguage.current())
            saveScreenshot("language-switched-chinese")

            compose.onNodeWithText("‹ 返回").performScrollTo().performClick()
            compose.onNodeWithText("服务器").assertExists()
            Thread.sleep(1500)
            assertEquals("Language switch must not refresh servers", before, prefs.getString("probe:${profile.id}", null))
            assertTrue(cache.shouldRefresh(profile))

            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("language-SYSTEM").performClick()
            compose.waitUntil(15000) { AppCompatDelegate.getApplicationLocales().isEmpty }
            assertEquals(AppLanguage.SYSTEM, AppLanguage.current())
            compose.waitForIdle()
            compose.onNodeWithTag("language-SYSTEM").assertIsSelected()
            assertEquals(before, prefs.getString("probe:${profile.id}", null))

            // A language without resources falls back to English (the default resources).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Servers").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithText("Language").assertExists()
            compose.onNodeWithTag("language-SYSTEM").assertIsSelected()
            assertTrue(visibleText().filter { cjk.containsMatchIn(it) }.all { it == "简体中文" })
        } finally { store.delete(profile.id) }
    }
}
