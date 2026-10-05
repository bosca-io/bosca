package bosca.experimentation.service

import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.ExclusionLayerInput
import bosca.experimentation.model.Experiment
import bosca.experimentation.repository.ExclusionLayerRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ExclusionLayerServiceImplTest {

    private val exclusionLayerRepository = mockk<ExclusionLayerRepository>()
    private val experimentRepository = mockk<ExperimentRepository>()

    private val service = ExclusionLayerServiceImpl(
        exclusionLayerRepository,
        experimentRepository
    )

    private val layerId = UUID.random()

    private val testLayer = ExclusionLayer(
        id = layerId,
        name = "Test Layer",
        description = "A test exclusion layer"
    )

    @Test
    fun `getAll delegates to repository`() = runTest {
        coEvery { exclusionLayerRepository.getAll() } returns listOf(testLayer)
        val result = service.getAll()
        assertEquals(1, result.size)
        assertEquals(testLayer, result[0])
    }

    @Test
    fun `getAll returns empty list when none exist`() = runTest {
        coEvery { exclusionLayerRepository.getAll() } returns emptyList()
        val result = service.getAll()
        assertEquals(0, result.size)
    }

    @Test
    fun `getById returns layer when found`() = runTest {
        coEvery { exclusionLayerRepository.getById(layerId) } returns testLayer
        val result = service.getById(layerId)
        assertEquals(testLayer, result)
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        coEvery { exclusionLayerRepository.getById(any()) } returns null
        assertNull(service.getById(UUID.random()))
    }

    @Test
    fun `add creates layer with provided name and description`() = runTest {
        val input = ExclusionLayerInput(name = "New Layer", description = "A new layer")
        coEvery { exclusionLayerRepository.add(any()) } returns testLayer.copy(name = "New Layer", description = "A new layer")

        val result = service.add(input)
        assertEquals("New Layer", result.name)
        assertEquals("A new layer", result.description)
    }

    @Test
    fun `add creates layer with empty description when null`() = runTest {
        val input = ExclusionLayerInput(name = "Minimal Layer")
        coEvery { exclusionLayerRepository.add(any()) } answers {
            val layer = firstArg<ExclusionLayer>()
            layer
        }

        val result = service.add(input)
        assertEquals("Minimal Layer", result.name)
        assertEquals("", result.description)
    }

    @Test
    fun `edit updates existing layer`() = runTest {
        val input = ExclusionLayerInput(name = "Updated Layer", description = "Updated description")
        coEvery { exclusionLayerRepository.getById(layerId) } returns testLayer
        coEvery { exclusionLayerRepository.update(any()) } answers { firstArg() }

        val result = service.edit(layerId, input)
        assertEquals("Updated Layer", result.name)
        assertEquals("Updated description", result.description)
    }

    @Test
    fun `edit preserves existing description when input description is null`() = runTest {
        val input = ExclusionLayerInput(name = "Renamed Layer")
        coEvery { exclusionLayerRepository.getById(layerId) } returns testLayer
        coEvery { exclusionLayerRepository.update(any()) } answers { firstArg() }

        val result = service.edit(layerId, input)
        assertEquals("Renamed Layer", result.name)
        assertEquals("A test exclusion layer", result.description)
    }

    @Test
    fun `edit throws when layer not found`() = runTest {
        val input = ExclusionLayerInput(name = "Updated")
        coEvery { exclusionLayerRepository.getById(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(UUID.random(), input)
        }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        coEvery { exclusionLayerRepository.deleteById(layerId) } returns Unit
        service.delete(layerId)
        coVerify { exclusionLayerRepository.deleteById(layerId) }
    }

    @Test
    fun `getExperiments returns experiments for layer`() = runTest {
        val experiment = Experiment(
            id = UUID.random(),
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "Layered Experiment",
            exclusionLayerId = layerId
        )
        coEvery { experimentRepository.getByLayerId(layerId) } returns listOf(experiment)

        val result = service.getExperiments(layerId)
        assertEquals(1, result.size)
        assertEquals(layerId, result[0].exclusionLayerId)
    }

    @Test
    fun `getExperiments returns empty list when no experiments in layer`() = runTest {
        coEvery { experimentRepository.getByLayerId(layerId) } returns emptyList()

        val result = service.getExperiments(layerId)
        assertEquals(0, result.size)
    }
}
