package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class TerminalKeyStrokeTest {
    private val ctrl=TerminalKeyStroke.CTRL
    private val shift=TerminalKeyStroke.SHIFT
    private val alt=TerminalKeyStroke.ALT
    @Test fun readlineAndVimKeysUseTerminalBytes() {
        assertEquals("\u0001",TerminalKeyStroke.encode("a",ctrl))
        assertEquals("\u0005",TerminalKeyStroke.encode("e",ctrl))
        assertEquals("\u0003",TerminalKeyStroke.encode("c",ctrl))
        assertEquals("I",TerminalKeyStroke.encode("i",shift))
        assertEquals("I",TerminalKeyStroke.encode("I",shift))
        assertEquals("!",TerminalKeyStroke.encode("1",shift))
        assertEquals("?",TerminalKeyStroke.encode("/",shift))
        assertEquals("\u001bb",TerminalKeyStroke.encode("b",alt))
        assertEquals("\u001bI",TerminalKeyStroke.encode("i",alt or shift))
        assertEquals("\u001b\u0001",TerminalKeyStroke.encode("a",alt or ctrl))
        // The legacy protocol cannot distinguish the shifted control letter from Ctrl+C.
        assertEquals("\u0003",TerminalKeyStroke.encode("c",ctrl or shift))
    }
    @Test fun arrowsAndFunctionKeysKeepModifiers() {
        assertEquals("\u001b[1;6D",TerminalKeyStroke.encode("ArrowLeft",ctrl or shift))
        assertEquals("\u001b[1;3C",TerminalKeyStroke.encode("ArrowRight",alt))
        assertEquals("\u001b[1;8P",TerminalKeyStroke.encode("F1",ctrl or shift or alt))
        assertEquals("\u001b[24;5~",TerminalKeyStroke.encode("F12",ctrl))
        assertEquals("\u001b[Z",TerminalKeyStroke.encode("Tab",shift))
        assertEquals("\b",TerminalKeyStroke.encode("Backspace",ctrl))
        assertEquals("\u001b\r",TerminalKeyStroke.encode("Enter",alt))
        val plain=listOf("F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12")
        assertEquals("\u001bOP\u001bOQ\u001bOR\u001bOS\u001b[15~\u001b[17~\u001b[18~\u001b[19~\u001b[20~\u001b[21~\u001b[23~\u001b[24~",
            plain.joinToString("") {TerminalKeyStroke.encode(it,0)!!})
    }
    @Test fun controlSymbolsUnicodeAndInvalidInputsAreBounded() {
        assertEquals("\u0000",TerminalKeyStroke.encode(" ",ctrl))
        assertEquals("\u001b",TerminalKeyStroke.encode("[",ctrl))
        assertEquals("\u001f",TerminalKeyStroke.encode("/",ctrl))
        assertEquals("\u001bé",TerminalKeyStroke.encode("é",alt))
        assertNull(TerminalKeyStroke.encode("中",ctrl))
        for(key in listOf("","two","\u001b","\n","\uD800")) assertNull(TerminalKeyStroke.encode(key,ctrl))
    }
    @Test fun modifiersToggleCombineAndReleaseExactlyOnce() {
        val sticky=StickyKeys()
        sticky.toggle(ctrl);sticky.toggle(shift);sticky.toggle(alt)
        assertEquals(ctrl or shift or alt,sticky.modifiers)
        sticky.toggle(alt)
        assertEquals("Ctrl+Shift",TerminalKeyStroke.label(sticky.modifiers))
        assertEquals(ctrl or shift,sticky.take());assertEquals(0,sticky.take())
        sticky.toggle(ctrl);sticky.clear();assertEquals(0,sticky.modifiers)
    }
    @Test fun pagerWrapsBothDirectionsAtEveryLogicalBoundary() {
        assertEquals(0,terminalKeyPage(TERMINAL_KEY_PAGER_START))
        assertEquals(listOf(0,1,2,3,0,1), (0..5).map {terminalKeyPage(TERMINAL_KEY_PAGER_START+it)})
        assertEquals(listOf(0,3,2,1,0,3), (0..5).map {terminalKeyPage(TERMINAL_KEY_PAGER_START-it)})
    }
    @Test fun composingKeyIsConsumedOnceAndCannotBeReplayedByOldIme() {
        val sticky=StickyKeys();sticky.toggle(ctrl)
        val sent=mutableListOf<String>();var restarts=0
        val gate=OneShotInputGate({text ->
            if(sticky.modifiers==0) false else {sent.add(TerminalKeyStroke.encode(text,sticky.take())!!);true}
        },{false},{restarts++})
        assertFalse(gate.text(""));assertEquals(ctrl,sticky.modifiers)
        assertTrue(gate.text("a"));assertEquals(listOf("\u0001"),sent)
        assertTrue(gate.text("a"));assertTrue(gate.text("and"));assertTrue(gate.delete(1,0))
        assertEquals(1,restarts);assertEquals(listOf("\u0001"),sent)
        val fresh=OneShotInputGate({false},{false},{fail("Normal draft input must not restart")})
        assertFalse(fresh.text("normal draft"));assertFalse(fresh.delete(1,0))
    }
    @Test fun backspaceAndRetirementDoNotConsumeOtherEdits() {
        var deletes=0;var resets=0
        val gate=OneShotInputGate({false},{deletes++;true},{resets++})
        assertFalse(gate.delete(0,1));assertFalse(gate.delete(2,0))
        assertTrue(gate.delete(1,0));assertTrue(gate.delete(1,0))
        assertEquals(1,deletes);assertEquals(1,resets)
        val retired=OneShotInputGate({fail("Retired input must not reach the new target");false},{false},{})
        retired.retire();assertTrue(retired.text("c"));assertTrue(retired.delete(1,0))
    }
}
