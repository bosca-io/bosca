package bosca.cli.harness

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.*
import kotlin.test.*

class HarnessEnvironmentTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `Docker Secrets supply current credentials without changing the inherited environment`() {
        val root = directory.root.toPath()
        root.resolve("bosca-runtime.json").writeText("""{"BOSCA_TOKEN":"bsk_generation","BOSCA_RUNTIME_TOKEN":"control","BOSCA_RUNTIME_EXPIRES":"123"}""")
        root.resolve("provider-OPENAI_API_KEY").writeText("provider-credential\n")
        val base = mapOf("PATH" to "/bin", "BOSCA_SECRET_FILES" to """{"OPENAI_API_KEY":"provider-OPENAI_API_KEY"}""")
        val actual = harnessEnvironment(base, root)
        assertEquals("bsk_generation", actual["BOSCA_TOKEN"])
        assertEquals("provider-credential", actual["OPENAI_API_KEY"])
        assertEquals("/bin", actual["PATH"])
        assertNull(base["BOSCA_TOKEN"])
        val local = mapOf("BOSCA_TOKEN" to "local-api", "BOSCA_RUNTIME_TOKEN" to "local-control", "BOSCA_RUNTIME_EXPIRES" to "456")
        assertEquals(local, harnessEnvironment(local, root), "A local harness must retain its explicitly supplied credentials")
    }

    @Test fun `runtimes with an egress proxy receive their egress token from the runtime secret`() {
        val root = directory.root.toPath()
        root.resolve("bosca-runtime.json").writeText("""{"BOSCA_TOKEN":"bsk_generation","BOSCA_RUNTIME_TOKEN":"control","BOSCA_RUNTIME_EXPIRES":"123","BOSCA_EGRESS_TOKEN":"egress"}""")
        val actual = harnessEnvironment(mapOf("BOSCA_EGRESS_PROXY" to "http://kit-egress:3128"), root)
        assertEquals("egress", actual["BOSCA_EGRESS_TOKEN"])
        assertEquals("http://kit-egress:3128", actual["BOSCA_EGRESS_PROXY"])
        root.resolve("bosca-runtime.json").writeText("""{"BOSCA_TOKEN":"bsk_generation","BOSCA_EGRESS_PROXY":"http://elsewhere:3128"}""")
        assertFailsWith<IllegalArgumentException>("The proxy origin is runtime environment, never a secret field") { harnessEnvironment(emptyMap(), root) }
    }

    @Test fun `unmounted deployment secrets preserve the existing environment`() {
        assertEquals(mapOf("BOSCA_TOKEN" to "current"), harnessEnvironment(mapOf("BOSCA_TOKEN" to "current"), directory.root.toPath()))
    }

    @Test fun `provider references cannot overwrite generation authority or escape the secret directory`() {
        for (reference in listOf("""{"BOSCA_TOKEN":"provider"}""", """{"API_KEY":"../outside"}""", """{"API_KEY":"bosca-runtime.json"}""")) {
            assertFailsWith<IllegalArgumentException> { harnessEnvironment(mapOf("BOSCA_SECRET_FILES" to reference), directory.root.toPath()) }
        }
        directory.root.toPath().resolve("bosca-runtime.json").writeText("""{"PATH":"unapproved"}""")
        assertFailsWith<IllegalArgumentException> { harnessEnvironment(emptyMap(), directory.root.toPath()) }
    }
}
