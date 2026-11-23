package bosca.cli.harness

import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class HarnessDescriptorTest {
    @get:Rule val directory = TemporaryFolder()
    private val root get() = directory.root.toPath()
    private val paths = mapOf("workspace" to "/workspace", "harness" to "/state/harness", "scratch" to "/state/scratch")
    private fun descriptor(claude: Boolean = false) = HarnessDescriptor(1, 1, 1,
        if (claude) "claude-0.3.303" else "codex-0.160.1",
        if (claude) "@agentclientprotocol/claude-agent-acp@0.94.0" else "@agentclientprotocol/codex-acp@2.2.2",
        if (claude) "@anthropic-ai/claude-agent-sdk@0.3.303" else "@openai/codex@0.160.1", paths)
    private fun install(descriptor: HarnessDescriptor) {
        for (reference in listOf(descriptor.adapter, descriptor.harness)) {
            val separator = reference.lastIndexOf('@')
            val name = reference.substring(0, separator)
            val file = root.resolve("node_modules/$name/package.json")
            file.parent.createDirectories()
            file.writeText("""{"name":"$name","version":"${reference.substring(separator + 1)}"}""")
        }
        root.resolve("compatibility.json").writeText(Json.encodeToString(HarnessDescriptor.serializer(), descriptor))
    }

    @Test fun `both image families report actual installed versions through the same descriptor`() {
        for (claude in listOf(false, true)) {
            val declared = descriptor(claude)
            install(declared)
            val read = HarnessDescriptor.read(root)
            assertEquals(declared, read)
            val actual = read.versions(root, "cli-version")
            assertEquals("cli-version", actual.cli)
            assertEquals(declared.adapter.substringAfterLast('@'), actual.adapter)
            assertEquals(declared.harness.substringAfterLast('@'), actual.harness)
            assertEquals(declared.statePolicy, actual.statePolicy)
        }
    }

    @Test fun `package drift missing descriptor unsupported protocol and changed paths fail before serving`() {
        assertFailsWith<java.nio.file.NoSuchFileException> { HarnessDescriptor.read(root) }
        val declared = descriptor()
        install(declared)
        assertFailsWith<IllegalArgumentException> { declared.copy(adapter = "@agentclientprotocol/codex-acp@different").versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(adapter = "@../outside@1").versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(harness = "@openai/codex").versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(acpVersion = 2).versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(transportVersion = 2).versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(archiveFormatVersion = 2).versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(statePolicy = "unknown").versions(root, "cli") }
        assertFailsWith<IllegalArgumentException> { declared.copy(paths = paths + ("harness" to "/other")).versions(root, "cli") }
    }

    @Test fun `installed harness upgrades do not require a new CLI file allowlist`() {
        for (claude in listOf(false, true)) {
            val old = descriptor(claude)
            val upgraded = old.copy(adapter = old.adapter.substringBeforeLast('@') + "@9.0.0",
                harness = old.harness.substringBeforeLast('@') + "@10.0.0",
                statePolicy = if (claude) "claude-home-v1" else "codex-home-v1")
            install(upgraded)
            assertEquals("10.0.0", upgraded.versions(root, "cli").harness)
            assertFailsWith<IllegalArgumentException> {
                upgraded.copy(statePolicy = if (claude) "codex-home-v1" else "claude-home-v1").versions(root, "cli")
            }
        }
    }
}
