package bosca.workops.controller

import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.project.Program
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseStoreCrashFeedback
import bosca.workops.model.release.ReleaseStoreObservation
import bosca.workops.model.release.ReleaseStoreState
import bosca.workops.model.release.ReleaseStoreTelemetry
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ReleaseService
import bosca.workops.service.StoreTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreTelemetryControllerTest {
    private val releases = mockk<ReleaseService>()
    private val programs = mockk<ProgramService>()
    private val permissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val telemetry = mockk<StoreTelemetryService>()
    private val authentication = mockk<AuthenticationContext>()
    private val query = StoreTelemetryQueryController(releases, programs, permissions, telemetry)

    @Test
    fun `query verifies program view and returns the release projection`() = runTest {
        val releaseId = UUID.random()
        val programId = UUID.random()
        val release = Release(id = releaseId, programId = programId, name = "R")
        val program = Program(
            id = programId,
            portfolioId = UUID.random(),
            key = "P",
            name = "Program",
            ownerProfileId = UUID.random(),
        )
        val expected = sample()
        coEvery { releases.getById(releaseId) } returns release
        coEvery { programs.getById(programId) } returns program
        coEvery { telemetry.forRelease(releaseId) } returns expected

        assertEquals(expected, query.releaseStoreTelemetry(authentication, releaseId))

        coVerify { permissions.verifyAllowed(authentication, program, bosca.security.model.PermissionAction.VIEW) }
    }

    @Test
    fun `query fails loudly when release or program is missing`() = runTest {
        val releaseId = UUID.random()
        coEvery { releases.getById(releaseId) } returns null
        assertFailsWith<IllegalStateException> { query.releaseStoreTelemetry(authentication, releaseId) }

        val programId = UUID.random()
        coEvery { releases.getById(releaseId) } returns Release(id = releaseId, programId = programId, name = "R")
        coEvery { programs.getById(programId) } returns null
        assertFailsWith<IllegalStateException> { query.releaseStoreTelemetry(authentication, releaseId) }
    }

    @Test
    fun `type controllers project every schema field`() {
        val value = sample()
        val aggregate = ReleaseStoreTelemetryTypeController()
        assertEquals(value.states, aggregate.states(value))
        assertEquals(value.observations, aggregate.observations(value))

        val state = value.states.single()
        val states = ReleaseStoreStateTypeController()
        assertEquals(state.projectId, states.projectId(state))
        assertEquals(state.versionId, states.versionId(state))
        assertEquals(state.environmentId, states.environmentId(state))
        assertEquals(state.environmentKey, states.environmentKey(state))
        assertEquals(state.store, states.store(state))
        assertEquals(state.applicationId, states.applicationId(state))
        assertEquals(state.appVersion, states.appVersion(state))
        assertEquals(state.buildNumber, states.buildNumber(state))
        assertEquals(state.track, states.track(state))
        assertEquals(state.rolloutPercentage, states.rolloutPercentage(state))
        assertEquals(state.releaseState, states.releaseState(state))
        assertEquals(state.reviewState, states.reviewState(state))
        assertEquals(state.betaReviewState, states.betaReviewState(state))
        assertEquals(state.buildProcessingState, states.buildProcessingState(state))
        assertEquals(state.phasedReleaseState, states.phasedReleaseState(state))
        assertEquals(state.testFlightGroups, states.testFlightGroups(state))
        assertEquals(state.testFlightCrashFeedback, states.testFlightCrashFeedback(state))

        val feedback = state.testFlightCrashFeedback.single()
        val feedbackFields = ReleaseStoreCrashFeedbackTypeController()
        assertEquals(feedback.id, feedbackFields.id(feedback))
        assertEquals(feedback.comment, feedbackFields.comment(feedback))
        assertEquals(feedback.email, feedbackFields.email(feedback))
        assertEquals(feedback.deviceModel, feedbackFields.deviceModel(feedback))
        assertEquals(feedback.osVersion, feedbackFields.osVersion(feedback))
        assertEquals(feedback.createdAt, feedbackFields.createdAt(feedback))

        val observation = value.observations.single()
        val observations = ReleaseStoreObservationTypeController()
        assertEquals(observation.store, observations.store(observation))
        assertEquals(observation.applicationId, observations.applicationId(observation))
        assertEquals(observation.appVersion, observations.appVersion(observation))
        assertEquals(observation.telemetryType, observations.telemetryType(observation))
        assertEquals(observation.observedAt, observations.observedAt(observation))
        assertEquals(observation.payload, observations.payload(observation))
    }

    private fun sample(): ReleaseStoreTelemetry {
        val state = ReleaseStoreState(
            projectId = UUID.random(),
            versionId = UUID.random(),
            environmentId = UUID.random(),
            environmentKey = "production",
            store = "GOOGLE_PLAY",
            applicationId = "io.example.app",
            appVersion = "1.4.0",
            buildNumber = "42",
            track = "production",
            rolloutPercentage = 25.0,
            releaseState = "inProgress",
            reviewState = "WAITING",
            betaReviewState = "APPROVED",
            buildProcessingState = "VALID",
            phasedReleaseState = "ACTIVE",
            testFlightGroups = listOf("Internal"),
            testFlightCrashFeedback = listOf(
                ReleaseStoreCrashFeedback("feedback-1", "Crash", "tester@example.com", "iPhone17,1", "19.0"),
            ),
        )
        val observation = ReleaseStoreObservation(
            store = "GOOGLE_PLAY",
            applicationId = "io.example.app",
            appVersion = "1.4.0",
            telemetryType = "store.vitals",
            observedAt = OffsetDateTime.parse("2026-07-22T12:00:00Z"),
            payload = buildJsonObject { put("crashRate", 0.4) },
        )
        return ReleaseStoreTelemetry(listOf(state), listOf(observation))
    }
}
