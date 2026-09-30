package dev.mtmux.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

/** Opt-in: scripts/test_ssh.py creates a loopback-only, disposable OpenSSH fixture. */
class SshIntegrationTest {
    private fun login(encrypted: Boolean = false): Login {
        assumeTrue("Run scripts/test_ssh.py to provision the fixture", System.getenv("MTMUX_TEST_PORT") != null)
        return Login("127.0.0.1", System.getenv("MTMUX_TEST_PORT").toInt(), System.getenv("MTMUX_TEST_USER"), "",
            File(System.getenv(if (encrypted) "MTMUX_TEST_ENCRYPTED_KEY" else "MTMUX_TEST_KEY")).readBytes(),
            if (encrypted) "p0-test-only" else "")
    }
    private fun trusted() = SshClient(object : PinStore {
        override fun get(endpoint: String) = System.getenv("MTMUX_TEST_HOST_KEY")
    })

    @Test fun `pump reports exit status for a normal exit and -1 when the connection drops`() {
        for ((command, expected) in listOf("printf done; exit 3" to 3, "printf done; kill -9 \$PPID" to -1)) {
            trusted().use { client ->
                client.connect(login())
                client.openTerminal(command, 80, 24)
                var status = Int.MIN_VALUE
                val reader = thread(isDaemon = true) { runCatching { status = client.pump { } } }
                reader.join(10_000)
                assertFalse(reader.isAlive, command)
                assertEquals(expected, status, command)
            }
        }
    }

    @Test fun `invalid key fails locally with safe location and authentication guidance`() {
        val first=login()
        trusted().use { client ->
            val failure=assertFailsWith<ConnectionFailure> {
                client.connect(Login(first.host,first.port,first.user,"", "PRIVATE_INVALID_MARKER".toByteArray()))
            }
            assertEquals(0,failure.hop)
            assertEquals(FailureReason.PRIVATE_KEY,failure.reason)
            assertFalse(failure.message!!.contains("PRIVATE_INVALID_MARKER"))
        }
    }

    @Test fun `two jump hosts verify each identity and carry exec and PTY without direct fallback`() {
        val first = login()
        val second = login(true)
        val target = Login(first.host,first.port,first.user,"",first.privateKey,jumps=listOf(first,second))
        val verified = mutableSetOf<String>()
        SshClient(object : PinStore { override fun get(endpoint: String): String? {
            verified.add(endpoint); return System.getenv("MTMUX_TEST_HOST_KEY")
        } }).use { client ->
            client.connect(target)
            assertEquals(3,verified.size)
            assertTrue(verified.contains(target.trustEndpoint))
            assertEquals("jump-exec",client.exec("printf jump-exec").output)
            assertTrue(client.discoverPanes(System.getenv("MTMUX_TEST_TMUX")).isNotEmpty())
            val token=client.openTerminal("stty -echo; read answer; printf 'JUMP:%s' \"\$answer\"",80,24)
            val result=java.io.ByteArrayOutputStream()
            val done=CountDownLatch(1)
            val reader=thread(isDaemon=true) { try { client.pump { result.write(it) } } finally { done.countDown() } }
            Thread.sleep(100)
            client.send(token,"中文\r".toByteArray())
            assertTrue(done.await(8,TimeUnit.SECONDS)); reader.join(1000)
            assertTrue(result.toString("UTF-8").contains("JUMP:中文"))
        }
        SshClient(object : PinStore { override fun get(endpoint:String) = if(endpoint==target.trustEndpoint) "changed" else System.getenv("MTMUX_TEST_HOST_KEY") }).use { client ->
            val failure=assertFailsWith<HostKeyRejected> { client.connect(target) }
            assertEquals(target.trustEndpoint,failure.challenge.endpoint)
        }
        val bad=Login(first.host,first.port,first.user,"bad-password")
        trusted().use { client ->
            val failure=assertFailsWith<ConnectionFailure> { client.connect(Login(first.host,first.port,first.user,"",first.privateKey,jumps=listOf(first,bad))) }
            assertEquals(2,failure.hop)
            assertEquals(FailureReason.AUTH,failure.reason)
            assertFailsWith<IllegalStateException> { client.exec("printf must-not-fallback") }
        }
    }

