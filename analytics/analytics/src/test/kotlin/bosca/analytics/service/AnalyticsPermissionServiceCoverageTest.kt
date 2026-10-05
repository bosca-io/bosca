@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsDashboardPermission
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationPermission
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.model.QueryDefinitionPermission
import bosca.analytics.model.QueryParameterType
import bosca.analytics.repository.AnalyticsDashboardPermissionRepository
import bosca.analytics.repository.AnalyticsDashboardRepository
import bosca.analytics.repository.AnalyticsDashboardVisualizationRepository
import bosca.analytics.repository.AnalyticsVisualizationPermissionRepository
import bosca.analytics.repository.AnalyticsVisualizationRepository
import bosca.analytics.repository.QueryDefinitionRepository
import bosca.analytics.repository.QueryParameterRepository
import bosca.analytics.repository.QueryPermissionRepository
import bosca.analytics.query.QueryParameterDeclaration
import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.cache.serializers.UUIDKeySerializer
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.service.SourceRefService
import bosca.graphql.Batch
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import bosca.db.transaction
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnalyticsPermissionServiceCoverageTest {
    private val cacheManager = mockk<CacheManager>()
    private val cache = mockk<Cache<UUID>>(relaxed = true)
    private val serializer = mockk<RequestCacheSerializer>(relaxed = true)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        every { cache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.maybeAddCache<UUID>(any(), any(), any()) } returns cache
        coEvery { cacheManager.getCache<UUID>(any()) } returns cache
        coEvery { cache.get(any()) } returns missing()
        coEvery { cache.getBatch(any()) } answers {
            firstArg<List<*>>().map { missing() }
        }
        provides<CacheManager> { cacheManager }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private fun missing(): CacheValue = mockk {
        every { exists } returns false
        every { value } returns null
    }

    private fun cachedNull(): CacheValue = mockk {
        every { exists } returns true
        every { value } returns null
    }

    private suspend fun <T> withCache(block: suspend () -> T): T =
        withContext(RequestCache(cacheManager, serializer).asCoroutineContext()) { block() }

    @Test
    fun `query permissions resolve individually and in batches and invalidate on mutations`() = runTest {
        val queryRepository = mockk<QueryDefinitionRepository>(relaxed = true)
        val parameterRepository = mockk<QueryParameterRepository>(relaxed = true)
        val permissions = mockk<QueryPermissionRepository>()
        val sourceRefs = mockk<ObjectProvider<SourceRefService>>()
        val resultCache = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
        val service = AnalyticsQueryServiceImpl(queryRepository, parameterRepository, permissions, sourceRefs, resultCache)
        val query = AnalyticsQuery(key = "q", name = "q", description = "q", query = "select 1")
        val other = UUID.random()
        val group = UUID.random()
        val stored = QueryDefinitionPermission(query.id, group, PermissionAction.VIEW)
        coEvery { permissions.getPermissionsById(query.id) } returns listOf(stored)
        coEvery { permissions.getPermissionsByIds(listOf(query.id, other)) } returns listOf(stored)

        assertEquals(listOf(stored), withCache { service.getPermissions(query) })
        coEvery { cache.get(any()) } returns cachedNull()
        assertEquals(emptyList(), withCache { service.getPermissions(query) })
        val batch = Batch<UUID, List<bosca.security.model.EntityPermission>>(listOf(query.id, other))
        withCache { service.addPermissionsToBatch(batch) }
        assertEquals(listOf(stored), batch.getData(query.id))
        assertNull(batch.getData(other))

        val input = PermissionInput(PermissionAction.EDIT, query.id, group)
        coJustRun { permissions.addPermission(any(), any(), any()) }
        coJustRun { permissions.deletePermission(any(), any(), any()) }
        withCache {
            assertEquals(group, service.addPermission(input).groupId)
            assertEquals(group, service.deletePermission(input).groupId)
        }
        coVerify { permissions.addPermission(query.id, group, PermissionAction.EDIT) }
        coVerify { permissions.deletePermission(query.id, group, PermissionAction.EDIT) }
        coEvery { parameterRepository.getParametersForQueries(listOf(query.id)) } returns emptyList()
        assertEquals(emptyList(), service.getParametersForQueries(listOf(query.id)))
    }

    @Test
    fun `query deletion handles present and absent source ref providers`() = runTest {
        val queryRepository = mockk<QueryDefinitionRepository>(relaxed = true)
        val parameterRepository = mockk<QueryParameterRepository>(relaxed = true)
        val permissions = mockk<QueryPermissionRepository>(relaxed = true)
        val sourceRefs = mockk<ObjectProvider<SourceRefService>>()
        val sourceService = mockk<SourceRefService>()
        val resultCache = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
        val cacheService = mockk<AnalyticsQueryResultCacheService>()
        coEvery { resultCache.get() } returns cacheService
        coJustRun { cacheService.invalidate(any()) }
        val locked = AnalyticsQuery(key = "locked", name = "locked", description = "", query = "select 1")
        coEvery { queryRepository.lockById(any()) } returnsMany listOf(locked, locked, null)
        coJustRun { sourceService.removeQuerySourceRef(any()) }
        coEvery { sourceRefs.get() } returns sourceService
        val service = AnalyticsQueryServiceImpl(queryRepository, parameterRepository, permissions, sourceRefs, resultCache)

        every { sourceRefs.exists } returns true
        service.deleteQueryById(UUID.random())
        every { sourceRefs.exists } returns false
        service.deleteQueryById(UUID.random())
        service.deleteQueryById(UUID.random())

        coVerify(exactly = 1) { sourceService.removeQuerySourceRef(any()) }
        coVerify(exactly = 2) { cacheService.invalidate(any()) }
    }

    @Test
    fun `git sync compares every persisted parameter attribute before replacing declarations`() = runTest {
        val queryRepository = mockk<QueryDefinitionRepository>()
        val parameterRepository = mockk<QueryParameterRepository>(relaxed = true)
        val permissions = mockk<QueryPermissionRepository>(relaxed = true)
        val sourceRefs = mockk<ObjectProvider<SourceRefService>>(relaxed = true)
        val resultCache = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
        val cacheService = mockk<AnalyticsQueryResultCacheService>(relaxed = true)
        coEvery { resultCache.get() } returns cacheService
        val query = AnalyticsQuery(key = "q", name = "q", description = "q", query = "select :p")
        val parameter = AnalyticsQueryParameter(
            queryId = query.id,
            parameter = "p",
            name = "Parameter",
            description = "description",
            type = QueryParameterType.STRING,
            arrayType = QueryParameterType.NONE,
            defaultValue = JsonPrimitive("default"),
            required = false,
            sort = 0,
        )
        val declaration = QueryParameterDeclaration(
            parameter = parameter.parameter,
            name = parameter.name,
            description = parameter.description,
            type = parameter.type,
            arrayType = parameter.arrayType,
            defaultValue = parameter.defaultValue,
            required = parameter.required,
            sort = null,
        )
        coEvery { queryRepository.getById(query.id) } returns query
        coEvery { parameterRepository.getParameters(query.id) } returns listOf(parameter)
        coEvery { queryRepository.edit(any()) } answers { firstArg() }
        val service = AnalyticsQueryServiceImpl(queryRepository, parameterRepository, permissions, sourceRefs, resultCache)

        assertEquals(query, service.applyGitSync(query.id, query.query, listOf(declaration)))
        val changedDeclarations = listOf(
            declaration.copy(parameter = "other"),
            declaration.copy(name = "Other"),
            declaration.copy(description = "other"),
            declaration.copy(type = QueryParameterType.INTEGER),
            declaration.copy(arrayType = QueryParameterType.STRING),
            declaration.copy(defaultValue = JsonPrimitive("other")),
            declaration.copy(required = true),
            declaration.copy(sort = 1),
        )
        changedDeclarations.forEach {
            service.applyGitSync(query.id, query.query, listOf(it))
        }

        coVerify(exactly = changedDeclarations.size) { queryRepository.edit(any()) }
        coVerify(exactly = changedDeclarations.size) { cacheService.invalidate(query.id) }
        coVerify(exactly = changedDeclarations.size) { parameterRepository.deleteParametersById(query.id) }
    }

    @Test
    fun `query add and edit normalize parameter names and array types`() = runTest {
        val queryRepository = mockk<QueryDefinitionRepository>()
        val parameterRepository = mockk<QueryParameterRepository>(relaxed = true)
        val permissions = mockk<QueryPermissionRepository>(relaxed = true)
        val sourceRefs = mockk<ObjectProvider<SourceRefService>>(relaxed = true)
        val resultCache = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
        val cacheService = mockk<AnalyticsQueryResultCacheService>(relaxed = true)
        coEvery { resultCache.get() } returns cacheService
        coEvery { queryRepository.add(any()) } answers { firstArg() }
        coEvery { queryRepository.edit(any()) } answers { firstArg() }
        val service = AnalyticsQueryServiceImpl(queryRepository, parameterRepository, permissions, sourceRefs, resultCache)
        val parameters = listOf(
            AnalyticsQueryParameterInput("", "fallback", "d", QueryParameterType.ARRAY, QueryParameterType.STRING),
            AnalyticsQueryParameterInput("named", "named", "d", QueryParameterType.STRING, null),
        )
        val input = AnalyticsQueryInput(
            key = "q",
            name = "q",
            description = "q",
            query = "select 1",
            parameters = parameters,
        )

        service.addQuery(input)
        service.editQuery(input.copy(id = UUID.random()))
        assertFailsWith<IllegalArgumentException> {
            service.addQuery(input.copy(refreshIntervalSeconds = 59))
        }
        assertFailsWith<IllegalArgumentException> {
            service.editQuery(input.copy(id = UUID.random(), refreshIntervalSeconds = 0))
        }

        coVerify(exactly = 2) {
            parameterRepository.addParameter(match { it.parameter == "fallback" && it.arrayType == QueryParameterType.STRING })
        }
        coVerify(exactly = 2) {
            parameterRepository.addParameter(match { it.parameter == "named" && it.arrayType == QueryParameterType.NONE })
        }
    }

    @Test
    fun `dashboard permission cache and missing visualization links are handled`() = runTest {
        val dashboards = mockk<AnalyticsDashboardRepository>()
        val permissions = mockk<AnalyticsDashboardPermissionRepository>()
        val links = mockk<AnalyticsDashboardVisualizationRepository>()
        val visualizations = mockk<AnalyticsVisualizationRepository>()
        val service = AnalyticsDashboardServiceImpl(dashboards, permissions, links, visualizations)
        val dashboard = AnalyticsDashboard(key = "d", name = "d", description = "d", configuration = JsonNull)
        val other = UUID.random()
        val group = UUID.random()
        val stored = AnalyticsDashboardPermission(dashboard.id, group, PermissionAction.VIEW)
        coEvery { permissions.getPermissionsById(dashboard.id) } returns listOf(stored)
        coEvery { permissions.getPermissionsByIds(listOf(dashboard.id, other)) } returns listOf(stored)

        assertEquals(listOf(stored), withCache { service.getPermissions(dashboard) })
        coEvery { cache.get(any()) } returns cachedNull()
        assertEquals(emptyList(), withCache { service.getPermissions(dashboard) })
        val batch = Batch<UUID, List<bosca.security.model.EntityPermission>>(listOf(dashboard.id, other))
        withCache { service.addPermissionsToBatch(batch) }
        assertEquals(listOf(stored), batch.getData(dashboard.id))
        assertNull(batch.getData(other))

        val input = PermissionInput(PermissionAction.EDIT, dashboard.id, group)
        coJustRun { permissions.addPermission(any(), any(), any()) }
        coJustRun { permissions.deletePermission(any(), any(), any()) }
        withCache {
            service.addPermission(input)
            service.deletePermission(input)
        }

        val link = bosca.analytics.model.AnalyticsDashboardVisualization(
            UUID.random(), dashboard.id, UUID.random(), JsonNull,
        )
        coEvery { links.getVisualizationsByDashboardId(dashboard.id) } returns listOf(link)
        coEvery { visualizations.getByIds(any()) } returns emptyList()
        assertEquals(emptyList(), service.getVisualizations(dashboard.id))
        coEvery { dashboards.getById(dashboard.id) } returns null
        assertFailsWith<IllegalStateException> {
            service.editDashboard(
                AnalyticsDashboardInput(
                    id = dashboard.id,
                    key = "d",
                    name = "d",
                    description = "d",
                    configuration = JsonNull,
                    visualizations = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `visualization permission cache mutations and missing edit target are handled`() = runTest {
        val visualizations = mockk<AnalyticsVisualizationRepository>()
        val permissions = mockk<AnalyticsVisualizationPermissionRepository>()
        val service = AnalyticsVisualizationServiceImpl(visualizations, permissions)
        val visualization = AnalyticsVisualization(
            key = "v", name = "v", description = "v", type = AnalyticsVisualizationType.BAR, configuration = JsonNull,
        )
        val other = UUID.random()
        val group = UUID.random()
        val stored = AnalyticsVisualizationPermission(visualization.id, group, PermissionAction.VIEW)
        coEvery { permissions.getPermissionsById(visualization.id) } returns listOf(stored)
        coEvery { permissions.getPermissionsByIds(listOf(visualization.id, other)) } returns listOf(stored)

        assertEquals(listOf(stored), withCache { service.getPermissions(visualization) })
        coEvery { cache.get(any()) } returns cachedNull()
        assertEquals(emptyList(), withCache { service.getPermissions(visualization) })
        val batch = Batch<UUID, List<bosca.security.model.EntityPermission>>(listOf(visualization.id, other))
        withCache { service.addPermissionsToBatch(batch) }
        assertEquals(listOf(stored), batch.getData(visualization.id))
        assertNull(batch.getData(other))

        val input = PermissionInput(PermissionAction.EDIT, visualization.id, group)
        coJustRun { permissions.addPermission(any(), any(), any()) }
        coJustRun { permissions.deletePermission(any(), any(), any()) }
        withCache {
            service.addPermission(input)
            service.deletePermission(input)
        }

        coEvery { visualizations.getById(visualization.id) } returns null
        assertFailsWith<IllegalStateException> {
            service.editVisualization(
                AnalyticsVisualizationInput(
                    id = visualization.id,
                    key = "v",
                    name = "v",
                    description = "v",
                    type = AnalyticsVisualizationType.BAR,
                    configuration = JsonNull,
                ),
            )
        }
    }
}
