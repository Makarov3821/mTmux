package dev.mtmux.core

import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class DiagnosticsTest {
    @Test fun `journal rotates persists clears and stays bounded`() {
        val dir=Files.createTempDirectory("journal-test").toFile()
        try {
            val journal=DiagnosticJournal(dir,128)
            repeat(80) { journal.append("event=$it "+"a".repeat(20)) }
            assertTrue(dir.listFiles()!!.sumOf {it.length()} <= 256)
            assertFalse(journal.snapshot().contains("event=0 "))
            assertTrue(journal.snapshot().contains("event=79 "))
            assertEquals(journal.snapshot(),DiagnosticJournal(dir,128).snapshot())
            journal.clear();assertEquals("",journal.snapshot())
            journal.append("a\nb\r");assertEquals("a b \n",journal.snapshot())
        } finally {dir.deleteRecursively()}
    }
    @Test fun `pasted private keys normalize CRLF reject public and oversized text`() {
        val key="-----BEGIN OPENSSH PRIVATE KEY-----\r\nYWJj\r\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals(key.replace("\r\n","\n")+"\n",PrivateKeyText.decode("  $key  ").toString(Charsets.UTF_8))
        assertFailsWith<IllegalArgumentException> {PrivateKeyText.decode("ssh-ed25519 AAAA public")}
        assertFailsWith<IllegalArgumentException> {PrivateKeyText.decode(key.replace("END OPENSSH", "END RSA"))}
        assertFailsWith<IllegalArgumentException> {PrivateKeyText.decode("x".repeat(65537))}
        assertTrue(PrivateKeyText.decode(key.replace("OPENSSH", "ENCRYPTED")).isNotEmpty())
    }
    @Test fun `default journal rotates at five MiB with ten MiB total cap`() {
        val dir=Files.createTempDirectory("journal-capacity-test").toFile()
        try {
            val journal=DiagnosticJournal(dir)
            assertEquals(5*1024*1024,journal.limit)
            val current=java.io.File(dir,"current.log")
            val previous=java.io.File(dir,"previous.log")
            current.writeBytes(ByteArray(journal.limit-4) { 'a'.code.toByte() })
            journal.append("abc")
            assertEquals(journal.limit.toLong(),current.length())
            assertFalse(previous.exists())
            journal.append("next")
            assertEquals(journal.limit.toLong(),previous.length())
            assertEquals("next\n",current.readText())
            current.writeBytes(ByteArray(journal.limit) { 'b'.code.toByte() })
            assertEquals(10*1024*1024L,dir.listFiles()!!.sumOf {it.length()})
            journal.append("latest")
            assertEquals('b',previous.readText().first())
            assertEquals("latest\n",current.readText())
            assertTrue(dir.listFiles()!!.sumOf {it.length()}<=10*1024*1024L)
            assertTrue(journal.snapshot().endsWith("latest\n"))
        } finally {dir.deleteRecursively()}
    }
}
