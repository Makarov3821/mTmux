package dev.mtmux

import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAboutUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)

    @Test fun installedVersionChannelAndProjectLink() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("app-version").performScrollTo().assertTextEquals(BuildConfig.VERSION_NAME)
        compose.onNodeWithTag("app-install-source").performScrollTo()
            .assertTextEquals(if (BuildConfig.DISTRIBUTION_CHANNEL == "play") "Google Play" else "GitHub")
        openLink("app-project-link", "/Makarov3821/mTmux")
    }

    @Test fun issueLinkOpensBrowser() {
        compose.onNodeWithTag("settings").performClick()
        openLink("app-issues-link", "/Makarov3821/mTmux/issues")
    }

    private fun openLink(tag: String, path: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            addDataAuthority("github.com", null)
            addDataPath(path, PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = instrumentation.addMonitor(filter, null, false)
        try {
            compose.onNodeWithTag(tag).performScrollTo().assertHasClickAction().performClick()
            assertEquals(1, monitor.hits)
            compose.waitUntil(15000) { !compose.activity.hasWindowFocus() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
