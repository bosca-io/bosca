@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.StringCacheKey
import bosca.cache.asCoroutineContext
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.languages.model.LanguageTagResolution
import bosca.languages.service.LanguagesService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.rating.service.ProfileRatingService
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.createProfileAttributeSignalsPrerequisites
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationCollectionFilter
import bosca.recommendations.model.RecommendationContentFilter
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationDismissal
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.model.RecommendationSource
import bosca.recommendations.seedCompletedContextModel
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.repository.RecommendationDismissalRepositoryImpl
import bosca.recommendations.repository.RecommendationContextRepositoryImpl
import bosca.recommendations.repository.RecommendationPlacementRepositoryImpl
import bosca.recommendations.repository.RecommendationPlacementStrategyRepositoryImpl
import bosca.recommendations.repository.RecommendationRepositoryImpl
import bosca.recommendations.repository.RecommendationStrategyRepositoryImpl
import bosca.recommendations.repository.CoEngagementRepositoryImpl
import bosca.recommendations.repository.CohortCoEngagementRepositoryImpl
import bosca.recommendations.repository.ProfileCohortRepositoryImpl
import bosca.profile.rating.model.ProfileRating
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the [RecommendationServiceImpl] serve path against a TestContainers instance.
 * This exercises what the unit suite cannot: the live retrieve→rank feed against a mock TF Serving (the
 * dismissal exclusion, the rank re-ordering) and the cold-start fallback to trending (the `expires_at`
 * gate and dismissal filter on real repository queries, the fallback marking/re-keying), end to end.
 * The external collaborators (segment/search/metadata/rating services) are mocked; the repositories are
 * real. `getForProfile` reads its feed through a [bosca.cache.ServiceCache], so calls run inside a
 * request-cache coroutine context ([withServing]).
 */
