package bosca.analytics.service

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.repository.AnalyticsVisualizationPermissionRepository
import bosca.analytics.repository.AnalyticsVisualizationRepository
import bosca.cache.CacheManager
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsVisualizationServiceTest {

    private val repository = mockk<AnalyticsVisualizationRepository>()
    private val permissionRepository = mockk<AnalyticsVisualizationPermissionRepository>()
    private lateinit var service: AnalyticsVisualizationServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        val cacheManager = mockk<CacheManager>()
        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns mockk(relaxed = true)
        provides { cacheManager }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        service = AnalyticsVisualizationServiceImpl(repository, permissionRepository)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `addVisualization should add and return visualization`() = runTest {
        val input = AnalyticsVisualizationInput(
            key = "key",
            name = "name",
            description = "description",
            queryId = UUID.random(),
            type = AnalyticsVisualizationType.BAR,
            configuration = JsonNull
        )
        val visualization = AnalyticsVisualization(
            id = UUID.random(),
            key = input.key,
            name = input.name,
            description = input.description,
            queryId = input.queryId,
            type = input.type,
            configuration = JsonNull
        )

        coEvery { repository.add(any()) } returns visualization

        val result = service.addVisualization(input)

        assertEquals(visualization, result)
        coVerify { repository.add(any()) }
    }
}
