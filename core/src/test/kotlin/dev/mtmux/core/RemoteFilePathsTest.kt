package dev.mtmux.core

import org.junit.Test
import kotlin.test.*

class RemoteFilePathsTest {
    @Test fun pathsAreDataAndNormalizeOnlyRemoteSegments() {
        assertEquals("/home/u/project/doc/中文 a.md",RemoteFilePaths.resolve("./doc/../doc/中文 a.md","/home/u/project","/home/u"))
        assertEquals("/home/u/a.md",RemoteFilePaths.resolve("~/a.md","/tmp","/home/u"))
        assertEquals("/a.md",RemoteFilePaths.resolve("../../../a.md","/tmp","/home/u"))
        assertEquals("/tmp/\$(touch nope);.md",RemoteFilePaths.resolve("\$(touch nope);.md","/tmp","/home/u"))
        assertFailsWith<RemoteFileException> {RemoteFilePaths.resolve("a\u0000.md","/tmp","/home/u")}
    }
    @Test fun canonicalResourceRootDoesNotAcceptSiblingPrefix() {
        assertTrue(RemoteFilePaths.inside("/project/docs/a.md","/project"))
        assertFalse(RemoteFilePaths.inside("/project-secret/a.md","/project"))
        assertTrue(RemoteFilePaths.inside("/a.md","/"))
        assertEquals("file.md",RemoteFilePaths.withoutLine("file.md:21:8"))
        assertEquals("file:21.md",RemoteFilePaths.withoutLine("file:21.md"))
        assertFailsWith<RemoteFileException> {RemoteFilePaths.kind("a.md.sh")}
    }
}
