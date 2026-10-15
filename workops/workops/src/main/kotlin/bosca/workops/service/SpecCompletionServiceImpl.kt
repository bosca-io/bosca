package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.*
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.spec.*
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.SpecTaskGenerationRepository
import bosca.workops.repository.SpecRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.WorkOpsGuidanceRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@ServiceImplementation
class SpecCompletionServiceImpl(
    private val guidance: WorkOpsGuidanceService,
    private val metadata: MetadataService,
    private val documents: DocumentService,
    private val tasks: TaskService,
    private val statuses: StatusRepository,
    private val comments: SpecCommentService,
    private val contexts: SpecContextService,
    private val generations: SpecTaskGenerationRepository,
    private val json: Json,
    private val revisions: WorkOpsGuidanceRepository,
    private val specs: SpecRepository,
) : SpecCompletionService {
    override suspend fun issues(spec: Spec, requirements: List<Requirement>): List<String> {
        revisions.lock()
        val effective = guidance.effective(spec.projectId)
        val policy = effective.policy
        val issues = mutableListOf<String>()
        if (requirements.isEmpty()) issues.add("Plan has no requirements")
        if (specs.hasIncompleteChildren(spec.id)) issues.add("Child specifications are incomplete")
        val document = document(spec.metadataId) ?: return issues + "Specification document is missing"
        val requirementDocuments = requirements.associate { it.id to document(it.metadataId) }
        val snapshot = SpecReviewSnapshot(spec.projectId?.toString(), document,
            requirements.mapNotNull { requirement -> requirementDocuments[requirement.id]?.let { ReviewRequirement(requirement.id.toString(), requirement.key, it, criteria(it)) } },
            spec.programId?.toString(), spec.parentSpecId?.toString(), spec.gitRepositoryId?.toString(), spec.gitPath,
            contexts.listBySpec(spec.id).map { ReviewContext(it.contextType.name, it.targetId, it.label, it.attributes) }
                .sortedWith(compareBy<ReviewContext> { it.type }.thenBy { it.targetId }.thenBy { it.label }), effective.revision)
        val recorded = allComments(spec.id).filter { !it.deleted && it.status != CommentStatus.BLOCKED }
            .sortedWith(compareBy<SpecComment> { it.created.toInstant() }.thenBy { it.id })
        val review = recorded.mapNotNull { comment -> decode(comment.content, "WorkOps review", SpecReviewRecord.serializer())
            ?.takeIf { it.schema == "bosca.workops.review.v1" && it.specId == spec.id.toString() && it.interfaces.isNotEmpty() && !it.note.isNullOrBlank() && it.interfaces.all { declaration -> declaration.path.isNotBlank() && declaration.declaration.isNotBlank() } }
            ?.let { comment.id to it } }.lastOrNull()
        if (policy.requireInterfaceReview) {
            if (review == null) issues.add("Current interface review is missing")
            else if (review.second.snapshot != snapshot) issues.add("Interface review is stale after plan or guidance changes")
        }
        val validation = recorded.mapNotNull { decode(it.content, "WorkOps validation", ValidationRecord.serializer())
            ?.takeIf { it.schema == "bosca.workops.validation.v1" } }.associateBy { it.requirementId }
        for (requirement in requirements) {
            val body = requirementDocuments[requirement.id]
            val criteria = body?.let(::criteria).orEmpty()
            val record = validation[requirement.id.toString()]
            val task = requirement.taskId?.let { tasks.getById(it) }
            val failures = mutableListOf<String>()
            if (body == null) failures.add("Requirement document is missing")
            if (criteria.isEmpty()) failures.add("Acceptance criteria are missing")
            if (statuses.getById(requirement.statusId)?.category != StatusCategory.DONE || task == null || statuses.getById(task.statusId)?.category != StatusCategory.DONE) failures.add("Requirement and linked task must both be done")
            if (record == null) failures.add("Validation evidence is missing")
            else {
                if (record.taskId != task?.let { it.id.toString() } || record.taskVersion != task.version || record.specContentHash != document.fingerprint() || record.requirementContentHash != body?.fingerprint() || record.guidanceRevision != effective.revision) failures.add("Validation evidence is stale")
                if (policy.requireInterfaceReview && (review == null || record.interfaceReviewId != review.first)) failures.add("Validation must reference the current interface review")
                for (criterion in criteria) {
                    val checks = record.checks.filter { it.criterion == criterion }
                    if (checks.isEmpty() || checks.any { it.outcome != ValidationOutcome.PASSED || it.evidence.isBlank() }) failures.add("Criterion lacks passing evidence: $criterion")
                    if (policy.requireTdd && checks.any { it.outcome == ValidationOutcome.PASSED && (it.tdd?.let { tdd -> tdd.issues().isNotEmpty() } != false) }) failures.add("Criterion lacks a valid RED, GREEN, REFACTOR cycle: $criterion")
                }
                if (record.checks.any { it.outcome != ValidationOutcome.PASSED || it.criterion !in criteria }) failures.add("Validation includes failed, unrun, or unknown checks")
                if (policy.requireTestQuality) {
                    val quality = record.testQuality
                    if (quality == null) failures.add("Measured coverage and test quality review are missing")
                    else failures.addAll(quality.issues(policy.minimumLineCoverage, policy.minimumBranchCoverage))
                }
            }
            issues.addAll(failures.map { "${requirement.key}: $it" })
        }
        var offset = 0L
        do {
            val page = generations.listBySpec(spec.id, offset, 100)
            for (taskId in page.flatMap { it.generatedTaskIds }.distinct()) {
                val task = tasks.getById(taskId)
                if (task == null || statuses.getById(task.statusId)?.category !in setOf(StatusCategory.DONE, StatusCategory.CANCELLED)) issues.add("Generated task is missing or incomplete: $taskId")
            }
            offset += page.size
        } while (page.size == 100)
        return issues
    }

    private suspend fun document(id: UUID): ReviewDocument? {
        val current = metadata.getById(id) ?: return null
        if (current.deleted) return null
        val document = documents.getDocument(id, current.version) ?: return null
        val body = document.content ?: return null
        return ReviewDocument(document.title, json.encodeToJsonElement(Content.serializer(), body))
    }

    private suspend fun allComments(id: UUID): List<SpecComment> {
        val result = mutableListOf<SpecComment>()
        do { val page = comments.listManager(id, result.size.toLong(), 100); result.addAll(page) } while (page.size == 100)
        return result
    }

    private fun <T> decode(value: String, name: String, serializer: KSerializer<T>): T? {
        val prefix = "## $name\n\n```json\n"
        if (!value.startsWith(prefix) || !value.endsWith("\n```")) return null
        return try { json.decodeFromString(serializer, value.removePrefix(prefix).removeSuffix("\n```")) }
        catch (_: SerializationException) { null }
    }

    private fun criteria(document: ReviewDocument): List<String> {
        val blocks = json.decodeFromJsonElement(Content.serializer(), document.content).document.content
        val start = blocks.indexOfFirst { it is HeadingNode && it.attributes.level == 2 && it.text().trim().equals("Acceptance criteria", ignoreCase = true) }
        if (start < 0) return emptyList()
        return blocks.drop(start + 1).takeWhile { it !is HeadingNode || it.attributes.level > 2 }
            .filter { it is BulletListNode || it is OrderedListNode }.flatMap { it.content }.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
    }
    private fun DocumentNode.text(): String = if (this is TextNode) text else content.joinToString("") { it.text() }
}
