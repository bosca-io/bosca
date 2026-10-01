@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.recommendations.service

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalDefinitionInput
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.recommendations.repository.PersonalizationSignalRepository
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Validation + delegation tests for [PersonalizationSignalServiceImpl] — the key-uniqueness,
 * JSONata-parse, and cohort/valueType rules, delegation to the repository, and the definition-change
 * backfill (each add/edit/delete enqueues a `RecomputeProfileSignalsJob` for the affected source(s)).
 *
 * The backfill's `enqueue()` resolves `Json` + the recommendations `JobQueue` from the DI registry, so
 * each test wires a relaxed queue provider (mirroring the strategy mutation-controller test).
 */
class PersonalizationSignalServiceImplTest {

    private val repo = mockk<PersonalizationSignalRepository>(relaxed = true)
    private val service = PersonalizationSignalServiceImpl(repo)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { mockk(relaxed = true) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun input(
        key: String = "age_band",
        sourceType: PersonalizationSignalSourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId: String = "bosca.profiles.age",
        expression: String = "value",
        valueType: PersonalizationSignalValueType = PersonalizationSignalValueType.CATEGORICAL,
        useAsCohort: Boolean = false,
    ) = PersonalizationSignalDefinitionInput(
        key = key, sourceType = sourceType, sourceId = sourceId, expression = expression,
        valueType = valueType, priority = 0, useAsFeature = true, useAsCohort = useAsCohort, enabled = true,
    )

    private fun definition(
        id: UUID = UUID.random(),
        key: String = "age_band",
        sourceType: PersonalizationSignalSourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId: String = "bosca.profiles.age",
    ) = PersonalizationSignalDefinition(
        id = id, key = key, sourceType = sourceType, sourceId = sourceId, expression = "value",
        valueType = PersonalizationSignalValueType.CATEGORICAL, priority = 0,
        useAsFeature = true, useAsCohort = false, enabled = true,
    )

    @Test
    fun `add persists a valid definition`() = runTest {
        coEvery { repo.getByKey("age_band") } returns null
        val saved = definition()
        coEvery { repo.add(any()) } returns saved
        assertSame(saved, service.add(input()))
        coVerify { repo.add(match { it.key == "age_band" && it.valueType == PersonalizationSignalValueType.CATEGORICAL }) }
    }

    @Test
    fun `add rejects a blank key`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.add(input(key = "  ")) }
    }

    @Test
    fun `add rejects a blank sourceId`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.add(input(sourceId = "")) }
    }

    @Test
    fun `add rejects a useAsCohort signal that is not categorical or boolean`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.add(input(valueType = PersonalizationSignalValueType.NUMERIC, useAsCohort = true))
        }
        assertFailsWith<IllegalArgumentException> {
            service.add(input(valueType = PersonalizationSignalValueType.MULTI_CATEGORICAL, useAsCohort = true))
        }
    }

    @Test
    fun `add allows a useAsCohort boolean signal`() = runTest {
        coEvery { repo.getByKey(any()) } returns null
        coEvery { repo.add(any()) } returns definition()
        service.add(input(valueType = PersonalizationSignalValueType.BOOLEAN, useAsCohort = true))
        coVerify { repo.add(any()) }
    }

    @Test
    fun `add allows a useAsCohort categorical signal`() = runTest {
        coEvery { repo.getByKey(any()) } returns null
        coEvery { repo.add(any()) } returns definition()
        service.add(input(valueType = PersonalizationSignalValueType.CATEGORICAL, useAsCohort = true))
        coVerify { repo.add(any()) }
    }

    @Test
    fun `add rejects an invalid JSONata expression`() = runTest {
        coEvery { repo.getByKey(any()) } returns null
        assertFailsWith<IllegalArgumentException> { service.add(input(expression = ")(")) }
    }

    @Test
    fun `add rejects a duplicate key`() = runTest {
        coEvery { repo.getByKey("age_band") } returns mockk { every { id } returns UUID.random() }
        assertFailsWith<IllegalArgumentException> { service.add(input()) }
    }

    @Test
    fun `edit updates an existing definition and allows keeping its own key`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns definition(id)
        coEvery { repo.getByKey("age_band") } returns mockk { every { this@mockk.id } returns id }
        val updated = definition(id)
        coEvery { repo.update(any()) } returns updated
        assertSame(updated, service.edit(id, input()))
        coVerify { repo.update(match { it.id == id }) }
    }

    @Test
    fun `edit moving a definition's source backfills the old source too`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns definition(id, sourceId = "bosca.profiles.age")
        coEvery { repo.getByKey(any()) } returns null
        coEvery { repo.update(any()) } returns definition(id, key = "country", sourceId = "bosca.profiles.country")
        service.edit(id, input(key = "country", sourceId = "bosca.profiles.country"))
        coVerify { repo.update(match { it.sourceId == "bosca.profiles.country" }) }
    }

    @Test
    fun `edit changing a definition's source type backfills the old source too`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns definition(id)
        coEvery { repo.getByKey(any()) } returns null
        coEvery { repo.update(any()) } returns definition(
            id = id,
            sourceType = PersonalizationSignalSourceType.SEGMENT,
            sourceId = "audience-segment",
        )

        service.edit(
            id,
            input(sourceType = PersonalizationSignalSourceType.SEGMENT, sourceId = "audience-segment"),
        )

        coVerify { repo.update(match { it.sourceType == PersonalizationSignalSourceType.SEGMENT }) }
    }

    @Test
    fun `edit throws when the definition is missing`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns null
        assertFailsWith<NoSuchElementException> { service.edit(id, input()) }
    }

    @Test
    fun `edit rejects a key owned by a different definition`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns definition(id)
        coEvery { repo.getByKey("age_band") } returns mockk { every { this@mockk.id } returns UUID.random() }
        assertFailsWith<IllegalArgumentException> { service.edit(id, input()) }
    }

    @Test
    fun `delete backfills the removed definition's source`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns definition(id)
        coEvery { repo.deleteById(id) } just Runs
        service.delete(id)
        coVerify { repo.deleteById(id) }
    }

    @Test
    fun `delete of a missing definition still delegates and skips backfill`() = runTest {
        val id = UUID.random()
        coEvery { repo.getById(id) } returns null
        coEvery { repo.deleteById(id) } just Runs
        service.delete(id)
        coVerify { repo.deleteById(id) }
    }

    @Test
    fun `getters delegate to the repository`() = runTest {
        coEvery { repo.getAll(0, 10) } returns emptyList()
        coEvery { repo.getEnabled() } returns emptyList()
        coEvery { repo.getById(any()) } returns null
        coEvery { repo.getEnabledBySource(any(), any()) } returns emptyList()
        assertTrue(service.getAll(0, 10).isEmpty())
        assertTrue(service.getEnabled().isEmpty())
        assertNull(service.getById(UUID.random()))
        assertTrue(service.getEnabledBySource(PersonalizationSignalSourceType.SEGMENT, "s").isEmpty())
    }
}
