package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The i18n manifest's pure halves: static-call scanning and JSON rendering. */
class BmlI18nManifestTest {

    @Test
    fun `scans every static t() call with line numbers`() {
        val source = """
            <page route="/">
              <script server provides="title">t("home.title")</script>
              <p>{ t("home.title") }</p>
              <span :data-x="t("nav.close")">x</span>
              <script server provides="d">t(dynamicKey)</script>
              <p>format("not.a.t.call")</p>
            </page>
        """.trimIndent()
        val entries = scanFunctionKeys(source, "home.bml")
        assertEquals(listOf("home.title", "home.title", "nav.close"), entries.map { it.key })
        assertEquals(BmlI18nOrigin.FUNCTION, entries[0].origin)
        assertEquals(2, entries[0].line)
        assertEquals(3, entries[1].line)
        assertEquals(4, entries[2].line)
    }

    @Test
    fun `scans authored defaults and placeholder pairs from static t calls`() {
        val source = """
            <message key="welcome">
              <script server provides="subject">t("mail.subject", "Welcome, {name}", "name" to user.displayName)</script>
              <script server provides="label">t("mail.label", "recipient" to recipientName)</script>
              <script server provides="count">t(
                "mail.count",
                itemCount,
                "owner" to format(user.name, locale),
              )</script>
            </message>
        """.trimIndent()

        val entries = scanFunctionKeys(source, "mail.bml").associateBy { it.key }

        assertEquals("Welcome, {name}", entries.getValue("mail.subject").message)
        assertEquals(
            listOf("name" to "user.displayName"),
            entries.getValue("mail.subject").placeholders.map { it.name to it.expression },
        )
        assertEquals(null, entries.getValue("mail.label").message)
        assertEquals(
            listOf("recipient" to "recipientName"),
            entries.getValue("mail.label").placeholders.map { it.name to it.expression },
        )
        assertEquals(
            listOf("owner" to "format(user.name, locale)"),
            entries.getValue("mail.count").placeholders.map { it.name to it.expression },
        )
    }

    @Test
    fun `manifest json carries messages, plural forms, placeholders, and escapes`() {
        val entries = listOf(
            BmlI18nEntry(
                key = "cart.items",
                message = null,
                pluralForms = mapOf("ONE" to "one \"item\"", "OTHER" to "{count} items"),
                placeholders = listOf(BmlI18nPlaceholder("count", "items.size")),
                origin = BmlI18nOrigin.ELEMENT,
                file = "cart.bml",
                line = 9,
            ),
            BmlI18nEntry(
                key = "home.title",
                message = "Welcome",
                origin = BmlI18nOrigin.FUNCTION,
                file = "home.bml",
                line = 2,
            ),
        )
        val json = buildI18nManifest(entries)
        assertTrue("\"manifestVersion\": 1" in json, json)
        assertTrue("\"key\": \"cart.items\", \"plural\": true" in json, json)
        assertTrue("\"forms\": {\"ONE\": \"one \\\"item\\\"\", \"OTHER\": \"{count} items\"}" in json, json)
        assertTrue("\"placeholders\": [{\"name\": \"count\", \"expression\": \"items.size\"}]" in json, json)
        assertTrue("\"message\": \"Welcome\"" in json, json)
        assertTrue("\"origin\": \"FUNCTION\"" in json, json)
        // Valid JSON end to end — parse it back with the platform's serializer.
        kotlinx.serialization.json.Json.parseToJsonElement(json)
    }
}
