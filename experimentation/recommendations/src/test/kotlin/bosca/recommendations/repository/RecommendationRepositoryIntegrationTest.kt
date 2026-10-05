@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.createProfileAttributeSignalsPrerequisites
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationDismissal
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContentFilter
import bosca.recommendations.model.RecommendationMetadataFilter
import bosca.recommendations.seedCompletedContextModel
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the two serve-critical recommendations repositories against a TestContainers
 * instance. The suite doubles as the migration smoke test — every `@BeforeTest` runs Flyway, so any SQL
 * error in `V1`/`V2` (the `strategy_type`/`strategy_status` enums, the cross-schema FKs into
 * `public.profiles` / `public.analytics_queries` / `segmentation.segments`, the partial unique indexes)
 * surfaces here before deploy. The cross-schema prerequisites are stubbed so the recommendations
 * migration can run in isolation (mirrors the segmentation/experimentation integration suites).
 *
 * What this pins that the unit suite cannot:
 *   1. The KSP-generated `@Query` SQL round-trips against a live PG (the `on conflict … do update` upsert,
 *      the `score desc` ordering, the `expires_at` filter, the `::jsonb` cast on `context`).
 *   2. The single-column `select metadata_id` / `select collection_id` projections return the real UUIDs —
 *      the exact shape of the KSP scalar-`@Query` bug, where a List<UUID> column could come back empty/NIL.
 */
@OptIn(ExperimentalUuidApi::class)
class RecommendationRepositoryIntegrationTest {

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