    @Test fun `server restart with identical session and pane IDs cannot resume old task`() {
        val login = login()
        val directory = java.nio.file.Files.createTempDirectory("mtmux-restart-").toFile()
        val wrapper = File(directory,"tmux-test")
        wrapper.writeText("#!/bin/sh\nexec tmux -S " + Tmux.quote(File(directory,"socket").path) + " \"\$@\"\n")
        wrapper.setExecutable(true)
        val path = wrapper.absolutePath
        trusted().use { control ->
            control.connect(login)
            val tmux = Tmux.quote(path)
            try {
                assertEquals(0,control.exec("$tmux new-session -d -s same-name 'sleep 300'").status)
                val original = control.bindPane("$0",path)
                control.exec("$tmux kill-server")
                Thread.sleep(100)
                assertEquals(0,control.exec("$tmux new-session -d -s same-name 'sleep 300'").status)
                val replacement = control.bindPane("$0",path)
                assertEquals(original.pane,replacement.pane)
                assertEquals(original.session,replacement.session)
                assertNotEquals(original.identity,replacement.identity)
                assertFailsWith<IllegalStateException> { control.verifyResume(original,path) }
                control.verifyResume(replacement,path)
            } finally {
                control.exec("$tmux kill-server")
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `resume restores exact window and pane without sending input or accepting rebuilt targets`() {
        val login = login()
        val path = System.getenv("MTMUX_TEST_TMUX")
        trusted().use { control ->
            control.connect(login)
            val tmux = Tmux.quote(path)
            val session = control.exec("$tmux new-session -d -P -F '#{session_id}' -s resume-test 'stty -echo; cat'").output.trim()
            try {
                val original = control.bindPane(session, path)
                control.exec("$tmux new-window -t ${Tmux.quote(session)} -n elsewhere 'stty -echo; cat'")
                assertNotEquals(original.pane, control.bindPane(session, path).pane)
                trusted().use { phone ->
                    phone.connect(login)
                    phone.verifyResume(original, path)
                    phone.openTerminal(Tmux.resume(original, path), 80, 24)
                    repeat(50) { if (control.bindPane(session, path) != original) Thread.sleep(20) }
                    assertEquals(original, control.bindPane(session, path))
                    assertTrue(control.exec(Tmux.history(original.pane, path)).output.isBlank())
                }
                control.exec("$tmux respawn-pane -k -t ${Tmux.quote(original.pane)} 'stty -echo; cat'")
                assertFailsWith<IllegalStateException> { control.verifyResume(original, path) }
                val fresh = control.bindPane(session, path)
                assertNotEquals(original, fresh)
                control.exec("$tmux kill-pane -t ${Tmux.quote(original.pane)}")
                assertFailsWith<IllegalStateException> { control.verifyResume(original, path) }
                val forgedServer = PaneBinding.parse(session, fresh.identity.replaceBefore(':', "99999999"))
                assertFailsWith<IllegalStateException> { control.verifyResume(forgedServer, path) }
            } finally { control.exec("$tmux kill-session -t ${Tmux.quote(session)}") }
        }
    }

    @Test fun `bound replies never follow desktop focus and reject stale targets`() {
        val login = login()
        val path = System.getenv("MTMUX_TEST_TMUX")
        trusted().use { client ->
            client.connect(login)
            val tmux = Tmux.quote(path)
            val session = client.exec("$tmux new-session -d -P -F '#{session_id}' -s bound-test -x 80 -y 24 'stty -echo; cat'").output.trim()
            try {
                val binding = client.bindPane(session, path)
                val epoch = client.openTerminal(Tmux.attach(session, path), 80, 24)
                val second = client.exec("$tmux split-window -h -P -F '#{pane_id}' -t ${Tmux.quote(binding.pane)} 'stty -echo; cat'").output.trim()
                fun capture(pane: String) = client.exec(Tmux.history(pane, path)).output
                Thread.sleep(150)
                assertFalse(client.sendToPane(epoch, binding, path, "MUST_NOT_SEND\n".toByteArray()))
                assertFalse(capture(second).contains("MUST_NOT_SEND"))
                assertFalse(capture(binding.pane).contains("MUST_NOT_SEND"))
                client.exec("$tmux select-pane -t ${Tmux.quote(binding.pane)}")
                val text = "BOUND_ONLY 中文 ; \$(literal) ' \" #{}\n"
                assertTrue(client.sendToPane(epoch, binding, path, text.toByteArray()))
                Thread.sleep(150)
                assertTrue(capture(binding.pane).contains("BOUND_ONLY 中文"))
                assertFalse(capture(second).contains("BOUND_ONLY"))
                val stale = PaneBinding.parse(session, binding.identity.replaceBefore(':', "99999999"))
                assertFalse(client.sendToPane(epoch, stale, path, "STALE\n".toByteArray()))
                client.exec("$tmux copy-mode -t ${Tmux.quote(binding.pane)}")
                assertFalse(client.sendToPane(epoch, binding, path, "IN_COPY\n".toByteArray()))
                assertEquals("MTMUX_CHANGED",client.exec(Tmux.leaveCopyMode(stale,path)).output.trim())
                assertEquals("1",client.exec("$tmux display-message -p -t ${Tmux.quote(binding.target)} '#{pane_in_mode}'").output.trim())
                assertEquals("MTMUX_LATEST",client.exec(Tmux.leaveCopyMode(binding,path)).output.trim())
                assertEquals("0",client.exec("$tmux display-message -p -t ${Tmux.quote(binding.target)} '#{pane_in_mode}'").output.trim())
                client.exec("$tmux set-window-option -t ${Tmux.quote(session)} synchronize-panes on")
                assertFalse(client.sendToPane(epoch, binding, path, "BROADCAST\n".toByteArray()))
                client.exec("$tmux set-window-option -t ${Tmux.quote(session)} synchronize-panes off")
                client.exec("$tmux respawn-pane -k -t ${Tmux.quote(binding.pane)} 'stty -echo; cat'")
                assertFalse(client.sendToPane(epoch, binding, path, "REUSED_ID\n".toByteArray()))
                assertFalse(capture(binding.pane).contains("REUSED_ID"))
                client.exec("$tmux kill-pane -t ${Tmux.quote(binding.pane)}")
                assertFalse(client.sendToPane(epoch, binding, path, "DELETED\n".toByteArray()))
                assertFalse(capture(second).contains("DELETED"))
                assertFalse(client.exec("$tmux list-buffers -F '#{buffer_name}'").output.contains("mtmux-"))
                client.invalidateInput()
                assertFailsWith<IllegalStateException> { client.sendToPane(epoch, binding, path, "OFFLINE".toByteArray()) }
            } finally { client.exec("$tmux kill-session -t ${Tmux.quote(session)}") }
        }
    }

    @Test fun `unknown and changed host keys block before authentication`() {
        val login = login()
        SshClient(object : PinStore { override fun get(endpoint: String): String? = null }).use {
            val error = assertFailsWith<HostKeyRejected> { it.connect(login) }
            assertFalse(error.challenge.changed)
        }
        SshClient(object : PinStore { override fun get(endpoint: String) = "different-key" }).use {
            val error = assertFailsWith<HostKeyRejected> { it.connect(login) }
            assertTrue(error.challenge.changed)
        }
    }

    @Test fun `ed25519 and encrypted OpenSSH keys authenticate and execute UTF8`() {
        listOf(false, true).forEach { encrypted ->
            val login = login(encrypted)
            trusted().use { client ->
                client.connect(login)
                val response = client.exec("printf '%s' '中文 Agent'; printf '%s' 'stderr' >&2; exit 7")
                assertEquals(7, response.status)
                assertEquals("中文 Agent", response.output)
                assertEquals("stderr", response.error)
            }
        }
    }

    @Test fun `PTY resize input and explicit disconnect work without replay`() {
        val login = login()
        trusted().use { client ->
            client.connect(login)
            val token = client.openTerminal("stty -echo; printf 'READY\\n'; read line; stty size; printf '%s\\n' \"\$line\"", 80, 24)
            val ready = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val output = java.io.ByteArrayOutputStream()
            var failure: Throwable? = null
            val reader = thread(isDaemon = true) {
                try { client.pump { bytes ->
                    synchronized(output) { output.write(bytes); if (output.toString("UTF-8").contains("READY")) ready.countDown() }
                } } catch (error: Throwable) { failure = error }
                finally { finished.countDown() }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            client.resize(100, 35)
            client.send(token, "中文回复\r".toByteArray())
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            reader.join(1000)
            failure?.let { throw it }
            val text = output.toString("UTF-8")
            assertTrue(text.contains("35 100"), text)
            assertTrue(text.contains("中文回复"), text)
            assertFailsWith<IllegalStateException> { client.send(token, "never-replay".toByteArray()) }
        }
    }

    @Test fun `ordinary shell executes draft and enter as one write and returns output`() {
        val login = login()
        trusted().use { client ->
            client.connect(login)
            val token = client.openTerminal(null, 80, 24)
            val received = CountDownLatch(1)
            val output = java.io.ByteArrayOutputStream()
            val reader = thread(isDaemon = true) {
                runCatching { client.pump { bytes ->
                    output.write(bytes)
                    if (output.toString("UTF-8").contains("MTMUX_READY")) received.countDown()
                } }
            }
            // The marker itself is absent from tty echo, so only execution passes.
            client.send(token, "printf '\\115\\124\\115\\125\\130_READY\\n'\r".toByteArray())
            assertTrue(received.await(10, TimeUnit.SECONDS), "No command output from shell PTY")
            client.close()
            reader.join(2000)
            assertFalse(reader.isAlive, "PTY reader did not stop after disconnect")
        }
    }
    @Test fun `named discovery and history do not alter tmux focus or execute names`() {
        val login = login()
        val path = System.getenv("MTMUX_TEST_TMUX") ?: error("Missing isolated tmux fixture")
        trusted().use { client ->
            client.connect(login)
            val first = client.discoverPanes(path).single()
            assertEquals("测试会话", first.sessionName)
            assertEquals("工作窗口", first.windowName)
            val unusual = "中文\t'\\\"\n" + '$' + "(echo MUST_NOT_EXECUTE)\n"
            val renamed = client.exec("${Tmux.quote(path)} rename-window -t ${Tmux.quote(first.window)} ${Tmux.quote(unusual)}")
            assertEquals(0, renamed.status)
            try {
                val named = client.discoverPanes(path).single()
                // tmux may normalize control characters when storing a window name.
                // Compare against the actual local server value, not a guessed normalization.
                val storedName = ProcessBuilder(path, "-u", "display-message", "-p", "-t", first.id, "#{window_name}")
                    .start().inputStream.bufferedReader().readText().removeSuffix("\n")
                assertEquals(storedName, named.windowName)
                val snapshot = client.discoverTaskSnapshots(path).single()
                assertEquals(storedName.take(512), snapshot.windowName)
                assertEquals(first.sessionName, snapshot.sessionName)
                assertTrue(snapshot.readable)
                assertEquals(first.id, snapshot.binding.pane)
                assertTrue(named.windowName.contains("中文"))
                assertTrue(named.windowName.contains('$' + "(echo MUST_NOT_EXECUTE)"))
                assertEquals(first.id, named.id)
                val capture = client.exec(Tmux.history(named.id, path))
                assertEquals(0, capture.status)
                assertTrue(capture.output.contains("BEFORE_ATTACH_HISTORY"))
                assertEquals(first.active, client.discoverPanes(path).single().active)
                assertEquals(0, client.exec("${Tmux.quote(path)} new-session -d -t ${Tmux.quote(first.session)} -s linked-test").status)
                try {
                    val linked = client.discoverPanes(path)
                    assertEquals(setOf("测试会话", "linked-test"), linked.map { it.sessionName }.toSet())
                    assertTrue(linked.all { it.id == first.id })
                    val snapshots = client.discoverTaskSnapshots(path)
                    assertEquals(linked.map { it.sessionName }.toSet(), snapshots.map { it.sessionName }.toSet())
                    assertEquals(2, snapshots.map { it.binding.identity }.distinct().size)
                } finally { client.exec("${Tmux.quote(path)} kill-session -t linked-test") }
                assertFailsWith<IllegalArgumentException> { Tmux.history("%1;kill-server", path) }
            } finally { client.exec("${Tmux.quote(path)} rename-window -t ${Tmux.quote(first.window)} '工作窗口'") }
        }
    }

    @Test fun `batch snapshots arrive incrementally and preserve copy mode`() {
        val login = login()
        val path = System.getenv("MTMUX_TEST_TMUX")!!
        val tmux = Tmux.quote(path)
        trusted().use { client ->
            client.connect(login)
            var session = ""
            try {
                session = client.exec("$tmux new-session -d -P -F '#{session_id}' -s batch-test 'printf \"Task completed successfully\\n\"; sleep 120'").output.trim()
                val examples = listOf(
                    "kiro_default · model · 31%\n› ask a question or describe a task ↵\n/copy to clipboard",
                    "▣ Plan · Model · 9.3s\n┃ Plan · Model\nctrl+p commands",
                    "\u001b]0;⠋ OC | fixture task\u0007busy output",
                    "回答结束。\n10:56 PM\n› Ask Codex to do anything\nGPT-6-Astra medium · ~/project…  ⚠ 5 · f2"
                )
                repeat(8) { index ->
                    val script = "printf '%s\\n' ${Tmux.quote(examples.getOrElse(index) { "ordinary output" })}; sleep 120"
                    assertEquals(0, client.exec("$tmux new-window -d -t ${Tmux.quote(session)} ${Tmux.quote(script)}").status)
                }
                assertEquals(0, client.exec("$tmux copy-mode -t ${Tmux.quote(session)}").status)
                val focus = "$tmux display-message -p -t ${Tmux.quote(session)} '#{window_id}:#{pane_id}:#{pane_in_mode}'"
                val before = client.exec(focus).output
                val batches = mutableListOf<Int>()
                val start = System.nanoTime()
                val snapshots = client.discoverTaskSnapshots(path) { batches.add(it.size) }
                println("BATCH_PROBE panes=${snapshots.size} channels=${2+batches.size} elapsedMs=${(System.nanoTime()-start)/1_000_000}")
                assertEquals(listOf(8,2), batches)
                assertEquals(9, snapshots.count { it.binding.session == session })
                assertTrue(snapshots.all { it.readable })
                val states = snapshots.filter { it.binding.session == session }.map { it.state }
                assertEquals(4, states.count { it == TaskState.COMPLETED })
                assertEquals(1, states.count { it == TaskState.RUNNING })
                assertEquals(before, client.exec(focus).output)
            } finally { if (session.startsWith("$")) client.exec("$tmux kill-session -t ${Tmux.quote(session)}") }
        }
    }
}
