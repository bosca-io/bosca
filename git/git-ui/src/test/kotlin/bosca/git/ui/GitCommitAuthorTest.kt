package bosca.git.ui

import bosca.git.ui.graphql.GitUiProfilesData.Profiles.Current
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class GitCommitAuthorTest {
    @Test
    fun `primary profile supplies the author before other profiles`() {
        val author =
            resolveGitCommitAuthor(listOf(profile(name = "Other", primary = false), profile()))
        assertEquals("Alice" to "alice@example.test", author)
    }

    @Test
    fun `first profile is used when no primary profile exists`() {
        assertEquals(
            "Alice" to "alice@example.test",
            resolveGitCommitAuthor(listOf(profile(primary = false))),
        )
    }

    @Test
    fun `missing name uses the slug then the Studio placeholder`() {
        assertEquals(
            "alice" to "alice@example.test",
            resolveGitCommitAuthor(listOf(profile(name = " "))),
        )
        assertEquals(
            "Studio User" to "alice@example.test",
            resolveGitCommitAuthor(listOf(profile(name = "", slug = null))),
        )
    }

    @Test
    fun `absent profiles use Studio author placeholders`() {
        for (profiles in listOf<List<Current>?>(null, emptyList())) {
            assertEquals("Studio User" to "studio@bosca.io", resolveGitCommitAuthor(profiles))
        }
    }

    @Test
    fun `missing and malformed email attributes use the Studio placeholder`() {
        val missingEmail = profile().copy(attributes = emptyList())
        assertEquals("Alice" to "studio@bosca.io", resolveGitCommitAuthor(listOf(missingEmail)))
        for (email in
            listOf(
                null,
                JsonNull,
                JsonPrimitive(""),
                JsonPrimitive(" "),
                JsonPrimitive(7),
                JsonPrimitive(false),
                JsonArray(emptyList()),
                JsonObject(emptyMap()),
            )) {
            assertEquals(
                "Alice" to "studio@bosca.io",
                resolveGitCommitAuthor(listOf(profile(email = email))),
                "email=$email",
            )
        }
        for (attributes in listOf(JsonNull, JsonPrimitive("bad"), JsonArray(emptyList()))) {
            assertEquals(
                "Alice" to "studio@bosca.io",
                resolveGitCommitAuthor(
                    listOf(
                        profile()
                            .copy(
                                attributes =
                                    listOf(Current.Attributes("bosca.profiles.email", attributes))
                            )
                    )
                ),
            )
        }
    }

    private fun profile(
        name: String = "Alice",
        slug: String? = "alice",
        primary: Boolean = true,
        email: JsonElement? = JsonPrimitive("alice@example.test"),
    ) =
        Current(
            "profile",
            name,
            slug,
            primary,
            listOf(
                Current.Attributes(
                    "bosca.profiles.email",
                    buildJsonObject { email?.let { put("email", it) } },
                )
            ),
        )
}
