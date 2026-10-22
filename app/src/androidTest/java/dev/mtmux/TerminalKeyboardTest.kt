package dev.mtmux

import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the real Compose/Android input connection without the ATD WebView renderer. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalKeyboardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val sticky=StickyKeys()
    private val session=TerminalImeSession()
    private val focus=FocusRequester()
    private val sent=mutableListOf<String>()
    private var draft by mutableStateOf("UNSENT DRAFT")
    private fun send(key:String,additional:Int=0) {sent.add(requireNotNull(TerminalKeyStroke.encode(key,sticky.take() or additional)))}
    private fun content() {
        compose.setContent {
            MaterialTheme(colorScheme=appColors(true,AppPalette.TOKYO),typography=appTypography,shapes=appShapes) {
                val view=LocalView.current
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().imePadding().padding(horizontal=12.dp)) {
                        Text("mTmux",Modifier.padding(12.dp),style=MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Surface(Modifier.fillMaxWidth().height(156.dp),color=MaterialTheme.colorScheme.surface) {
                            Column {
                                TerminalKeys(true,sticky.modifiers,{bit ->
                                    session.finishComposition();sticky.toggle(bit);focus.requestFocus();session.restart(view)
                                },{key -> send(if(key=="Ctrl-C") "c" else key,if(key=="Ctrl-C") TerminalKeyStroke.CTRL else 0)})
                                TerminalKeyInput(session,{sticky.modifiers},{text ->
                                    if(sticky.modifiers==0) false else {send(text);true}
                                },{if(sticky.modifiers==0) false else {send("Backspace");true}}) {
                                    DraftInput(draft,{draft=it},{},true,false,sticky.modifiers,
                                        Modifier.focusRequester(focus).testTag("command-input").captureTerminalKey(sticky,session,::send))
                                }
                                TerminalActions(true,{},{}) {
                                    VoiceInputControl(Modifier,true,"keyboard-fixture","keyboard-target",{},
                                        {_,_,_ -> VoiceDraftOffer.NO_SPEECH},{_,_,_,_ -> false},{})
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun connection():InputConnection {
        var result:InputConnection?=null
        compose.runOnUiThread {result=compose.activity.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())}
        return requireNotNull(result)
    }
    private fun commit(text:String):InputConnection {
        val input=connection()
        compose.runOnUiThread {assertTrue(input.commitText(text,1))}
        compose.waitForIdle()
        return input
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertTrue("ATD drawing must be enabled",pixels.any {it and 0xFFFFFF != 0})
        val folder=File(compose.activity.getExternalFilesDir(null),"terminal-keyboard").apply {mkdirs()}
        File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun circularPagesAndImeShortcutsLeaveDraftUntouched() {
        content()
        val pages=compose.onNodeWithTag("terminal-key-pages")
        compose.onNodeWithTag("terminal-key-Esc").assertIsDisplayed()
        pages.performTouchInput {swipeLeft()}
        compose.onNodeWithTag("terminal-key-Ctrl").assertIsDisplayed()
        pages.performTouchInput {swipeRight()}
        compose.onNodeWithTag("terminal-key-Esc").assertIsDisplayed()
        repeat(3) {pages.performTouchInput {swipeRight()}}
        compose.onNodeWithTag("terminal-key-Ctrl").assertIsDisplayed()
        capture("dock-borderless")
        for ((modifier,char,expected) in listOf(Triple("Ctrl","a","\u0001"),Triple("Ctrl","e","\u0005"),Triple("Shift","i","I"))) {
            compose.onNodeWithTag("terminal-key-$modifier").performClick().assertIsSelected()
            val old=commit(char)
            compose.onNodeWithTag("terminal-key-$modifier").assertIsNotSelected()
            compose.runOnUiThread {assertTrue(old.commitText(char,1))}
            assertEquals(expected,sent.last())
            compose.onNodeWithTag("command-input").assertTextContains("UNSENT DRAFT")
        }
        assertEquals(listOf("\u0001","\u0005","I"),sent)
        compose.onNodeWithTag("terminal-key-Ctrl").performClick().assertIsSelected()
        compose.onNodeWithTag("terminal-key-Shift").performClick().assertIsSelected()
        capture("dock-sticky-ctrl-shift")
        compose.onNodeWithTag("terminal-key-←").performClick()
        assertEquals("\u001b[1;6D",sent.last())
        compose.onNodeWithTag("terminal-key-Ctrl").assertIsNotSelected()
        compose.onNodeWithTag("terminal-key-Shift").assertIsNotSelected()
        pages.performTouchInput {swipeRight()}
        compose.onNodeWithTag("terminal-key-Esc").assertIsDisplayed()
    }
    @Test fun hardwareKeyReleasesLatchAndNormalTypingStillEditsDraft() {
        content()
        compose.onNodeWithTag("terminal-key-pages").performTouchInput {swipeLeft()}
        compose.onNodeWithTag("terminal-key-Shift").performClick().assertIsSelected()
        compose.onNodeWithTag("command-input").performKeyInput {pressKey(Key.I)}
        compose.onNodeWithTag("terminal-key-Shift").assertIsNotSelected()
        assertEquals(listOf("I"),sent)
        compose.onNodeWithTag("command-input").assertTextContains("UNSENT DRAFT")
        commit("z")
        compose.onNodeWithTag("command-input").assertTextContains("UNSENT DRAFT",substring=true).assertTextContains("z",substring=true)
        assertEquals(listOf("I"),sent)
        compose.onNodeWithTag("terminal-key-Alt").performClick().assertIsSelected()
        // Keep cancellation independent of pointer coordinates while the IME resizes the window.
        compose.onNodeWithTag("terminal-key-Alt").performSemanticsAction(SemanticsActions.OnClick) {it()}.assertIsNotSelected()
    }
}
