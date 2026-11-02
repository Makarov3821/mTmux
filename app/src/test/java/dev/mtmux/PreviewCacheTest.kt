package dev.mtmux

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import dev.mtmux.core.RemoteFileException

class PreviewCacheTest {
    @Test fun cacheEvictsLeastRecentlyUsedInsteadOfRefusingMoreFiles() {
        val directory=Files.createTempDirectory("preview-lru").toFile()
        try {
            val cache=PreviewDiskCache(30)
            fun put(key:String)=File(directory,key).apply {writeBytes(ByteArray(10));cache.put(key,this)}
            val a=put("a");val b=put("b");put("c")
            cache.get("a");cache.reserve(10);put("d")
            assertTrue(a.exists());assertFalse(b.exists());assertNull(cache.get("b"));assertEquals(30,cache.bytes)
            repeat(50) {put("next-$it")}
            assertEquals(30,cache.bytes);assertEquals(3,directory.listFiles()!!.size)
        } finally {directory.deleteRecursively()}
    }
    @Test fun cachedFileCanBeEvictedWhileItsOpenedResponseIsStillBeingRead() {
        val directory=Files.createTempDirectory("preview-stream").toFile()
        try {
            val cache=PreviewDiskCache(4)
            val old=File(directory,"old").apply {writeText("test");cache.put("old",this)}
            old.inputStream().use {stream ->
                cache.reserve(4)
                File(directory,"new").apply {writeText("next");cache.put("new",this)}
                assertFalse(old.exists());assertEquals("test",stream.readBytes().toString(Charsets.UTF_8))
            }
        } finally {directory.deleteRecursively()}
    }
    @Test fun oversizedCacheEntryIsRejectedWithoutThrowingAwayExistingFiles() {
        val directory=Files.createTempDirectory("preview-budget").toFile()
        try {
            val cache=PreviewDiskCache(4)
            val old=File(directory,"old").apply {writeText("test");cache.put("old",this)}
            try {cache.reserve(5);fail("oversized reservation")} catch(error:RemoteFileException) {
                assertEquals(5L,error.actualBytes);assertEquals(4L,error.limitBytes)
            }
            assertTrue(old.exists())
        } finally {directory.deleteRecursively()}
    }
    @Test fun newLimitDefaultsAndCorruptPreferencesRemainConsistent() {
        assertEquals(10L*1024*1024,PreviewLimits().fileBytes)
        assertEquals(50L*1024*1024,PreviewLimits().cacheBytes)
        assertEquals(PreviewLimits(1,1),PreviewLimits.normalized(-4,-2))
        assertEquals(PreviewLimits(100,100),PreviewLimits.normalized(Int.MAX_VALUE,3))
    }
    @Test fun cacheRecoversIfAndroidRemovesAnOldTemporaryFile() {
        val directory=Files.createTempDirectory("preview-cleared").toFile()
        try {
            val cache=PreviewDiskCache(4)
            val old=File(directory,"old").apply {writeText("test");cache.put("old",this)}
            old.delete();assertNull(cache.get("old"));assertEquals(0L,cache.bytes)
            File(directory,"new").apply {writeText("next");cache.put("new",this)}
            assertEquals(4L,cache.bytes)
        } finally {directory.deleteRecursively()}
    }
    @Test fun lateViewportPlansAndImageRequestsCannotReviveCancelledWork() {
        val transfers=PreviewTransfers()
        transfers.update(1,1);val first=transfers.begin(1)
        transfers.update(3,3);assertTrue(first.isCancelled)
        transfers.update(2,2)
        try {transfers.begin(2);fail("late request started")} catch(_:RemoteFileException) {}
        val current=transfers.begin(3);assertFalse(current.isCancelled)
        transfers.close();assertTrue(current.isCancelled)
        try {transfers.begin(3);fail("closed view started a request")} catch(_:RemoteFileException) {}
    }
}
