package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.api.WorkOpsApi
import bosca.cli.documents.Content
import bosca.cli.documents.DocumentNode
import bosca.cli.documents.DocumentSerializers
import bosca.cli.documents.TextNode
import bosca.graphql.gen.*
import kotlin.uuid.Uuid
import bosca.workops.model.spec.ReviewContext
import bosca.workops.model.spec.ReviewDocument
import bosca.workops.model.spec.ReviewRequirement

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

private typealias ReviewRecord = bosca.workops.model.spec.SpecReviewRecord
private typealias Snapshot = bosca.workops.model.spec.SpecReviewSnapshot

/** Review baselines and dependency-aware recommendations over existing WorkOps records. */
internal object WorkOpsPlanReview {
    private const val PREFIX = "## WorkOps review\n\n```json\n"
    private const val SUFFIX = "\n```"
    private const val PAGE_SIZE = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; serializersModule = DocumentSerializers }

    suspend fun review(api: WorkOpsApi, content: ContentApi, id: Uuid, note: String?, interfaces: List<ReviewedInterface> = emptyList()): JsonObject {
        require(interfaces.all { it.path.isNotBlank() && it.declaration.isNotBlank() }) { "Each reviewed interface needs a path and declaration" }
        require(interfaces.isEmpty() || !note.isNullOrBlank()) { "Interface review needs reviewNote describing the review and representative callers" }
        val snapshot = snapshot(api, content, id)
        val record = ReviewRecord(specId = id.toString(), note = note, snapshot = snapshot, interfaces = interfaces)
        val comment = api.addSpecComment(id, WorkOpsSpecCommentInput(content = PREFIX + json.encodeToString(ReviewRecord.serializer(), record) + SUFFIX))
        return buildJsonObject {
            put("specId", id.toString()); put("reviewId", comment.id); put("reviewNote", note)
            put("requirementCount", snapshot.requirements.size)
            put("guidanceRevision", snapshot.guidanceRevision)
            put("recorded", true)
            put("interfaces", json.encodeToJsonElement(ListSerializer(ReviewedInterface.serializer()), interfaces))
            put("meaning", "Records the content reviewed by the caller; does not approve execution or change workflow state.")
        }
    }

    /** Reports whether caller-reviewed interface declarations still match the current plan. */
    suspend fun interfaceReview(api: WorkOpsApi, content: ContentApi, id: Uuid): JsonObject {
        val baseline = baseline(api, id, null, interfacesOnly = true)
        val issues = when {
            baseline == null -> listOf("Interface review is missing; design and iterate on interfaces before implementing behavior")
            changes(baseline.record.snapshot, snapshot(api, content, id)).isNotEmpty() -> listOf("Interface review is stale after plan or guidance changes; revisit the contracts before implementing behavior")
            else -> emptyList()
        }
        return buildJsonObject {
            put("reviewId", baseline?.id)
            put("interfaces", if (baseline == null) JsonArray(emptyList()) else json.encodeToJsonElement(ListSerializer(ReviewedInterface.serializer()), baseline.record.interfaces))
            put("issues", JsonArray(issues.map(::JsonPrimitive)))
            put("verificationScope", "Caller-recorded declarations and review evidence bound to plan content. Does not read repository files or establish human approval.")
        }
    }

    suspend fun diff(api: WorkOpsApi, content: ContentApi, id: Uuid, reviewId: Long?): JsonObject {
        require(reviewId == null || reviewId > 0) { "reviewId must be positive" }
        val baseline = baseline(api, id, reviewId)
        val current = snapshot(api, content, id)
        return diffResult(id, baseline, current)
    }

    private fun diffResult(id: Uuid, baseline: Baseline?, current: Snapshot): JsonObject = buildJsonObject {
        put("specId", id.toString()); put("reviewId", baseline?.id); put("reviewedAt", baseline?.created)
        put("hasReview", baseline != null)
        val changes = baseline?.let { changes(it.record.snapshot, current) }.orEmpty()
        put("changedSinceReview", if (baseline == null) JsonNull else JsonPrimitive(changes.isNotEmpty()))
        put("changes", JsonArray(changes))
        put("message", if (baseline == null) "No review snapshot exists. Review the current plan, then record it with action=review." else if (changes.isEmpty()) "Plan content and acceptance criteria match this review." else "Review these changes before continuing work.")
    }

    suspend fun nextAction(api: WorkOpsApi, content: ContentApi, id: Uuid): JsonObject {
        val spec = api.getPlanContext(id, 0, PAGE_SIZE) ?: error("Spec not found")
        if (spec.status.category == WorkOpsStatusCategory.CANCELLED) return recommendation(id, "REOPEN", "The spec is cancelled. Review its scope and choose an available workflow transition before resuming.", transitions = spec.transitions.map { WorkOpsPlanToolRegistrar.transitionJson(it) })
        val snapshot = snapshot(api, content, id)
        val baseline = baseline(api, id, null)
        val diff = diffResult(id, baseline, snapshot)
        if (baseline == null || changes(baseline.record.snapshot, snapshot).isNotEmpty()) {
            return buildJsonObject {
                put("specId", id.toString()); put("action", "REVIEW"); put("reason", "The plan has no review baseline or has changed since review.")
                put("review", diff); put("suggestedTool", "workops_plan")
                put("suggestedArguments", buildJsonObject { put("action", "context"); put("id", id.toString()) })
            }
        }
        if (spec.status.category == WorkOpsStatusCategory.DONE) return recommendation(id, "VERIFY", "The spec is done. Check current evidence before reopening it or choosing more work.")

        val guidance = api.getEffectiveGuidance(spec.projectId)
        val interfaceReview = interfaceReview(api, content, id)
        if (guidance.policy.requireInterfaceReview && interfaceReview.getValue("issues").jsonArray.isNotEmpty()) return buildJsonObject {
            put("specId", id.toString()); put("action", "DESIGN_INTERFACES")
            put("reason", "Present and iterate on interfaces and representative callers before implementing behavior. Then record the interface review.")
            put("interfaceReview", interfaceReview)
            put("suggestedTool", "workops_plan")
            put("suggestedArguments", buildJsonObject { put("action", "context"); put("id", id.toString()) })
        }

        val requirements = allRequirements(api, id)
        val taskIds = requirements.mapNotNull { it.taskId }.toMutableList()
        var generationOffset = 0L
        do {
            val page = api.getPlanGenerations(id, generationOffset, PAGE_SIZE)
            taskIds.addAll(page.flatMap { it.generatedTaskIds })
            generationOffset += page.size
        } while (page.size == PAGE_SIZE)
        val unavailable = mutableListOf<String>()
        val blocked = mutableListOf<JsonObject>()
        val available = mutableListOf<Pair<Int, JsonObject>>()
        for (taskId in taskIds.distinct()) {
            val task = api.getPlanTask(taskId)
            if (task == null) { unavailable.add(taskId.toString()); continue }
            if (task.status.category == WorkOpsStatusCategory.DONE || task.status.category == WorkOpsStatusCategory.CANCELLED) continue
            val blockers = api.getPlanTaskLinks(taskId).filter { it.linkType.category == WorkOpsLinkCategory.BLOCKS }
                .mapNotNull { link ->
                    when {
                        link.sourceTask?.id == taskId -> null // This task blocks someone else.
                        link.targetTask?.id != taskId -> buildJsonObject { put("linkId", link.id.toString()); put("reason", "Dependency direction is inaccessible") }
                        link.sourceTask == null -> buildJsonObject { put("linkId", link.id.toString()); put("reason", "Blocking task is inaccessible") }
                        link.sourceTask.status.category in listOf(WorkOpsStatusCategory.DONE, WorkOpsStatusCategory.CANCELLED) -> null
                        else -> buildJsonObject {
                            put("taskId", link.sourceTask.id.toString()); put("key", link.sourceTask.key)
                            put("summary", link.sourceTask.summary); put("status", link.sourceTask.status.name)
                        }
                    }
                }
            val start = task.transitions.firstOrNull { it.toState.status.category == WorkOpsStatusCategory.IN_PROGRESS }
            if (blockers.isNotEmpty() || (task.status.category != WorkOpsStatusCategory.IN_PROGRESS && start == null)) {
                blocked.add(buildJsonObject {
                    put("taskId", taskId.toString()); put("key", task.key); put("blockers", JsonArray(blockers))
                    put("reason", if (blockers.isNotEmpty()) "Unresolved or inaccessible blocking dependencies" else "No available transition to an in-progress status")
                    put("transitions", JsonArray(task.transitions.map { WorkOpsPlanToolRegistrar.transitionJson(it) }))
                })
            } else {
                val continuing = task.status.category == WorkOpsStatusCategory.IN_PROGRESS
                available.add((if (continuing) 0 else 1) to buildJsonObject {
                    put("specId", id.toString()); put("action", if (continuing) "CONTINUE" else "START_TASK")
                    put("taskId", taskId.toString()); put("key", task.key); put("summary", task.summary)
                    put("requirementId", requirements.firstOrNull { it.taskId == taskId }?.let { it.id.toString() })
                    put("reason", if (continuing) "Continue existing work with no unresolved blocking links." else "This unfinished task has no unresolved blocking links and has an available start transition.")
                    put("guidance", json.encodeToJsonElement(PlanGuidance.serializer(), guidance))
                    if (!continuing) {
                        put("suggestedTool", "workops_task")
                        put("suggestedArguments", buildJsonObject {
                            put("action", "transition"); put("id", taskId.toString()); put("transitionId", requireNotNull(start).id.toString()); put("expectedVersion", task.version)
                        })
                    }
                    put("specTransitions", JsonArray(spec.transitions.map { WorkOpsPlanToolRegistrar.transitionJson(it) }))
                })
            }
        }
        if (available.isNotEmpty()) return JsonObject(available.minBy { it.first }.second + mapOf(
            "blockedTasks" to JsonArray(blocked), "inaccessibleTasks" to JsonArray(unavailable.map(::JsonPrimitive)),
        ))
        if (blocked.isNotEmpty() || unavailable.isNotEmpty()) return buildJsonObject {
            put("specId", id.toString()); put("action", "UNBLOCK"); put("reason", "No readable unfinished task can currently be started.")
            put("blockedTasks", JsonArray(blocked)); put("inaccessibleTasks", JsonArray(unavailable.map(::JsonPrimitive)))
        }
        return recommendation(id, "VERIFY", "All readable linked/generated tasks are finished or cancelled. Verify criteria, evidence, and child specs before completion.")
    }

    private fun recommendation(id: Uuid, action: String, reason: String, transitions: List<JsonObject> = emptyList()) = buildJsonObject {
        put("specId", id.toString()); put("action", action); put("reason", reason)
        put("transitions", JsonArray(transitions))
        if (action == "VERIFY") {
            put("suggestedTool", "workops_plan")
            put("suggestedArguments", buildJsonObject { put("action", "verify"); put("id", id.toString()) })
        }
    }

    private suspend fun allRequirements(api: WorkOpsApi, id: Uuid): List<GetWorkOpsPlanContextData.WorkOps.Specs.Spec.Requirements> {
        val first = api.getPlanContext(id, 0, PAGE_SIZE) ?: error("Spec not found")
        val result = first.requirements.toMutableList()
        while (result.size.toLong() < first.requirementCount) {
            val next = api.getPlanContext(id, result.size.toLong(), PAGE_SIZE) ?: error("Spec disappeared")
            require(next.requirementCount == first.requirementCount) { "Requirements changed while reading; retry" }
            check(next.requirements.isNotEmpty()) { "Some requirements are inaccessible; cannot assess the full plan" }
            result.addAll(next.requirements)
        }
        check(result.size.toLong() == first.requirementCount && result.map { it.id }.distinct().size == result.size) { "Requirements changed or are inaccessible; retry" }
        return result
    }

    private suspend fun snapshot(api: WorkOpsApi, content: ContentApi, id: Uuid): Snapshot {
        val spec = api.getPlanContext(id) ?: error("Spec not found")
        suspend fun document(metadataId: Uuid): ReviewDocument {
            val doc = content.metadata.getDocumentContext(metadataId)?.document ?: error("Document $metadataId is inaccessible; cannot compare plan scope")
            return ReviewDocument(doc.title, doc.content)
        }
        val specDocument = document(spec.metadataId)
        val requirements = allRequirements(api, id).map {
            val doc = document(it.metadataId)
            ReviewRequirement(it.id.toString(), it.key, doc, WorkOpsPlanToolRegistrar.acceptanceCriteria(doc.content))
        }
        val final = api.getPlanContext(id) ?: error("Spec disappeared")
        check(final.version == spec.version && final.requirementCount == requirements.size.toLong()) { "Plan changed while reading; retry" }
        return Snapshot(spec.projectId?.toString(), specDocument, requirements,
            spec.programId?.toString(), spec.parentSpecId?.toString(), spec.gitRepositoryId?.toString(), spec.gitPath,
            spec.contexts.map { ReviewContext(it.contextType.name, it.targetId, it.label, it.attributes) }
                .sortedWith(compareBy<ReviewContext> { it.type }.thenBy { it.targetId }.thenBy { it.label }),
            api.getEffectiveGuidance(spec.projectId).revision,
        )
    }

    private suspend fun baseline(api: WorkOpsApi, id: Uuid, reviewId: Long?, interfacesOnly: Boolean = false): Baseline? {
        val records = mutableListOf<Baseline>()
        var offset = 0L
        do {
            val page = api.getSpecComments(id, offset, PAGE_SIZE.toLong())
            for (comment in page.filter { !it.deleted && it.status != CommentStatus.BLOCKED }) {
                val record = decode(comment.content)?.takeIf { it.specId == id.toString() } ?: continue
                if (interfacesOnly && record.interfaces.isEmpty()) continue
                records.add(Baseline(comment.id, comment.created.toString(), record, comment.created.toInstant()))
            }
            offset += page.size
        } while (page.size == PAGE_SIZE)
        if (reviewId != null) return records.firstOrNull { it.id == reviewId } ?: error("Review snapshot not found or inaccessible: $reviewId")
        return records.maxWithOrNull(compareBy<Baseline> { it.instant }.thenBy { it.id })
    }

    private fun decode(value: String): ReviewRecord? {
        if (!value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) return null
        return try {
            json.decodeFromString(ReviewRecord.serializer(), value.removePrefix(PREFIX).removeSuffix(SUFFIX))
                .takeIf { it.schema == "bosca.workops.review.v1" && it.interfaces.all { declaration -> declaration.path.isNotBlank() && declaration.declaration.isNotBlank() } && (it.interfaces.isEmpty() || !it.note.isNullOrBlank()) }
        } catch (_: SerializationException) { null }
    }

    private fun changes(before: Snapshot, after: Snapshot): List<JsonObject> = buildList {
        if (before.guidanceRevision != after.guidanceRevision) add(change("guidance", "revision", before.guidanceRevision, after.guidanceRevision))
        if (before.projectId != after.projectId) add(change("scope", "project", before.projectId, after.projectId))
        for ((field, values) in mapOf("program" to (before.programId to after.programId), "parentSpec" to (before.parentSpecId to after.parentSpecId),
            "gitRepository" to (before.gitRepositoryId to after.gitRepositoryId), "gitPath" to (before.gitPath to after.gitPath))) {
            if (values.first != values.second) add(change("scope", field, values.first, values.second))
        }
        if (before.contexts != after.contexts) add(change("references", "spec", before.contexts.joinToString { it.summary() }, after.contexts.joinToString { it.summary() }))
        documentChanges("spec", before.document, after.document).forEach { add(it) }
        val old = before.requirements.associateBy { it.id }
        val new = after.requirements.associateBy { it.id }
        for (id in (old.keys + new.keys)) {
            val a = old[id]; val b = new[id]
            when {
                a == null -> new.getValue(id).let { added -> add(change("requirement", added.key, null, added.document.readable(), "ADDED")) }
                b == null -> add(change("requirement", a.key, a.document.readable(), null, "REMOVED"))
                else -> {
                    documentChanges(a.key, a.document, b.document).forEach { add(it) }
                    if (a.acceptanceCriteria != b.acceptanceCriteria) add(change("acceptanceCriteria", a.key, a.acceptanceCriteria.joinToString("\n"), b.acceptanceCriteria.joinToString("\n")))
                }
            }
        }
        if (before.requirements.map { it.id } != after.requirements.map { it.id } && old.keys == new.keys) {
            add(change("requirementOrder", "spec", before.requirements.joinToString { it.key }, after.requirements.joinToString { it.key }))
        }
    }

    private fun documentChanges(key: String, before: ReviewDocument, after: ReviewDocument): List<JsonObject> = buildList {
        if (before.title != after.title) add(change("title", key, before.title, after.title))
        if (before.content != after.content) add(buildJsonObject {
            put("kind", "UPDATED"); put("field", "document"); put("key", key)
            val a = before.readable(); val b = after.readable()
            put("before", a); put("after", b)
            put("diff", lineDiff(a, b))
            put("textUnchanged", a == b)
            if (a == b) { put("beforeContent", before.content); put("afterContent", after.content) }
        })
    }

    internal fun lineDiff(before: String, after: String): String {
        val a = before.lines(); val b = after.lines()
        val prefix = a.zip(b).takeWhile { it.first == it.second }.size
        var suffix = 0
        while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
        return (a.subList(prefix, a.size - suffix).map { "- $it" } + b.subList(prefix, b.size - suffix).map { "+ $it" }).joinToString("\n")
    }

    private fun change(field: String, key: String, before: String?, after: String?, kind: String = "UPDATED") = buildJsonObject {
        put("kind", kind); put("field", field); put("key", key); put("before", before); put("after", after)
    }

    private fun ReviewContext.summary(): String = "$type: $targetId (${label.orEmpty()}) ${attributes ?: ""}"

    private fun ReviewDocument.readable(): String = json.decodeFromJsonElement(Content.serializer(), content).document.content
        .joinToString("\n") { it.text() }
    private fun DocumentNode.text(): String = if (this is TextNode) text else content.joinToString(if (content.any { it !is TextNode }) "\n" else "") { it.text() }

    private data class Baseline(val id: Long, val created: String, val record: ReviewRecord, val instant: java.time.Instant)
}
