package dev.mtmux.core

import org.junit.Test
import kotlin.test.*

class AgentSignalsTest {
    @Test fun `snapshot lights require explicit cues and prefer newer evidence`() {
        assertEquals(TaskState.ATTENTION,TaskStates.inspect("Do you want to proceed?\n1. Yes\n2. No"))
        assertEquals(TaskState.ATTENTION,TaskStates.inspect("是否允许执行此操作？"))
        assertEquals(TaskState.ATTENTION,TaskStates.inspect("Error: rate limit exceeded"))
        assertEquals(TaskState.RUNNING,TaskStates.inspect("Thinking..."))
        assertEquals(TaskState.RUNNING,TaskStates.inspect("Working (esc to interrupt)"))
        assertEquals(TaskState.COMPLETED,TaskStates.inspect("Task completed successfully"))
        assertEquals(TaskState.COMPLETED,TaskStates.inspect("Error: old\nWorked for 1m 2s"))
        assertEquals(TaskState.ATTENTION,TaskStates.inspect("Task completed\nDo you want to continue?"))
        assertEquals(TaskState.UNKNOWN,TaskStates.inspect("$ echo Task completed"))
        assertEquals(TaskState.UNKNOWN,TaskStates.inspect("$ "))
        assertEquals(TaskState.UNKNOWN,TaskStates.inspect("Error: stale\n"+(1..15).joinToString("\n") {"ordinary $it"}))
        assertEquals(TaskState.UNKNOWN,TaskStates.aggregate(listOf(TaskState.COMPLETED,TaskState.UNKNOWN)))
        assertEquals(TaskState.RUNNING,TaskStates.aggregate(listOf(TaskState.COMPLETED,TaskState.RUNNING)))
        assertEquals(TaskState.ATTENTION,TaskStates.aggregate(listOf(TaskState.ATTENTION,TaskState.RUNNING)))
        assertEquals(TaskState.COMPLETED,TaskStates.aggregate(listOf(TaskState.COMPLETED,TaskState.COMPLETED)))
    }
    @Test fun `reported Kiro and OpenCode layouts and active controls`() {
        val kiro = "回答已输出。\n────────────────────────\nkiro_default · claude-opus-5 · ◑ 31%\n~/project/example · (master)\n›  ask a question or describe a task ↵\n/copy to clipboard"
        val open = "回答已输出。\n▣  Plan · Model · 9.3s\n┃\n┃ Plan · Model\n╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n24.7K (2%) ·ctrl+p\ncommands"
        assertEquals(TaskState.COMPLETED, TaskStates.inspect(kiro))
        assertEquals(TaskState.COMPLETED, TaskStates.inspect(open))
        assertEquals(TaskState.RUNNING, TaskStates.inspect(open + "\nesc interrupt", "opencode"))
        assertEquals(TaskState.RUNNING, TaskStates.inspect(open + "\nesc again to interrupt", "opencode"))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect(open + "\nPermission required\nAllow once Allow always Reject", "opencode"))
        assertEquals(TaskState.RUNNING, TaskStates.inspect("kiro_default\n› Kiro is working · Type to queue"))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect("Yes, single permission\nTrust, always allow in this session\nKiro is working · Type to queue", "kiro-cli"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect("ordinary output", "opencode"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect("ordinary output", "kiro-cli"))
    }
    @Test fun `Orca inspired title signals ignore names and stale shell titles`() {
        assertEquals(TaskState.COMPLETED, TaskStates.inspect("", "opencode", "OC | 项目概览"))
        assertEquals(TaskState.RUNNING, TaskStates.inspect("", "opencode", "⠋ OC | 项目概览"))
        assertEquals(TaskState.RUNNING, TaskStates.inspect("", "node", "ssh | ⠸ OC | 项目概览"))
        assertEquals(TaskState.COMPLETED, TaskStates.inspect("", "opencode", "OC | fix waiting spinner ⠋"))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect("Permission required\nAllow once", "opencode", "OC | 项目概览"))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect("Error: capacity unavailable", "opencode", "OC | 项目概览"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect("$ ", "bash", "OC | previous task"))
        assertEquals(TaskState.UNKNOWN, AgentTitle.inspect("~/codex/ready"))
        assertEquals(TaskState.UNKNOWN, AgentTitle.inspect("codex-project"))
        assertEquals(TaskState.UNKNOWN, AgentTitle.inspect("kiro"))
        assertEquals(TaskState.UNKNOWN, AgentTitle.inspect("⠋ arbitrary terminal"))
        assertEquals(TaskState.RUNNING, AgentTitle.inspect("⠋ Codex"))
        assertEquals(TaskState.ATTENTION, AgentTitle.inspect("Codex - action required"))
        assertEquals(TaskState.COMPLETED, AgentTitle.inspect("Codex ready"))
    }
    @Test fun `Codex reported composer needs footer and active controls override it`() {
        val screen = "回答结束。\n  10:56 PM\n\n› Ask Codex to do anything\n\n  GPT-6-Astra medium · ~/project…  ⚠ 5 · f2\n[mtmux] project | mtmux"
        assertEquals(TaskState.COMPLETED, TaskStates.inspect(screen))
        assertEquals(TaskState.COMPLETED, TaskStates.inspect(screen, "codex", "host"))
        assertEquals(TaskState.RUNNING, TaskStates.inspect("• Working (2s · esc to interrupt)\n"+screen))
        assertEquals(TaskState.RUNNING, TaskStates.inspect(screen+"\ntab to queue message"))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect("Would you like to proceed?\n1. Yes\n2. No\n"+screen))
        assertEquals(TaskState.ATTENTION, TaskStates.inspect("Error: authentication failed\n"+screen))
        assertEquals(TaskState.RUNNING, TaskStates.inspect(screen, "codex", "⠋ Codex"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect("› Ask Codex to do anything"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect("$ echo Ask Codex to do anything\nGPT-6-Astra medium · ~/project"))
        assertEquals(TaskState.UNKNOWN, TaskStates.inspect(screen, "bash"))
        assertEquals(TaskState.COMPLETED, TaskStates.inspect(screen.replace("GPT-6-Astra", "gpt-5.4")))
    }
}
