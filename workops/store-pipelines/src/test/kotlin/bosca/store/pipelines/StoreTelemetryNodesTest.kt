@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.store.pipelines

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTelemetryNodesTest {
    private val secrets = mockk<PipelineSecretService>()
    private val play = mockk<PlayPublisher>()
    private val appStore = mockk<AppStorePublisher>()
    private val context = PipelineContext(AuthenticationContext(null, null), Json)
    private val inputs = NodeInputs(emptyMap())

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<PipelineSecretService> { secrets }
        provides<PlayPublisher> { play }
        provides<AppStorePublisher> { appStore }
        coEvery { secrets.resolveForExecution(any(), false) } answers { "credential:${firstArg<String>()}" }
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private suspend fun json(node: bosca.pipelines.node.PipelineNode): JsonObject =
        (node.run(context, inputs).result?.value as JsonObject)

    @Test
    fun `play vitals emits release-dimensional crash and ANR telemetry`() = runTest {
        coEvery { play.vitals("credential:play", "io.example.app", 42, Duration.ofHours(6)) } returns
            PlayVitalsResult(0.4, Duration.ofHours(6), 42, anrRate = 0.2)

        val output = json(PlayVitalsNode("vitals", applicationId = "io.example.app", appVersion = "1.2.3", versionCode = 42, windowHours = 6, credentialSecretName = "play"))

        assertEquals("GOOGLE_PLAY", output.getValue("store").jsonPrimitive.content)
        assertEquals("1.2.3", output.getValue("appVersion").jsonPrimitive.content)
        assertEquals(0.4, output.getValue("crashRate").jsonPrimitive.content.toDouble())
        assertEquals(0.2, output.getValue("anrRate").jsonPrimitive.content.toDouble())
        assertEquals(21_600, output.getValue("windowSeconds").jsonPrimitive.content.toLong())
    }

    @Test
    fun `review fetch nodes normalize Play and App Store reviews`() = runTest {
        coEvery { play.reviews("credential:play", "io.example.app", 2) } returns listOf(
            PlayReviewResult("p1", 2, "Needs work", "Pat", "en-US", 123),
        )
        coEvery { appStore.reviews("credential:asc", "io.example.app", 3) } returns listOf(
            AppStoreReviewResult("a1", 5, "Great", "Love it", "Sam", "USA", "2026-07-22T00:00:00Z"),
        )

        val playOutput = json(PlayReviewsNode("play", applicationId = "io.example.app", appVersion = "1.2.3", maxResults = 2, credentialSecretName = "play"))
        val appOutput = json(AppStoreReviewsNode("app", applicationId = "io.example.app", appVersion = "1.2.3", maxResults = 3, credentialSecretName = "asc"))

        assertEquals("Needs work", playOutput.getValue("reviews").jsonArray.single().jsonObject.getValue("body").jsonPrimitive.content)
        assertEquals("Great", appOutput.getValue("reviews").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("APP_STORE", appOutput.getValue("store").jsonPrimitive.content)
    }

    @Test
    fun `app store state emits review rollout processing and TestFlight state`() = runTest {
        coEvery { appStore.state("credential:asc", "io.example.app", "1.2.3", "42") } returns
            AppStoreStateResult(
                "1.2.3", "42", "WAITING_FOR_REVIEW", "APPROVED", "VALID", "ACTIVE", listOf("Internal"),
                listOf(
                    AppStoreCrashFeedbackResult("feedback-1", "Crash", null, "iPhone17,1", "19.0", null),
                    AppStoreCrashFeedbackResult(
                        "feedback-2", null, "tester@example.com", null, null, "2026-07-22T00:00:00Z",
                    ),
                ),
            )

        val output = json(AppStoreStateNode("state", applicationId = "io.example.app", appVersion = "1.2.3", buildNumber = "42", credentialSecretName = "asc"))

        assertEquals("WAITING_FOR_REVIEW", output.getValue("reviewState").jsonPrimitive.content)
        assertEquals("ACTIVE", output.getValue("phasedReleaseState").jsonPrimitive.content)
        assertEquals("Internal", output.getValue("testFlightGroups").jsonArray.single().jsonPrimitive.content)
        assertEquals(
            "Crash",
            output.getValue("testFlightCrashFeedback").jsonArray.first().jsonObject.getValue("comment").jsonPrimitive.content,
        )
    }

    @Test
    fun `telemetry nodes omit unavailable optional vendor fields`() = runTest {
        coEvery { play.vitals("credential:play", "io.example.app", null, Duration.ofHours(24)) } returns
            PlayVitalsResult(0.0, Duration.ofHours(24), null)
        coEvery { play.reviews("credential:play", "io.example.app", 1) } returns listOf(
            PlayReviewResult("p1", 3, "Okay", null, null, null),
        )
        coEvery { appStore.reviews("credential:asc", "io.example.app", 1) } returns listOf(
            AppStoreReviewResult("a1", 3, null, "Okay", null, null, null),
        )
        coEvery { appStore.state("credential:asc", "io.example.app", "1.2.3", null) } returns
            AppStoreStateResult("1.2.3", null, "UNKNOWN", null, null, null, emptyList())

        val vitals = json(
            PlayVitalsNode(
                "vitals", applicationId = "io.example.app", appVersion = "1.2.3",
                credentialSecretName = "play",
            ),
        )
        val playReview = json(
            PlayReviewsNode(
                "play", name = "Play Reviews", applicationId = "io.example.app", appVersion = "1.2.3", maxResults = 1,
                credentialSecretName = "play",
            ),
        ).getValue("reviews").jsonArray.single().jsonObject
        val appReview = json(
            AppStoreReviewsNode(
                "app", name = "App Reviews", applicationId = "io.example.app", appVersion = "1.2.3", maxResults = 1,
                credentialSecretName = "asc",
            ),
        ).getValue("reviews").jsonArray.single().jsonObject
        val state = json(
            AppStoreStateNode(
                "state", name = "App State", applicationId = "io.example.app", appVersion = "1.2.3",
                credentialSecretName = "asc",
            ),
        )

        assertTrue("versionCode" !in vitals)
        assertTrue(listOf("reviewer", "locale", "createdEpochSeconds").none { it in playReview })
        assertTrue(listOf("title", "reviewer", "territory", "createdAt").none { it in appReview })
        assertTrue(
            listOf("buildNumber", "betaReviewState", "buildProcessingState", "phasedReleaseState")
                .none { it in state },
        )
        assertTrue(state.getValue("testFlightCrashFeedback").jsonArray.isEmpty())
    }

    @Test
    fun `dry runs emit dimensional previews without resolving credentials or calling stores`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)
        val nodes = listOf(
            PlayVitalsNode("v", applicationId = "play", appVersion = "1", windowHours = 0),
            PlayReviewsNode("pr", applicationId = "play", appVersion = "1"),
            AppStoreReviewsNode("ar", applicationId = "app", appVersion = "1"),
            AppStoreStateNode("as", applicationId = "app", appVersion = "1"),
        )

        nodes.forEach { node ->
            val output = node.run(dry, inputs).result?.value as JsonObject
            assertTrue(output.getValue("dryRun").jsonPrimitive.content.toBoolean())
        }
        coVerify(exactly = 0) { secrets.resolveForExecution(any(), any()) }
        coVerify(exactly = 0) { play.vitals(any(), any(), any(), any()) }
        coVerify(exactly = 0) { play.reviews(any(), any(), any()) }
        coVerify(exactly = 0) { appStore.reviews(any(), any(), any()) }
        coVerify(exactly = 0) { appStore.state(any(), any(), any(), any()) }
    }

    @Test
    fun `nodes fail loudly for missing dimensions and invalid windows`() = runTest {
        assertTrue("applicationId" in assertFailsWith<IllegalArgumentException> {
            PlayVitalsNode("v", name = "Named", appVersion = "1").run(context, inputs)
        }.message.orEmpty())
        assertTrue("appVersion" in assertFailsWith<IllegalArgumentException> {
            PlayReviewsNode("r", applicationId = "app").run(context, inputs)
        }.message.orEmpty())
        assertTrue("positive windowHours" in assertFailsWith<IllegalArgumentException> {
            PlayVitalsNode("v", applicationId = "app", appVersion = "1", windowHours = 0).run(context, inputs)
        }.message.orEmpty())
        assertTrue("credentialSecretName" in assertFailsWith<IllegalArgumentException> {
            AppStoreStateNode("s", applicationId = "app", appVersion = "1").run(context, inputs)
        }.message.orEmpty())
    }

    @Test
    fun `all telemetry nodes round-trip through explicit serializers`() {
        val serializers = SerializersModule {
            contextual(bosca.serialization.UUID::class, bosca.serialization.UUIDSerializer())
        }
        val nodeJson = Json { serializersModule = serializers }
        val encodeDefaultsJson = Json { encodeDefaults = true; serializersModule = serializers }
        fun <T : PipelineNode> T.fullBody(): T = apply {
            retry = RetryPolicy(3, 1, 2.0, 30)
            timeoutSeconds = 60
            rollbackPipeline = bosca.serialization.UUID.random()
        }
        encodeDefaultsJson.encodeToString(PlayVitalsNode.serializer(), PlayVitalsNode("vd"))
        encodeDefaultsJson.encodeToString(PlayReviewsNode.serializer(), PlayReviewsNode("pd"))
        encodeDefaultsJson.encodeToString(AppStoreReviewsNode.serializer(), AppStoreReviewsNode("ad"))
        encodeDefaultsJson.encodeToString(AppStoreStateNode.serializer(), AppStoreStateNode("sd"))
        val defaults = listOf(
            nodeJson.decodeFromString(PlayVitalsNode.serializer(), nodeJson.encodeToString(PlayVitalsNode.serializer(), PlayVitalsNode("v"))).id,
            nodeJson.decodeFromString(PlayReviewsNode.serializer(), nodeJson.encodeToString(PlayReviewsNode.serializer(), PlayReviewsNode("p"))).id,
            nodeJson.decodeFromString(AppStoreReviewsNode.serializer(), nodeJson.encodeToString(AppStoreReviewsNode.serializer(), AppStoreReviewsNode("a"))).id,
            nodeJson.decodeFromString(AppStoreStateNode.serializer(), nodeJson.encodeToString(AppStoreStateNode.serializer(), AppStoreStateNode("s"))).id,
        )
        val position = NodePosition(1.0, 2.0)
        val full = listOf(
            nodeJson.decodeFromString(
                PlayVitalsNode.serializer(),
                nodeJson.encodeToString(
                    PlayVitalsNode.serializer(),
                    PlayVitalsNode("vf", "Vitals", "Description", "app", "1", 42L, 12L, "secret", position).fullBody(),
                ),
            ).id,
            nodeJson.decodeFromString(
                PlayReviewsNode.serializer(),
                nodeJson.encodeToString(
                    PlayReviewsNode.serializer(),
                    PlayReviewsNode("pf", "Play", "Description", "app", "1", 25, "secret", position).fullBody(),
                ),
            ).id,
            nodeJson.decodeFromString(
                AppStoreReviewsNode.serializer(),
                nodeJson.encodeToString(
                    AppStoreReviewsNode.serializer(),
                    AppStoreReviewsNode("af", "App", "Description", "app", "1", 25, "secret", position).fullBody(),
                ),
            ).id,
            nodeJson.decodeFromString(
                AppStoreStateNode.serializer(),
                nodeJson.encodeToString(
                    AppStoreStateNode.serializer(),
                    AppStoreStateNode("sf", "State", "Description", "app", "1", "42", "secret", position).fullBody(),
                ),
            ).id,
        )
        assertEquals(listOf("v", "p", "a", "s"), defaults)
        assertEquals(listOf("vf", "pf", "af", "sf"), full)
    }
}
