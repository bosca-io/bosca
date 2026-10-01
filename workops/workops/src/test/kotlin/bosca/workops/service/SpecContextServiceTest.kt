package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import bosca.workops.repository.SpecContextRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpecContextServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    private val contextRepository = mockk<SpecContextRepository>()
    private val specRepository = mockk<SpecRepository>()
    private val specHistoryRepository = mockk<SpecHistoryRepository>(relaxUnitFun = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val service = SpecContextServiceImpl(
        contextRepository = contextRepository,
        specRepository = specRepository,
        specHistoryRepository = specHistoryRepository,
        json = json,
    )

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val specId = UUID.random()

    private val sampleSpec = Spec(
        id = specId, key = "SPEC-1", metadataId = UUID.random(), projectId = UUID.random(),
        statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    @Test
    fun `add creates context and writes history`() = runTest {
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        val repoId = UUID.random()
        val savedContext = SpecContext(
            id = UUID.random(), specId = specId,
            contextType = SpecContextType.GIT_RESOURCE, targetId = repoId.toString(),
            label = "main branch", addedByProfileId = profileId,
        )
        coEvery { contextRepository.add(any(), any(), any(), any(), any(), any()) } returns savedContext
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.add(
            specId = specId,
            input = CreateSpecContextInput(
                contextType = SpecContextType.GIT_RESOURCE,
                targetId = repoId.toString(),
                label = "main branch",
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(SpecContextType.GIT_RESOURCE, result.contextType)
        assertEquals("main branch", result.label)
        coVerify(exactly = 1) { specHistoryRepository.add(eq(specId), any(), any(), any(), any()) }
    }

    @Test
    fun `add throws when spec not found`() = runTest {
        coEvery { specRepository.getActiveById(specId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.add(
                specId = specId,
                input = CreateSpecContextInput(
                    contextType = SpecContextType.METADATA,
                    targetId = UUID.random().toString(),
                ),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
            )
        }
    }

    @Test
    fun `remove deletes context and writes history`() = runTest {
        val contextId = UUID.random()
        coEvery { contextRepository.delete(contextId, specId) } just Runs
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.remove(specId, contextId, principalId, profileId)

        coVerify(exactly = 1) { contextRepository.delete(contextId, specId) }
        coVerify(exactly = 1) { specHistoryRepository.add(eq(specId), any(), any(), any(), any()) }
    }

    @Test
    fun `listBySpec delegates to repository`() = runTest {
        val contexts = listOf(
            SpecContext(id = UUID.random(), specId = specId, contextType = SpecContextType.PROFILE,
                targetId = profileId.toString(), addedByProfileId = profileId),
        )
        coEvery { contextRepository.listBySpec(specId) } returns contexts

        val result = service.listBySpec(specId)
        assertEquals(1, result.size)
        assertEquals(SpecContextType.PROFILE, result.first().contextType)
    }
}
