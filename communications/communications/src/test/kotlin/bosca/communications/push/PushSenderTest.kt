package bosca.communications.push

import bosca.communications.model.PushAttachment
import bosca.communications.model.PushAction
import bosca.communications.model.PushConversation
import bosca.communications.model.PushOptions
import bosca.communications.model.PushRichContent
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.devices.model.PushProvider
import bosca.serialization.UUID
import com.google.firebase.messaging.MessagingErrorCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the behavior of [PushSendResult] delivery outcome tracking
 * and the completeness of the [PushPlatform] enumeration.
 */
class PushSenderTest {

    private val json = Json
    private val configurationId = UUID.random()
    private val configurationService = mockk<ConfigurationService>()

    init {
        coEvery { configurationService.getByKey(PushConfiguration.KEY) } returns Configuration(
            id = configurationId,
            key = PushConfiguration.KEY,
            description = "Push Notification Delivery Configuration",
            public = false,
        )
    }

    private fun configure(configuration: PushConfiguration) {
        coEvery { configurationService.getValue(configurationId) } returns json.encodeToJsonElement(
            PushConfiguration.serializer(),
            configuration,
        )
    }

    @Test
    fun `rich push uses its first image and serializes current conversation data`() {
        val options = PushOptions(
            richContent = PushRichContent(
                attachments = listOf(PushAttachment(url = "https://cdn.example/image.jpg")),
                conversation = PushConversation(
                    id = "channel-1",
                    messageId = "42",
                    senderName = "Ada",
                    body = "Project update",
                ),
            ),
        )

        assertEquals("https://cdn.example/image.jpg", options.notificationImageUrl())
        val payload = Json.parseToJsonElement(requireNotNull(options.richContentPayload(Json))).jsonObject
        assertEquals("channel-1", payload["conversation"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertFalse("messages" in requireNotNull(payload["conversation"]?.jsonObject))
    }

    @Test
    fun `provider payload validation counts encoded utf8 bytes`() {
        requireProviderPayloadWithinLimit("APNs", "x".repeat(4096))
        assertFailsWith<IllegalArgumentException> {
            requireProviderPayloadWithinLimit("APNs", "é".repeat(2049))
        }
    }

    @Test
    fun `APNs relevance score is constrained to the provider range`() {
        requireValidRelevanceScore(0.0)
        requireValidRelevanceScore(1.0)
        assertFailsWith<IllegalArgumentException> { requireValidRelevanceScore(-0.1) }
        assertFailsWith<IllegalArgumentException> { requireValidRelevanceScore(1.1) }
    }

    @Test
    fun `push actions serialize as a versioned provider envelope`() {
        val options = PushOptions(
            defaultAction = PushAction("open", "Open", "https://example.com/items/1"),
            actions = listOf(PushAction("dismiss", "Dismiss", destructive = true)),
        )

        val payload = Json.parseToJsonElement(requireNotNull(options.actionPayload(Json))).jsonObject
        assertEquals("open", payload["defaultAction"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("dismiss", payload["actions"]?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `push action envelope rejects duplicate action ids`() {
        val options = PushOptions(
            defaultAction = PushAction("open", url = "https://example.com/items/1"),
            actions = listOf(PushAction("open")),
        )

        assertFailsWith<IllegalArgumentException> { options.actionPayload(Json) }
    }

    @Test
    fun `Android pushes with actions require client rendering`() {
        val options = PushOptions(
            defaultAction = PushAction("open", url = "https://example.com/items/1"),
        )

        assertTrue(requiresAndroidClientRendering(PushPlatform.ANDROID, options))
    }

    @Test
    fun `Android rich pushes without conversations require client rendering`() {
        val options = PushOptions(
            richContent = PushRichContent(
                attachments = listOf(PushAttachment(url = "https://cdn.example/image.jpg")),
            ),
        )

        assertTrue(requiresAndroidClientRendering(PushPlatform.ANDROID, options))
    }

    @Test
    fun `Android client rendering carries the selected image and badge in provider data`() {
        val options = PushOptions(
            imageUrl = "https://cdn.example/standalone.jpg",
            badge = 7,
            defaultAction = PushAction("open", url = "https://example.com/items/1"),
        )

        val data = options.providerData(json, renderOnAndroidClient = true, title = "Title", body = "Body")

        assertEquals("https://cdn.example/standalone.jpg", data[ANDROID_NOTIFICATION_IMAGE_DATA_KEY])
        assertEquals("7", data[ANDROID_NOTIFICATION_BADGE_DATA_KEY])
        assertEquals("Title", data["title"])
        assertEquals("Body", data["body"])
    }

    @Test
    fun `ordinary Android and non Android pushes retain provider rendering`() {
        assertFalse(requiresAndroidClientRendering(PushPlatform.ANDROID, PushOptions()))
        assertFalse(
            requiresAndroidClientRendering(
                PushPlatform.IOS,
                PushOptions(defaultAction = PushAction("open")),
            ),
        )
    }

    /**
     * When every token in the batch fails and none succeed,
     * [PushSendResult.allFailed] must report true so callers
     * can trigger retry or alerting logic.
     */
    @Test
    fun allFailed_isTrueWhenSuccessCountIsZeroAndFailureCountIsPositive() {
        val result = PushSendResult(successCount = 0, failureCount = 3)
        assertTrue(result.allFailed)
    }

    /**
     * When at least one token succeeds, the batch is not considered
     * a total failure regardless of how many tokens failed.
     */
    @Test
    fun allFailed_isFalseWhenSuccessCountIsPositive() {
        val result = PushSendResult(successCount = 1, failureCount = 2)
        assertFalse(result.allFailed)
    }

    /**
     * A vacuous send (zero tokens attempted) should not be treated
     * as a total failure since no delivery was actually attempted.
     */
    @Test
    fun allFailed_isFalseWhenBothCountsAreZero() {
        val result = PushSendResult(successCount = 0, failureCount = 0)
        assertFalse(result.allFailed)
    }

    /**
     * Ensures the [PushPlatform] enum covers all supported device
     * platforms: Android, Web, iOS, and Desktop.
     */
    @Test
    fun pushPlatform_hasExactlyFourValues() {
        val values = PushPlatform.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(PushPlatform.ANDROID))
        assertTrue(values.contains(PushPlatform.WEB))
        assertTrue(values.contains(PushPlatform.IOS))
        assertTrue(values.contains(PushPlatform.DESKTOP))
    }

    /**
     * Data class structural equality should hold for [PushSendResult]
     * instances with identical success and failure counts.
     */
    @Test
    fun pushSendResult_equalityForIdenticalInstances() {
        val a = PushSendResult(successCount = 5, failureCount = 2)
        val b = PushSendResult(successCount = 5, failureCount = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * The [copy] function should produce a new instance with only the
     * specified fields changed, preserving the rest.
     */
    @Test
    fun pushSendResult_copyModifiesSpecifiedFields() {
        val original = PushSendResult(successCount = 3, failureCount = 1)
        val copied = original.copy(failureCount = 5)
        assertEquals(3, copied.successCount)
        assertEquals(5, copied.failureCount)
    }

    /**
     * Default parameter values should initialize both counts to zero
     * when no arguments are provided.
     */
    @Test
    fun pushSendResult_defaultsToZeroCounts() {
        val result = PushSendResult()
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertTrue(result.invalidTokens.isEmpty())
    }

    @Test
    fun pushSendResult_tracksPermanentlyInvalidTokens() {
        val result = PushSendResult(
            failureCount = 2,
            invalidTokens = setOf("expired-token", "unregistered-token"),
        )

        assertEquals(setOf("expired-token", "unregistered-token"), result.invalidTokens)
    }

    @Test
    fun unregisteredFcmTokenIsAlwaysInvalid() {
        assertTrue(isPermanentlyInvalidFcmToken(MessagingErrorCode.UNREGISTERED, payloadWasAccepted = false))
    }

    @Test
    fun invalidArgumentOnlyInvalidatesTokenWhenSharedPayloadWasAccepted() {
        assertFalse(isPermanentlyInvalidFcmToken(MessagingErrorCode.INVALID_ARGUMENT, payloadWasAccepted = false))
        assertTrue(isPermanentlyInvalidFcmToken(MessagingErrorCode.INVALID_ARGUMENT, payloadWasAccepted = true))
    }

    @Test
    fun unregisteredApnsTokenIsAlwaysInvalid() {
        assertTrue(isPermanentlyInvalidApnsToken(400, "Unregistered"))
        assertTrue(isPermanentlyInvalidApnsToken(410, null))
    }

    @Test
    fun apnsTopicAndEnvironmentRejectionsAreNeverPurgedAutomatically() {
        assertFalse(isPermanentlyInvalidApnsToken(400, "BadDeviceToken"))
        assertFalse(isPermanentlyInvalidApnsToken(400, "DeviceTokenNotForTopic"))
    }

    @Test
    fun incompleteApnsConfigurationFailsWithoutAttemptingDelivery() = runTest {
        configure(PushConfiguration(enabled = true, apns = ApnsConfiguration()))
        val sender = PushSenderImpl(json, configurationService)

        val result = sender.send(
            tokens = listOf("apns-token"),
            subject = "Subject",
            content = "Content",
            platform = PushPlatform.IOS,
            provider = PushProvider.APNS,
        )

        assertEquals(0, result.successCount)
        assertEquals(1, result.failureCount)
        assertTrue(result.invalidTokens.isEmpty())
    }

    @Test
    fun `the current push configuration is resolved for every send`() = runTest {
        val sender = PushSenderImpl(json, configurationService)

        configure(PushConfiguration(enabled = false, apns = ApnsConfiguration()))
        val disabledResult = sender.send(
            tokens = listOf("apns-token"),
            subject = "Subject",
            content = "Content",
            platform = PushPlatform.IOS,
            provider = PushProvider.APNS,
        )

        configure(PushConfiguration(enabled = true, apns = ApnsConfiguration()))
        val enabledResult = sender.send(
            tokens = listOf("apns-token"),
            subject = "Subject",
            content = "Content",
            platform = PushPlatform.IOS,
            provider = PushProvider.APNS,
        )

        assertEquals(PushSendResult(), disabledResult)
        assertEquals(1, enabledResult.failureCount)
    }

    @Test
    fun `missing push configuration safely disables delivery`() = runTest {
        coEvery { configurationService.getByKey(PushConfiguration.KEY) } returns null
        val sender = PushSenderImpl(json, configurationService)

        val result = sender.send(
            tokens = listOf("apns-token"),
            subject = "Subject",
            content = "Content",
            platform = PushPlatform.IOS,
            provider = PushProvider.APNS,
        )

        assertEquals(PushSendResult(), result)
    }
}
