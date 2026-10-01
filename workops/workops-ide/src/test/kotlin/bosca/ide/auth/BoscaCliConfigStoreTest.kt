package bosca.ide.auth

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoscaCliConfigStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads named CLI profiles and resolves the active account for a shared endpoint`() {
        val directory = temporaryFolder.newFolder("config").toPath()
        Files.writeString(
            directory.resolve("config.json"),
            """
                {
                  "activeProfile": "personal",
                  "profiles": {
                    "work": {
                      "endpoint": "https://bosca.example/graphql",
                      "auth": { "token": "work-token", "principalId": "work-principal" }
                    },
                    "personal": {
                      "endpoint": "https://bosca.example/graphql/",
                      "auth": { "refreshToken": "personal-refresh" }
                    },
                    "signed-out": { "endpoint": "https://other.example/graphql" }
                  }
                }
            """.trimIndent(),
        )
        val store = BoscaCliConfigStore(directory)

        val profiles = store.profiles()

        assertEquals(listOf("personal", "signed-out", "work"), profiles.map { it.name })
        assertTrue(profiles.first { it.name == "personal" }.active)
        assertTrue(profiles.first { it.name == "personal" }.authenticated)
        assertFalse(profiles.first { it.name == "signed-out" }.authenticated)
        assertEquals("personal", store.resolveProfileName("https://bosca.example/graphql"))
        assertEquals("work-principal", store.auth("work", "https://bosca.example/graphql/")?.principalId)
    }

    @Test
    fun `token storage rotates one CLI session without overwriting other profile data`() = runBlocking {
        val directory = temporaryFolder.newFolder("rotate").toPath()
        Files.writeString(
            directory.resolve("config.json"),
            """
                {
                  "activeProfile": "work",
                  "futureField": { "keep": true },
                  "profiles": {
                    "work": {
                      "endpoint": "https://work.example/graphql",
                      "auth": {
                        "token": "old-token",
                        "refreshToken": "old-refresh",
                        "principalId": "principal"
                      }
                    },
                    "other": {
                      "endpoint": "https://other.example/graphql",
                      "auth": { "token": "other-token" }
                    }
                  }
                }
            """.trimIndent(),
        )
        val storage = BoscaCliTokenStorage(
            BoscaCliConfigStore(directory),
            "work",
            "https://work.example/graphql",
        )

        storage.saveToken("new-token")
        storage.saveRefreshToken("new-refresh")

        val saved = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).asJsonObject
        val profiles = saved.getAsJsonObject("profiles")
        val auth = profiles.getAsJsonObject("work").getAsJsonObject("auth")
        assertEquals("new-token", auth.get("token").asString)
        assertEquals("new-refresh", auth.get("refreshToken").asString)
        assertEquals("principal", auth.get("principalId").asString)
        assertEquals("other-token", profiles.getAsJsonObject("other").getAsJsonObject("auth").get("token").asString)
        assertTrue(saved.getAsJsonObject("futureField").get("keep").asBoolean)
    }

    @Test
    fun `storage refuses to read or write credentials after a CLI profile changes endpoint`() = runBlocking {
        val directory = temporaryFolder.newFolder("retargeted").toPath()
        Files.writeString(
            directory.resolve("config.json"),
            """
                {
                  "activeProfile": "work",
                  "profiles": {
                    "work": {
                      "endpoint": "https://new.example/graphql",
                      "auth": { "token": "new-server-token" }
                    }
                  }
                }
            """.trimIndent(),
        )
        val storage = BoscaCliTokenStorage(
            BoscaCliConfigStore(directory),
            "work",
            "https://old.example/graphql",
        )

        assertNull(storage.getToken())
        assertThrows(BoscaCliConfigException::class.java) {
            runBlocking { storage.saveToken("must-not-be-written") }
        }
        assertEquals(
            "new-server-token",
            JsonParser.parseString(Files.readString(directory.resolve("config.json")))
                .asJsonObject.getAsJsonObject("profiles")
                .getAsJsonObject("work").getAsJsonObject("auth").get("token").asString,
        )
    }

    @Test
    fun `updating a legacy single-profile config preserves its session while migrating the shape`() = runBlocking {
        val directory = temporaryFolder.newFolder("legacy").toPath()
        Files.writeString(
            directory.resolve("config.json"),
            """{"endpoint":"https://bosca.example/graphql","auth":{"token":"old","principalId":"p"}}""",
        )
        val store = BoscaCliConfigStore(directory)
        val storage = BoscaCliTokenStorage(store, DEFAULT_CLI_PROFILE_NAME, "https://bosca.example/graphql")

        assertEquals(DEFAULT_CLI_PROFILE_NAME, store.profiles().single().name)
        storage.saveToken("new")

        val saved = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).asJsonObject
        assertEquals(DEFAULT_CLI_PROFILE_NAME, saved.get("activeProfile").asString)
        assertFalse(saved.has("endpoint"))
        assertEquals(
            "new",
            saved.getAsJsonObject("profiles").getAsJsonObject(DEFAULT_CLI_PROFILE_NAME)
                .getAsJsonObject("auth").get("token").asString,
        )
    }
}