        private val profileId = UUID.random()
        private val strategyId = UUID.random()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = RecommendationRepositoryImpl()
    private val dismissals = RecommendationDismissalRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            withDb {
                // Cross-schema FK targets the recommendations migration references — stubbed so it runs alone.
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE SCHEMA IF NOT EXISTS segmentation") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS segmentation.segments (id uuid PRIMARY KEY)") { it.execute() }
                // V5 prunes orphaned scheduled evaluation jobs of the removed strategy types; stub the table.
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.scheduled_jobs (id uuid PRIMARY KEY)") { it.execute() }
                createProfileAttributeSignalsPrerequisites()  // for the profile_cohort view (V8)
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(RecommendationsMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("UPDATE recommendations.contexts SET active_model_version = NULL, requested_model_version = NULL") { it.execute() }
                connection().useStatement("TRUNCATE recommendations.context_models RESTART IDENTITY CASCADE") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.recommendations") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.dismissals") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.strategies") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata_embeddings") { it.execute() }
                connection().useStatement("DELETE FROM public.metadata") { it.execute() }
                connection().useStatement("DELETE FROM public.collection_language_variants") { it.execute() }
                connection().useStatement("DELETE FROM public.collections") { it.execute() }
                connection().useStatement("DELETE FROM public.profiles") { it.execute() }
                connection().useStatement(
                    """
                    INSERT INTO public.language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
                    SELECT id, 'en', 'en' FROM public.language_resolution_contexts WHERE key = 'recommendations'
                    ON CONFLICT (context_id, source_language_tag)
                    DO UPDATE SET resolved_language_tag = excluded.resolved_language_tag
                    """.trimIndent(),
                ) { it.execute() }
            }
            transaction {
                connection().useStatement("INSERT INTO public.profiles (id) VALUES (?)") {
                    it.setObject(1, profileId.toJavaUuid()); it.execute()
                }
                connection().useStatement(
                    "INSERT INTO recommendations.strategies (id, name, type) VALUES (?, 'Test Strategy', 'trending')",
                ) {
                    it.setObject(1, strategyId.toJavaUuid()); it.execute()
                }
            }
        }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

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

    private fun rec(metadataId: UUID, score: Double, expiresAt: OffsetDateTime? = null) =
        Recommendation(
            metadataId = metadataId,
            strategyId = strategyId,
            score = score,
            expiresAt = expiresAt,
        )

    private suspend fun upsertMetadata(
        recommendation: Recommendation,
        contexts: List<String> = listOf("default"),
        recommendable: Boolean = true,
        languageTag: String = "en",
    ): Recommendation {
        connection().useStatement(
            """
            INSERT INTO public.metadata (id, recommendation_contexts, recommendable, language_tag, content_type)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                recommendation_contexts = excluded.recommendation_contexts,
                recommendable = excluded.recommendable,
                language_tag = excluded.language_tag,
                content_type = excluded.content_type
            """.trimIndent(),
        ) {
            it.setObject(1, recommendation.metadataId?.toJavaUuid())
            it.setArray(2, connection().createArrayOf("text", contexts.toTypedArray()))
            it.setBoolean(3, recommendable)
            it.setString(4, languageTag)
            it.setString(5, if ("images" in contexts) "image/png" else "bosca/v-document")
            it.execute()
        }
        return repository.upsertMetadata(recommendation)
    }

    @Test
    fun `trending fills pages after excluding more than fifty higher scoring assets`() {
        withDb {
            repeat(60) { index ->
                upsertMetadata(rec(UUID.random(), 100.0 + index), contexts = listOf("images"))
            }
            val candidates = (1..12).map { score ->
                UUID.random().also { upsertMetadata(rec(it, score.toDouble())) }
            }.reversed()

            val firstPage = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true)
            val secondPage = repository.getByStrategyId(strategyId, "default", 10, 10, metadataEnabled = true)
            assertEquals(candidates.take(10), firstPage.map { it.metadataId })
            assertEquals(candidates.drop(10), secondPage.map { it.metadataId })
        }
    }

    // ── RecommendationRepository ──────────────────────────────────────────────────────────────────

    @Test
    fun `upsertMetadata inserts a row the DB ids and getByStrategyId reads back`() {
        val metadataId = UUID.random()
        lateinit var saved: Recommendation
        withDb { transaction { saved = upsertMetadata(rec(metadataId, 0.7)) } }
        assertEquals(metadataId, saved.metadataId)
        assertEquals(0.7, saved.score)
        assertNotEquals(UUID.NIL, saved.id)

        var fetched: List<Recommendation> = emptyList()
        withDb { transaction { fetched = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true) } }
        assertEquals(1, fetched.size)
        assertEquals(metadataId, fetched.first().metadataId)
        assertEquals(strategyId, fetched.first().strategyId)
    }

    @Test
    fun `getByStrategyId orders by score descending`() {
        withDb {
            transaction {
                upsertMetadata(rec(UUID.random(), 0.1))
                upsertMetadata(rec(UUID.random(), 0.9))
                upsertMetadata(rec(UUID.random(), 0.5))
            }
        }
        var scores: List<Double> = emptyList()
        withDb { transaction { scores = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true).map { it.score } } }
        assertEquals(listOf(0.9, 0.5, 0.1), scores)
    }

    @Test
    fun `getByStrategyId excludes metadata and collections that are not recommendable`() {
        val includedMetadataId = UUID.random()
        val excludedMetadataId = UUID.random()
        val excludedCollectionId = UUID.random()
        withDb {
            transaction {
                upsertMetadata(rec(includedMetadataId, 0.5))
                upsertMetadata(rec(excludedMetadataId, 0.9), recommendable = false)
                connection().useStatement(
                    "INSERT INTO public.collections (id, recommendation_contexts, recommendable) VALUES (?, ARRAY['default'], false)",
                ) {
                    it.setObject(1, excludedCollectionId.toJavaUuid())
                    it.execute()
                }
                repository.upsertCollection(
                    Recommendation(collectionId = excludedCollectionId, strategyId = strategyId, score = 1.0),
                )
            }
        }

        var recommendations: List<Recommendation> = emptyList()
        withDb {
            transaction {
                recommendations = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true)
            }
        }

        assertEquals(listOf(includedMetadataId), recommendations.mapNotNull { it.metadataId })
        assertTrue(recommendations.none { it.collectionId == excludedCollectionId })
    }

    @Test
    fun `getByStrategyId filters metadata and collection variants by language`() {
        val englishMetadataId = UUID.random()
        val frenchMetadataId = UUID.random()
        val frenchOnlyCollectionId = UUID.random()
        val englishOnlyCollectionId = UUID.random()
        withDb {
            transaction {
                upsertMetadata(rec(englishMetadataId, 0.9), languageTag = "en")
                upsertMetadata(rec(frenchMetadataId, 0.8), languageTag = "fr")
                connection().useStatement(
                    "INSERT INTO public.collections (id, language_tag, recommendation_contexts, recommendable) VALUES (?, 'en', ARRAY['default'], false), (?, 'en', ARRAY['default'], true)",
                ) {
                    it.setObject(1, frenchOnlyCollectionId.toJavaUuid())
                    it.setObject(2, englishOnlyCollectionId.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    "INSERT INTO public.collection_language_variants (id, language_tag, recommendable) VALUES (?, 'fr', true), (?, 'fr', false)",
                ) {
                    it.setObject(1, frenchOnlyCollectionId.toJavaUuid())
                    it.setObject(2, englishOnlyCollectionId.toJavaUuid())
                    it.execute()
                }
                repository.upsertCollection(
                    Recommendation(collectionId = frenchOnlyCollectionId, strategyId = strategyId, score = 0.7),
                )
                repository.upsertCollection(
                    Recommendation(collectionId = englishOnlyCollectionId, strategyId = strategyId, score = 0.6),
                )
            }
        }

        var english: List<Recommendation> = emptyList()
        var french: List<Recommendation> = emptyList()
        withDb {
            transaction {
                english = repository.getByStrategyId(strategyId, "default", 0, 10, "en", metadataEnabled = true)
                french = repository.getByStrategyId(strategyId, "default", 0, 10, "fr", metadataEnabled = true)
            }
        }

        assertEquals(listOf(englishMetadataId), english.mapNotNull { it.metadataId })
        assertEquals(listOf(englishOnlyCollectionId), english.mapNotNull { it.collectionId })
        assertEquals("en", english.single { it.collectionId != null }.collectionLanguageTag)
        assertEquals(listOf(frenchMetadataId), french.mapNotNull { it.metadataId })
        assertEquals(listOf(frenchOnlyCollectionId), french.mapNotNull { it.collectionId })
        assertEquals("fr", french.single { it.collectionId != null }.collectionLanguageTag)
    }

    @Test
    fun `eligible collection lookup returns the representation selected at query time`() {
        val collectionId = UUID.random()
        withDb {
            transaction {
                connection().useStatement(
                    "INSERT INTO public.collections (id, language_tag, recommendation_contexts, recommendable) VALUES (?, 'en', ARRAY['default'], true)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    "INSERT INTO public.collection_language_variants (id, language_tag, recommendable) VALUES (?, 'en-US', false)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
                connection().useStatement(
                    """
                    INSERT INTO public.language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
                    SELECT id, 'en-US', 'en' FROM public.language_resolution_contexts WHERE key = 'recommendations'
                    ON CONFLICT (context_id, source_language_tag)
                    DO UPDATE SET resolved_language_tag = excluded.resolved_language_tag
                    """.trimIndent(),
                ) { it.execute() }
            }
        }

        var selectedLanguageTag = ""
        withDb {
            transaction {
                selectedLanguageTag = repository.getEligibleCollections(
                    listOf(collectionId),
                    contextType = "default",
                    languageTag = "en",
                    sourceLanguageTag = "de-DE",
                ).single().languageTag
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
        }
        assertEquals("en", selectedLanguageTag)

        withDb {
            transaction {
                selectedLanguageTag = repository.getEligibleCollections(
                    listOf(collectionId),
                    contextType = "default",
                    languageTag = "en",
                    sourceLanguageTag = "de-DE",
                ).single().languageTag
            }
        }

        assertEquals("en-US", selectedLanguageTag)
    }

    @Test
    fun `fallback language remains eligible without an identity mapping`() {
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        withDb {
            transaction {
                upsertMetadata(rec(metadataId, 0.9), languageTag = "en")
                connection().useStatement(
                    "INSERT INTO public.collections (id, language_tag, recommendation_contexts, recommendable) VALUES (?, 'en', ARRAY['default'], true)",
                ) {
                    it.setObject(1, collectionId.toJavaUuid())
                    it.execute()
                }
                repository.upsertCollection(
                    Recommendation(collectionId = collectionId, strategyId = strategyId, score = 0.8),
                )
                connection().useStatement(
                    "DELETE FROM public.language_tag_mappings WHERE source_language_tag = 'en' AND context_id = (SELECT id FROM public.language_resolution_contexts WHERE key = 'recommendations')",
                ) { it.execute() }
            }
        }

        var recommendations: List<Recommendation> = emptyList()
        withDb {
            transaction {
                recommendations = repository.getByStrategyId(strategyId, "default", 0, 10, "en", metadataEnabled = true)
                connection().useStatement(
                    """
                    INSERT INTO public.language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
                    SELECT id, 'en', 'en' FROM public.language_resolution_contexts WHERE key = 'recommendations'
                    ON CONFLICT (context_id, source_language_tag)
                    DO UPDATE SET resolved_language_tag = excluded.resolved_language_tag
                    """.trimIndent(),
                ) { it.execute() }
            }
        }

        assertEquals(listOf(metadataId), recommendations.mapNotNull { it.metadataId })
        assertEquals(listOf(collectionId), recommendations.mapNotNull { it.collectionId })
        assertEquals("en", recommendations.single { it.collectionId != null }.collectionLanguageTag)
    }

    @Test
    fun `upsertMetadata updates score on (item, strategy) conflict rather than duplicating`() {
        val metadataId = UUID.random()
        withDb { transaction { upsertMetadata(rec(metadataId, 0.3)) } }
        withDb { transaction { upsertMetadata(rec(metadataId, 0.8)) } }

        var recs: List<Recommendation> = emptyList()
        withDb { transaction { recs = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true) } }
        assertEquals(1, recs.size)
        assertEquals(0.8, recs.first().score)
    }

    @Test
    fun `getByStrategyId excludes expired recommendations`() {
        withDb {
            transaction {
                upsertMetadata(rec(UUID.random(), 0.5, expiresAt = OffsetDateTime.now().minusHours(1)))
                upsertMetadata(rec(UUID.random(), 0.6, expiresAt = OffsetDateTime.now().plusHours(1)))
            }
        }
        var recs: List<Recommendation> = emptyList()
        withDb { transaction { recs = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true) } }
        assertEquals(1, recs.size)
        assertEquals(0.6, recs.first().score)
    }

    @Test
    fun `deleteByStrategyId removes the strategy's recommendations`() {
        withDb {
            transaction {
                upsertMetadata(rec(UUID.random(), 0.5))
                upsertMetadata(rec(UUID.random(), 0.6))
            }
        }
        withDb { transaction { repository.deleteByStrategyId(strategyId) } }

        var recs: List<Recommendation> = emptyList()
        withDb { transaction { recs = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true) } }
        assertTrue(recs.isEmpty())
    }

    @Test
    fun `deleteExpired removes only expired rows and returns the count`() {
        withDb {
            transaction {
                upsertMetadata(rec(UUID.random(), 0.5, expiresAt = OffsetDateTime.now().minusHours(1)))
                upsertMetadata(rec(UUID.random(), 0.6, expiresAt = OffsetDateTime.now().minusHours(2)))
                upsertMetadata(rec(UUID.random(), 0.7, expiresAt = OffsetDateTime.now().plusHours(1)))
            }
        }
        var deleted = 0L
        withDb { transaction { deleted = repository.deleteExpired() } }
        assertEquals(2L, deleted)

        var remaining: List<Recommendation> = emptyList()
        withDb { transaction { remaining = repository.getByStrategyId(strategyId, "default", 0, 10, metadataEnabled = true) } }
        assertEquals(1, remaining.size)
    }

    @Test
    fun `getByStrategyId applies context before ranking and limit`() {
        val image = UUID.random()
        val article = UUID.random()
        withDb {
            transaction {
                upsertMetadata(rec(image, 1.0), listOf("images"))
                upsertMetadata(rec(article, 0.8), listOf("default"))
            }
        }

        var recommendations: List<Recommendation> = emptyList()
        withDb {
            transaction {
                recommendations = repository.getByStrategyId(strategyId, "default", 0, 1, metadataEnabled = true)
            }
        }

        assertEquals(listOf(article), recommendations.mapNotNull { it.metadataId })
    }

    // ── RecommendationDismissalRepository ─────────────────────────────────────────────────────────

    @Test
    fun `fallback queries apply supplied filters with include precedence before limiting`() {
        withDb {
            val article = UUID.random()
            val guide = UUID.random()
            upsertMetadata(rec(article, 0.5))
            upsertMetadata(rec(guide, 1.0))
            for ((id, type) in listOf(article to " Article ", guide to "guide")) {
                connection().useStatement("UPDATE public.metadata SET content_type = ' Text/Plain; charset=utf-8 ', attributes = jsonb_build_object('type', ?::text) WHERE id = ?") {
                    it.setString(1, type); it.setObject(2, id.toJavaUuid()); it.execute()
                }
            }
            assertEquals(listOf(article), repository.getByStrategyId(strategyId, "default", 0, 1,
                metadataEnabled = true, includedContentTypePrefixes = listOf("text/"),
                excludedContentTypePrefixes = listOf("text/"), includedAttributeTypes = listOf("article"),
                excludedAttributeTypes = listOf("article")).map { it.metadataId })
            assertEquals(listOf(guide), repository.getByStrategyId(strategyId, "default", 0, 1,
                metadataEnabled = true, includedAttributeTypes = listOf("guide")).map { it.metadataId })
            val untyped = UUID.random()
            upsertMetadata(rec(untyped, 2.0))
            assertEquals(listOf(untyped), repository.getByStrategyId(strategyId, "default", 0, 1,
                metadataEnabled = true, excludedAttributeTypes = listOf("article", "guide")).map { it.metadataId })
            assertEquals(listOf(article), repository.getByStrategyId(strategyId, "default", 0, 1,
                metadataEnabled = true, includedAttributeTypes = listOf("article")).map { it.metadataId })
            connection().useStatement("SELECT to_regclass('recommendations.context_model_items')") { statement ->
                statement.executeQuery().use { rows -> rows.next(); assertNull(rows.getString(1)) }
            }
        }
    }

    @Test
    fun `dismissal round-trips and getDismissedMetadataIds returns the real id`() {
        val metadataId = UUID.random()
        withDb { transaction { dismissals.add(RecommendationDismissal(profileId = profileId, metadataId = metadataId)) } }

        var ids: List<UUID> = emptyList()
        withDb { transaction { ids = dismissals.getDismissedMetadataIds(profileId) } }
        // The single-column projection must return the actual UUID — guards the KSP scalar-@Query bug shape.
        assertEquals(listOf(metadataId), ids)
    }

    @Test
    fun `dismissal add is idempotent on conflict`() {
        val metadataId = UUID.random()
        var first: RecommendationDismissal? = null
        var second: RecommendationDismissal? = RecommendationDismissal(profileId = profileId)
        withDb { transaction { first = dismissals.add(RecommendationDismissal(profileId = profileId, metadataId = metadataId)) } }
        withDb { transaction { second = dismissals.add(RecommendationDismissal(profileId = profileId, metadataId = metadataId)) } }
        assertNotNull(first)
        assertNull(second)

        var ids: List<UUID> = emptyList()
        withDb { transaction { ids = dismissals.getDismissedMetadataIds(profileId) } }
        assertEquals(1, ids.size)
    }

    @Test
    fun `removeByMetadata clears a metadata dismissal`() {
        val metadataId = UUID.random()
        withDb { transaction { dismissals.add(RecommendationDismissal(profileId = profileId, metadataId = metadataId)) } }
        withDb { transaction { dismissals.removeByMetadata(profileId, metadataId) } }

        var ids: List<UUID> = emptyList()
        withDb { transaction { ids = dismissals.getDismissedMetadataIds(profileId) } }
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `getDismissedCollectionIds returns dismissed collection ids`() {
        val collectionId = UUID.random()
        withDb { transaction { dismissals.add(RecommendationDismissal(profileId = profileId, collectionId = collectionId)) } }

        var ids: List<UUID> = emptyList()
        withDb { transaction { ids = dismissals.getDismissedCollectionIds(profileId) } }
        assertEquals(listOf(collectionId), ids)
    }
}
