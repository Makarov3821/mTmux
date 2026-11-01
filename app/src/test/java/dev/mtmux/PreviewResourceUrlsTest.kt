package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class PreviewResourceUrlsTest {
    private val base="https://preview.mtmux.invalid/session/document/remote/home/u/readme.md"
    private val urls=PreviewResourceUrls(base,"dev.example")
    @Test fun absoluteAndFileReferencesUseTheCurrentRemoteRoute() {
        val expected="https://preview.mtmux.invalid/session/document/remote/home/u/a%20b.png"
        assertEquals(expected,urls.rewrite("/home/u/a b.png"))
        assertEquals(expected,urls.rewrite("file:///home/u/a%20b.png"))
        assertEquals(expected,urls.rewrite("file://localhost/home/u/a%20b.png"))
        assertEquals(expected,urls.rewrite("file://DEV.EXAMPLE/home/u/a%20b.png"))
        assertEquals("pic.png",urls.rewrite("pic.png"))
        assertEquals("//other.example/pic.png",urls.rewrite("//other.example/pic.png"))
        assertNull(urls.rewrite("file://other.example/home/u/pic.png"))
        assertNull(urls.rewrite("file://user@dev.example/home/u/pic.png"))
        assertNull(urls.rewrite("file:///home/u/a%00.png"))
        assertNull(urls.rewrite("file:relative.png"))
    }
    @Test fun escapedCharactersDecodeOnceAndVirtualAssetsAreNotRemoteFiles() {
        assertEquals("https://preview.mtmux.invalid/session/document/remote/home/u/%2520.png",urls.rewrite("file:///home/u/%2520.png"))
        assertEquals("/home/u/a.png",urls.remotePath("https","preview.mtmux.invalid","/home/u/a.png"))
        assertNull(urls.remotePath("https","other.example","/home/u/a.png"))
        assertNull(urls.remotePath("https","preview.mtmux.invalid","/session/document/assets/preview.js"))
        assertNull(urls.remotePath("https","preview.mtmux.invalid","/session/other/remote/home/u/a.png"))
    }
    @Test fun htmlRewritingPreservesQuotesCommentsRawTextAndRelativeResources() {
        val source="""<!-- <img src="file:///comment.png"> --><title>literal <img src="file:///title.png"></title><script>const s='<img src="file:///script.png">';</script><style>p:after{content:'<img src="file:///css.png">'}</style><IMG title='greater > less' SRC='file:///home/u/a%20b.png'><img src=/home/u/c.png><img src=relative.png><a href="/home/u/next.md">next</a>"""
        val mapped=urls.html(source)
        assertTrue(mapped.contains("<!-- <img src=\"file:///comment.png\"> -->"))
        assertTrue(mapped.contains("<title>literal <img src=\"file:///title.png\"></title>"))
        assertTrue(mapped.contains("const s='<img src=\"file:///script.png\">';"))
        assertTrue(mapped.contains("content:'<img src=\"file:///css.png\">'"))
        assertTrue(mapped.contains("SRC=\"https://preview.mtmux.invalid/session/document/remote/home/u/a%20b.png\""))
        assertTrue(mapped.contains("src=relative.png"))
        assertFalse(mapped.contains("src=/home/u/c.png"))
        assertTrue(urls.html("<img src='file&#58;///home/u/a.png'>") {it.replace("&#58;",":")}.contains("/remote/home/u/a.png"))
        assertEquals("<img src=\"\">",urls.html("<img src='file://other.example/home/u/a.png'>"))
        assertEquals("<img src='file:///unfinished",urls.html("<img src='file:///unfinished"))
    }
}
