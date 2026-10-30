package dev.mtmux

import android.app.Dialog
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android's real input-method service, without SSH or a WebView. */
@RunWith(AndroidJUnit4::class)
class TerminalImeFocusTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun connection(view: DirectTerminalInput): InputConnection? =
        DirectTerminalInput::class.java.getDeclaredField("connection").run {
            isAccessible = true
            get(view) as InputConnection?
        }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail(description)
    }

    @Test fun explicitKeyboardTapRecreatesAClosedConnection() {
        ActivityScenario.launch(UiCatalogActivity::class.java).use { scenario ->
            lateinit var view: DirectTerminalInput
            val sent = mutableListOf<String>()
            scenario.onActivity { activity ->
                view = DirectTerminalInput(activity).apply { inputEnabled = true; onText = { sent.add(it) } }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(200, 80))
                })
                view.showKeyboard()
            }
            await("initial IME connection was not created") { connection(view) != null }
            lateinit var old: InputConnection
            scenario.onActivity {
                old = connection(view)!!
                old.closeConnection()
                view.showKeyboard()
            }
            await("keyboard tap retained the closed input connection") {
                connection(view)?.let { it !== old } == true
            }
            scenario.onActivity {
                old.commitText("MUST_NOT_REPLAY", 1)
                connection(view)!!.commitText("恢复输入", 1)
                assertEquals(listOf("恢复输入"), sent)
            }
        }
    }

    @Test fun returningFromAnotherEditorWindowRecoversWithoutActivityResume() {
        ActivityScenario.launch(UiCatalogActivity::class.java).use { scenario ->
            lateinit var view: DirectTerminalInput
            lateinit var dialog: Dialog
            lateinit var old: InputConnection
            val sent = mutableListOf<String>()
            scenario.onActivity { activity ->
                view = DirectTerminalInput(activity).apply { inputEnabled = true; onText = { sent.add(it) } }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(200, 80))
                })
                view.showKeyboard()
            }
            await("initial IME connection was not created") { connection(view) != null }
            scenario.onActivity { activity ->
                old = connection(view)!!
                dialog = Dialog(activity).apply {
                    setContentView(EditText(activity))
                    show()
                }
            }
            await("dialog did not take window focus") { !view.hasWindowFocus() }
            scenario.onActivity {
                old.commitText("MUST_NOT_REPLAY", 1)
                assertTrue(sent.isEmpty())
                dialog.dismiss()
            }
            await("window focus returned without a fresh input connection") {
                view.hasWindowFocus() && connection(view)?.let { it !== old } == true
            }
            scenario.onActivity {
                old.closeConnection()
                connection(view)!!.commitText("切回继续", 1)
                assertEquals(listOf("切回继续"), sent)
            }
        }
    }
}
