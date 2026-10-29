package dev.mtmux.core

import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TmuxDoctorTest {
    private fun exec(command: String): ExecResult {
        val process = ProcessBuilder("sh", "-c", command).apply { environment().remove("XDG_CONFIG_HOME") }.start()
        assertTrue(process.waitFor(8, TimeUnit.SECONDS), "remote fixture timed out")
        return ExecResult(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
    }
    private fun fakeTmux(folder: File) = File(folder, "tmux 'fixture").apply {
        writeText("#!/bin/sh\ncase \"\$1\" in -V) echo 'tmux 3.4';; list-sessions) echo 'no server running on fixture' >&2; exit 1;; *) exit 1;; esac\n")
        setExecutable(true)
    }
    @Test fun `release baseline includes patch suffix and numeric minor`() {
        assertTrue(TmuxRelease.parse("tmux 3.2")!! < TmuxRelease.BASELINE)
        assertEquals(TmuxRelease.BASELINE, TmuxRelease.parse("tmux 3.2a"))
        assertTrue(TmuxRelease.parse("3.10")!! > TmuxRelease.parse("3.9a")!!)
        assertNull(TmuxRelease.parse("next-3.9"))
    }
    @Test fun `availability expires locally and never follows a changed connection revision`() {
        val entry=DoctorAvailabilityRecord("original",1000,TmuxAvailability.UNAVAILABLE)
        assertEquals(TmuxAvailability.UNAVAILABLE,entry.effective("original",1001))
        assertEquals(TmuxAvailability.UNKNOWN,entry.effective("edited",1001))
        assertEquals(TmuxAvailability.UNKNOWN,entry.effective("original",999))
        assertEquals(TmuxAvailability.UNKNOWN,entry.effective("original",1000+DoctorAvailabilityRecord.TTL))
        assertEquals(TmuxAvailability.AVAILABLE,entry.copy(state=TmuxAvailability.AVAILABLE).effective("original",1001))
    }
    @Test fun `initialization requires a fresh compatible ordinary executable and an empty user setup`() {
        val fresh=TmuxDoctorReport(mapOf("path.status" to "ok","version.client" to "tmux 3.4","server.status" to "none",
            "config.status" to "absent","home.safe" to "yes","bootstrap.helpers" to "yes","kernel" to "Linux","config.loader" to "standard"))
        assertTrue(fresh.canInitialize)
        for(change in listOf("config.status" to "exists","server.status" to "ready","version.client" to "tmux 3.1c",
            "home.safe" to "no","bootstrap.helpers" to "no","config.loader" to "unknown")) {
            assertFalse(fresh.copy(facts=fresh.facts+change).canInitialize,"must not initialize: $change")
        }
    }
    @Test fun `byte framed diagnosis survives multiline unicode and rejects corruption`() {
        fun record(key: String, value: String) = "$key:${value.toByteArray().size}:$value\n"
        val output = "MTMUX_DOCTOR_1\n" + record("path", "/测试\n:tmux") + "MTMUX_END\n"
        assertEquals("/测试\n:tmux", TmuxEnvironmentProbe.parse(ExecResult(0, output, ""))["path"])
        for (bad in listOf(output.dropLast(1), output.replace("path:", "path:999"), output.replace("MTMUX_END", record("path", "again") + "MTMUX_END"))) {
            assertFails { TmuxEnvironmentProbe.parse(ExecResult(0, bad, "")) }
        }
    }
    @Test fun `missing executable is distinct from no server and version warning`() {
        val missing = TmuxEnvironmentProbe.parse(exec(TmuxEnvironmentProbe.command("/tmp/mtmux-doctor-no-such-executable")))
        assertEquals(TmuxAvailability.UNAVAILABLE, missing.availability)
        assertFalse(missing.canCreate)
        assertEquals(DoctorGrade.UNKNOWN, missing.findings().single { it.check == DoctorCheck.DEPENDENCIES }.grade)
        val folder = Files.createTempDirectory("doctor-test-").toFile()
        try {
            val report = TmuxEnvironmentProbe.parse(exec(TmuxEnvironmentProbe.command(fakeTmux(folder).path)))
            assertEquals("none", report["server.status"])
            assertTrue(report.canCreate)
            assertEquals(DoctorGrade.PASS, report.findings().single { it.check == DoctorCheck.SESSIONS }.grade)
            val legacy = report.copy(facts = report.facts + ("version.client" to "tmux 3.0a"))
            assertTrue(legacy.belowBaseline)
            assertEquals(DoctorGrade.WARNING, legacy.findings().single { it.check == DoctorCheck.VERSION }.grade)
            assertTrue(legacy.canCreate, "version warning alone must not claim tmux is missing")
        } finally { folder.deleteRecursively() }
    }
    @Test fun `minimal file creation refuses existing configs and undo preserves a backup`() {
        val folder = Files.createTempDirectory("doctor-config-").toFile()
        try {
            val binary = fakeTmux(folder)
            val home = File(folder, "用户 'home").apply { mkdir() }
            val command = TmuxConfigBootstrap.createCommand(binary.path, home.path, 10000, "tmux 3.4")
            val result = exec(command)
            assertEquals(0, result.status, result.error)
            val receipt = TmuxConfigBootstrap.parseCreated(result, home.path, 10000)
            val target = File(home, ".tmux.conf")
            assertEquals(TmuxConfigBootstrap.template(10000), target.readText())
            assertNotEquals(0, exec(command).status)
            val undo = TmuxConfigBootstrap.parseUndone(exec(TmuxConfigBootstrap.undoCommand(receipt)), receipt)
            assertFalse(target.exists())
            assertEquals(TmuxConfigBootstrap.template(10000), File(undo.backup).readText())
            File(home, ".config/tmux").mkdirs()
            File(home, ".config/tmux/tmux.conf").writeText("# user's configuration\n")
            assertNotEquals(0, exec(command).status)
            assertFalse(target.exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun `modified or replaced configs cannot be undone and symlinks cannot be overwritten`() {
        val folder = Files.createTempDirectory("doctor-undo-").toFile()
        try {
            val binary = fakeTmux(folder)
            val home = File(folder, "home").apply { mkdir() }
            val command = TmuxConfigBootstrap.createCommand(binary.path, home.path, 10000, "tmux 3.4")
            val receipt = TmuxConfigBootstrap.parseCreated(exec(command), home.path, 10000)
            val target = File(home, ".tmux.conf")
            target.appendText("# user edit\n")
            assertNotEquals(0, exec(TmuxConfigBootstrap.undoCommand(receipt)).status)
            assertTrue(target.readText().contains("user edit"))
            target.delete()
            val outside = File(folder, "outside").apply { writeText("preserve") }
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            assertNotEquals(0, exec(command).status)
            assertEquals("preserve", outside.readText())
        } finally { folder.deleteRecursively() }
    }
    @Test fun `concurrent initializations publish exactly one complete configuration`() {
        val folder=Files.createTempDirectory("doctor-race-").toFile()
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val binary=fakeTmux(folder)
            val home=File(folder,"home").apply {mkdir()}
            val sizes=listOf(10000,20000)
            val futures=sizes.map {size->workers.submit<ExecResult> {exec(TmuxConfigBootstrap.createCommand(binary.path,home.path,size,"tmux 3.4"))}}
            val results=futures.map {it.get(10,TimeUnit.SECONDS)}
            assertEquals(1,results.count {it.status==0})
            val winner=sizes[results.indexOfFirst {it.status==0}]
            assertEquals(TmuxConfigBootstrap.template(winner),File(home,".tmux.conf").readText())
        } finally {workers.shutdownNow();folder.deleteRecursively()}
    }
    @Test fun `real tmux default bindings and format capabilities are diagnosed without changing them`() {
        val binary=File(System.getenv("MTMUX_DOCTOR_BINARY") ?: "/usr/bin/tmux")
        assumeTrue(binary.canExecute())
        val folder = Files.createTempDirectory("doctor-real-").toFile()
        val socket = File(folder, "socket").path
        val wrapper = File(folder, "tmux").apply {
            writeText("#!/bin/sh\nexec ${Tmux.quote(binary.absolutePath)} -S ${Tmux.quote(socket)} -f /dev/null \"\$@\"\n")
            setExecutable(true)
        }
        try {
            assertEquals(0, exec("${Tmux.quote(wrapper.path)} new-session -d -s doctor 'sleep 30'").status)
            val before = exec("${Tmux.quote(wrapper.path)} list-keys").output
            val report = TmuxEnvironmentProbe.parse(exec(TmuxEnvironmentProbe.command(wrapper.path)))
            assertEquals(DoctorGrade.PASS, report.findings().single { it.check == DoctorCheck.PROTOCOL }.grade)
            assertEquals(DoctorGrade.PASS, report.findings().single { it.check == DoctorCheck.WHEEL }.grade)
            assertEquals(DoctorGrade.WARNING, report.findings().single { it.check == DoctorCheck.MOUSE }.grade)
            assertFalse(report.canInitialize)
            assertEquals(before, exec("${Tmux.quote(wrapper.path)} list-keys").output)
            assertEquals(0,exec("${Tmux.quote(wrapper.path)} bind-key -T copy-mode WheelUpPane run-shell 'echo scroll-up -N123SENSITIVE_FIXTURE'").status)
            val custom=TmuxEnvironmentProbe.parse(exec(TmuxEnvironmentProbe.command(wrapper.path)))
            assertEquals(DoctorGrade.WARNING,custom.findings().single {it.check==DoctorCheck.WHEEL}.grade)
            assertTrue(custom.facts.values.none {"SENSITIVE_FIXTURE" in it})
            assertEquals("",custom["step.copy.up"])
        } finally { exec("${Tmux.quote(wrapper.path)} kill-server"); folder.deleteRecursively() }
    }
}