@OptIn(ExperimentalUuidApi::class)
class RecommendationServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_recommendations_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    // Per-test ids (a new test instance per method) so the process-wide feed/dismissal caches, keyed by
    // profile, never carry one test's state into another.
    private val profileId = UUID.random()
    private val strategyId = UUID.random()
    private val sourceId = UUID.random()

    private val json = Json { ignoreUnknownKeys = true }
    private val seedRecs = RecommendationRepositoryImpl()
    private val seedDismissals = RecommendationDismissalRepositoryImpl()
    private val seedStrategies = RecommendationStrategyRepositoryImpl()
    private val seedContexts = RecommendationContextRepositoryImpl()

    // Stands in for TF Serving's `rank` API when exercising the personalized co-engagement ML blend path.
    private lateinit var server: MockWebServer

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val ratingService = mockk<ProfileRatingService>(relaxed = true)
    private val languagesService = mockk<LanguagesService>(relaxed = true)

    // The feed serve path reads through a ServiceCache; these back the RequestCache that [withServing]
    // layers onto the coroutine context. The remote tier always misses, so every get runs the loader.
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val remoteCache = mockk<Cache<Any>>(relaxed = true)

    private lateinit var service: RecommendationServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        // A deterministic local key (real value-equality) — a relaxed keySerializer returns identity-keyed
        // fakes, which makes the RequestCache's local map nondeterministic across the feed tests.
        val keySerializer = mockk<CacheKeySerializer<Any>>()
        @Suppress("UNCHECKED_CAST")
        every { keySerializer.toLocalKey(any(), any()) } answers { StringCacheKey(firstArg(), secondArg<Any>().toString()) as CacheKey<Any> }
        every { remoteCache.keySerializer } returns keySerializer
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns false
            every { value } returns null
        }
        provides<CacheManager>(singleton = true) { cacheManager }

        if (!schemaInitialized) {
            withDb {
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE SCHEMA IF NOT EXISTS segmentation") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS segmentation.segments (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.scheduled_jobs (id uuid PRIMARY KEY)") { it.execute() }
                createProfileAttributeSignalsPrerequisites()  // for the profile_cohort view (V8)
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(RecommendationsMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM recommendations.recommendations") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.dismissals") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.co_engagements") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.cohort_co_engagements") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.personalization_signals") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.contexts WHERE type <> 'default'") { it.execute() }
                connection().useStatement("UPDATE recommendations.contexts SET active_model_version = NULL, requested_model_version = NULL") { it.execute() }
                connection().useStatement("TRUNCATE recommendations.context_models RESTART IDENTITY CASCADE") { it.execute() }
                connection().useStatement("DELETE FROM public.profile_attributes") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.strategies") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata_embeddings") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata") { it.execute() }
                connection().useStatement("DELETE FROM public.collection_language_variants") { it.execute() }
                connection().useStatement("DELETE FROM public.collections") { it.execute() }
                connection().useStatement("DELETE FROM public.profiles") { it.execute() }
                connection().useStatement(
                    """
                    UPDATE public.language_tag_mappings mapping
                    SET resolved_language_tag = 'en'
                    FROM public.language_resolution_contexts context
                    WHERE mapping.context_id = context.id
                      AND context.key = 'recommendations'
                      AND mapping.source_language_tag = 'en-US'
                    """.trimIndent(),
                ) { it.execute() }
                seedContexts.getByType("default")?.let { seedContexts.update(it.copy(contentFilter = RecommendationContentFilter.DEFAULT)) }
                seedCompletedContextModel("default")
            }
            transaction {
                connection().useStatement("INSERT INTO public.profiles (id) VALUES (?)") {
                    it.setObject(1, profileId.toJavaUuid()); it.execute()
                }
                // A neutral active strategy (not TRENDING/PERSONALIZED) so tests that seed their own trending or
                // ML strategy aren't ambiguous with getActive()/getTrending picking this one.
                connection().useStatement(
                    "INSERT INTO recommendations.strategies (id, name, type, status) VALUES (?, 'Test', 'co_engagement', 'active')",
                ) {
                    it.setObject(1, strategyId.toJavaUuid()); it.execute()
                }
            }
        }

        // No ratings, no categories → assembler keeps score order and applies no re-rank.
        coEvery { ratingService.getRatingsByProfile(any()) } returns emptyList()
        coEvery { profileService.getAttributes(any()) } returns emptyList()
        coEvery { languagesService.resolveLanguageTag(any(), any()) } answers {
            val requested = secondArg<String?>()?.trim()?.takeIf { it.isNotEmpty() }
            val resolved = when {
                requested == null -> "en"
                requested.equals("en-US", ignoreCase = true) -> "en"
                else -> requested
            }
            LanguageTagResolution(requested, requested, resolved, requested == null)
        }
        coEvery { metadataService.getCategories(any()) } returns emptyList()
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Metadata(
                    id = id,
                    name = "Recommendable metadata",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "published",
                )
            }
        }
        coEvery { collectionService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Collection(
                    id = id,
                    name = "Recommendable collection",
                    languageTag = "en",
                    workflowStateId = "published",
                )
            }
        }

        server = MockWebServer()
        (server.dispatcher as mockwebserver3.QueueDispatcher).setFailFast(
            MockResponse.Builder().code(200).body("""{"predictions":[]}""").build(),
        )
        server.start()

        service = RecommendationServiceImpl(
            RecommendationRepositoryImpl(),
            RecommendationDismissalRepositoryImpl(),
            RecommendationPlacementRepositoryImpl(),
            RecommendationPlacementStrategyRepositoryImpl(),
            RecommendationStrategyRepositoryImpl(),
            CoEngagementRepositoryImpl(),
            metadataService,
            profileService,
            ratingService,
            RecommendationContextServiceImpl(
                RecommendationContextRepositoryImpl(),
                mockk<bosca.content.recommendation.service.RecommendationContextClassifier>(relaxed = true),
            ),
            languagesService,
            Json { ignoreUnknownKeys = true },
            TfServingConfiguration(url = server.url("/").toString().trimEnd('/')),
        )
    }

    @AfterTest
    fun teardown() {
        server.close()
        ProviderRegistry.clear()
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    /** [withDb] plus a fresh request cache — what the GraphQL layer provides on the real serve path. */
    private fun withServing(block: suspend () -> Unit) {
        withDb {
            withContext(RequestCache(cacheManager, requestCacheSerializer).asCoroutineContext()) { block() }
        }
    }

    private suspend fun ensureMetadata(
        metadataId: UUID,
        contexts: List<String> = listOf(RecommendationContext.DEFAULT_TYPE),
    ) {
        connection().useStatement(
            """
            INSERT INTO public.metadata (id, recommendation_contexts, content_type, attributes)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET recommendation_contexts = excluded.recommendation_contexts,
                content_type = excluded.content_type, attributes = excluded.attributes
            """.trimIndent(),
        ) {
            it.setObject(1, metadataId.toJavaUuid())
            it.setArray(2, connection().createArrayOf("text", contexts.toTypedArray()))
            it.setString(3, if ("images" in contexts) "image/png" else "bosca/v-document")
            it.setString(4, if ("articles_and_series" in contexts) """{"type":"article"}""" else "{}")
            it.execute()
        }

    }

    private suspend fun ensureCollection(
        collectionId: UUID,
        contexts: List<String> = listOf(RecommendationContext.DEFAULT_TYPE),
    ) {
        connection().useStatement(
            """
            INSERT INTO public.collections (id, recommendation_contexts)
            VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET recommendation_contexts = excluded.recommendation_contexts
            """.trimIndent(),
        ) {
            it.setObject(1, collectionId.toJavaUuid())
            it.setArray(2, connection().createArrayOf("text", contexts.toTypedArray()))
            it.execute()
        }
    }

    private suspend fun seedRecommendation(
        recommendation: Recommendation,
        contexts: List<String> = listOf(RecommendationContext.DEFAULT_TYPE),
    ) {
        ensureMetadata(requireNotNull(recommendation.metadataId), contexts)
        seedRecs.upsertMetadata(recommendation)
    }

    private suspend fun seedCollectionRecommendation(
        recommendation: Recommendation,
        contexts: List<String> = listOf(RecommendationContext.DEFAULT_TYPE),
    ) {
        ensureCollection(requireNotNull(recommendation.collectionId), contexts)
        seedRecs.upsertCollection(recommendation)
    }

    private suspend fun seedEmbedding(
        metadataId: UUID,
        contexts: List<String> = listOf(RecommendationContext.DEFAULT_TYPE),
        chunkIndex: Int = 0,
        axis: Int = 0,
    ) {
        ensureMetadata(metadataId, contexts)
        val embedding = List(768) { if (it == axis) 1.0f else 0.0f }
            .joinToString(prefix = "[", postfix = "]", separator = ",")
        connection().useStatement(
            """
            INSERT INTO public.metadata_embeddings (
                metadata_id, chunk_index, token_start, token_end, token_count, aggregation_weight, embedding
            )
            VALUES (?, ?, ? * 100, (? + 1) * 100, 100, 100, cast(? as vector))
            ON CONFLICT (metadata_id, chunk_index) DO UPDATE SET
                token_start = excluded.token_start,
                token_end = excluded.token_end,
                token_count = excluded.token_count,
                aggregation_weight = excluded.aggregation_weight,
                embedding = excluded.embedding
            """.trimIndent(),
        ) {
            it.setObject(1, metadataId.toJavaUuid())
            it.setInt(2, chunkIndex)
            it.setInt(3, chunkIndex)
            it.setInt(4, chunkIndex)
            it.setString(5, embedding)
            it.execute()
        }
    }

    private suspend fun seed(metadataId: UUID, score: Double, expiresAt: OffsetDateTime? = null) {
        seedRecommendation(
            Recommendation(metadataId = metadataId, strategyId = strategyId, score = score, expiresAt = expiresAt),
        )
    }

    private suspend fun seedContext(type: String, contentFilter: RecommendationContentFilter) {
        seedContexts.add(
            RecommendationContext(
                type = type,
                name = type,
                contentFilter = contentFilter,
            ),
        )
        seedCompletedContextModel(type, seedStrategies.getActive().any { it.type == RecommendationStrategyType.PERSONALIZED })
    }

    /** Inserts a co-occurrence co-engagement edge for the shared source item, FK-anchored to [strategyId]. */
    private suspend fun seedRelated(related: UUID, score: Double, reason: String? = null) {
        ensureMetadata(related)
        connection().useStatement(
            "INSERT INTO recommendations.co_engagements (source_metadata_id, co_engaged_metadata_id, strategy_id, score, reason) " +
                "VALUES (?, ?, ?, ?, ?)",
        ) {
            it.setObject(1, sourceId.toJavaUuid())
            it.setObject(2, related.toJavaUuid())
            it.setObject(3, strategyId.toJavaUuid())
            it.setDouble(4, score)
            it.setString(5, reason)
            it.execute()
        }
    }

    /** Adds one canonical keyed cohort membership for [profileId]: a useAsCohort definition plus a cached
     *  attribute carrying that value. Reusing [key] adds another value to the same multi-valued signal. */
    private suspend fun seedCohortSignal(key: String, value: String) {
        connection().useStatement(
            "INSERT INTO recommendations.personalization_signals " +
                "(key, source_type, source_id, expression, value_type, use_as_feature, use_as_cohort, enabled) " +
                "VALUES (?, 'attribute', ?, 'value', 'categorical', false, true, true) " +
                "ON CONFLICT (key) DO NOTHING",
        ) {
            it.setString(1, key); it.setString(2, "bosca.profiles.$key"); it.execute()
        }
        connection().useStatement(
            "INSERT INTO public.profile_attributes (profile, type_id, priority, signals) VALUES (?, ?, 0, ?::jsonb)",
        ) {
            it.setObject(1, profileId.toJavaUuid()); it.setString(2, "bosca.profiles.$key")
            it.setString(3, """[{"key":"$key","value":"$value"}]"""); it.execute()
        }
    }

    /** Inserts a cohort co-engagement edge for the shared source item + [cohortKey], FK-anchored to [strategyId]. */
    private suspend fun seedCohortEdge(coEngaged: UUID, cohortKey: String, score: Double, reason: String? = null) {
        ensureMetadata(coEngaged)
        connection().useStatement(
            "INSERT INTO recommendations.cohort_co_engagements " +
                "(source_metadata_id, cohort_key, co_engaged_metadata_id, strategy_id, score, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ) {
            it.setObject(1, sourceId.toJavaUuid()); it.setString(2, cohortKey)
            it.setObject(3, coEngaged.toJavaUuid()); it.setObject(4, strategyId.toJavaUuid()); it.setDouble(5, score)
            it.setString(6, reason)
            it.execute()
        }
    }

    private fun cohortKey(key: String, value: String): String =
        "[${JsonPrimitive(key)}, ${JsonPrimitive(value)}]"

    /** An active ML strategy with NO configuration → the service uses the default TfServingConfiguration. */
    private suspend fun seedMlStrategyNoConfig() {
        seedStrategies.add(RecommendationStrategy(name = "ML", type = RecommendationStrategyType.PERSONALIZED, status = RecommendationStrategyStatus.ACTIVE))
    }

    /** Seeds an active PERSONALIZED strategy whose TF Serving config points at the MockWebServer. */
    private suspend fun seedActiveMlStrategy(): RecommendationStrategy {
        connection().useStatement("UPDATE recommendations.context_models SET personalized = true") { it.execute() }
        return seedStrategies.add(
            RecommendationStrategy(
                name = "ML",
                type = RecommendationStrategyType.PERSONALIZED,
                status = RecommendationStrategyStatus.ACTIVE,
                configuration = Json.encodeToJsonElement(
                    TfServingConfiguration.serializer(),
                    TfServingConfiguration(url = server.url("/").toString().trimEnd('/')),
                ),
            ),
        )

    }


    @Test
    fun `personalized related on the heuristic arm keeps co-occurrence order and never calls TF Serving`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                seedRelated(a, 9.0)
                seedRelated(b, 1.0)
                seedActiveMlStrategy() // present, but the heuristic arm must bypass it
            }
        }

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, profileId, 10, mlEnabled = false, modelVersion = null) }

        assertEquals(listOf(a, b), result.map { it.metadataId })
        assertEquals(0, server.requestCount, "the heuristic arm must not call the ML ranker")
    }

    @Test
    fun `personalized related falls back to co-occurrence order when no ML model is active`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                seedRelated(a, 9.0)
                seedRelated(b, 1.0)
                // No PERSONALIZED strategy seeded — only the content_based strategy from setup.
            }
        }

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, profileId, 10, mlEnabled = true, modelVersion = null) }

        assertEquals(listOf(a, b), result.map { it.metadataId })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `personalized related falls back to co-occurrence order when the ML ranker errors`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                seedRelated(a, 9.0)
                seedRelated(b, 1.0)
                seedActiveMlStrategy()
            }
        }
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, profileId, 10, mlEnabled = true, modelVersion = null) }

        // The ranker returned no usable scores, so the heuristic assembler decides the order.
        assertEquals(listOf(a, b), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile preserves the context models final ranking with one inference call`() {
        val a = UUID.random()
        val b = UUID.random()
        val c = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(a)
                ensureMetadata(b)
                ensureMetadata(c)
            }
        }
        // The export has already ranked all eligible items; Kotlin must preserve that final ordering.
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$b","$c","$a"],"scores":[0.9,0.5,0.1]}]}""").build(),
        )

        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 3) }

        assertEquals(listOf(b, c, a), result.map { it.metadataId })
        assertTrue(result.all { it.reason == "Recommended by context model" })
        assertTrue(result.all { it.sources.isEmpty() && it.inference != null })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getForProfile removes a live model candidate after metadata becomes non-recommendable`() {
        val eligible = UUID.random()
        val disabled = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(eligible)
                ensureMetadata(disabled)
                connection().useStatement("UPDATE public.metadata SET recommendable = false WHERE id = ?") {
                    it.setObject(1, disabled.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Metadata(
                    id = id,
                    name = "Candidate",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "published",
                    recommendable = id != disabled,
                )
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$disabled","$eligible"],"scores":[0.9,0.8]}]}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.9,0.8]}""").build())

        var result: List<Recommendation> = emptyList()
        withServing {
            result = service.getForProfile(profileId, 0, 10, mlEnabled = true, modelVersion = 1L)
        }

        assertEquals(listOf(eligible), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile filters live model candidates to the requested language`() {
        val english = UUID.random()
        val french = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(english)
                ensureMetadata(french)
                connection().useStatement("UPDATE public.metadata SET language_tag = 'fr' WHERE id = ?") {
                    it.setObject(1, french.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Metadata(
                    id = id,
                    name = "Candidate",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = if (id == french) "fr" else "en",
                    workflowStateId = "published",
                )
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$english","$french"],"scores":[0.9,0.8]}]}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.9,0.8]}""").build())

        var result: List<Recommendation> = emptyList()
        withServing {
            result = service.getForProfile(
                profileId,
                0,
                10,
                mlEnabled = true,
                modelVersion = 1L,
                languageTag = "fr",
            )
        }

        assertEquals(listOf(french), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile supplements a stale model after a language mapping moves content`() {
        val moved = UUID.random()
        val surviving = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(surviving)
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Remapped content",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                seedRecommendation(Recommendation(metadataId = moved, strategyId = trending.id, score = 0.9))
                seedRecommendation(Recommendation(metadataId = surviving, strategyId = trending.id, score = 0.4))
                connection().useStatement("UPDATE public.metadata SET language_tag = 'en-US' WHERE id = ?") {
                    it.setObject(1, moved.toJavaUuid())
                    it.execute()
                }
                connection().useStatement("UPDATE public.metadata SET language_tag = 'fr' WHERE id = ?") {
                    it.setObject(1, surviving.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    """
                    UPDATE public.language_tag_mappings mapping
                    SET resolved_language_tag = 'fr'
                    FROM public.language_resolution_contexts context
                    WHERE mapping.context_id = context.id
                      AND context.key = 'recommendations'
                      AND mapping.source_language_tag = 'en-US'
                    """.trimIndent(),
                ) { it.execute() }
            }
        }
        coEvery { languagesService.resolveLanguageTag(any(), "en-US") } returns
            LanguageTagResolution("en-US", "en-US", "fr", false)
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Metadata(
                    id = id,
                    name = "Mapped candidate",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = if (id == moved) "en-US" else "fr",
                    workflowStateId = "published",
                )
            }
        }
        // The stale model still has one French candidate but does not contain the newly remapped item.
        // A non-empty partial result must still be supplemented from the live SQL candidate pool.
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$surviving"],"scores":[0.8]}]}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.8]}""").build())

        var result: List<Recommendation> = emptyList()
        withServing {
            result = service.getForProfile(
                profileId,
                0,
                10,
                mlEnabled = true,
                modelVersion = 1L,
                languageTag = "en-US",
            )
        }

        assertEquals(listOf(surviving, moved), result.mapNotNull { it.metadataId })
        assertEquals(setOf(RecommendationSource.TRENDING), result.first().sources)
        assertEquals(0.8, result.first().score)
        assertEquals(JsonPrimitive(true), (result.last().context as JsonObject)["fallback"])
    }

    @Test
    fun `getForProfile uses variant eligibility for the requested collection language`() {
        val collectionId = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "French collections",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                seedCollectionRecommendation(
                    Recommendation(collectionId = collectionId, strategyId = trending.id, score = 0.9),
                )
                connection().useStatement("UPDATE public.collections SET recommendable = false WHERE id = ?") {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    "INSERT INTO public.collection_language_variants (id, language_tag, recommendable) VALUES (?, 'fr', true)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(
            Collection(
                id = collectionId,
                name = "English base",
                languageTag = "en",
                workflowStateId = "published",
                recommendable = false,
            ),
        )
        coEvery { collectionService.getLanguageVariants(listOf(collectionId), "fr") } returns listOf(
            CollectionLanguageVariant(
                id = collectionId,
                languageTag = "fr",
                name = "Variante française",
                workflowStateId = "published",
                recommendable = true,
            ),
        )

        var french: List<Recommendation> = emptyList()
        var english: List<Recommendation> = emptyList()
        withServing {
            french = service.getForProfile(profileId, 0, 10, mlEnabled = false, languageTag = "fr")
            english = service.getForProfile(profileId, 0, 10, mlEnabled = false, languageTag = "en")
        }

        assertEquals(listOf(collectionId), french.mapNotNull { it.collectionId })
        assertTrue(english.isEmpty())
    }

    @Test
    fun `collection variants remain separately recommendable when languages share a model facet`() {
        val collectionId = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Regional collections",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                seedCollectionRecommendation(
                    Recommendation(collectionId = collectionId, strategyId = trending.id, score = 0.9),
                )
                connection().useStatement(
                    "INSERT INTO public.collection_language_variants (id, language_tag, recommendable) VALUES (?, 'en-US', false)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(
            Collection(
                id = collectionId,
                name = "English base",
                languageTag = "en",
                workflowStateId = "published",
                recommendable = true,
            ),
        )

        var english: List<Recommendation> = emptyList()
        var regionalEnglish: List<Recommendation> = emptyList()
        withServing {
            english = service.getForProfile(profileId, 0, 10, mlEnabled = false, languageTag = "en")
            regionalEnglish = service.getForProfile(profileId, 0, 10, mlEnabled = false, languageTag = "en-US")
        }

        assertEquals(listOf(collectionId), english.mapNotNull { it.collectionId })
        assertTrue(regionalEnglish.isEmpty())
    }

    @Test
    fun `cached collection recommendation is reconciled to the currently eligible representation`() {
        val collectionId = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Cached regional collections",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                seedCollectionRecommendation(
                    Recommendation(collectionId = collectionId, strategyId = trending.id, score = 0.9),
                )
                connection().useStatement(
                    "INSERT INTO public.collection_language_variants (id, language_tag, recommendable) VALUES (?, 'en-US', false)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { languagesService.resolveLanguageTag(any(), "de-DE") } returns
            LanguageTagResolution("de-DE", "de-DE", "en", false)

        var first: List<Recommendation> = emptyList()
        var second: List<Recommendation> = emptyList()
        withServing {
            first = service.getForProfile(profileId, 0, 10, languageTag = "de-DE")
            transaction {
                connection().useStatement("UPDATE public.collections SET recommendable = false WHERE id = ?") {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    "UPDATE public.collection_language_variants SET recommendable = true WHERE id = ? AND language_tag = 'en-US'",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
            }
            second = service.getForProfile(profileId, 0, 10, languageTag = "de-DE")
        }

        assertEquals("en", first.single().collectionLanguageTag)
        assertEquals("en-US", second.single().collectionLanguageTag)
    }

    @Test
    fun `getForProfile uses the preferred profile locale and blank explicitly falls back to English`() {
        val english = UUID.random()
        val french = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Localized",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                seedRecommendation(Recommendation(metadataId = english, strategyId = trending.id, score = 0.9))
                seedRecommendation(Recommendation(metadataId = french, strategyId = trending.id, score = 0.8))
                connection().useStatement("UPDATE public.metadata SET language_tag = 'fr' WHERE id = ?") {
                    it.setObject(1, french.toJavaUuid())
                    it.execute()
                }
            }
        }
        coEvery { profileService.getAttributes(profileId) } returns listOf(
            ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.locale",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 0,
                source = "test",
                attributes = JsonObject(mapOf("locale" to JsonPrimitive("fr"))),
            ),
        )
        coEvery { metadataService.getByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Metadata(
                    id = id,
                    name = "Localized candidate",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = if (id == french) "fr" else "en",
                    workflowStateId = "published",
                )
            }
        }

        var preferred: List<Recommendation> = emptyList()
        var blank: List<Recommendation> = emptyList()
        withServing {
            preferred = service.getForProfile(profileId, 0, 10, mlEnabled = false)
            blank = service.getForProfile(profileId, 0, 10, mlEnabled = false, languageTag = "  ")
        }

        assertEquals(listOf(french), preferred.mapNotNull { it.metadataId })
        assertEquals(listOf(english), blank.mapNotNull { it.metadataId })
    }

    @Test
    fun `getForProfile applies the default and explicit request content contexts`() {
        val article = UUID.random()
        val image = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(article)
                ensureMetadata(image, listOf("images"))
                seedContext(
                    "images",
                    RecommendationContentFilter(
                        metadata = RecommendationMetadataFilter(includedContentTypePrefixes = listOf("image/")),
                        collections = null,
                    ),
                )
            }
        }

        fun enqueueFeed(id: UUID) {
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"predictions":[{"content_ids":["$id"],"scores":[0.9]}]}""").build(),
            )
        }

        enqueueFeed(article)
        var defaultResults: List<Recommendation> = emptyList()
        withServing {
            defaultResults = service.getForProfile(profileId, 0, 10, true, 1L)
        }

        enqueueFeed(image)
        var assetResults: List<Recommendation> = emptyList()
        withServing {
            assetResults = service.getForProfile(
                profileId,
                0,
                10,
                true,
                2L,
                "images",
            )
        }

        assertEquals(listOf(article), defaultResults.map { it.metadataId })
        assertEquals(listOf(image), assetResults.map { it.metadataId })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `activation during a request preserves its snapshot and the next request uses the new model`() {
        val oldCandidate = UUID.random()
        val newCandidate = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(oldCandidate)
                ensureMetadata(newCandidate, emptyList())
            }
        }
        var activated = false
        coEvery { languagesService.resolveLanguageTag(any(), "en") } coAnswers {
            if (!activated) {
                seedCompletedContextModel("default", personalized = true)
                activated = true
            }
            LanguageTagResolution("en", "en", "en", false)
        }
        for (candidate in listOf(oldCandidate, newCandidate)) {
            server.enqueue(MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$candidate"],"scores":[0.8]}]}""")
                .build())
        }
        var original: List<Recommendation> = emptyList()
        var next: List<Recommendation> = emptyList()
        withServing { original = service.getForProfile(profileId, 0, 1, languageTag = "en") }
        withServing { next = service.getForProfile(profileId, 0, 1, languageTag = "en") }

        assertEquals(listOf(oldCandidate), original.map { it.metadataId })
        assertEquals(listOf(newCandidate), next.map { it.metadataId })
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().url.encodedPath.contains("/versions/1:"))
        assertTrue(server.takeRequest().url.encodedPath.contains("/versions/2:"))
    }

    @Test
    fun `pending context filters and refreshed tags do not change the active metadata population`() {
        val included = UUID.random()
        val excluded = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(included)
                ensureMetadata(excluded, emptyList())
                val saved = requireNotNull(seedContexts.getByType("default"))
                seedContexts.update(saved.copy(contentFilter = RecommendationContentFilter(
                    metadata = RecommendationMetadataFilter(includedContentTypePrefixes = listOf("image/")),
                    collections = null,
                )))
                connection().useStatement("UPDATE public.metadata SET recommendation_contexts = ARRAY[]::text[] WHERE id = ?") {
                    it.setObject(1, included.toJavaUuid())
                    it.execute()
                }
                connection().useStatement("UPDATE public.metadata SET recommendation_contexts = ARRAY['default'] WHERE id = ?") {
                    it.setObject(1, excluded.toJavaUuid())
                    it.execute()
                }
            }
        }
        server.enqueue(MockResponse.Builder().code(200)
            .body("""{"predictions":[{"content_ids":["$included"],"scores":[0.8]}]}""")
            .build())
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 1) }

        assertEquals(listOf(included), result.map { it.metadataId })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a model from another context cannot be selected explicitly`() {
        withDb {
            seedContext("other", RecommendationContentFilter())
        }
        assertFailsWith<IllegalArgumentException> {
            withServing { service.getForProfile(profileId, 0, 1, modelVersion = 2L) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `metadata endpoints return empty before the context has its first completed model`() {
        val candidate = UUID.random()
        withDb {
            transaction {
                seedRelated(candidate, 0.9)
                seedActiveMlStrategy()
                connection().useStatement("UPDATE recommendations.contexts SET active_model_version = NULL") { it.execute() }
            }
        }
        withServing {
            assertTrue(service.getSimilar(sourceId, 10).isEmpty())
            assertTrue(service.getRecommended(sourceId, profileId, 10).isEmpty())
            assertTrue(service.getCoEngaged(sourceId, profileId, 10).isEmpty())
            assertTrue(service.getForProfile(profileId, 0, 10).isEmpty())
            assertTrue(service.getTrending(0, 10).isEmpty())
            service.invalidatePersonalizedFeeds()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `completed personalized context serves without a legacy personalized strategy`() {
        val candidate = UUID.random()
        withDb {
            transaction {
                ensureMetadata(candidate)
                seedCompletedContextModel("default", personalized = true)
            }
        }
        server.enqueue(MockResponse.Builder().code(200)
            .body("""{"predictions":[{"content_ids":["$candidate"],"scores":[0.8]}]}""")
            .build())
        withServing {
            val result = service.getForProfile(profileId, 0, 1)
            assertEquals(listOf(candidate), result.map { it.metadataId })
            assertEquals(UUID.NIL, result.single().strategyId)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing and unfinished explicit model versions cannot serve`() {
        assertFailsWith<IllegalArgumentException> {
            withServing { service.getForProfile(profileId, 0, 1, modelVersion = Long.MAX_VALUE) }
        }
        withDb {
            connection().useStatement("UPDATE recommendations.context_models SET status = 'running' WHERE version = 1") { it.execute() }
        }
        assertFailsWith<IllegalArgumentException> {
            withServing { service.getForProfile(profileId, 0, 1, modelVersion = 1) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getForProfile excludes dismissed metadata from the retrieved candidates`() {
        val dismissed = UUID.random()
        val kept = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(dismissed)
                ensureMetadata(kept)
                seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = dismissed))
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$dismissed","$kept"],"scores":[0.9,0.5]}]}""").build(),
        )
        // The dismissed candidate is dropped before ranking, so `rank` scores only [kept].
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.5]}""").build())

        // Pin a model version so this runs the live (uncached) A/B arm — the dismissal exclusion is in the
        // retrieve→rank path either way, and this keeps the assertion off the cached default arm.
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10, mlEnabled = true, modelVersion = 1L) }

        assertEquals(listOf(kept), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile falls back to trending marked as fallback when no ML model is active`() {
        val top = UUID.random()
        val second = UUID.random()
        val expired = UUID.random()
        val dismissed = UUID.random()
        withDb {
            transaction {
                // Trending is a global, strategy-keyed pool — no per-profile rows.
                val trending = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Trending",
                        type = RecommendationStrategyType.TRENDING,
                        status = RecommendationStrategyStatus.ACTIVE,
                    ),
                )
                suspend fun seedTrending(metadataId: UUID, score: Double, expiresAt: OffsetDateTime? = null) {
                    seedRecommendation(
                        Recommendation(metadataId = metadataId, strategyId = trending.id, score = score, expiresAt = expiresAt),
                    )
                }
                seedTrending(top, 0.9)
                seedTrending(second, 0.4)
                seedTrending(expired, 1.0, expiresAt = OffsetDateTime.now().minusHours(1))
                seedTrending(dismissed, 0.7)
                seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = dismissed))
            }
        }

        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }

        // Score order, minus the expired row (repository gate) and this profile's dismissal (fallback filter).
        assertEquals(listOf(top, second), result.map { it.metadataId })
        assertEquals(1, result.map { (it.context as JsonObject)["recommendation_request_id"] }.toSet().size)
        // Clearly marked as a fallback, not a personalized result.
        assertTrue(result.all { (it.context as JsonObject)["fallback"] == JsonPrimitive(true) })
        assertEquals(0, server.requestCount, "no active ML model — TF Serving must not be called")
    }

    @Test
    fun `getForProfile returns empty when no model is active and nothing is trending`() {
        withDb {
            transaction {
                // Materialized strategy rows are no longer served directly — the live feed ignores them.
                seed(UUID.random(), 0.9)
            }
        }
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `removeExpired delegates to deleteExpired and returns the affected count`() {
        withDb {
            transaction {
                seed(UUID.random(), 0.5, expiresAt = OffsetDateTime.now().minusHours(1))
                seed(UUID.random(), 0.6, expiresAt = OffsetDateTime.now().minusHours(2))
                seed(UUID.random(), 0.7, expiresAt = OffsetDateTime.now().plusHours(1))
            }
        }
        var removed = 0L
        withDb { removed = service.removeExpired() }
        assertEquals(2L, removed)
    }

    // ── trending ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getTrending returns the active trending strategy's pool ordered by score`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE),
                )
                seedRecommendation(Recommendation(metadataId = a, strategyId = trending.id, score = 0.3))
                seedRecommendation(Recommendation(metadataId = b, strategyId = trending.id, score = 0.9))
            }
        }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getTrending(0, 10) }
        assertEquals(listOf(b, a), result.map { it.metadataId })
        assertTrue(result.all { it.sources == setOf(RecommendationSource.TRENDING) })
    }

    @Test
    fun `getTrending applies the active context filters before ranking`() {
        val article = UUID.random()
        val image = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE),
                )
                seedRecommendation(
                    Recommendation(metadataId = image, strategyId = trending.id, score = 1.0),
                    listOf("images"),
                )
                seedRecommendation(Recommendation(metadataId = article, strategyId = trending.id, score = 0.8))
                seedContext(
                    "images",
                    RecommendationContentFilter(
                        metadata = RecommendationMetadataFilter(includedContentTypePrefixes = listOf("image/")),
                        collections = null,
                    ),
                )
            }
        }

        var defaultResults: List<Recommendation> = emptyList()
        var assetResults: List<Recommendation> = emptyList()
        withDb {
            defaultResults = service.getTrending(0, 10)
            assetResults = service.getTrending(
                0,
                10,
                " IMAGES ",
            )
        }

        assertEquals(listOf(article), defaultResults.map { it.metadataId })
        assertEquals(listOf(image), assetResults.map { it.metadataId })
        assertEquals(JsonPrimitive("images"), (assetResults.single().context as JsonObject)["recommendation_context"])
    }

    @Test
    fun `trending passes normalized selected model filters through pending edits activation and rollback`() {
        val article = UUID.random()
        val guide = UUID.random()
        withDb {
            val trending = seedStrategies.add(RecommendationStrategy(
                name = "Snapshot trending", type = RecommendationStrategyType.TRENDING,
                status = RecommendationStrategyStatus.ACTIVE,
            ))
            seedRecommendation(Recommendation(metadataId = article, strategyId = trending.id, score = 0.5))
            seedRecommendation(Recommendation(metadataId = guide, strategyId = trending.id, score = 1.0))
            for ((id, type) in listOf(article to " Article ", guide to "guide")) {
                connection().useStatement("UPDATE public.metadata SET content_type = ' Text/Plain; charset=utf-8 ', attributes = jsonb_build_object('type', ?::text) WHERE id = ?") {
                    it.setString(1, type)
                    it.setObject(2, id.toJavaUuid())
                    it.execute()
                }
            }
            val saved = requireNotNull(seedContexts.getByType("default"))
            seedContexts.update(saved.copy(contentFilter = RecommendationContentFilter(metadata = RecommendationMetadataFilter(
                includedContentTypePrefixes = listOf(" TEXT/ ", " "), excludedContentTypePrefixes = listOf("text/"),
                includedAttributeTypes = listOf(" ARTICLE ", ""), excludedAttributeTypes = listOf("article"),
            ))))
            val original = seedCompletedContextModel("default")
            val current = requireNotNull(seedContexts.getByType("default"))
            seedContexts.update(current.copy(contentFilter = RecommendationContentFilter(metadata = RecommendationMetadataFilter(
                includedAttributeTypes = listOf("guide"),
            ))))
            assertEquals(listOf(article), service.getTrending(0, 1).map { it.metadataId })
            seedCompletedContextModel("default")
            assertEquals(listOf(guide), service.getTrending(0, 1).map { it.metadataId })
            val selection = seedContexts.nextSelection(saved.id)
            seedContexts.activateModel(saved.id, original.version, selection.selectionRevision)
            assertEquals(listOf(article), service.getTrending(0, 1).map { it.metadataId })
        }
    }

    @Test
    fun `getTrending supports contexts that contain metadata but no collections`() {
        val article = UUID.random()
        val collection = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE),
                )
                seedCollectionRecommendation(Recommendation(collectionId = collection, strategyId = trending.id, score = 1.0))
                seedRecommendation(
                    Recommendation(metadataId = article, strategyId = trending.id, score = 0.8),
                    listOf(RecommendationContext.DEFAULT_TYPE, "metadata_only"),
                )
                seedContext(
                    "metadata_only",
                    RecommendationContentFilter(
                        metadata = RecommendationMetadataFilter.ALL,
                        collections = null,
                    ),
                )
            }
        }

        var metadataOnly: List<Recommendation> = emptyList()
        withDb { metadataOnly = service.getTrending(0, 10, "metadata_only") }
        assertEquals(listOf(article), metadataOnly.map { it.metadataId })
    }

    @Test
    fun `getTrending uses captured metadata filters and collection context tags`() {
        val article = UUID.random()
        val teaser = UUID.random()
        val series = UUID.random()
        val folder = UUID.random()
        val show = UUID.random()
        withDb {
            transaction {
                val trending = seedStrategies.add(
                    RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE),
                )
                seedRecommendation(
                    Recommendation(metadataId = article, strategyId = trending.id, score = 1.0),
                    listOf("articles_and_series"),
                )
                seedRecommendation(Recommendation(metadataId = teaser, strategyId = trending.id, score = 0.9))
                seedCollectionRecommendation(
                    Recommendation(collectionId = series, strategyId = trending.id, score = 0.8),
                    listOf("articles_and_series"),
                )
                seedCollectionRecommendation(Recommendation(collectionId = folder, strategyId = trending.id, score = 0.7))
                seedCollectionRecommendation(Recommendation(collectionId = show, strategyId = trending.id, score = 0.6))
                seedContext(
                    "articles_and_series",
                    RecommendationContentFilter(
                        metadata = RecommendationMetadataFilter(
                            excludedContentTypePrefixes = emptyList(),
                            includedAttributeTypes = listOf("article"),
                        ),
                        collections = RecommendationCollectionFilter(
                            includedTypes = listOf("standard"),
                            includedAttributeTypes = listOf("series"),
                        ),
                    ),
                )
            }
        }

        var results: List<Recommendation> = emptyList()
        withDb { results = service.getTrending(0, 10, "articles_and_series") }

        assertEquals(listOf(article, series), results.map { it.metadataId ?: it.collectionId })
    }

    @Test
    fun `getTrending is empty when there is no trending strategy`() {
        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getTrending(0, 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `recommendation requests reject an unknown context type`() {
        assertFailsWith<NoSuchElementException> {
            withDb { service.getTrending(0, 10, "missing") }
        }
    }

    // ── placement ─────────────────────────────────────────────────────────────────────────────────

    /** Seeds a placement and links it to [strategyIds], defaulting to the setup strategy carrying the pool. */
    private suspend fun seedPlacement(
        slug: String,
        maxItems: Int = 10,
        configuration: String? = null,
        strategyIds: List<UUID> = listOf(strategyId),
    ): UUID {
        val placementId = UUID.random()
        connection().useStatement("INSERT INTO recommendations.placements (id, name, slug, max_items, configuration) VALUES (?, 'P', ?, ?, ?::jsonb)") {
            it.setObject(1, placementId.toJavaUuid()); it.setString(2, slug); it.setInt(3, maxItems); it.setString(4, configuration); it.execute()
        }
        strategyIds.forEachIndexed { priority, linkedStrategyId ->
            connection().useStatement(
                "INSERT INTO recommendations.placement_strategies (placement_id, strategy_id, priority) VALUES (?, ?, ?)",
            ) {
                it.setObject(1, placementId.toJavaUuid())
                it.setObject(2, linkedStrategyId.toJavaUuid())
                it.setInt(3, priority)
                it.execute()
            }
        }
        return placementId
    }

    @Test
    fun `getForPlacement returns empty for an unknown slug`() {
        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getForPlacement(null, "nope", 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getForPlacement anonymous returns the pool's top-scored candidates`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                seed(a, 0.2)
                seed(b, 0.8)
                seedPlacement("home")
            }
        }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getForPlacement(null, "home", 10) }
        assertEquals(listOf(b, a), result.map { it.metadataId })
    }

    @Test
    fun `getForPlacement invokes a linked personalized strategy for the selected profile`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                val mlStrategy = seedActiveMlStrategy()
                ensureMetadata(a)
                ensureMetadata(b)
                seedPlacement("personalized-home", strategyIds = listOf(mlStrategy.id))
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$b","$a"],"scores":[0.9,0.1]}]}""")
                .build(),
        )

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getForPlacement(profileId, "personalized-home", 10) }

        assertEquals(listOf(b, a), result.map { it.metadataId })
        assertTrue(result.all { it.reason == "Recommended by context model" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getForPlacement does not invoke a linked personalized strategy for an anonymous request`() {
        withDb {
            transaction {
                val mlStrategy = seedActiveMlStrategy()
                seedPlacement("anonymous-home", strategyIds = listOf(mlStrategy.id))
            }
        }

        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getForPlacement(null, "anonymous-home", 10) }

        assertTrue(result.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getForPlacement does not invoke a paused linked personalized strategy`() {
        withDb {
            transaction {
                val pausedStrategy = seedStrategies.add(
                    RecommendationStrategy(
                        name = "Paused ML",
                        type = RecommendationStrategyType.PERSONALIZED,
                        status = RecommendationStrategyStatus.PAUSED,
                        configuration = Json.encodeToJsonElement(
                            TfServingConfiguration.serializer(),
                            TfServingConfiguration(url = server.url("/").toString().trimEnd('/')),
                        ),
                    ),
                )
                seedPlacement("paused-home", strategyIds = listOf(pausedStrategy.id))
            }
        }

        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getForPlacement(profileId, "paused-home", 10) }

        assertTrue(result.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getForPlacement keeps materialized candidates when a linked personalized strategy is unavailable`() {
        val materialized = UUID.random()
        withDb {
            transaction {
                seed(materialized, 10.0)
                val mlStrategy = seedActiveMlStrategy()
                seedPlacement("fallback-home", strategyIds = listOf(strategyId, mlStrategy.id))
            }
        }
        server.enqueue(MockResponse.Builder().code(500).body("unavailable").build())

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getForPlacement(profileId, "fallback-home", 10) }

        assertEquals(listOf(materialized), result.map { it.metadataId })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getForPlacement normalizes personalized and materialized sources before blending`() {
        val materializedHigh = UUID.random()
        val materializedLow = UUID.random()
        val personalizedHigh = UUID.random()
        val personalizedLow = UUID.random()
        withDb {
            transaction {
                seed(materializedHigh, 100.0)
                seed(materializedLow, 50.0)
                val mlStrategy = seedActiveMlStrategy()
                ensureMetadata(personalizedHigh)
                ensureMetadata(personalizedLow)
                // Later entries have higher placement priority, so the personalized source wins equal-score ties.
                seedPlacement("blended-home", strategyIds = listOf(strategyId, mlStrategy.id))
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(
                    """{"predictions":[{"content_ids":["$personalizedHigh","$personalizedLow"],"scores":[0.9,0.8]}]}""",
                )
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.9,0.8]}""").build())

        var result: List<Recommendation> = emptyList()
        withDb { result = service.getForPlacement(profileId, "blended-home", 2) }

        assertEquals(setOf(personalizedHigh, materializedHigh), result.mapNotNull { it.metadataId }.toSet())
    }

    @Test
    fun `getForPlacement applies the request content context`() {
        val article = UUID.random()
        val image = UUID.random()
        withDb {
            transaction {
                seed(article, 0.9)
                seedRecommendation(
                    Recommendation(metadataId = image, strategyId = strategyId, score = 0.8),
                    listOf("images"),
                )
                seedPlacement("image-picker")
                seedContext(
                    "images",
                    RecommendationContentFilter(
                        metadata = RecommendationMetadataFilter(includedContentTypePrefixes = listOf("image/")),
                        collections = null,
                    ),
                )
            }
        }

        var result: List<Recommendation> = emptyList()
        withDb {
            result = service.getForPlacement(
                null,
                "image-picker",
                10,
                "images",
            )
        }

        assertEquals(listOf(image), result.map { it.metadataId })
    }

    @Test
    fun `getForPlacement for a profile drops the viewer's dismissals`() {
        val kept = UUID.random()
        val dismissed = UUID.random()
        withDb {
            transaction {
                seed(kept, 0.7)
                seed(dismissed, 0.9)
                seedPlacement("home2")
                seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = dismissed))
            }
        }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getForPlacement(profileId, "home2", 10) }
        assertEquals(listOf(kept), result.map { it.metadataId })
    }

    @Test
    fun `getForPlacement is empty when the mapped strategies have no candidates`() {
        var result: List<Recommendation> = listOf(mockk())
        withDb {
            transaction { seedPlacement("empty") }
            result = service.getForPlacement(null, "empty", 10)
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `related endpoints preserve final model scores and never merge SQL candidates`() {
        val modelItem = UUID.random()
        val sqlItem = UUID.random()
        withDb { transaction { seedActiveMlStrategy(); ensureMetadata(modelItem); seedRelated(sqlItem, 99.0) } }
        for (coEngaged in listOf(false, true)) {
            server.enqueue(MockResponse.Builder().code(200).body(
                """{"predictions":[{"content_ids":["$modelItem"],"scores":[0.27]}]}""",
            ).build())
            var result = emptyList<Recommendation>()
            withServing {
                result = if (coEngaged) service.getCoEngaged(sourceId, profileId, 1, true, 1L)
                    else service.getRecommended(sourceId, profileId, 1, true, 1L)
            }
            assertEquals(listOf(modelItem), result.map { it.metadataId })
            assertEquals(.27, result.single().score)
            assertTrue(result.single().sources.isEmpty())
            assertEquals(1L, result.single().inference?.modelVersion)
            val request = server.takeRequest()
            assertTrue(request.url.encodedPath.contains("/versions/1:predict"))
            val body = request.body!!.utf8()
            val signature = if (coEngaged) "co_engaged" else "related"
            assertTrue(body.contains("\"signature_name\":\"$signature\""))
            assertTrue(body.contains(sourceId.toString()))
            assertTrue(body.contains(profileId.toString()))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unknown model source does not fall through to SQL related candidates`() {
        withDb { transaction { seedActiveMlStrategy(); seedRelated(UUID.random(), 99.0) } }
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[]}""").build())
        withServing { assertTrue(service.getRecommended(sourceId, profileId, 5, true, 1L).isEmpty()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unavailable personalized related model falls back to content similarity`() {
        val item = UUID.random()
        withDb { transaction { seedActiveMlStrategy(); ensureMetadata(item) } }
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$item"],"scores":[0.4]}]}""",
        ).build())
        withServing {
            val result = service.getRecommended(sourceId, profileId, 1, true, 1L)
            assertEquals(item, result.single().metadataId)
            assertEquals(false, result.single().inference?.personalized)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `related refill removes self invalid duplicates and dismissed items before requesting another page`() {
        val dismissed = UUID.random()
        val first = UUID.random()
        val next = UUID.random()
        withDb { transaction {
            seedActiveMlStrategy()
            listOf(dismissed, first, next).forEach { ensureMetadata(it) }
            seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = dismissed))
        } }
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$sourceId","invalid","$dismissed","$first"],"scores":[1,0.9,0.8,0.7]}]}""",
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$first","$next"],"scores":[0.7,0.6]}]}""",
        ).build())
        withServing { assertEquals(listOf(first, next), service.getRecommended(sourceId, profileId, 2, true, 1L).map { it.metadataId }) }
        assertTrue(server.takeRequest().body!!.utf8().contains("\"offset\":0"))
        assertTrue(server.takeRequest().body!!.utf8().contains("\"offset\":4"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sources resolve only on demand and retain the ranked model version after activation changes`() {
        val item = UUID.random()
        withDb { transaction { seedActiveMlStrategy(); ensureMetadata(item) } }
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$item"],"scores":[0.4]}]}""",
        ).build())
        var ranked = emptyList<Recommendation>()
        withServing { ranked = service.getRecommended(sourceId, profileId, 1, true, 1L) }
        assertEquals(1, server.requestCount)
        server.takeRequest()
        withDb { connection().useStatement("UPDATE recommendations.contexts SET active_model_version = NULL") { it.execute() } }
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[[0.3,0.2,0,-0.1]]}""").build())
        withServing {
            assertEquals(listOf(listOf(RecommendationSource.CONTENT_MODEL, RecommendationSource.PERSONALIZED_MODEL)), service.getSources(ranked))
        }
        assertTrue(server.takeRequest().url.encodedPath.contains("/versions/1:predict"))
        assertEquals(.4, ranked.single().score)
    }

    @Test
    fun `GraphQL selects sources lazily and batches results through the generated dispatcher`() {
        val recommendationService = mockk<RecommendationService>()
        val recommendations = listOf(Recommendation(strategyId = UUID.NIL, score = .7),
            Recommendation(strategyId = UUID.random(), score = .3))
        coEvery { recommendationService.getSources(any()) } answers {
            firstArg<List<Recommendation>>().map { if (it.score > .5) listOf(RecommendationSource.CONTENT_MODEL) else emptyList() }
        }
        provides<io.opentelemetry.api.trace.Tracer>(singleton = true) {
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("recommendation-test")
        }
        val controller = bosca.recommendations.graphql.RecommendationController(
            mockk(), metadataService, collectionService, mockk(), mockk(), mockk(), recommendationService,
        )
        val dispatcher = bosca.recommendations.graphql.RecommendationControllerDispatcher(controller)
        val executable = bosca.graphql.server.ExecutableSchema.fromSdl(
            """type Query { results: [RecommendationResult!]! }
                type RecommendationResult { score: Float! sources: [RecommendationSource!]! }
                enum RecommendationSource { CONTENT_MODEL PERSONALIZED_MODEL CO_ENGAGEMENT COHORT_CO_ENGAGEMENT TRENDING }
            """,
            bosca.graphql.server.runtimeWiring {
                type("Query") { field("results") { recommendations } }
                type(bosca.graphql.server.TypeRuntimeWiring.newTypeWiring("RecommendationResult")
                    .field("score", dispatcher.type.fieldResolvers.getValue("score"))
                    .field("sources", dispatcher.type.fieldResolvers.getValue("sources"))
                    .build())
            },
        )
        val graphql = bosca.graphql.server.GraphQL(executable)
        val context = bosca.graphql.server.GraphQLContext(mapOf(
            "authenticationContext" to mockk<bosca.security.service.AuthenticationContext>(),
            "call" to mockk<bosca.server.ServerCall>(),
        ))
        withServing {
            for (query in listOf("{ results { score } }", "{ results { score sources @skip(if: true) } }")) {
                val response = graphql.execute(bosca.graphql.server.GraphQLRequest(query, context = context))
                assertTrue(response.errors.isEmpty(), response.errors.toString())
            }
            io.mockk.coVerify(exactly = 0) { recommendationService.getSources(any()) }
            val response = graphql.execute(bosca.graphql.server.GraphQLRequest("{ results { score sources } }", context = context))
            assertTrue(response.errors.isEmpty(), response.errors.toString())
            assertEquals("""{"results":[{"score":0.7,"sources":["CONTENT_MODEL"]},{"score":0.3,"sources":[]}]}""", response.data.toString())
        }
        io.mockk.coVerify(exactly = 1) { recommendationService.getSources(recommendations) }
    }

    @Test
    fun `sources return empty for unavailable models and malformed explanations and preserve actual trending`() {
        val item = UUID.random()
        val input = bosca.recommendations.model.RecommendationInference(1, false, "default", "en", sourceId = sourceId)
        val modelResult = Recommendation(metadataId = item, strategyId = UUID.NIL, inference = input)
        withServing {
            assertTrue(service.getSources(emptyList()).isEmpty())
            assertEquals(listOf(emptyList(), listOf(RecommendationSource.TRENDING), emptyList(), emptyList()), service.getSources(listOf(
                Recommendation(strategyId = UUID.NIL, sources = setOf(RecommendationSource.CONTENT_MODEL)),
                Recommendation(strategyId = UUID.NIL, sources = setOf(RecommendationSource.TRENDING)),
                modelResult.copy(metadataId = null),
                modelResult.copy(inference = input.copy(modelVersion = 9999)),
            )))
        }
        assertEquals(0, server.requestCount)
        withDb { connection().useStatement("UPDATE recommendations.context_models SET status = 'running' WHERE version = 1") { it.execute() } }
        withServing { assertEquals(listOf(emptyList()), service.getSources(listOf(modelResult))) }
        assertEquals(0, server.requestCount)
        withDb { connection().useStatement("UPDATE recommendations.context_models SET status = 'completed' WHERE version = 1") { it.execute() } }
        server.enqueue(MockResponse.Builder().code(200).body("malformed").build())
        withServing { assertEquals(listOf(emptyList()), service.getSources(listOf(modelResult))) }
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        withServing { assertEquals(listOf(emptyList()), service.getSources(listOf(modelResult))) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `repeated model pages terminate refill without duplicate results`() {
        val item = UUID.random()
        withDb { transaction { ensureMetadata(item) } }
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$item"],"scores":[0.7]}]}""",
        ).build()) }
        withServing { assertEquals(listOf(item), service.getSimilar(sourceId, 3).map { it.metadataId }) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `zero result limits do not call the model`() {
        withServing {
            assertTrue(service.getSimilar(sourceId, 0).isEmpty())
            assertTrue(service.getRecommended(sourceId, profileId, 0).isEmpty())
            assertTrue(service.getCoEngaged(sourceId, profileId, 0).isEmpty())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `SQL co-engagement fallback refills past multiple pages of dismissals and the source itself`() {
        val expected = listOf(UUID.random(), UUID.random())
        withDb { transaction {
            seedRelated(sourceId, 100.0)
            repeat(5) {
                val dismissed = UUID.random()
                seedRelated(dismissed, 90.0 - it)
                seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = dismissed))
            }
            expected.forEachIndexed { i, id -> seedRelated(id, 10.0 - i) }
        } }
        withServing { assertEquals(expected, service.getCoEngaged(sourceId, profileId, 2, mlEnabled = false).map { it.metadataId }) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a context awaiting its first model returns no metadata but preserves collection trending`() {
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        withDb { transaction {
            val trending = seedStrategies.add(RecommendationStrategy(name = "Trending", type = RecommendationStrategyType.TRENDING,
                status = RecommendationStrategyStatus.ACTIVE))
            seedRecommendation(Recommendation(metadataId = metadataId, strategyId = trending.id, score = 1.0))
            seedCollectionRecommendation(Recommendation(collectionId = collectionId, strategyId = trending.id, score = .8))
            connection().useStatement("UPDATE recommendations.contexts SET active_model_version = NULL") { it.execute() }
        } }
        withServing {
            assertTrue(service.getSimilar(sourceId, 2).isEmpty())
            assertTrue(service.getRecommended(sourceId, profileId, 2).isEmpty())
            assertTrue(service.getCoEngaged(sourceId, profileId, 2).isEmpty())
            val trending = service.getTrending(0, 2)
            assertEquals(listOf(collectionId), trending.map { it.collectionId })
            assertNull((trending.single().context as JsonObject)["recommendation_model_version"])
            val feed = service.getForProfile(profileId, 0, 2)
            assertEquals(listOf(collectionId), feed.map { it.collectionId })
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nonfinite model scores are rejected and a failed later page preserves valid candidates`() {
        val invalid = UUID.random()
        val valid = UUID.random()
        withDb { transaction { ensureMetadata(invalid); ensureMetadata(valid) } }
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"predictions":[{"content_ids":["$invalid","$valid"],"scores":["NaN",0.7]}]}""",
        ).build())
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        withServing { assertEquals(listOf(valid), service.getSimilar(sourceId, 2).map { it.metadataId }) }
        assertEquals(2, server.requestCount)
    }

    // ── similar ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `getSimilar uses the independently configured content model`() {
        val source = UUID.random()
        val sim = UUID.random()
        withDb {
            transaction {
                ensureMetadata(sim)
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$source","$sim"],"scores":[1.0,0.8]}]}""").build(),
        )
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        // The source item itself is filtered out; the neighbour carries the actual request provenance.
        assertEquals(listOf(sim), result.map { it.metadataId })
        val provenance = result.single().context as JsonObject
        assertEquals(null, provenance["fallback"])
        assertEquals(JsonPrimitive(source.toString()), provenance["recommendation_source_id"])
        assertEquals(JsonPrimitive("default"), provenance["recommendation_context"])
        assertEquals(JsonPrimitive(1L), provenance["recommendation_model_version"])
        assertNotNull(provenance["recommendation_request_id"])
        assertTrue(result.single().sources.isEmpty())
        assertNotNull(result.single().inference)
    }

    @Test
    fun `getSimilar ignores stored vectors and uses the content model without a personalized strategy`() {
        val source = UUID.random()
        val sim = UUID.random()
        withDb {
            transaction {
                seedEmbedding(source)
                seedEmbedding(sim)
                ensureMetadata(sim)
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$sim"],"scores":[0.8]}]}""").build(),
        )
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        assertEquals(listOf(sim), result.map { it.metadataId })
        assertEquals(2, server.requestCount) // final empty page establishes catalog exhaustion
    }

    // ── related (global) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `getCoEngaged global returns co-occurrence order for every viewer`() {
        val x = UUID.random()
        val y = UUID.random()
        withDb {
            transaction {
                seedRelated(x, 0.4)
                seedRelated(y, 0.9)
            }
        }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, null, 10, mlEnabled = true, modelVersion = null) }
        assertEquals(listOf(y, x), result.map { it.metadataId })
    }

    // ── recommended ───────────────────────────────────────────────────────────────────────────────




    @Test
    fun `getRecommended uses the resolved language when normalization is absent or falls back`() {
        coEvery { languagesService.resolveLanguageTag(any(), "und") } returns
            LanguageTagResolution("und", null, "en", false)
        coEvery { languagesService.resolveLanguageTag(any(), "de-DE") } returns
            LanguageTagResolution("de-DE", "de-DE", "en", true)
        repeat(2) {
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"predictions":[{"content_ids":[],"scores":[]}]}""").build(),
            )
        }

        var withoutNormalization: List<Recommendation> = listOf(mockk())
        var withFallback: List<Recommendation> = listOf(mockk())
        withDb {
            withoutNormalization = service.getRecommended(sourceId, null, 10, languageTag = "und")
            withFallback = service.getRecommended(sourceId, null, 10, languageTag = "de-DE")
        }

        assertTrue(withoutNormalization.isEmpty())
        assertTrue(withFallback.isEmpty())
    }






    @Test
    fun `getRecommended omits cohort candidates for an uncohorted viewer`() {
        val cohortItem = UUID.random()
        withDb {
            transaction {
                // A cohort edge exists, but the viewer has no useAsCohort signal → no cohort_key → not folded in.
                seedCohortEdge(cohortItem, cohortKey("age_band", "25-34"), 0.8)
            }
        }
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[],"scores":[]}]}""").build())
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getRecommended(sourceId, profileId, 10, mlEnabled = false, modelVersion = null) }
        assertTrue(result.none { it.metadataId == cohortItem })
    }




    @Test
    fun `getRecommended is empty when there are no related or similar candidates`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[],"scores":[]}]}""").build())
        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getRecommended(UUID.random(), null, 10, mlEnabled = true, modelVersion = null) }
        assertTrue(result.isEmpty())
    }

    // ── dismiss / undismiss ───────────────────────────────────────────────────────────────────────

    @Test
    fun `dismiss then undismiss round-trips a metadata dismissal`() {
        val metadataId = UUID.random()
        withServing { service.dismiss(profileId, metadataId, null) }
        var afterDismiss: List<UUID> = emptyList()
        withDb { afterDismiss = seedDismissals.getDismissedMetadataIds(profileId) }
        assertEquals(listOf(metadataId), afterDismiss)

        withServing { service.undismiss(profileId, metadataId, null) }
        var afterUndismiss: List<UUID> = listOf(UUID.random())
        withDb { afterUndismiss = seedDismissals.getDismissedMetadataIds(profileId) }
        assertTrue(afterUndismiss.isEmpty())
    }

    // ── content-model cold start ────────────────────────────────────────────────────────────────

    @Test
    fun `getForPlacement reads the care floor from every placement configuration shape`() {
        // Exercises careFloorOf across: no config, non-object, object w/o careFloor, non-primitive careFloor,
        // non-numeric careFloor, and a valid numeric careFloor.
        val configs = listOf(null, "5", "{}", """{"careFloor":{}}""", """{"careFloor":"x"}""", """{"careFloor":0.2}""")
        withDb {
            transaction {
                seed(UUID.random(), 0.9)
                configs.forEachIndexed { i, cfg -> seedPlacement("cf$i", configuration = cfg) }
            }
        }
        configs.forEachIndexed { i, _ ->
            withDb { service.getForPlacement(profileId, "cf$i", 10) }
        }
    }

    @Test
    fun `getSimilar is empty when the content model returns no neighbours`() {
        val source = UUID.random()
        val sim = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                seedEmbedding(source)
                seedEmbedding(sim)
            }
        }
        // Operational chunks are training input, not an alternate serving result source.
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[],"scores":[]}]}""").build())
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getSimilar is empty when the content model is unreachable`() {
        val source = UUID.random()
        val sim = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                seedEmbedding(source)
                seedEmbedding(sim)
            }
        }
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `source-less cold start uses trending without consulting profile ratings`() {
        val ratedItem = UUID.random()
        val trend = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedRecommendation(Recommendation(metadataId = trend, strategyId = t.id, score = 0.5))
            }
        }
        coEvery { ratingService.getRatingsByProfile(profileId) } returns listOf(ProfileRating(profileId = profileId, metadataId = ratedItem, rating = 5))
        // The model reports an unknown profile; this source-less request falls back to trending.
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[""],"scores":[0.0]}]}""").build())
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        // Falls through to trending, marked as a fallback.
        assertEquals(listOf(trend), result.map { it.metadataId })
        assertTrue(result.all { (it.context as JsonObject)["fallback"] == JsonPrimitive(true) })
        assertEquals(1, server.requestCount)
        io.mockk.coVerify(exactly = 0) { ratingService.getRatingsByProfile(any()) }
    }

    @Test
    fun `getForProfile retains accepted model candidates when refill fails`() {
        val a = UUID.random()
        val b = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(a)
                ensureMetadata(b)
            }
        }
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":["$a","$b"],"scores":[0.9,0.4]}]}""").build())
        // Refilling fails; candidates already returned by the model remain usable.
        server.enqueue(MockResponse.Builder().code(500).body("rank down").build())
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(a, b), result.map { it.metadataId })
    }




    @Test
    fun `personalized related with all candidates dismissed yields nothing`() {
        val a = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                seedRelated(a, 0.9)
                seedDismissals.add(RecommendationDismissal(profileId = profileId, metadataId = a))
            }
        }
        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getCoEngaged(sourceId, profileId, 10, mlEnabled = true, modelVersion = null) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `personalized related with a config-less ML strategy degrades to the heuristic order`() {
        val a = UUID.random()
        withDb {
            transaction {
                seedMlStrategyNoConfig() // default TF config → unreachable host → ranker fails → heuristic
                seedRelated(a, 0.9)
            }
        }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, profileId, 10, mlEnabled = true, modelVersion = null) }
        assertEquals(listOf(a), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile source-less cold start does not call the content model`() {
        val ratedItem = UUID.random()
        val trend = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedRecommendation(Recommendation(metadataId = trend, strategyId = t.id, score = 0.5))
            }
        }
        coEvery { ratingService.getRatingsByProfile(profileId) } returns listOf(ProfileRating(profileId = profileId, metadataId = ratedItem, rating = 5))
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[""],"scores":[0.0]}]}""").build()) // retrieval blank
        server.enqueue(MockResponse.Builder().code(500).body("down").build()) // content-model similar unreachable
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(trend), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile falls back to trending independently of legacy strategy configuration`() {
        // The default TF config points at an unreachable host, so both the retrieval and the content-model
        // cold-start calls fail — exercising the default-config + catch branches of liveRetrieveRank AND
        // contentBasedFallback in one path.
        val ratedItem = UUID.random()
        val trend = UUID.random()
        withDb {
            transaction {
                seedMlStrategyNoConfig()
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedRecommendation(Recommendation(metadataId = trend, strategyId = t.id, score = 0.5))
            }
        }
        coEvery { ratingService.getRatingsByProfile(profileId) } returns listOf(ProfileRating(profileId = profileId, metadataId = ratedItem, rating = 5))
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(trend), result.map { it.metadataId })
    }

    @Test
    fun `getSimilar with a config-less ML strategy does not serve stored vectors`() {
        val source = UUID.random()
        val sim = UUID.random()
        withDb {
            transaction {
                seedMlStrategyNoConfig()
                seedEmbedding(source)
                seedEmbedding(sim)
            }
        }
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[],"scores":[]}]}""").build())
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        assertTrue(result.isEmpty())
    }


    @Test
    fun `getForProfile drops retrieved content ids that are not UUIDs`() {
        val valid = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(valid)
            }
        }
        // A non-empty but non-UUID id survives the client's blank filter, then fails UUID.parse → skipped.
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":["not-a-uuid","$valid"],"scores":[0.9,0.4]}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[0.5]}""").build())
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(valid), result.map { it.metadataId })
    }





    @Test
    fun `getForProfile cold start with no ratings falls straight through to trending`() {
        val trend = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedRecommendation(Recommendation(metadataId = trend, strategyId = t.id, score = 0.5))
            }
        }
        // No ratings → the content-model cold start has no seeds → straight to trending.
        server.enqueue(MockResponse.Builder().code(200).body("""{"predictions":[{"content_ids":[""],"scores":[0.0]}]}""").build())
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(trend), result.map { it.metadataId })
    }



    @Test
    fun `getSimilar drops the source item and non-UUID neighbours from the content model`() {
        val source = UUID.random()
        val neighbour = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                ensureMetadata(neighbour)
            }
        }
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"predictions":[{"content_ids":["$source","not-a-uuid","$neighbour"],"scores":[1.0,0.9,0.8]}]}""").build(),
        )
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getSimilar(source, 10) }
        assertEquals(listOf(neighbour), result.map { it.metadataId })
    }

    @Test
    fun `getForProfile on the heuristic arm skips the ML ranker and serves trending`() {
        val trend = UUID.random()
        withDb {
            transaction {
                seedActiveMlStrategy()
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedRecommendation(Recommendation(metadataId = trend, strategyId = t.id, score = 0.5))
            }
        }
        // mlEnabled = false → the uncached heuristic arm, which never calls the ML retriever/ranker.
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10, mlEnabled = false, modelVersion = null) }
        assertEquals(listOf(trend), result.map { it.metadataId })
        assertEquals(0, server.requestCount, "the heuristic arm must not call TF Serving")
    }

    @Test
    fun `cold-start trending filters a dismissed collection recommendation`() {
        val keptColl = UUID.random()
        val dismissedColl = UUID.random()
        withDb {
            transaction {
                val t = seedStrategies.add(RecommendationStrategy(name = "T", type = RecommendationStrategyType.TRENDING, status = RecommendationStrategyStatus.ACTIVE))
                seedCollectionRecommendation(Recommendation(collectionId = keptColl, strategyId = t.id, score = 0.9))
                seedCollectionRecommendation(Recommendation(collectionId = dismissedColl, strategyId = t.id, score = 0.8))
                seedDismissals.add(RecommendationDismissal(profileId = profileId, collectionId = dismissedColl))
            }
        }
        // No ML strategy → straight to trending; the dismissed collection is filtered by collection id.
        var result: List<Recommendation> = emptyList()
        withServing { result = service.getForProfile(profileId, 0, 10) }
        assertEquals(listOf(keptColl), result.map { it.collectionId })
    }

    @Test
    fun `getForPlacement is empty when the placement has no strategies mapped`() {
        withDb {
            transaction {
                val placementId = UUID.random()
                connection().useStatement("INSERT INTO recommendations.placements (id, name, slug, max_items) VALUES (?, 'P', 'nolinks', 10)") {
                    it.setObject(1, placementId.toJavaUuid()); it.execute()
                }
            }
        }
        var result: List<Recommendation> = listOf(mockk())
        withDb { result = service.getForPlacement(null, "nolinks", 10) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCoEngaged global preserves a co-occurrence edge's stored reason`() {
        val a = UUID.random()
        withDb { transaction { seedRelated(a, 0.9, reason = "Also bought") } }
        var result: List<Recommendation> = emptyList()
        withDb { result = service.getCoEngaged(sourceId, null, 10, mlEnabled = true, modelVersion = null) }
        assertEquals("Also bought", result.single().reason)
    }




    @Test
    fun `undismiss clears a collection dismissal`() {
        val collectionId = UUID.random()
        withServing { service.dismiss(profileId, null, collectionId) }
        withServing { service.undismiss(profileId, null, collectionId) }
        var ids: List<UUID> = listOf(UUID.random())
        withDb { ids = seedDismissals.getDismissedCollectionIds(profileId) }
        assertTrue(ids.isEmpty())
    }

}
