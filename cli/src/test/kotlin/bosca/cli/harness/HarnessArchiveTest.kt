package bosca.cli.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*
import kotlin.test.*

class HarnessArchiveTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var roots: Map<String, Path>
    private lateinit var path: Path
    private lateinit var archive: HarnessArchive
    private val metadata = buildJsonObject { put("sessionId", "fixture"); put("generation", 2); put("nativeSessionId", "native") }
    @BeforeTest fun setup() {
        roots = HarnessArchive.ROOTS.associateWith { directory.root.toPath().resolve(it).createDirectories() }
        roots.getValue("harness").resolve("sessions").createDirectory().resolve("native.json").writeText("history")
        roots.getValue("harness").resolve("auth.json").writeText("secret")
        roots.getValue("harness").resolve("state_5.sqlite-wal").writeBytes(byteArrayOf(0, 1, -1))
        roots.getValue("workspace").resolve("untracked").writeText("work")
        roots.getValue("workspace").resolve("repository/src").createDirectories().resolve("Main.kt").writeText("nested work")
        val script = roots.getValue("workspace").resolve("run.sh"); script.writeText("true"); HarnessArchive.chmod(script, 489)
        Files.createSymbolicLink(roots.getValue("workspace").resolve("link"), Path.of("untracked"))
        path = directory.root.toPath().resolve("archive.tar.gz")
        archive = HarnessArchive(roots, "codex-0.160.1", 1024 * 1024)
    }
    private fun empty() { roots.values.forEach { HarnessArchive.removeTree(it); it.createDirectory() } }
    @AfterTest fun cleanup() { roots.values.forEach { if (it.exists()) HarnessArchive.chmod(it, 448) } }

    @Test fun `round trip preserves opaque journals executable modes links and excludes auth`() = runBlocking {
        val receipt = archive.export(path, metadata, emptyList())
        empty()
        val manifest = archive.restore(path, receipt.sha256, "fixture", false)
        assertEquals(metadata, manifest.metadata)
        assertContentEquals(byteArrayOf(0, 1, -1), roots.getValue("harness").resolve("state_5.sqlite-wal").readBytes())
        assertEquals("work", roots.getValue("workspace").resolve("link").readText())
        assertEquals("nested work", roots.getValue("workspace").resolve("repository/src/Main.kt").readText())
        assertEquals(489, HarnessArchive.mode(roots.getValue("workspace").resolve("run.sh")))
        assertFalse(roots.getValue("harness").resolve("auth.json").exists())
    }
    @Test fun `restore preserves deployment permissions on fixed roots`() = runBlocking {
        roots.values.forEach { HarnessArchive.chmod(it, 448) }
        val receipt = archive.export(path, metadata, emptyList())
        empty()
        roots.values.forEach { HarnessArchive.chmod(it, 504) }

        archive.restore(path, receipt.sha256, "fixture", false)

        roots.values.forEach { assertEquals(504, HarnessArchive.mode(it)) }
        assertEquals("work", roots.getValue("workspace").resolve("untracked").readText())
        assertEquals(489, HarnessArchive.mode(roots.getValue("workspace").resolve("run.sh")))
    }
    @Test fun `upgrades retain the entire operational directory without knowing native file formats`() = runBlocking {
        for ((family, authentication) in listOf("codex" to "auth.json", "claude" to ".credentials.json")) {
            empty()
            val files = mapOf(
                "settings.json" to "{\"preference\":true}".toByteArray(),
                "state_42.sqlite" to byteArrayOf(0, 1, -1),
                "state_42.sqlite-wal" to byteArrayOf(9, 8, 7),
                "plugins/new-plugin/data/state.bin" to byteArrayOf(5, 4, 3),
                "skills/custom/SKILL.md" to "user instructions".toByteArray(),
                "new-version-state/unknown-format" to "opaque native state".toByteArray(),
            )
            for ((name, bytes) in files) {
                val file = roots.getValue("harness").resolve(name)
                file.parent.createDirectories(); file.writeBytes(bytes)
            }
            roots.getValue("harness").resolve(authentication).writeText("provider-secret")
            val receipt = HarnessArchive(roots, "$family-old", 1024 * 1024).export(path, metadata, listOf("provider-secret"))
            empty()
            HarnessArchive(roots, "$family-new", 1024 * 1024).restore(path, receipt.sha256, "fixture", false)
            for ((name, bytes) in files) assertContentEquals(bytes, roots.getValue("harness").resolve(name).readBytes(), "$family: $name")
            assertFalse(roots.getValue("harness").resolve(authentication).exists())
        }
    }
    @Test fun `native IPC sockets are transient while persistent scratch is retained`() = runBlocking {
        val socket = roots.getValue("harness").resolve("native.sock")
        java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.bind(java.net.UnixDomainSocketAddress.of(socket))
            roots.getValue("scratch").resolve("tool-result").writeText("persistent tool output")
            val receipt = archive.export(path, metadata, emptyList())
            empty()
            archive.restore(path, receipt.sha256, "fixture", false)
            assertFalse(socket.exists())
            assertEquals("persistent tool output", roots.getValue("scratch").resolve("tool-result").readText())
        }
    }
    @Test fun `checksum identity policy and nonempty destinations fail without modifying local files`() = runBlocking {
        val receipt = archive.export(path, metadata, emptyList())
        assertFailsWith<IllegalArgumentException> { archive.restore(path, "0".repeat(64), "fixture", false) }
        assertFailsWith<IllegalArgumentException> { archive.restore(path, receipt.sha256, "foreign", false) }
        assertFailsWith<IllegalArgumentException> { HarnessArchive(roots, "claude-0.3.303", 1024 * 1024).restore(path, receipt.sha256, "fixture", false) }
        assertFailsWith<IllegalArgumentException> { archive.restore(path, receipt.sha256, "fixture", false) }
        assertEquals("history", roots.getValue("harness").resolve("sessions/native.json").readText())
    }
    @Test fun `profile changes restore workspace only and preserve session validation`() = runBlocking {
        roots.getValue("scratch").resolve("context").writeText("old")
        val receipt = archive.export(path, metadata, emptyList()); empty()
        val changed = HarnessArchive(roots, "claude-0.3.303", 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { changed.restore(path, receipt.sha256, "foreign", true) }
        changed.restore(path, receipt.sha256, "fixture", true)
        assertEquals("work", roots.getValue("workspace").resolve("link").readText())
        assertTrue(roots.getValue("harness").listDirectoryEntries().isEmpty())
        assertTrue(roots.getValue("scratch").listDirectoryEntries().isEmpty())
    }
    @Test fun `partial installation failure leaves empty roots for retry`() = runBlocking {
        val receipt = archive.export(path, metadata, emptyList()); empty()
        HarnessArchive.chmod(roots.getValue("harness"), 320)
        assertFailsWith<java.io.IOException> { archive.restore(path, receipt.sha256, "fixture", false) }
        assertTrue(roots.values.all { it.listDirectoryEntries().isEmpty() })
        HarnessArchive.chmod(roots.getValue("harness"), 448)
        archive.restore(path, receipt.sha256, "fixture", false)
        assertEquals("history", roots.getValue("harness").resolve("sessions/native.json").readText())
    }
    @Test fun `export rejects escaping links excessive state and credentials across chunk boundaries`() = runBlocking {
        val escape = roots.getValue("workspace").resolve("escape")
        Files.createSymbolicLink(escape, Path.of("../../outside"))
        assertFailsWith<IllegalArgumentException> { archive.export(path, metadata, emptyList()) }; escape.deleteExisting()
        assertFailsWith<IllegalArgumentException> { HarnessArchive(roots, "codex-0.160.1", 1).export(path, metadata, emptyList()) }
        roots.getValue("workspace").resolve("secret.bin").writeBytes(ByteArray(1024 * 1024 - 5) + "fixture-secret".toByteArray())
        assertContains(assertFailsWith<IllegalArgumentException> { HarnessArchive(roots, "codex-0.160.1", 2 * 1024 * 1024).export(path, metadata, listOf("fixture-secret")) }.message.orEmpty(), "refusing checkpoint")
    }
    @Test fun `duplicate traversal and corrupt file members cannot restore`() = runBlocking {
        archive.export(path, metadata, emptyList())
        val original = mutableListOf<Pair<TarArchiveEntry, ByteArray>>()
        TarArchiveInputStream(GZIPInputStream(path.inputStream())).use { tar ->
            while (true) { val entry = tar.nextEntry ?: break; original += entry to tar.readBytes() }
        }
        for (name in listOf("workspace/untracked", "../escape")) {
            TarArchiveOutputStream(GZIPOutputStream(path.outputStream())).use { tar ->
                original.forEach { (entry, bytes) -> tar.putArchiveEntry(entry); if (entry.isFile) tar.write(bytes); tar.closeArchiveEntry() }
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = 1; mode = 384 }); tar.write(byteArrayOf(1)); tar.closeArchiveEntry()
            }
            assertFailsWith<IllegalArgumentException> { archive.restore(path, HarnessArchive.digest(path), "fixture", false) }
        }
        TarArchiveOutputStream(GZIPOutputStream(path.outputStream())).use { tar ->
            original.forEach { (entry, bytes) ->
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(if (entry.name == "workspace/untracked") byteArrayOf(0, 0, 0, 0) else bytes)
                tar.closeArchiveEntry()
            }
        }
        assertFailsWith<IllegalArgumentException> { archive.restore(path, HarnessArchive.digest(path), "fixture", false) }
        assertEquals("work", roots.getValue("workspace").resolve("untracked").readText())
    }
}
