package bosca.notifications.web

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class PreferencesTest {

    private val rows = listOf(
        TypeRow(key = "marketing", name = "Marketing", optional = true),
        TypeRow(key = "digest", name = "Digest", optional = true, emailOptedOut = true, pushOptedOut = true),
        TypeRow(key = "security", name = "Security", optional = false),
    )

    @Test
    fun `toggling email flips only that row's email channel`() {
        val toggledRows = toggled(rows, "marketing", CHANNEL_EMAIL)
        val marketing = toggledRows.first { it.key == "marketing" }
        assertTrue(marketing.emailOptedOut)
        assertFalse(marketing.pushOptedOut)
        assertEquals(rows.filter { it.key != "marketing" }, toggledRows.filter { it.key != "marketing" })
    }

    @Test
    fun `toggling push flips only that row's push channel`() {
        val toggledRows = toggled(rows, "digest", CHANNEL_PUSH)
        val digest = toggledRows.first { it.key == "digest" }
        assertTrue(digest.emailOptedOut)
        assertFalse(digest.pushOptedOut)
    }

    @Test
    fun `non-optional rows never toggle`() {
        assertEquals(rows, toggled(rows, "security", CHANNEL_EMAIL))
        assertEquals(rows, toggled(rows, "security", CHANNEL_PUSH))
    }

    @Test
    fun `unknown keys change nothing`() {
        assertEquals(rows, toggled(rows, "nope", CHANNEL_EMAIL))
    }

    @Test
    fun `unsubscribe-all opts out email on optional rows only, leaving push alone`() {
        val off = allOptionalEmailOff(rows)
        assertTrue(off.first { it.key == "marketing" }.emailOptedOut)
        assertFalse(off.first { it.key == "marketing" }.pushOptedOut)
        assertTrue(off.first { it.key == "digest" }.emailOptedOut)
        assertFalse(off.first { it.key == "security" }.emailOptedOut)
    }

    @Test
    fun `status class reflects the failed flag`() {
        val model = PreferencesModel(token = "t")
        assertEquals("status ok", model.statusClass)
        model.failed = true
        assertEquals("status error", model.statusClass)
    }

    @Test
    fun `preferences page renders accessible visual switches`() = runBlocking {
        val gql = object : GraphQLClient {
            override suspend fun execute(
                query: String,
                variables: JsonObject?,
                operationName: String?,
                token: String?,
            ): JsonElement {
                assertEquals("TokenPreferences", operationName)
                return Json.parseToJsonElement(
                    """
                    {
                      "communications": {
                        "notificationTypes": [{
                          "key": "marketing",
                          "name": "Marketing & updates",
                          "description": "Product news",
                          "optional": true,
                          "system": false,
                          "displayOrder": 1
                        }],
                        "tokenNotificationPreferences": [
                          {"channel": "EMAIL", "type": "marketing", "optedOut": false},
                          {"channel": "PUSH", "type": "marketing", "optedOut": true}
                        ]
                      }
                    }
                    """.trimIndent(),
                )
            }
        }
        val context = RenderContext(gql = gql, query = mapOf("token" to "preference-token"))

        withRenderContext(context) {
            bml.generated.PagesPreferencesPage.render(context)
        }

        val html = context.writer.toString()
        assertEquals(2, Regex("""role="switch"""").findAll(html).count(), html)
        assertTrue("""aria-checked="true"""" in html, html)
        assertTrue("""aria-checked="false"""" in html, html)
        assertTrue("""aria-label="Email notifications for Marketing &amp; updates"""" in html, html)
        assertTrue("""aria-label="Push notifications for Marketing &amp; updates"""" in html, html)
        assertEquals(2, Regex("""class="switch-thumb"""").findAll(html).count(), html)
        assertFalse(">On</button>" in html, html)
        assertFalse(">Off</button>" in html, html)
    }
}
