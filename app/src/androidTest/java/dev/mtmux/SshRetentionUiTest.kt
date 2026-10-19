package dev.mtmux

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mtmux.core.SshRetention
import dev.mtmux.core.TaskState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshRetentionUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)

    @Test fun mediumIsDefaultAndEveryLevelPersistsAcrossRecreation() {
        val prefs = compose.activity.getSharedPreferences("terminal-settings", 0)
        val previous = prefs.getInt("sshIdleCapacity", 2)
        try {
            prefs.edit().remove("sshIdleCapacity").commit()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("ssh-retention-MEDIUM").performScrollTo().assertIsSelected()
            for (option in SshRetention.entries) {
                compose.onNodeWithTag("ssh-retention-${option.name}").performScrollTo().performClick().assertIsSelected()
                assertEquals(option, savedSshRetention(compose.activity))
            }
            compose.onNodeWithTag("ssh-retention-NONE").performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("ssh-retention-NONE").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("ssh-retention-help").assertTextEquals("使用结束即关闭，不保留空闲连接。")
        } finally { prefs.edit().putInt("sshIdleCapacity", previous).commit() }
    }

    @Test fun partialRefreshCachePreservesIndividualStateAges() {
        val profile = ServerProfile(name = "age-fixture", host = "127.0.0.1", port = 9, user = "test", path = "tmux", keyAuthentication = false)
        val store = ServerProfiles(compose.activity)
        store.save(profile, SavedCredentials("fixture"))
        try {
            val task = RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path,
                "fixture-pin", "1:2:\$0:@0:%0:3", "session", "window")
            val now = System.currentTimeMillis()
            val snapshot = ServerSnapshot(listOf(task), now, mapOf(task.identity!! to TaskState.RUNNING),
                mapOf(task.identity to now - 29 * 60 * 1000L))
            val cache = ServerCache(compose.activity)
            cache.write(profile, snapshot)
            val restored = requireNotNull(cache.read(profile))
            assertEquals(snapshot, restored)
            assertEquals(TaskState.RUNNING, restored.taskState(task, now))
            assertEquals(TaskState.UNKNOWN, restored.taskState(task, now + 60_000))
        } finally { store.delete(profile.id) }
    }
}
