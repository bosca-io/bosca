package bosca.workops.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.serialization.UUID
import bosca.workops.model.spec.*
import bosca.workops.model.requirement.Requirement
import bosca.workops.repository.PlanCreationRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SpecPlanServiceTest {
    private val specs = mockk<SpecService>(relaxed = true)
    private val requirements = mockk<RequirementService>(relaxed = true)
    private val metadata = mockk<MetadataService>(relaxed = true)
    private val receipts = mockk<PlanCreationRepository>()
    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val specId = UUID.random()
    private val metadataId = UUID.random()
    private val requestId = UUID.random()
    private val content = RequirementContentInput("Requirement", "Body", listOf("Pass"))
    private val input = CreateSpecPlanInput(requestId, UUID.random(), "Plan", "Scope", listOf(content))
    private val spec = mockk<Spec> { every { id } returns specId; every { metadataId } returns this@SpecPlanServiceTest.metadataId }
    private var fingerprint = ""
    private var targetId: UUID? = null
    private val service = SpecPlanServiceImpl(specs, requirements, metadata, receipts, Json)

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { receipts.reserve(any(), any(), any()) } answers { fingerprint = arg(2); 1 }
        coEvery { receipts.getForUpdate(requestId, principalId) } answers { PlanCreation(requestId, principalId, fingerprint, targetId) }
        coEvery { receipts.complete(any(), any(), any()) } returns 1
        coEvery { specs.create(any(), principalId, profileId, profileId) } returns spec
        coEvery { specs.getById(specId) } returns spec
        coEvery { metadata.getById(metadataId) } returns mockk<Metadata>(relaxed = true)
    }

    @AfterTest
    fun cleanup() = unmockkStatic("bosca.db.ConnectionManagerKt")

    @Test
    fun `plan validates everything before writes and uses content system with collaboration reset`() = runTest {
        for (invalid in listOf(input.copy(name = ""), input.copy(markdownContent = " "), input.copy(requirements = emptyList()),
            input.copy(requirements = listOf(content.copy(acceptanceCriteria = emptyList()))))) {
            assertFailsWith<IllegalArgumentException> { service.create(invalid, principalId, profileId) }
        }
        coVerify(exactly = 0) { receipts.reserve(any(), any(), any()) }
        assertSame(spec, service.create(input, principalId, profileId))
        coVerify { metadata.setDocument(any(), match { it.title == "Plan" && it.content != null }, CollaborationSyncMode.RESET) }
        coVerify { requirements.createWithContent(bosca.workops.model.requirement.RequirementParent.SPEC, specId, content, principalId, profileId) }
    }

    @Test
    fun `replay returns current entity without writing and rejects changed or deleted targets`() = runTest {
        targetId = specId
        assertSame(spec, service.create(input, principalId, profileId))
        coVerify(exactly = 0) { specs.create(any(), any(), any(), any()) }
        coEvery { receipts.getForUpdate(requestId, principalId) } returns PlanCreation(requestId, principalId, "different fingerprint", specId)
        assertFailsWith<IllegalArgumentException> { service.create(input, principalId, profileId) }
        coEvery { receipts.getForUpdate(requestId, principalId) } answers { PlanCreation(requestId, principalId, fingerprint, specId) }
        coEvery { specs.getById(specId) } returns null
        assertFailsWith<IllegalStateException> { service.create(input, principalId, profileId) }
    }

    @Test
    fun `missing receipts metadata or completion counts fail loudly`() = runTest {
        coEvery { receipts.getForUpdate(requestId, principalId) } returns null
        assertFailsWith<IllegalStateException> { service.create(input, principalId, profileId) }
        coEvery { receipts.getForUpdate(requestId, principalId) } answers { PlanCreation(requestId, principalId, fingerprint, null) }
        coEvery { metadata.getById(metadataId) } returns null
        assertFailsWith<IllegalStateException> { service.create(input, principalId, profileId) }
        coEvery { metadata.getById(metadataId) } returns mockk(relaxed = true)
        coEvery { receipts.complete(any(), any(), any()) } returns 0
        assertFailsWith<IllegalStateException> { service.create(input, principalId, profileId) }
    }

    @Test
    fun `requirement additions validate retry and enforce missing parent and deleted target failures`() = runTest {
        val requirementId = UUID.random()
        val requirement = mockk<Requirement> { every { id } returns requirementId }
        coEvery { requirements.createWithContent(any(), any(), any(), any(), any()) } returns requirement
        assertFailsWith<IllegalArgumentException> { service.addRequirement(specId, requestId, content.copy(name = ""), principalId, profileId) }
        assertSame(requirement, service.addRequirement(specId, requestId, content, principalId, profileId))
        targetId = requirementId
        coEvery { requirements.getById(requirementId) } returns requirement
        assertSame(requirement, service.addRequirement(specId, requestId, content, principalId, profileId))
        coEvery { requirements.getById(requirementId) } returns null
        assertFailsWith<IllegalStateException> { service.addRequirement(specId, requestId, content, principalId, profileId) }
        targetId = null
        coEvery { specs.getById(specId) } returns null
        assertFailsWith<IllegalStateException> { service.addRequirement(specId, requestId, content, principalId, profileId) }
        coEvery { specs.getById(specId) } returns spec
        coEvery { receipts.complete(any(), any(), any()) } returns 0
        assertFailsWith<IllegalStateException> { service.addRequirement(specId, requestId, content, principalId, profileId) }
    }
}
