@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.service

import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentInput
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Unit tests for [RecommendationExperimentServiceImpl] — provisioning the ML-vs-heuristic and
 * model-vs-model A/B tests by orchestrating the flag + experiment services. Pins the idempotency
 * (return the existing experiment untouched), the flag upsert for the model test, and the shared
 * engagement goals.
 */
class RecommendationExperimentServiceImplTest {

    private val flagService = mockk<FeatureFlagService>(relaxed = true)
    private val experimentService = mockk<ExperimentService>(relaxed = true)
    private val service = RecommendationExperimentServiceImpl(flagService, experimentService)

    private fun flag(key: String) = mockk<FeatureFlag> {
        every { id } returns UUID.random()
        every { this@mockk.key } returns key
    }

    private fun experiment() = mockk<Experiment> { every { id } returns UUID.random() }

    // ── engine (ML vs heuristic) ────────────────────────────────────────────────────────────────

    @Test
    fun `provisionEngineExperiment creates the flag, experiment and goals on a fresh install`() = runTest {
        val flag = flag("recommendation-engine")
        val flagInput = slot<FeatureFlagInput>()
        val experimentInput = slot<ExperimentInput>()
        coEvery { flagService.getByKey("recommendation-engine") } returns null
        coEvery { flagService.add(capture(flagInput)) } returns flag
        coEvery { experimentService.getByFlagId(flag.id) } returns emptyList()
        coEvery { experimentService.add(capture(experimentInput)) } returns experiment()

        val result = service.provisionEngineExperiment()

        assertTrue(result.created)
        assertEquals("recommendation-engine", result.flagKey)
        assertEquals("recommendation-engine", flagInput.captured.key)
        assertEquals("heuristic", experimentInput.captured.controlVariationKey)
        coVerify(exactly = 2) { experimentService.addConversionGoal(any(), any()) }
    }

    @Test
    fun `provisionEngineExperiment is idempotent when an experiment already exists`() = runTest {
        val flag = flag("recommendation-engine")
        val existing = experiment()
        coEvery { flagService.getByKey("recommendation-engine") } returns flag
        coEvery { experimentService.getByFlagId(any<UUID>()) } returns listOf(existing)

        val result = service.provisionEngineExperiment()

        assertFalse(result.created)
        coVerify(exactly = 0) { experimentService.add(any()) }
        coVerify(exactly = 0) { flagService.add(any()) }
    }

    // ── model (version vs version) ──────────────────────────────────────────────────────────────

    @Test
    fun `provisionModelExperiment adds the flag and experiment when none exist`() = runTest {
        val flag = flag("recommendation-model")
        val flagInput = slot<FeatureFlagInput>()
        val experimentInput = slot<ExperimentInput>()
        coEvery { flagService.getByKey("recommendation-model") } returns null
        coEvery { flagService.add(capture(flagInput)) } returns flag
        coEvery { experimentService.getByFlagId(flag.id) } returns emptyList()
        coEvery { experimentService.add(capture(experimentInput)) } returns experiment()

        val result = service.provisionModelExperiment(championVersion = 3, challengerVersion = 4)

        assertTrue(result.created)
        // The champion/challenger versions are encoded into the flag's variations.
        assertTrue(flagInput.captured.variations.toString().contains("Champion (v3)"))
        assertTrue(flagInput.captured.variations.toString().contains("Challenger (v4)"))
        assertEquals("champion", experimentInput.captured.controlVariationKey)
        coVerify(exactly = 2) { experimentService.addConversionGoal(any(), any()) }
    }

    @Test
    fun `provisionModelExperiment edits an existing flag to re-point the version pair`() = runTest {
        val existingFlag = flag("recommendation-model")
        coEvery { flagService.getByKey("recommendation-model") } returns existingFlag
        coEvery { flagService.edit(existingFlag.id, any()) } returns existingFlag
        coEvery { experimentService.getByFlagId(existingFlag.id) } returns emptyList()
        coEvery { experimentService.add(any()) } returns experiment()

        val result = service.provisionModelExperiment(championVersion = 5, challengerVersion = 6)

        assertTrue(result.created)
        coVerify { flagService.edit(existingFlag.id, any()) }
        coVerify(exactly = 0) { flagService.add(any()) }
    }

    @Test
    fun `provisionModelExperiment is idempotent when the experiment already exists`() = runTest {
        val flag = flag("recommendation-model")
        coEvery { flagService.getByKey("recommendation-model") } returns flag
        coEvery { experimentService.getByFlagId(any<UUID>()) } returns listOf(experiment())

        val result = service.provisionModelExperiment(championVersion = 1, challengerVersion = 2)

        assertFalse(result.created)
        coVerify(exactly = 0) { experimentService.add(any()) }
    }
}
