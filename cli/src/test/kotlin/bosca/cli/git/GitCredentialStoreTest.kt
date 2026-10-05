package bosca.cli.git

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.core.CliktError
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitCredentialStoreTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("bosca-gitcred-test", "").apply {
            delete()
            mkdirs()
        }
        CliConfigStore.directoryOverride = tempDir
        CliInvocation.selectProfile(null)
    }

    @AfterTest
    fun tearDown() {
        CliInvocation.selectProfile(null)
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `put get and remove round-trip per host and profile`() {
        assertNull(GitCredentialStore.get("git.one.io", "work"))

        GitCredentialStore.put("git.one.io", "work-one", "work")
        GitCredentialStore.put("git.one.io", "personal-one", "personal")
        GitCredentialStore.put("git.two.io", "work-two", "work")
        assertEquals("work-one", GitCredentialStore.get("git.one.io", "work"))
        assertEquals("personal-one", GitCredentialStore.get("git.one.io", "personal"))
        assertEquals("work-two", GitCredentialStore.get("git.two.io", "work"))

        GitCredentialStore.put("git.one.io", "work-one-rotated", "work")
        assertEquals("work-one-rotated", GitCredentialStore.get("git.one.io", "work"))
        assertEquals("personal-one", GitCredentialStore.get("git.one.io", "personal"))

        GitCredentialStore.remove("git.one.io", "work")
        assertNull(GitCredentialStore.get("git.one.io", "work"))
        assertEquals("personal-one", GitCredentialStore.get("git.one.io", "personal"))
        assertEquals("work-two", GitCredentialStore.get("git.two.io", "work"))
    }

    @Test
    fun `resolve prefers the selected profile and falls back across profiles by host`() {
        GitCredentialStore.put("git.shared.io", "work-shared", "work")
        GitCredentialStore.put("git.shared.io", "personal-shared", "personal")
        GitCredentialStore.put("git.personal.io", "personal-only", "personal")

        assertEquals(
            GitCredentialStore.LookupResult.Found("work", "work-shared"),
            GitCredentialStore.resolve("git.shared.io", "work"),
        )
        assertEquals(
            GitCredentialStore.LookupResult.Found("personal", "personal-only"),
            GitCredentialStore.resolve("git.personal.io", "work"),
        )
        assertEquals(
            GitCredentialStore.LookupResult.Missing,
            GitCredentialStore.resolve("git.missing.io", "work"),
        )
    }

    @Test
    fun `resolve refuses to choose between multiple profiles for the same host`() {
        GitCredentialStore.put("git.shared.io", "work-shared", "work")
        GitCredentialStore.put("git.shared.io", "personal-shared", "personal")

        assertEquals(
            GitCredentialStore.LookupResult.Ambiguous(listOf("personal", "work")),
            GitCredentialStore.resolve("git.shared.io", "other"),
        )
    }

    @Test
    fun `repository routes select the token that previously succeeded for that path`() {
        GitCredentialStore.put("git.shared.io", "work-token", "work")
        GitCredentialStore.put("git.shared.io", "personal-token", "personal")
        GitCredentialStore.putFromHelper(
            "git.shared.io",
            "personal-token",
            "personal",
            path = "/acme/widgets.git",
        )

        assertEquals(
            GitCredentialStore.LookupResult.Found("personal", "personal-token"),
            GitCredentialStore.resolve("git.shared.io", "work", "acme/widgets.git"),
        )
    }

    @Test
    fun `invocation profile overrides a previously learned repository route`() = runBlocking {
        GitCredentialStore.put("git.shared.io", "work-token", "work")
        GitCredentialStore.put("git.shared.io", "personal-token", "personal")
        GitCredentialStore.putFromHelper(
            "git.shared.io",
            "personal-token",
            "personal",
            path = "acme/widgets.git",
        )
        CliInvocation.selectProfile("work")

        assertEquals(
            "work-token",
            GitCredentialHelper.resolveToken("git.shared.io", "acme/widgets.git"),
        )
    }

    @Test
    fun `helper store and erase preserve the profile and routes that own a token`() {
        GitCredentialStore.put("git.shared.io", "work-token", "work")
        GitCredentialStore.putFromHelper(
            "git.shared.io",
            "work-token",
            "personal",
            path = "acme/work.git",
        )

        assertEquals("work-token", GitCredentialStore.get("git.shared.io", "work"))
        assertNull(GitCredentialStore.get("git.shared.io", "personal"))

        GitCredentialStore.put("git.shared.io", "personal-token", "personal")
        GitCredentialStore.putFromHelper(
            "git.shared.io",
            "personal-token",
            "personal",
            path = "acme/personal.git",
        )
        GitCredentialStore.removeFromHelper(
            "git.shared.io",
            "personal-token",
            "work",
            path = "acme/personal.git",
        )

        assertEquals("work-token", GitCredentialStore.get("git.shared.io", "work"))
        assertNull(GitCredentialStore.get("git.shared.io", "personal"))
        assertEquals(
            GitCredentialStore.LookupResult.Found("work", "work-token"),
            GitCredentialStore.resolve("git.shared.io", "work", "acme/personal.git"),
        )
    }

    @Test
    fun `legacy host token map migrates to the default profile`() {
        File(GitCredentialStore.path()).writeText(
            """
            {
              "tokens": {
                "git.one.io": "legacy-token"
              }
            }
            """.trimIndent(),
        )

        assertEquals("legacy-token", GitCredentialStore.get("git.one.io", "default"))
        assertNull(GitCredentialStore.get("git.one.io", "work"))

        val stored = Json.parseToJsonElement(File(GitCredentialStore.path()).readText()).jsonObject
        assertTrue("profiles" in stored)
        assertFalse("tokens" in stored)
    }

    @Test
    fun `removing a missing credential does not create a store file`() {
        GitCredentialStore.remove("git.missing.io", "work")

        assertFalse(File(GitCredentialStore.path()).exists())
    }

    @Test
    fun `invalid credential store is reported without overwriting it`() {
        val credentialFile = File(GitCredentialStore.path())
        val invalid = "{ definitely-not-json"
        credentialFile.writeText(invalid)

        val error = assertFailsWith<CliktError> {
            GitCredentialStore.get("git.one.io", "work")
        }

        assertTrue(error.message.orEmpty().contains("Could not read Bosca Git credentials"))
        assertEquals(invalid, credentialFile.readText())
    }
}
