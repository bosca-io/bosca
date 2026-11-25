package bosca.cli.harness

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class HarnessPackageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `identity reads installed package metadata and rejects missing or wrong packages`() {
        val root = directory.root.toPath()
        val name = "@agentclientprotocol/codex-acp"
        assertFailsWith<java.nio.file.NoSuchFileException> { HarnessPackage.version(root, name) }
        val file = root.resolve("node_modules/$name/package.json")
        file.parent.createDirectories()
        file.writeText("""{"name":"$name","version":"2.2.2","other":"ignored"}""")
        assertEquals("2.2.2", HarnessPackage.version(root, name))
        file.writeText("""{"name":"$name","version":"changed"}""")
        assertEquals("changed", HarnessPackage.version(root, name))
        file.writeText("""{"name":"another-package","version":"2.2.2"}""")
        assertFailsWith<IllegalArgumentException> { HarnessPackage.version(root, name) }
        file.writeText("""{"name":"$name","version":""}""")
        assertFailsWith<IllegalArgumentException> { HarnessPackage.version(root, name) }
    }
}
