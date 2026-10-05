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
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the KSP-generated [PersonalizationSignalRepository] impl — CRUD, enum
 * round-trip (`source_type` / `value_type`), and the enabled/source filters — driven against a
 * TestContainers instance with the recommendations migrations (incl. V7) applied.
 */
@OptIn(ExperimentalUuidApi::class)
class PersonalizationSignalRepositoryIntegrationTest {

    companion object {
        private const val MAX_MATERIALIZED_COHORT_VALUES = 64
        private const val MAX_MATERIALIZED_COHORT_VALUE_LENGTH = 256

        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_rec_signal_test")
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

    private val json = Json { ignoreUnknownKeys = true }
    private val repo = PersonalizationSignalRepositoryImpl()
    private val cohortRepo = ProfileCohortRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

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
                connection().useStatement("DELETE FROM recommendations.personalization_signals") { it.execute() }
                connection().useStatement("DELETE FROM public.profile_attributes") { it.execute() }
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

    private fun def(
        key: String = "age_band",
        enabled: Boolean = true,
        priority: Int = 0,
        sourceType: PersonalizationSignalSourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId: String = "bosca.profiles.age",
        valueType: PersonalizationSignalValueType = PersonalizationSignalValueType.CATEGORICAL,
        useAsCohort: Boolean = false,
    ) = PersonalizationSignalDefinition(
        key = key, sourceType = sourceType, sourceId = sourceId, expression = "value",
        valueType = valueType, priority = priority, useAsFeature = true, useAsCohort = useAsCohort, enabled = enabled,
    )

    /** Inserts a profile_attributes row carrying pre-computed `signals` (the JSONB the profile_cohort view reads). */
    private suspend fun insertAttribute(profile: UUID, typeId: String, priority: Int, signalsJson: String) {
        connection().useStatement(
            "insert into public.profile_attributes (profile, type_id, priority, signals) " +
                "values ('$profile', '$typeId', $priority, '$signalsJson'::jsonb)",
        ) { it.execute() }
    }

    private fun cohortKey(key: String, value: String): String =
        "[${JsonPrimitive(key)}, ${JsonPrimitive(value)}]"

    @Test
    fun `add then read round-trips all fields including enums`() {
        var saved: PersonalizationSignalDefinition? = null
        var byId: PersonalizationSignalDefinition? = null
        var byKey: PersonalizationSignalDefinition? = null
        withDb {
            saved = repo.add(def(valueType = PersonalizationSignalValueType.MULTI_CATEGORICAL))
            byId = repo.getById(saved.id)
            byKey = repo.getByKey("age_band")
        }
        val savedSignal = assertNotNull(saved)
        assertEquals("age_band", savedSignal.key)
        val signalById = assertNotNull(byId)
        assertEquals(PersonalizationSignalSourceType.ATTRIBUTE, signalById.sourceType)
        assertEquals(PersonalizationSignalValueType.MULTI_CATEGORICAL, signalById.valueType)
        assertEquals("bosca.profiles.age", signalById.sourceId)
        assertEquals(savedSignal.id, assertNotNull(byKey).id)
    }

    @Test
    fun `update changes mutable fields`() {
        var updated: PersonalizationSignalDefinition? = null
        withDb {
            val saved = repo.add(def())
            updated = repo.update(
                saved.copy(
                    key = "age_bucket", priority = 9, enabled = false,
                    valueType = PersonalizationSignalValueType.NUMERIC,
                ),
            )
        }
        val updatedSignal = assertNotNull(updated)
        assertEquals("age_bucket", updatedSignal.key)
        assertEquals(9, updatedSignal.priority)
        assertEquals(false, updatedSignal.enabled)
        assertEquals(PersonalizationSignalValueType.NUMERIC, updatedSignal.valueType)
    }

    @Test
    fun `getEnabled excludes disabled and orders by priority desc`() {
        var enabled: List<PersonalizationSignalDefinition> = emptyList()
        withDb {
            repo.add(def(key = "low", priority = 1))
            repo.add(def(key = "high", priority = 5))
            repo.add(def(key = "off", priority = 9, enabled = false))
            enabled = repo.getEnabled()
        }
        assertEquals(listOf("high", "low"), enabled.map { it.key })
    }

    @Test
    fun `getEnabledBySource filters by source type and id`() {
        var bySource: List<PersonalizationSignalDefinition> = emptyList()
        withDb {
            repo.add(def(key = "a", sourceId = "bosca.profiles.age"))
            repo.add(def(key = "g", sourceId = "bosca.profiles.gender"))
            repo.add(def(key = "seg", sourceType = PersonalizationSignalSourceType.SEGMENT, sourceId = "seg-1"))
            bySource = repo.getEnabledBySource(PersonalizationSignalSourceType.ATTRIBUTE, "bosca.profiles.age")
        }
        assertEquals(listOf("a"), bySource.map { it.key })
    }

    @Test
    fun `getAll returns every definition`() {
        var all: List<PersonalizationSignalDefinition> = emptyList()
        withDb {
            repo.add(def(key = "a"))
            repo.add(def(key = "b"))
            all = repo.getAll(0, 10)
        }
        assertEquals(2, all.size)
    }

    @Test
    fun `deleteById removes the row`() {
        var existed: PersonalizationSignalDefinition? = null
        var afterDelete: PersonalizationSignalDefinition? = null
        withDb {
            val saved = repo.add(def())
            existed = repo.getById(saved.id)
            repo.deleteById(saved.id)
            afterDelete = repo.getById(saved.id)
        }
        assertNotNull(existed)
        assertNull(afterDelete)
    }

    // ── profile_cohort membership view / ProfileCohortRepository ─────────────────────────

    @Test
    fun `profile_cohort exposes every distinct useAsCohort signal value as a membership`() {
        val profile = UUID.random()
        var cohortKeys: List<String> = emptyList()
        withDb {
            // Two useAsCohort signals + a feature-only signal that must NOT become a membership.
            repo.add(def(key = "age_band", sourceId = "bosca.profiles.age", useAsCohort = true))
            repo.add(def(key = "gender", sourceId = "bosca.profiles.gender", useAsCohort = true))
            repo.add(def(key = "affinity", sourceId = "bosca.profiles.affinity", useAsCohort = false))
            insertAttribute(
                profile, "bosca.profiles.age", 0,
                """[{"key":"age_band","value":"25-34"},{"key":"affinity","value":"sports"}]""",
            )
            insertAttribute(profile, "bosca.profiles.gender", 0, """[{"key":"gender","value":"female"}]""")
            cohortKeys = cohortRepo.getCohortKeys(profile)
        }
        assertEquals(listOf(cohortKey("age_band", "25-34"), cohortKey("gender", "female")), cohortKeys)
    }

    @Test
    fun `profile with no useAsCohort signals has no cohort memberships`() {
        val profile = UUID.random()
        var cohortKeys: List<String> = listOf("sentinel")
        withDb {
            repo.add(def(key = "affinity", useAsCohort = false))
            insertAttribute(profile, "bosca.profiles.age", 0, """[{"key":"affinity","value":"sports"}]""")
            cohortKeys = cohortRepo.getCohortKeys(profile)
        }
        assertTrue(cohortKeys.isEmpty())
    }

    @Test
    fun `profile_cohort preserves multiple values for one signal key and removes exact duplicates`() {
        val profile = UUID.random()
        var cohortKeys: List<String> = emptyList()
        withDb {
            repo.add(def(key = "interest_category", sourceId = "bosca.recommendations.learned_interest", useAsCohort = true))
            insertAttribute(profile, "bosca.recommendations.learned_interest", 0, """[{"key":"interest_category","value":"hiking"}]""")
            insertAttribute(profile, "bosca.recommendations.learned_interest", 0, """[{"key":"interest_category","value":"photography"}]""")
            insertAttribute(profile, "bosca.recommendations.learned_interest", 9, """[{"key":"interest_category","value":"hiking"}]""")
            cohortKeys = cohortRepo.getCohortKeys(profile)
        }
        assertEquals(
            listOf(
                cohortKey("interest_category", "hiking"),
                cohortKey("interest_category", "photography"),
            ),
            cohortKeys,
        )
    }

    @Test
    fun `profile_cohort identity cannot collide when signal keys and values contain separators`() {
        val profile = UUID.random()
        var cohortKeys: List<String> = emptyList()
        withDb {
            repo.add(def(key = "interest", sourceId = "source-a", useAsCohort = true))
            repo.add(def(key = "interest=category", sourceId = "source-b", useAsCohort = true))
            insertAttribute(profile, "source-a", 0, """[{"key":"interest","value":"category=hiking"}]""")
            insertAttribute(profile, "source-b", 0, """[{"key":"interest=category","value":"hiking"}]""")
            cohortKeys = cohortRepo.getCohortKeys(profile)
        }
        assertEquals(
            listOf(
                cohortKey("interest", "category=hiking"),
                cohortKey("interest=category", "hiking"),
            ),
            cohortKeys,
        )
    }

    @Test
    fun `profile_cohort defensively caps values and ignores overlong labels`() {
        val profile = UUID.random()
        var cohortKeys: List<String> = emptyList()
        withDb {
            repo.add(def(key = "interest_category", sourceId = "learned", useAsCohort = true))
            repeat(MAX_MATERIALIZED_COHORT_VALUES + 1) { value ->
                insertAttribute(
                    profile,
                    "learned",
                    value,
                    """[{"key":"interest_category","value":"interest-$value"}]""",
                )
            }
            insertAttribute(
                profile,
                "learned",
                Int.MAX_VALUE,
                """[{"key":"interest_category","value":"${"x".repeat(MAX_MATERIALIZED_COHORT_VALUE_LENGTH + 1)}"}]""",
            )
            cohortKeys = cohortRepo.getCohortKeys(profile)
        }
        assertEquals(MAX_MATERIALIZED_COHORT_VALUES, cohortKeys.size)
        assertTrue(cohortKey("interest_category", "interest-64") in cohortKeys)
        assertTrue(cohortKey("interest_category", "interest-0") !in cohortKeys)
    }
}
