package dev.mtmux

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android BaseInputConnection lifecycle; paused Backspace cases remain below. */
@RunWith(AndroidJUnit4::class)
class DirectTerminalInputTest {
    private fun check(block: (DirectTerminalInput, MutableList<String>, MutableList<String>) -> Unit) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view=DirectTerminalInput(instrumentation.targetContext)
            val sent=mutableListOf<String>();val composing=mutableListOf<String>()
            view.inputEnabled=true
            view.onText={sent.add(it)}
            view.onKey={key,_ -> sent.add("<$key>")}
            view.onComposition={composing.add(it)}
            block(view,sent,composing)
            view.retire()
        }
    }
    @Test fun chineseCompositionStaysLocalUntilCommittedExactlyOnce() = check {view,sent,composing ->
        val input=view.onCreateInputConnection(EditorInfo())!!
        input.setComposingText("n",1);input.setComposingText("ni",1)
        assertTrue(sent.isEmpty());assertEquals("ni",composing.last())
        input.commitText("你",1);input.finishComposingText()
        assertEquals(listOf("你"),sent);assertEquals("",composing.last())
    }
    /* Backspace experiment paused: keep these two targeted cases for later comparison.
    @Test fun deleteFinishesTheComposingWordThenContinuesWithoutLifting() = check {view,sent,_ ->
        val input=view.onCreateInputConnection(EditorInfo())!!
        input.setComposingText("。",1)
        input.deleteSurroundingText(1,0)
        assertTrue(sent.isEmpty())
        repeat(12) {input.deleteSurroundingText(1,0)}
        assertEquals(List(12) {"<Backspace>"},sent)
    }
    @Test fun repeatedDeleteKeyDownsKeepWorkingAfterTheFirstCharacter() = check {view,sent,_ ->
        val input=view.onCreateInputConnection(EditorInfo())!!
        input.commitText("一大段语音。",1)
        repeat(15) {repeat ->
            input.sendKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL,repeat))
        }
        input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DEL))
        assertEquals("一大段语音。",sent.first())
        assertEquals(List(15) {"<Backspace>"},sent.drop(1))
    }
    */
    @Test fun retiredConnectionCannotCommitOrDeleteIntoTheNextTarget() = check {view,sent,_ ->
        val old=view.onCreateInputConnection(EditorInfo())!!
        old.setComposingText("old",1)
        val fresh=view.onCreateInputConnection(EditorInfo())!!
        old.commitText("old",1);old.finishComposingText();old.deleteSurroundingText(1,0)
        fresh.commitText("新目标",1)
        assertEquals(listOf("新目标"),sent)
    }
    @Test fun latchedKeyCannotReplayItsComposingCommit() = check {view,sent,_ ->
        view.modifiers=TerminalKeyStroke.CTRL
        val input=view.onCreateInputConnection(EditorInfo())!!
        input.setComposingText("a",1);input.commitText("a",1)
        assertEquals(listOf("<a>"),sent)
    }

    @Test fun windowFocusLossInvalidatesImeEvenWithoutAnActivityStop() = check {view,sent,composing ->
        val old=view.onCreateInputConnection(EditorInfo())!!
        old.setComposingText("unfinished",1)
        view.onWindowFocusChanged(false)
        assertEquals("",composing.last())
        old.commitText("late commit",1)
        old.deleteSurroundingText(1,0)
        assertTrue(sent.isEmpty())
        // The re-created endpoint accepts input, even though the SSH target did not change.
        val fresh=view.onCreateInputConnection(EditorInfo())!!
        old.closeConnection() // A late close must not invalidate its replacement.
        fresh.commitText("回来继续输入",1)
        assertEquals(listOf("回来继续输入"),sent)
    }

    @Test fun retiredConnectionCannotSwallowOrDeleteTextFromItsReplacement() = check {view,sent,_ ->
        val old=view.onCreateInputConnection(EditorInfo())!!
        view.restart()
        old.commitText("stale",1)
        val fresh=view.onCreateInputConnection(EditorInfo())!!
        old.closeConnection()
        fresh.setComposingText("xian",1)
        fresh.commitText("现在",1)
        assertEquals(listOf("现在"),sent)
    }
}
