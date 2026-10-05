package bosca.cli.config

import bosca.cli.BoscaCommand
import bosca.cli.git.GitCredentialStore
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCommandsTest {

    private lateinit var tempDir: File
    private lateinit var workingDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("bosca-profile-test", "").apply {
            delete()
            mkdirs()
        }
        CliConfigStore.directoryOverride = tempDir
        workingDir = File(tempDir, "repository").apply { mkdirs() }
        CliConfigStore.workingDirectoryOverride = workingDir
        CliInvocation.selectProfile(null)
    }

    @AfterTest
    fun tearDown() {
        CliInvocation.selectProfile(null)
        CliConfigStore.workingDirectoryOverride = null
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    private fun profileCli() = BoscaCommand().subcommands(
        ProfileCommand().subcommands(
            ProfileListCommand(),
            ProfileShowCommand(),
            ProfileUseCommand(),
            ProfileUnsetCommand(),
            ProfileRemoveCommand(),
        ),
    )

    @Test
    fun `root help advertises temporary profile selection and profile commands`() {
        val result = profileCli().test("--help")

        assertEquals(0, result.statusCode)
        assertTrue("--profile" in result.stdout)
        assertTrue("profile" in result.stdout)
    }

    @Test
    fun `empty profile list explains how to create one`() {
        val result = profileCli().test("profile list")

        assertEquals(0, result.statusCode)
        assertTrue("No profiles saved" in result.stdout)
        assertTrue("bosca login --profile" in result.stdout)
    }

    @Test
    fun `show without saved profiles reports the implicit localhost default`() {
        val result = profileCli().test("profile show")

        assertEquals(0, result.statusCode)
        assertTrue("Name:      default" in result.stdout)
        assertTrue("Endpoint:  $DEFAULT_BOSCA_ENDPOINT" in result.stdout)
        assertTrue("Auth:      not configured" in result.stdout)
    }

    @Test
    fun `login with API token creates and activates a named profile`() {
        GitCredentialStore.put("git.example", "old-git-token", "work")

        val result = LoginCommand().test(
            "--profile work --url https://work.example/graphql --api-token secret-work",
        )

        assertEquals(0, result.statusCode)
        val config = CliConfigStore.load()
        assertEquals("work", config.activeProfile)
        assertEquals("https://work.example/graphql", config.profiles["work"]?.endpoint)
        assertEquals("secret-work", config.profiles["work"]?.auth?.token)
        assertNull(GitCredentialStore.get("git.example", "work"))
        assertFalse("secret-work" in result.stdout)
    }

    @Test
    fun `list and show never print stored tokens or refresh tokens`() {
        saveProfiles()

        val list = profileCli().test("profile list")
        val show = profileCli().test("profile show personal")

        assertEquals(0, list.statusCode)
        assertEquals(0, show.statusCode)
        assertTrue("work.example" in list.stdout)
        assertTrue("principal-work" in list.stdout)
        assertTrue("personal.example" in show.stdout)
        for (secret in listOf("access-work", "refresh-work", "access-personal", "refresh-personal")) {
            assertFalse(secret in list.stdout)
            assertFalse(secret in show.stdout)
        }
    }

    @Test
    fun `use changes the persistent active profile`() {
        saveProfiles()

        val result = profileCli().test("profile use personal")

        assertEquals(0, result.statusCode)
        assertEquals("personal", CliConfigStore.load().activeProfile)
    }

    @Test
    fun `local use selects a profile for the current directory and descendants`() {
        saveProfiles()

        val result = profileCli().test("profile use personal --local")

        assertEquals(0, result.statusCode, result.output)
        val config = CliConfigStore.load()
        val directory = CliConfigStore.directoryPath(workingDir)
        val directoryConfigFile = File(workingDir, DIRECTORY_CONFIG_FILE_NAME)
        val directoryConfig = Json.decodeFromString(
            DirectoryConfig.serializer(),
            directoryConfigFile.readText(),
        )
        assertEquals("work", config.activeProfile)
        assertEquals("https://personal.example/graphql", directoryConfig.url)
        assertEquals(
            setOf("url"),
            Json.parseToJsonElement(directoryConfigFile.readText()).jsonObject.keys,
        )
        assertEquals("personal", CliConfigStore.selectedProfileName(config))

        CliConfigStore.workingDirectoryOverride = File(workingDir, "src/main").apply { mkdirs() }
        val show = profileCli().test("profile show")
        assertEquals(0, show.statusCode, show.output)
        assertTrue("Name:      personal" in show.stdout)
        assertTrue("Selected by: directory $directory" in show.stdout)
    }

    @Test
    fun `nearest directory profile overrides a parent directory profile`() {
        saveProfiles()
        val nested = File(workingDir, "services/api").apply { mkdirs() }
        val profiles = CliConfigStore.load().profiles
        CliConfigStore.saveDirectoryProfile(
            "personal",
            profiles.getValue("personal"),
            workingDir,
        )
        CliConfigStore.saveDirectoryProfile(
            "work",
            profiles.getValue("work"),
            nested,
        )

        CliConfigStore.workingDirectoryOverride = File(nested, "src").apply { mkdirs() }
        assertEquals("work", CliConfigStore.selectedProfileName())

        CliConfigStore.workingDirectoryOverride = File(workingDir, "web").apply { mkdirs() }
        assertEquals("personal", CliConfigStore.selectedProfileName())
    }

    @Test
    fun `temporary profile override takes precedence over a directory profile`() {
        saveProfiles()
        profileCli().test("profile use personal --local")

        val result = profileCli().test("--profile work profile show")

        assertEquals(0, result.statusCode, result.output)
        assertTrue("Name:      work" in result.stdout)
        assertTrue("Selected by: invocation override" in result.stdout)
        assertEquals("personal", CliConfigStore.directoryProfileSelection()?.profileName)
    }

    @Test
    fun `local use rejects a same endpoint profile the URL cannot distinguish`() {
        saveProfiles()
        val config = CliConfigStore.load()
        CliConfigStore.save(
            config.withProfile(
                "second-personal",
                config.profiles.getValue("personal").copy(auth = null),
            ),
        )

        val result = profileCli().test("profile use personal --local")

        assertTrue(result.statusCode != 0)
        assertTrue("A URL-only $DIRECTORY_CONFIG_FILE_NAME cannot distinguish them" in result.stderr)
        assertFalse(File(workingDir, DIRECTORY_CONFIG_FILE_NAME).exists())
    }

    @Test
    fun `active profile resolves multiple profiles and future directory fields are ignored`() {
        saveProfiles()
        val config = CliConfigStore.load()
        CliConfigStore.save(
            config.withProfile(
                "second-personal",
                config.profiles.getValue("personal").copy(auth = null),
            ).copy(activeProfile = "personal"),
        )
        File(workingDir, DIRECTORY_CONFIG_FILE_NAME).writeText(
            """{"url":"https://personal.example/graphql","futureSetting":true}""",
        )

        assertEquals("personal", CliConfigStore.selectedProfileName())
    }

    @Test
    fun `unset from a descendant removes the nearest directory profile`() {
        saveProfiles()
        profileCli().test("profile use personal --local")
        CliConfigStore.workingDirectoryOverride = File(workingDir, "src/main").apply { mkdirs() }

        val result = profileCli().test("profile unset")

        assertEquals(0, result.statusCode, result.output)
        assertTrue("Removed directory profile configuration" in result.stdout)
        assertFalse(File(workingDir, DIRECTORY_CONFIG_FILE_NAME).exists())
        assertEquals("work", CliConfigStore.selectedProfileName())
    }

    @Test
    fun `unset reports when no directory profile applies`() {
        saveProfiles()

        val result = profileCli().test("profile unset")

        assertTrue(result.statusCode != 0)
        assertTrue("No $DIRECTORY_CONFIG_FILE_NAME applies" in result.stderr)
    }

    @Test
    fun `malformed directory profile fails loudly and can be unset from a descendant`() {
        saveProfiles()
        File(workingDir, DIRECTORY_CONFIG_FILE_NAME).writeText("{not-json")
        CliConfigStore.workingDirectoryOverride = File(workingDir, "src").apply { mkdirs() }

        val show = profileCli().test("profile show")

        assertTrue(show.statusCode != 0)
        assertTrue("Could not read directory configuration" in show.stderr)

        val unset = profileCli().test("profile unset")
        assertEquals(0, unset.statusCode, unset.output)
        assertFalse(File(workingDir, DIRECTORY_CONFIG_FILE_NAME).exists())
    }

    @Test
    fun `directory profile rejects unmatched and ambiguous URLs`() {
        saveProfiles()
        val file = File(workingDir, DIRECTORY_CONFIG_FILE_NAME)
        file.writeText("""{"url":"https://missing.example/graphql"}""")

        val missing = profileCli().test("profile show")

        assertTrue(missing.statusCode != 0)
        assertTrue("No saved profile targets the URL" in missing.stderr)
        assertTrue("https://missing.example/graphql" in missing.stderr)

        val config = CliConfigStore.load()
        CliConfigStore.save(
            config.withProfile(
                "second-personal",
                config.profiles.getValue("personal").copy(auth = null),
            ).copy(activeProfile = "work"),
        )
        file.writeText("""{"url":"https://personal.example/graphql"}""")
        val ambiguous = profileCli().test("profile show")

        assertTrue(ambiguous.statusCode != 0)
        assertTrue("Multiple saved profiles" in ambiguous.stderr)
        assertTrue("personal, second-personal" in ambiguous.stderr)
    }

    @Test
    fun `login uses a directory profile without changing the global active profile`() {
        saveProfiles()
        profileCli().test("profile use personal --local")

        val result = LoginCommand().test(
            "--url https://new-personal.example/graphql --api-token replacement-personal-token",
        )

        assertEquals(0, result.statusCode, result.output)
        val config = CliConfigStore.load()
        assertEquals("work", config.activeProfile)
        assertEquals("access-work", config.profiles["work"]?.auth?.token)
        assertEquals("replacement-personal-token", config.profiles["personal"]?.auth?.token)
        assertEquals("https://new-personal.example/graphql", config.profiles["personal"]?.endpoint)
        assertEquals(
            "https://new-personal.example/graphql",
            Json.decodeFromString(
                DirectoryConfig.serializer(),
                File(workingDir, DIRECTORY_CONFIG_FILE_NAME).readText(),
            ).url,
        )
        assertEquals(
            "https://new-personal.example/graphql",
            CliConfigStore.directoryProfileSelection(config)?.endpoint,
        )
    }

    @Test
    fun `login bootstraps a missing saved profile from boscarc`() {
        saveProfiles()
        CliConfigStore.save(CliConfigStore.load().withoutProfile("personal"))
        File(workingDir, DIRECTORY_CONFIG_FILE_NAME).writeText(
            """{"url":"https://personal.example/graphql"}""",
        )

        val result = LoginCommand().test("--profile personal --api-token new-personal-token")

        assertEquals(0, result.statusCode, result.output)
        val config = CliConfigStore.load()
        assertEquals("work", config.activeProfile)
        assertEquals("https://personal.example/graphql", config.profiles["personal"]?.endpoint)
        assertEquals("new-personal-token", config.profiles["personal"]?.auth?.token)
        assertEquals("personal", CliConfigStore.directoryProfileSelection(config)?.profileName)
    }

    @Test
    fun `config endpoint update keeps boscarc consistent`() {
        saveProfiles()
        profileCli().test("profile use personal --local")

        val result = ConfigCommand().test("--url https://new-personal.example/graphql")

        assertEquals(0, result.statusCode, result.output)
        val config = CliConfigStore.load()
        assertEquals("https://new-personal.example/graphql", config.profiles["personal"]?.endpoint)
        assertEquals(
            "https://new-personal.example/graphql",
            CliConfigStore.directoryProfileSelection(config)?.endpoint,
        )
    }

    @Test
    fun `use rejects invalid and unknown profile names`() {
        saveProfiles()

        val invalid = profileCli().test("profile use ../work")
        val unknown = profileCli().test("profile use unknown")

        assertTrue(invalid.statusCode != 0)
        assertTrue("Invalid profile name" in invalid.stderr)
        assertTrue(unknown.statusCode != 0)
        assertTrue("does not exist" in unknown.stderr)
        assertEquals("work", CliConfigStore.load().activeProfile)
    }

    @Test
    fun `root profile override changes only the selected profile for one invocation`() {
        saveProfiles()

        val result = profileCli().test("--profile personal profile show")

        assertEquals(0, result.statusCode)
        assertTrue("Name:      personal" in result.stdout)
        assertTrue("Active:    no" in result.stdout)
        assertTrue("Selected:  yes" in result.stdout)
        assertEquals("work", CliConfigStore.load().activeProfile)
    }

    @Test
    fun `root profile override rejects invalid profile names`() {
        saveProfiles()

        val result = profileCli().test("--profile ../work profile list")

        assertTrue(result.statusCode != 0)
        assertTrue("Invalid profile name" in result.stderr)
        assertEquals("work", CliConfigStore.load().activeProfile)
    }

    @Test
    fun `remove requires unsetting a matching boscarc then removes only the requested profile`() {
        saveProfiles()
        profileCli().test("profile use work --local")
        GitCredentialStore.put("git.example", "work-git-token", "work")
        GitCredentialStore.put("git.example", "personal-git-token", "personal")

        val blocked = profileCli().test("profile remove work")

        assertTrue(blocked.statusCode != 0)
        assertTrue("Run 'bosca profile unset' before removing it" in blocked.stderr)
        assertTrue("work" in CliConfigStore.load().profiles)

        assertEquals(0, profileCli().test("profile unset").statusCode)
        val result = profileCli().test("profile remove work")
        assertEquals(0, result.statusCode)
        val config = CliConfigStore.load()
        assertNull(config.profiles["work"])
        assertEquals("personal", config.activeProfile)
        assertEquals("access-personal", config.profiles["personal"]?.auth?.token)
        assertNull(GitCredentialStore.get("git.example", "work"))
        assertEquals("personal-git-token", GitCredentialStore.get("git.example", "personal"))
    }

    @Test
    fun `removing the final profile leaves no active profile`() {
        CliConfigStore.save(
            CliConfig(
                activeProfile = "only",
                profiles = mapOf("only" to ProfileConfig("https://only.example/graphql")),
            ),
        )

        val result = profileCli().test("profile remove only")

        assertEquals(0, result.statusCode)
        assertTrue(CliConfigStore.load().profiles.isEmpty())
        assertNull(CliConfigStore.load().activeProfile)
        assertFalse("Active profile:" in result.stdout)
    }

    @Test
    fun `remove rejects an unknown profile`() {
        saveProfiles()

        val result = profileCli().test("profile remove unknown")

        assertTrue(result.statusCode != 0)
        assertTrue("does not exist" in result.stderr)
    }

    @Test
    fun `logout clears only the targeted profile credentials`() {
        saveProfiles(
            workAuth = AuthConfig(refreshToken = "refresh-work", principalId = "principal-work"),
        )
        GitCredentialStore.put("git.example", "work-git-token", "work")
        GitCredentialStore.put("git.example", "personal-git-token", "personal")

        val result = LogoutCommand().test("--profile work")

        assertEquals(0, result.statusCode)
        val config = CliConfigStore.load()
        assertNull(config.profiles["work"]?.auth)
        assertEquals("access-personal", config.profiles["personal"]?.auth?.token)
        assertNull(GitCredentialStore.get("git.example", "work"))
        assertEquals("personal-git-token", GitCredentialStore.get("git.example", "personal"))
    }

    @Test
    fun `refresh refuses to send a saved refresh token to another server`() {
        saveProfiles()

        val result = LoginCommand().test(
            "--profile work --url https://other.example/graphql --refresh-token",
        )

        assertTrue(result.statusCode != 0)
        assertTrue("Cannot refresh profile 'work' against a different server" in result.stderr)
        val work = CliConfigStore.load().profiles["work"]
        assertEquals("https://work.example/graphql", work?.endpoint)
        assertEquals("refresh-work", work?.auth?.refreshToken)
    }

    @Test
    fun `temporary missing profile reports an error without changing the active profile`() {
        saveProfiles()

        val result = profileCli().test("--profile missing profile show")

        assertTrue(result.statusCode != 0)
        assertTrue("does not exist" in result.stderr)
        assertEquals("work", CliConfigStore.load().activeProfile)
    }

    @Test
    fun `profile names reject whitespace and path separators`() {
        assertTrue(isValidProfileName("work-prod_2"))
        assertFalse(isValidProfileName("work prod"))
        assertFalse(isValidProfileName("../work"))
        assertFalse(isValidProfileName(""))
    }

    @Test
    fun `config shows the selected profile without printing secrets`() {
        saveProfiles()
        CliInvocation.selectProfile("personal")

        val result = ConfigCommand().test("")

        assertEquals(0, result.statusCode)
        assertTrue("Profile:  personal" in result.stdout)
        assertTrue("personal.example" in result.stdout)
        assertTrue("principal-personal" in result.stdout)
        assertFalse("access-personal" in result.stdout)
        assertFalse("refresh-personal" in result.stdout)
    }

    @Test
    fun `config endpoint update changes only the selected profile and clears server-bound credentials`() {
        saveProfiles()
        CliInvocation.selectProfile("personal")
        GitCredentialStore.put("personal.example", "personal-git-token", "personal")

        val result = ConfigCommand().test("--url https://new-personal.example/graphql")

        assertEquals(0, result.statusCode)
        val config = CliConfigStore.load()
        assertEquals("work", config.activeProfile)
        assertEquals("https://work.example/graphql", config.profiles["work"]?.endpoint)
        assertEquals("https://new-personal.example/graphql", config.profiles["personal"]?.endpoint)
        assertNull(config.profiles["personal"]?.auth)
        assertNull(GitCredentialStore.get("personal.example", "personal"))
        assertTrue("credentials were cleared" in result.stdout)
    }

    @Test
    fun `equivalent endpoint update preserves credentials`() {
        saveProfiles()
        CliInvocation.selectProfile("personal")

        val result = ConfigCommand().test("--url https://personal.example/graphql/")

        assertEquals(0, result.statusCode)
        assertEquals(
            "access-personal",
            CliConfigStore.load().profiles["personal"]?.auth?.token,
        )
        assertFalse("credentials were cleared" in result.stdout)
    }

    @Test
    fun `config endpoint update creates the initial default profile`() {
        val result = ConfigCommand().test("--url https://first.example/graphql")

        assertEquals(0, result.statusCode)
        val config = CliConfigStore.load()
        assertEquals(DEFAULT_PROFILE_NAME, config.activeProfile)
        assertEquals("https://first.example/graphql", config.profiles[DEFAULT_PROFILE_NAME]?.endpoint)
    }

    @Test
    fun `invalid config is reported without overwriting it`() {
        val configFile = File(CliConfigStore.configPath())
        val invalid = "{ definitely-not-json"
        configFile.writeText(invalid)

        val result = profileCli().test("profile list")

        assertTrue(result.statusCode != 0)
        assertTrue("Could not read Bosca CLI configuration" in result.stderr)
        assertEquals(invalid, configFile.readText())
    }

    private fun saveProfiles(
        workAuth: AuthConfig = AuthConfig(
            token = "access-work",
            refreshToken = "refresh-work",
            principalId = "principal-work",
        ),
    ) {
        CliConfigStore.save(
            CliConfig(
                activeProfile = "work",
                profiles = mapOf(
                    "work" to ProfileConfig(
                        endpoint = "https://work.example/graphql",
                        auth = workAuth,
                    ),
                    "personal" to ProfileConfig(
                        endpoint = "https://personal.example/graphql",
                        auth = AuthConfig(
                            token = "access-personal",
                            refreshToken = "refresh-personal",
                            principalId = "principal-personal",
                        ),
                    ),
                ),
            ),
        )
    }
}
