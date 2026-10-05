package bosca.analytics.api

import bosca.analytics.experimentation.FeatureFlag
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalyticsSerializationTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val compactJson = Json { encodeDefaults = false; explicitNulls = true }

    @Test
    fun `models support minimal and populated collector forms`() {
        val minimalContent = json.decodeFromString(ContentElement.serializer(), """{"id":"id","type":"type"}""")
        val fullContent = json.decodeFromString(
            ContentElement.serializer(),
            """{"id":"id","type":"type","index":3,"percent":0.75}""",
        )
        val minimalElement = json.decodeFromString(AnalyticsElement.serializer(), """{"id":"id","type":"type"}""")
        val fullElement = AnalyticsElement("id", "type", listOf(fullContent), mapOf("key" to "value"))
        val minimalPage = json.decodeFromString(Page.serializer(), "{}")
        val fullPage = Page("/path", "https://example.test/path", "Title")
        val minimalError = json.decodeFromString(ErrorInfo.serializer(), """{"message":"failed"}""")
        val fullError = ErrorInfo("failed", "network", "trace", true, "E1")
        val minimalGeo = json.decodeFromString(Geo.serializer(), "{}")
        val fullGeo = Geo("Austin", "TX", "US")
        val browser = Browser("agent")
        val device = Device("iid", "maker", "model", "platform", "en", "system", "UTC", "desktop", "1")
        val minimalContext = AnalyticsContext("app", "1", "client", device, sessionId = "session")
        val fullContext = AnalyticsContext("app", "1", "client", device, fullGeo, browser, "session", "user")
        val minimalFlag = json.decodeFromString(FeatureFlag.serializer(), """{"flagKey":"flag","value":true}""")
        val fullFlag = FeatureFlag("flag", JsonPrimitive("value"), "variation", "experiment")
        val minimalEvent = AnalyticsEvent("event", AnalyticsEventType.INTERACTION, 1, 2, minimalElement)
        val fullEvent = AnalyticsEvent("event", AnalyticsEventType.ERROR, 1, 2, fullElement, fullPage, fullError)
        val minimalEvents = Events(minimalContext, 1, 2, listOf(minimalEvent))
        val fullEvents = Events(fullContext, 1, 2, listOf(fullEvent))

        exerciseCopies(
            minimalContent,
            fullContent,
            minimalElement,
            fullElement,
            minimalPage,
            fullPage,
            minimalError,
            fullError,
            minimalGeo,
            fullGeo,
            device,
            minimalContext,
            fullContext,
            minimalFlag,
            fullFlag,
        )

        listOf(
            encodeBoth(ContentElement.serializer(), minimalContent),
            encodeBoth(ContentElement.serializer(), fullContent),
            encodeBoth(AnalyticsElement.serializer(), minimalElement),
            encodeBoth(AnalyticsElement.serializer(), fullElement),
            encodeBoth(Page.serializer(), minimalPage),
            encodeBoth(Page.serializer(), fullPage),
            encodeBoth(ErrorInfo.serializer(), minimalError),
            encodeBoth(ErrorInfo.serializer(), fullError),
            encodeBoth(Geo.serializer(), minimalGeo),
            encodeBoth(Geo.serializer(), fullGeo),
            encodeBoth(Browser.serializer(), browser),
            encodeBoth(Device.serializer(), device),
            encodeBoth(AnalyticsContext.serializer(), minimalContext),
            encodeBoth(AnalyticsContext.serializer(), fullContext),
            encodeBoth(FeatureFlag.serializer(), minimalFlag),
            encodeBoth(FeatureFlag.serializer(), fullFlag),
            encodeBoth(AnalyticsEvent.serializer(), minimalEvent),
            encodeBoth(AnalyticsEvent.serializer(), fullEvent),
            encodeBoth(Events.serializer(), minimalEvents),
            encodeBoth(Events.serializer(), fullEvents),
        ).flatten().forEach { assertTrue(it.isNotBlank()) }

        assertEquals(device, json.decodeFromString(Device.serializer(), json.encodeToString(Device.serializer(), device)))
        assertEquals(browser, json.decodeFromString(Browser.serializer(), """{"agent":"agent"}"""))
        assertEquals(fullEvent, json.decodeFromString(AnalyticsEvent.serializer(), json.encodeToString(AnalyticsEvent.serializer(), fullEvent)))
        assertEquals(fullEvents, json.decodeFromString(Events.serializer(), json.encodeToString(Events.serializer(), fullEvents)))
    }

    @Test
    fun `serializers reject missing required properties`() {
        listOf(
            ContentElement.serializer(),
            AnalyticsElement.serializer(),
            ErrorInfo.serializer(),
            Device.serializer(),
            Browser.serializer(),
            AnalyticsContext.serializer(),
            FeatureFlag.serializer(),
            AnalyticsEvent.serializer(),
            Events.serializer(),
        ).forEach { serializer ->
            assertFailsWith<SerializationException> {
                @Suppress("UNCHECKED_CAST")
                json.decodeFromString(serializer as DeserializationStrategy<Any>, "{}")
            }
        }
    }

    private fun <T> encodeBoth(serializer: kotlinx.serialization.KSerializer<T>, value: T): List<String> = listOf(
        json.encodeToString(serializer, value),
        compactJson.encodeToString(serializer, value),
    )

    @Suppress("LongParameterList")
    private fun exerciseCopies(
        minimalContent: ContentElement,
        fullContent: ContentElement,
        minimalElement: AnalyticsElement,
        fullElement: AnalyticsElement,
        minimalPage: Page,
        fullPage: Page,
        minimalError: ErrorInfo,
        fullError: ErrorInfo,
        minimalGeo: Geo,
        fullGeo: Geo,
        device: Device,
        minimalContext: AnalyticsContext,
        fullContext: AnalyticsContext,
        minimalFlag: FeatureFlag,
        fullFlag: FeatureFlag,
    ) {
        minimalContent.copy(id = "copy")
        fullContent.copy(type = "copy", index = null, percent = null)
        minimalElement.copy(id = "copy")
        fullElement.copy(type = "copy", content = emptyList(), extras = emptyMap())
        minimalPage.copy(path = "/copy")
        fullPage.copy(url = null, title = null)
        minimalError.copy(message = "copy")
        fullError.copy(type = null, stackTrace = null, fatal = false, code = null)
        minimalGeo.copy(city = "copy")
        fullGeo.copy(region = "", country = "")
        minimalContext.copy(appId = "copy")
        fullContext.copy(
            appVersion = "2",
            clientId = "copy",
            device = device.copy(model = "copy"),
            geo = Geo(),
            browser = null,
            sessionId = "copy",
            userId = null,
        )
        minimalFlag.copy(flagKey = "copy")
        fullFlag.copy(value = JsonPrimitive(false), variationKey = null, experimentId = null)
    }
}
