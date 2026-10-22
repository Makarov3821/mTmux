package dev.mtmux

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.TaskState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UiRefreshTest {
    val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)
    private fun screenshot(name:String) {
        compose.waitForIdle();Thread.sleep(1000)
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertTrue("Screenshot is blank; enable emulator rendering and check capture policy",pixels.any {it and 0xFFFFFF != 0})
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder=File(output?.let {File(it)} ?: compose.activity.getExternalFilesDir(null),"ui-refresh").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun seed():ServerProfile {
        val store=ServerProfiles(compose.activity)
        store.all().forEach {store.delete(it.id)}
        val org=ServerOrganization(compose.activity)
        org.folders().forEach {org.deleteFolder(it.id)}
        val folder=org.saveFolder(null,"开发环境")
        val profile=ServerProfile(name="家里的服务器",host="127.0.0.1",port=9,user="fixture",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("fixture-only"));org.move(profile.id,folder.id)
        val tasks=listOf("agent / codex","dev / backend","work / opencode").mapIndexed { i,name ->
            RecentTask(profile.id,profile.host,profile.port,profile.user,profile.path,"fixture-pin","1:2:\$0:@$i:%$i:${i+10}",name.substringBefore(" / "),name.substringAfter(" / "))
        }
        val sibling=tasks[1].copy(identity="1:2:\$0:@1:%4:14")
        ServerCache(compose.activity).apply {
            write(profile,tasks+sibling,mapOf(tasks[0].identity!! to TaskState.COMPLETED,tasks[1].identity!! to TaskState.RUNNING,
                tasks[2].identity!! to TaskState.ATTENTION,sibling.identity!! to TaskState.UNKNOWN))
            markAttempt(profile)
        }
        store.rememberTask(tasks[0])
        return profile
    }
    @Test fun cardsSettingsPalettesAndScrollPreferenceRenderAndPersist() {
        val profile=seed()
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("theme-DARK").performScrollTo().performClick()
            compose.onNodeWithTag("palette-TOKYO").performScrollTo().performClick().assertIsSelected()
            screenshot("settings-tokyo")
            compose.onNodeWithTag("settings-speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(10f)}
            assertEquals(10f,compose.activity.getSharedPreferences("terminal-settings",0).getFloat("scrollSensitivity",0f))
            compose.onNodeWithText("‹ 返回").performClick()
            compose.onNodeWithTag("profile-${profile.id}").assertIsDisplayed()
            screenshot("home-tokyo")
            compose.onNodeWithTag("task-${profile.id}-%1").performClick()
            compose.onNodeWithTag("pane-${profile.id}-%4").assertExists()
            screenshot("home-panes-tokyo")
            compose.onNodeWithTag("settings").performClick()
            for(palette in AppPalette.entries) compose.onNodeWithTag("palette-${palette.name}").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("theme-LIGHT").performScrollTo().performClick()
            screenshot("settings-paper")
            compose.onNodeWithText("‹ 返回").performClick()
            screenshot("home-paper")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("palette-PAPER").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("theme-LIGHT").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("settings-speed").performScrollTo().assertExists()
            assertEquals(10f,compose.activity.getSharedPreferences("terminal-settings",0).getFloat("scrollSensitivity",0f))
        } finally {ServerProfiles(compose.activity).delete(profile.id)}
    }
    private fun terminal():TerminalView {
        fun find(view:View):TerminalView? {
            if(view is TerminalView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let {return it}
            return null
        }
        var result:TerminalView?=null
        compose.runOnUiThread {result=find(compose.activity.window.decorView)}
        return requireNotNull(result)
    }
    private fun evaluate(script:String):String {
        val done=CountDownLatch(1);var result=""
        compose.runOnUiThread {terminal().evaluateJavascript(script){result=it;done.countDown()}}
        assertTrue(done.await(30,TimeUnit.SECONDS));return result
    }
    @Test fun terminalDockToolsAndKeyboardKeepTheLogicalGrid() {
        val profile=seed()
        try {
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread {
                fun state(name:String,value:Any) {
                    val field=MainActivity::class.java.getDeclaredField(name+"\$delegate").apply {isAccessible=true}
                    @Suppress("UNCHECKED_CAST")
                    (field.get(compose.activity) as androidx.compose.runtime.MutableState<Any>).value=value
                }
                state("showConfig",false);state("status",TerminalStatus.NONE);state("wireToken","ui-fixture");state("renderedBytes",100L)
            }
            compose.waitForIdle()
            compose.waitUntil(60000) { evaluate("typeof setConnection")=="\"function\"" }
            evaluate("terminal.write('\\x1b[32m✓ mTmux UI\\x1b[0m\\r\\n$ echo ready\\r\\nready');setConnection('ui-fixture')")
            screenshot("terminal-dock")
            val before=evaluate("JSON.stringify([terminal.cols,terminal.rows])")
            compose.onNodeWithTag("command-input").performClick().performTextInput("检查一下当前任务")
            screenshot("terminal-keyboard")
            assertEquals(before,evaluate("JSON.stringify([terminal.cols,terminal.rows])"))
            compose.runOnUiThread {
                (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
            }
            compose.onNodeWithTag("terminal-tools").performClick()
            compose.onNodeWithTag("scroll-speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(5f)}
            screenshot("terminal-tools")
            assertEquals(5f,compose.activity.getSharedPreferences("terminal-settings",0).getFloat("scrollSensitivity",0f))
            assertEquals(before,evaluate("JSON.stringify([terminal.cols,terminal.rows])"))
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("terminal-home").performClick()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("settings-speed").performScrollTo().assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo(5f,1f..10f,8)))
        } finally {ServerProfiles(compose.activity).delete(profile.id)}
    }
}
