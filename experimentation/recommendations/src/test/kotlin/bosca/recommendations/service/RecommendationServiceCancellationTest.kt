@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.service

import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.languages.model.LanguageTagResolution
import bosca.languages.service.LanguagesService
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class RecommendationServiceCancellationTest {

    private lateinit var server: MockWebServer
    private val contextService = mockk<RecommendationContextService>()
    private val languagesService = mockk<LanguagesService>()

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        val context = RecommendationContext(
            id = UUID.random(),
            type = RecommendationContext.DEFAULT_TYPE,
            name = "Default",
            activeModelVersion = 1,
        )
        coEvery { contextService.getByType(RecommendationContext.DEFAULT_TYPE) } returns context
        coEvery { contextService.getModel(1) } returns RecommendationContextModel(
            version = 1, contextId = context.id, revision = 0, selectionRevision = 0, context = context,
            status = RecommendationTrainingStatus.COMPLETED, exported = true,
        )
        coEvery { languagesService.resolveLanguageTag(any(), any()) } returns LanguageTagResolution(
            requestedLanguageTag = null,
            normalizedLanguageTag = null,
            resolvedLanguageTag = "en",
            usedFallback = true,
        )
    }

    @AfterTest
    fun teardown() {
        server.close()
        ProviderRegistry.clear()
    }

    @Test
    fun `getSimilar propagates cancellation from the content model request`() {
        runBlocking {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .headersDelay(30, TimeUnit.SECONDS)
                    .body("""{"predictions":[]}""")
                    .build(),
            )
            val service = service()

            coroutineScope {
                val result = async { service.getSimilar(UUID.random(), 10) }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                result.cancel()

                assertFailsWith<CancellationException> { result.await() }
            }
        }
    }

    private fun service() = RecommendationServiceImpl(
        recommendationRepository = mockk(relaxed = true),
        dismissalRepository = mockk(relaxed = true),
        placementRepository = mockk(relaxed = true),
        placementStrategyRepository = mockk(relaxed = true),
        strategyRepository = mockk(relaxed = true),
        coEngagementRepository = mockk(relaxed = true),
        metadataService = mockk(relaxed = true),
        profileService = mockk(relaxed = true),
        ratingService = mockk(relaxed = true),
        contextService = contextService,
        languagesService = languagesService,
        json = Json { ignoreUnknownKeys = true },
        tfServingConfiguration = TfServingConfiguration(
            url = server.url("/").toString().trimEnd('/'),
            timeoutSeconds = 60,
        ),
    )

    @Test
    fun `sources propagate cancellation instead of replacing it with empty attribution`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS)
            .body("""{"predictions":[]}""").build())
        val recommendation = bosca.recommendations.model.Recommendation(
            metadataId = UUID.random(), strategyId = UUID.NIL,
            inference = bosca.recommendations.model.RecommendationInference(1, false, "default", "en", sourceId = UUID.random()),
        )
        coroutineScope {
            val result = async { service().getSources(listOf(recommendation)) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            result.cancel()
            assertFailsWith<CancellationException> { result.await() }
        }
    }

}
