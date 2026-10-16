package com.m57.hermescontrol.share

import com.m57.hermescontrol.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class ShareStagingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val own = "com.m57.hermescontrol.fileprovider"

    @Test
    fun `accepts foreign content uris`() {
        assertTrue(isAcceptableShareUri("content://media/external/images/media/42", own))
        assertTrue(isAcceptableShareUri("CONTENT://com.android.providers.media.documents/document/image%3A1", own))
    }

    @Test
    fun `rejects file scheme and other schemes`() {
        assertFalse(isAcceptableShareUri("file:///data/data/com.m57.hermescontrol/files/secret", own))
        assertFalse(isAcceptableShareUri("http://example.com/a.png", own))
        assertFalse(isAcceptableShareUri("/sdcard/a.png", own))
        assertFalse(isAcceptableShareUri("content:///no-authority", own))
    }

    @Test
    fun `rejects own provider including user id prefixes and encoding`() {
        assertFalse(isAcceptableShareUri("content://$own/camera/x.jpg", own))
        assertFalse(isAcceptableShareUri("content://0@$own/camera/x.jpg", own))
        assertFalse(isAcceptableShareUri("content://10@$own/camera/x.jpg", own))
        assertFalse(isAcceptableShareUri("content://COM.M57.HERMESCONTROL.FILEPROVIDER/camera/x.jpg", own))
        assertFalse(isAcceptableShareUri("content://0%40$own/camera/x.jpg", own))
        assertFalse(isAcceptableShareUri("content://$own?x=1", own))
    }

    @Test
    fun `subject joins text only when not already present`() {
        assertEquals("Title\nhttps://a.example", composeSharedText("https://a.example", "Title"))
        assertEquals("Title https://a.example", composeSharedText("Title https://a.example", "Title"))
        assertEquals("plain", composeSharedText("  plain ", null))
        assertNull(composeSharedText(null, "Only subject"))
        assertNull(composeSharedText("   ", "x"))
    }

    @Test
    fun `provider names cannot carry paths or control characters`() {
        assertEquals("a.pdf", sanitizeShareName("../../etc/a.pdf"))
        assertEquals("a.pdf", sanitizeShareName("C:\\x\\a.pdf"))
        assertEquals("ab", sanitizeShareName("a\u0000b"))
        assertNull(sanitizeShareName(".."))
        assertNull(sanitizeShareName("   "))
        assertNull(sanitizeShareName(null))
    }

    private fun stage(
        uris: List<String>,
        sources: Map<String, () -> ShareSource>,
        maxFiles: Int = 8,
        maxTotal: Long = 20L * 1024 * 1024,
        dir: File = tmp.newFolder(),
    ) = stageShareUris(
        uris = uris,
        ownAuthority = own,
        directory = dir,
        source = { sources.getValue(it)() },
        publish = { "content://$own/shared/${it.name}" },
        maxFiles = maxFiles,
        maxTotalBytes = maxTotal,
    )

    private fun ok(
        name: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        mime: String? = "image/png",
    ) = { ShareSource(name, mime) { ByteArrayInputStream(bytes) } }

    @Test
    fun `stages a mixed batch once and dedupes repeated uris`() {
        val result =
            stage(
                uris = listOf("content://a/1", "content://a/2", "content://a/1"),
                sources =
                    mapOf(
                        "content://a/1" to ok("pic.png"),
                        "content://a/2" to ok("doc.pdf", mime = "application/pdf"),
                    ),
            )
        assertEquals(listOf("pic.png", "doc.pdf"), result.attachments.map(Attachment::name))
        assertEquals("application/pdf", result.attachments[1].mimeType)
        assertTrue(result.failed.isEmpty())
        assertEquals(3L, result.attachments[0].size)
        assertEquals(
            2,
            result.attachments
                .map(Attachment::uri)
                .toSet()
                .size,
        )
    }

    @Test
    fun `unknown mime falls back to octet stream`() {
        val result = stage(listOf("content://a/1"), mapOf("content://a/1" to ok("blob", mime = null)))
        assertEquals("application/octet-stream", result.attachments.single().mimeType)
    }

    @Test
    fun `revoked empty and rejected uris are reported not thrown`() {
        val result =
            stage(
                uris = listOf("content://a/revoked", "content://a/empty", "file:///etc/passwd", "content://a/good"),
                sources =
                    mapOf(
                        "content://a/revoked" to { throw SecurityException("grant revoked") },
                        "content://a/empty" to ok("empty.txt", bytes = ByteArray(0)),
                        "content://a/good" to ok("good.png"),
                    ),
            )
        assertEquals(listOf("good.png"), result.attachments.map(Attachment::name))
        assertEquals(3, result.failed.size)
        assertTrue("empty.txt" in result.failed)
    }

    @Test
    fun `null stream is a failure`() {
        val result =
            stage(
                listOf("content://a/1"),
                mapOf("content://a/1" to { ShareSource("x.png", "image/png") { null } }),
            )
        assertTrue(result.attachments.isEmpty())
        assertEquals(listOf("x.png"), result.failed)
    }

    @Test
    fun `stream failure mid copy leaves no staged file`() {
        val dir = tmp.newFolder()
        val failing =
            object : java.io.InputStream() {
                override fun read(): Int = throw IOException("boom")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw IOException("boom")
            }
        val result =
            stage(
                listOf("content://a/1"),
                mapOf("content://a/1" to { ShareSource("x.png", "image/png") { failing } }),
                dir = dir,
            )
        assertTrue(result.attachments.isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `per file limit fails only the oversize file`() {
        val result =
            stage(
                uris = listOf("content://a/big", "content://a/small"),
                sources =
                    mapOf(
                        "content://a/big" to ok("big.bin", bytes = ByteArray(11 * 1024 * 1024)),
                        "content://a/small" to ok("small.png"),
                    ),
            )
        assertEquals(listOf("small.png"), result.attachments.map(Attachment::name))
        assertEquals(listOf("big.bin"), result.failed)
    }

    @Test
    fun `total byte budget and file count are enforced`() {
        val bytes = ByteArray(6)
        val three = (1..3).map { "content://a/$it" }
        val byBytes = stage(three, three.associateWith { ok("f.png", bytes) }, maxTotal = 10)
        assertEquals(1, byBytes.attachments.size)
        assertEquals(2, byBytes.failed.size)
        val byCount = stage(three, three.associateWith { ok("f.png", bytes) }, maxFiles = 2)
        assertEquals(2, byCount.attachments.size)
        assertEquals(1, byCount.failed.size)
    }

    @Test
    fun `hostile display name never becomes a path`() {
        val dir = tmp.newFolder()
        val result =
            stage(listOf("content://a/1"), mapOf("content://a/1" to ok("../../evil.png")), dir = dir)
        assertEquals("evil.png", result.attachments.single().name)
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(
            dir
                .listFiles()!!
                .single()
                .canonicalPath
                .startsWith(dir.canonicalPath),
        )
    }

    @Test
    fun `two shares merge without losing either`() {
        val a = IncomingShare(text = "one", attachments = listOf(Attachment("u1", "a", "t")), failed = listOf("x"))
        val b = IncomingShare(text = "two", attachments = listOf(Attachment("u2", "b", "t")))
        val merged = a + b
        assertEquals("one\n\ntwo", merged.text)
        assertEquals(listOf("u1", "u2"), merged.attachments.map(Attachment::uri))
        assertEquals(listOf("x"), merged.failed)
        assertNull((IncomingShare() + IncomingShare()).text)
    }

    @Test
    fun `failed names are summarised`() {
        assertEquals("a, b", describeFailedShares(listOf("a", "b")))
        assertEquals("a, b, c +2", describeFailedShares(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `inbox accumulates and hands off exactly once`() {
        ShareInbox.clear()
        ShareInbox.offer(IncomingShare())
        assertNull(ShareInbox.pending)
        ShareInbox.offer(IncomingShare(text = "a"))
        ShareInbox.offer(IncomingShare(text = "b"))
        assertEquals("a\n\nb", ShareInbox.take()?.text)
        assertNull(ShareInbox.take())
    }
}
