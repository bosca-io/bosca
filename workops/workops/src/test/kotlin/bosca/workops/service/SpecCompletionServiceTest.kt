package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.guidance.*
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.spec.*
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.SpecTaskGenerationRepository
import bosca.workops.repository.StatusRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SpecCompletionServiceTest {
    private val guidance = mockk<WorkOpsGuidanceService>()
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val tasks = mockk<TaskService>()
    private val statuses = mockk<StatusRepository>()
    private val comments = mockk<SpecCommentService>()
    private val contexts = mockk<SpecContextService>()
    private val generations = mockk<SpecTaskGenerationRepository>()
    private val specs = mockk<bosca.workops.repository.SpecRepository>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; serializersModule = bosca.documents.DocumentSerializers }
    private val service = SpecCompletionServiceImpl(guidance, metadata, documents, tasks, statuses, comments, contexts, generations, json, mockk(relaxed = true), specs)
    private val specId = UUID.random()
    private val specMetadata = UUID.random()
    private val requirementMetadata = UUID.random()
    private val taskId = UUID.random()
    private val requirementId = UUID.random()
    private val done = Status(UUID.random(), "Done", category = StatusCategory.DONE, colorHex = "#00aa00")
    private val spec = Spec(specId, "SPEC-1", specMetadata, statusId = done.id, workflowId = UUID.random(), ownerProfileId = UUID.random(), createdByPrincipalId = UUID.random(), modifiedByPrincipalId = UUID.random())
    private val requirement = mockk<Requirement>()
    private val task = mockk<Task>()
    private val bodies = mutableMapOf<UUID, Document>()
    private val records = mutableListOf<SpecComment>()
    private lateinit var review: SpecReviewRecord
    private lateinit var validation: ValidationRecord
    private var policy = WorkOpsGuidancePolicy()

    @BeforeTest fun setup() {
        coEvery { specs.hasIncompleteChildren(any()) } returns false
        bodies[specMetadata] = Document(specMetadata, 1, title = "Plan", content = MarkdownConverter.fromMarkdown("# Scope"))
        bodies[requirementMetadata] = Document(requirementMetadata, 1, title = "Deliverable", content = MarkdownConverter.fromMarkdown("Body\n\n## Acceptance criteria\n\n- Behavior works"))
        every { requirement.id } returns requirementId
        every { requirement.key } returns "REQ-1"
        every { requirement.metadataId } returns requirementMetadata
        every { requirement.taskId } returns taskId
        every { requirement.statusId } returns done.id
        every { task.id } returns taskId
        every { task.statusId } returns done.id
        every { task.version } returns 1L
        coEvery { guidance.effective(any()) } answers { WorkOpsGuidance("guide-1", policy, false, emptyList()) }
        coEvery { metadata.getById(any<UUID>()) } answers { mockk<Metadata> { every { id } returns firstArg<UUID>(); every { version } returns 1; every { deleted } returns false } }
        coEvery { documents.getDocument(any(), any()) } answers { bodies[firstArg()] }
        coEvery { tasks.getById(taskId) } returns task
        coEvery { statuses.getById(done.id) } returns done
        coEvery { comments.listManager(any(), any(), any()) } answers { records.drop(secondArg<Long>().toInt()).take(thirdArg<Long>().toInt()) }
        coEvery { contexts.listBySpec(specId) } returns emptyList()
        coEvery { generations.listBySpec(any(), any(), any()) } returns emptyList()
        fun body(id: UUID) = bodies.getValue(id).let { ReviewDocument(it.title, json.encodeToJsonElement(Content.serializer(), requireNotNull(it.content))) }
        review = SpecReviewRecord(specId = specId.toString(), note = "Reviewed interfaces and representative callers", snapshot = SpecReviewSnapshot(null, body(specMetadata), listOf(ReviewRequirement(requirementId.toString(), "REQ-1", body(requirementMetadata), listOf("Behavior works"))), null, null, null, null, emptyList(), "guide-1"), interfaces = listOf(ReviewedInterface("Service.kt", "interface Service")))
        val tdd = TddEvidence(TddRun("test red", ValidationOutcome.FAILED, "Expected assertion failed"), TddRun("test green", ValidationOutcome.PASSED, "Assertions passed"), TddRun("test refactor", ValidationOutcome.PASSED, "Assertions passed"))
        validation = ValidationRecord(requirementId = requirementId.toString(), taskId = taskId.toString(), taskVersion = 1, specContentHash = body(specMetadata).fingerprint(), requirementContentHash = body(requirementMetadata).fingerprint(), checks = listOf(ValidationCheck("Behavior works", ValidationOutcome.PASSED, "Regression passed", tdd)), interfaceReviewId = 1L, testQuality = TestQualityEvidence("Behavior and failure assertions reviewed", listOf(CoverageEvidence("Production service", "coverage command", "coverage.xml", 100, 0, 100, 0, "No uncovered paths"))), guidanceRevision = "guide-1")
        publish()
    }
    private fun publish() {
        records.clear()
        records += comment(1, "## WorkOps review\n\n```json\n" + json.encodeToString(SpecReviewRecord.serializer(), review) + "\n```")
        records += comment(2, "## WorkOps validation\n\n```json\n" + json.encodeToString(ValidationRecord.serializer(), validation) + "\n```")
    }
    private fun comment(id: Long, body: String) = SpecComment(id, specId = specId, profileId = UUID.random(), created = OffsetDateTime.now(), modified = OffsetDateTime.now(), status = CommentStatus.APPROVED, content = body)

    @Test fun `child read failure and cancellation propagate`() = runTest {
        for (failure in listOf(IllegalStateException("child lookup failed"), kotlinx.coroutines.CancellationException("cancelled"))) {
            coEvery { specs.hasIncompleteChildren(spec.id) } throws failure
            assertSame(failure, assertFailsWith<Exception> { service.issues(spec, listOf(requirement)) })
        }
    }

    @Test fun `active child states override both falsely complete and falsely incomplete rollups`() = runTest {
        coEvery { specs.hasIncompleteChildren(spec.id) } returns true
        assertEquals(listOf("Child specifications are incomplete"), service.issues(spec.copy(childCount = 1, childDoneCount = 1), listOf(requirement)))
        coEvery { specs.hasIncompleteChildren(spec.id) } returns false
        assertEquals(emptyList(), service.issues(spec.copy(childCount = 3, childDoneCount = 0), listOf(requirement)))
    }

    @Test fun `deleted spec and requirement documents cannot supply completion evidence`() = runTest {
        for (id in listOf(specMetadata, requirementMetadata)) {
            coEvery { metadata.getById(id) } returns mockk { every { version } returns 1; every { deleted } returns true }
            assertTrue(service.issues(spec, listOf(requirement)).any { "document is missing" in it.lowercase() })
            coEvery { metadata.getById(id) } returns mockk { every { version } returns 1; every { deleted } returns false }
            assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        }
    }

    @Test fun `completion uses the document at the version returned by the metadata service`() = runTest {
        coEvery { metadata.getById(specMetadata) } returns mockk { every { version } returns 2; every { deleted } returns false }
        coEvery { documents.getDocument(specMetadata, 2) } returns Document(specMetadata, 2, title = "Changed current plan", content = bodies.getValue(specMetadata).content)
        assertTrue(service.issues(spec, listOf(requirement)).any { "stale" in it })
    }

    @Test fun `completion respects missing documents returned by the document service`() = runTest {
        for (id in listOf(specMetadata, requirementMetadata)) {
            coEvery { documents.getDocument(id, 1) } returns null
            assertTrue(service.issues(spec, listOf(requirement)).any { "document is missing" in it.lowercase() })
            coEvery { documents.getDocument(id, 1) } answers { bodies[id] }
            assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        }
    }
    @Test fun `completion requires current reviewed interfaces passing TDD and measured coverage`() = runTest {
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        for (bad in listOf(validation.copy(guidanceRevision = "old"), validation.copy(taskVersion = 0), validation.copy(specContentHash = "old"), validation.copy(requirementContentHash = "old"), validation.copy(interfaceReviewId = null), validation.copy(checks = emptyList()), validation.copy(testQuality = null), validation.copy(checks = listOf(validation.checks.single().copy(tdd = null))), validation.copy(checks = listOf(validation.checks.single().copy(outcome = ValidationOutcome.FAILED))))) {
            validation = bad; publish()
            assertTrue(service.issues(spec, listOf(requirement)).isNotEmpty(), bad.toString())
        }
    }
    @Test fun `missing blocked deleted malformed and stale reviews cannot establish completion`() = runTest {
        for (body in listOf("Ordinary comment", "## WorkOps review\n\n```json\ninvalid\n```")) {
            records[0] = comment(1, body)
            assertTrue(service.issues(spec, listOf(requirement)).isNotEmpty())
        }
        review = review.copy(snapshot = review.snapshot.copy(guidanceRevision = "old")); publish()
        assertTrue(service.issues(spec, listOf(requirement)).isNotEmpty())
        assertTrue(service.issues(spec.copy(childCount = 1), listOf(requirement)).isNotEmpty())
        assertTrue(service.issues(spec, emptyList()).isNotEmpty())
    }
    @Test fun `each failed criterion TDD and coverage contract reports its specific gap`() = runTest {
        val original = validation
        val check = original.checks.single()
        val cycle = assertNotNull(check.tdd)
        val quality = assertNotNull(original.testQuality)
        val coverage = quality.coverage.single()
        for ((bad, expected) in listOf(
            original.copy(taskId = "other") to "stale",
            original.copy(checks = listOf(check.copy(evidence = ""))) to "passing evidence",
            original.copy(checks = listOf(check.copy(criterion = "Unknown"))) to "unknown checks",
            original.copy(checks = listOf(check.copy(outcome = ValidationOutcome.NOT_RUN))) to "unrun",
            original.copy(checks = listOf(check.copy(tdd = cycle.copy(red = cycle.red.copy(outcome = ValidationOutcome.PASSED))))) to "RED, GREEN, REFACTOR",
            original.copy(testQuality = quality.copy(review = "")) to "quality review",
            original.copy(testQuality = quality.copy(coverage = emptyList())) to "coverage is missing",
            original.copy(testQuality = quality.copy(coverage = listOf(coverage.copy(branchCovered = 96, branchMissed = 4)))) to "below",
        )) {
            validation = bad; publish()
            assertTrue(service.issues(spec, listOf(requirement)).any { expected in it }, expected)
        }
    }
    @Test fun `policy disabled checks remain independent from required criterion results`() = runTest {
        policy = WorkOpsGuidancePolicy(requireInterfaceReview = false, requireTdd = false, requireTestQuality = false)
        validation = validation.copy(interfaceReviewId = null, testQuality = null, checks = listOf(validation.checks.single().copy(tdd = null)))
        publish(); records.removeAt(0)
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        validation = validation.copy(checks = listOf(validation.checks.single().copy(evidence = ""))); publish()
        assertTrue(service.issues(spec, listOf(requirement)).any { "passing evidence" in it })
    }
    @Test fun `review validity handles edited deleted blocked and foreign comments and every page`() = runTest {
        val original = review
        for (bad in listOf(original.copy(schema = "unknown"), original.copy(specId = "other"), original.copy(note = ""), original.copy(interfaces = emptyList()), original.copy(interfaces = listOf(ReviewedInterface("", "declaration"))), original.copy(interfaces = listOf(ReviewedInterface("file", ""))))) {
            review = bad; publish()
            assertTrue(service.issues(spec, listOf(requirement)).any { "interface review" in it })
        }
        review = original; publish()
        records[0] = records[0].copy(deleted = true)
        assertTrue(service.issues(spec, listOf(requirement)).any { "interface review" in it })
        publish(); records[0] = records[0].copy(status = CommentStatus.BLOCKED)
        assertTrue(service.issues(spec, listOf(requirement)).any { "interface review" in it })
        publish(); records[1] = records[1].copy(content = "## WorkOps validation\n\n```json\n{}\n```")
        assertTrue(service.issues(spec, listOf(requirement)).any { "Validation evidence is missing" in it })
        publish(); records[1] = records[1].copy(content = "## WorkOps validation\n\n```json\n" + json.encodeToString(ValidationRecord.serializer(), validation.copy(schema = "unknown")) + "\n```")
        assertTrue(service.issues(spec, listOf(requirement)).any { "Validation evidence is missing" in it })
        publish(); repeat(105) { records += comment(it + 3L, "Ordinary comment") }
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        records += comment(200, "## WorkOps review\n\n```json\n" + json.encodeToString(SpecReviewRecord.serializer(), original) + "\n```")
        assertTrue(service.issues(spec, listOf(requirement)).any { "current interface review" in it })
    }
    @Test fun `missing documents criteria tasks and unfinished statuses prevent completion`() = runTest {
        coEvery { metadata.getById(specMetadata) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "Specification document is missing" in it })
        coEvery { metadata.getById(specMetadata) } returns mockk { every { version } returns 1; every { deleted } returns false }
        val specBody = bodies.remove(specMetadata)
        assertTrue(service.issues(spec, listOf(requirement)).any { "Specification document is missing" in it })
        bodies[specMetadata] = assertNotNull(specBody)
        bodies[requirementMetadata] = Document(requirementMetadata, 1, title = "Deliverable", content = null)
        assertTrue(service.issues(spec, listOf(requirement)).any { "Requirement document is missing" in it })
        bodies[requirementMetadata] = Document(requirementMetadata, 1, title = "Deliverable", content = MarkdownConverter.fromMarkdown("No criteria"))
        assertTrue(service.issues(spec, listOf(requirement)).any { "Acceptance criteria are missing" in it })
        every { requirement.taskId } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "linked task must both be done" in it })
        every { requirement.taskId } returns taskId
        coEvery { tasks.getById(taskId) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "linked task must both be done" in it })
        coEvery { tasks.getById(taskId) } returns task
        coEvery { statuses.getById(done.id) } returns done.copy(category = StatusCategory.IN_PROGRESS)
        assertTrue(service.issues(spec, listOf(requirement)).any { "linked task must both be done" in it })
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        coEvery { documents.getDocument(any(), any()) } throws cancellation
        assertSame(cancellation, assertFailsWith<kotlinx.coroutines.CancellationException> { service.issues(spec, listOf(requirement)) })
    }
    @Test fun `generated tasks are assessed across every generation page including cancellation and missing tasks`() = runTest {
        val generated = UUID.random()
        val generation = SpecTaskGeneration(specId = specId, metadataVersion = 1, source = GenerationSource.MANUAL, generatedTaskIds = listOf(generated), createdByPrincipalId = UUID.random())
        coEvery { generations.listBySpec(any(), 0, 100) } returns List(100) { generation }
        coEvery { generations.listBySpec(any(), 100, 100) } returns listOf(generation)
        coEvery { tasks.getById(generated) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "Generated task" in it })
        coEvery { tasks.getById(generated) } returns task
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        val cancelled = done.copy(id = UUID.random(), category = StatusCategory.CANCELLED)
        val generatedTask = mockk<Task> { every { statusId } returns cancelled.id }
        coEvery { tasks.getById(generated) } returns generatedTask
        coEvery { statuses.getById(cancelled.id) } returns cancelled
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
        coEvery { statuses.getById(cancelled.id) } returns cancelled.copy(category = StatusCategory.TODO)
        assertTrue(service.issues(spec, listOf(requirement)).any { "Generated task" in it })
        coEvery { statuses.getById(cancelled.id) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "Generated task" in it })
    }

    @Test fun `partial metadata missing statuses and unfinished linked tasks fail closed`() = runTest {
        review = review.copy(note = null); publish()
        assertTrue(service.issues(spec, listOf(requirement)).any { "interface review" in it })
        review = review.copy(note = "Reviewed", interfaces = listOf(ReviewedInterface("Service.kt", "interface Service"))); publish()
        coEvery { metadata.getById(requirementMetadata) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "Requirement document is missing" in it })
        coEvery { metadata.getById(requirementMetadata) } returns mockk { every { version } returns 1; every { deleted } returns false }
        val unfinished = UUID.random()
        every { task.statusId } returns unfinished
        coEvery { statuses.getById(unfinished) } returns done.copy(id = unfinished, category = StatusCategory.TODO)
        assertTrue(service.issues(spec, listOf(requirement)).any { "must both be done" in it })
        coEvery { statuses.getById(unfinished) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "must both be done" in it })
        every { task.statusId } returns done.id
        coEvery { statuses.getById(done.id) } returns null
        assertTrue(service.issues(spec, listOf(requirement)).any { "must both be done" in it })
        records[0] = comment(1, "## WorkOps review\n\n```json\n{}")
        assertTrue(service.issues(spec, listOf(requirement)).any { "interface review" in it })
    }

    @Test fun `criteria respect rich text ordered lists subheadings blank items and the section boundary`() = runTest {
        val original = bodies.getValue(requirementMetadata)
        val content = MarkdownConverter.fromMarkdown("# Acceptance criteria\n\n## Other\n\nBefore\n\n## Acceptance criteria\n\nContext\n\n### Examples\n\n1. Behavior works\n2. Behavior works\n\n## Other section\n\n- Excluded")
        val encoded = json.encodeToJsonElement(Content.serializer(), content).toString()
        val tree = kotlinx.serialization.json.Json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }
        val doc = tree.getValue("document") as kotlinx.serialization.json.JsonObject
        val blocks = (doc.getValue("content") as kotlinx.serialization.json.JsonArray).toMutableList()
        val orderedIndex = blocks.indexOfFirst { (it as kotlinx.serialization.json.JsonObject)["type"] == kotlinx.serialization.json.JsonPrimitive("orderedList") }
        val ordered = blocks[orderedIndex] as kotlinx.serialization.json.JsonObject
        val items = (ordered.getValue("content") as kotlinx.serialization.json.JsonArray).toMutableList()
        items += kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"listItem","content":[{"type":"paragraph","content":[]}]}""")
        blocks[orderedIndex] = kotlinx.serialization.json.JsonObject(ordered + ("content" to kotlinx.serialization.json.JsonArray(items)))
        val modified = json.decodeFromJsonElement(Content.serializer(), kotlinx.serialization.json.JsonObject(tree + ("document" to kotlinx.serialization.json.JsonObject(doc + ("content" to kotlinx.serialization.json.JsonArray(blocks))))))
        bodies[requirementMetadata] = Document(requirementMetadata, 1, title = original.title, content = modified)
        val body = ReviewDocument(original.title, json.encodeToJsonElement(Content.serializer(), modified))
        review = review.copy(snapshot = review.snapshot.copy(requirements = listOf(ReviewRequirement(requirementId.toString(), "REQ-1", body, listOf("Behavior works")))))
        validation = validation.copy(requirementContentHash = body.fingerprint()); publish()
        assertTrue(service.issues(spec, listOf(requirement)).isEmpty())
    }
    @Test fun `later unusable review comments do not replace the latest valid interface review`() = runTest {
        for (bad in listOf(review.copy(note = null), review.copy(note = ""), review.copy(interfaces = listOf(ReviewedInterface("", "Contract"))), review.copy(interfaces = listOf(ReviewedInterface("Contract.kt", ""))))) {
            publish()
            records += comment(3, "## WorkOps review\n\n```json\n" + json.encodeToString(SpecReviewRecord.serializer(), bad) + "\n```")
            assertTrue(service.issues(spec, listOf(requirement)).isEmpty(), bad.toString())
        }
    }
    @Test fun `reordering reviewed requirements requires a current interface review`() = runTest {
        val otherId = UUID.random()
        val otherTaskId = UUID.random()
        val other = mockk<Requirement> {
            every { id } returns otherId
            every { key } returns "REQ-2"
            every { metadataId } returns requirementMetadata
            every { taskId } returns otherTaskId
            every { statusId } returns done.id
        }
        coEvery { tasks.getById(otherTaskId) } returns mockk<Task> {
            every { id } returns otherTaskId
            every { version } returns 1
            every { statusId } returns done.id
        }
        review = review.copy(snapshot = review.snapshot.copy(requirements = review.snapshot.requirements + review.snapshot.requirements.single().copy(id = otherId.toString(), key = "REQ-2")))
        publish()
        records += comment(3, "## WorkOps validation\n\n```json\n" + json.encodeToString(ValidationRecord.serializer(), validation.copy(requirementId = otherId.toString(), taskId = otherTaskId.toString())) + "\n```")
        assertTrue(service.issues(spec, listOf(requirement, other)).isEmpty())
        assertTrue(service.issues(spec, listOf(other, requirement)).any { "Interface review is stale" in it })
    }

}
