package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.api.WorkOpsApi
import bosca.cli.documents.BulletListNode
import bosca.cli.documents.Content
import bosca.cli.documents.DocumentNode
import bosca.cli.documents.DocumentSerializers
import bosca.cli.documents.HeadingNode
import bosca.cli.documents.OrderedListNode
import bosca.cli.documents.TextNode
import bosca.graphql.gen.*
import bosca.workops.model.spec.ReviewedInterface
import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.TestQualityEvidence
import bosca.workops.model.spec.ValidationCheck
import bosca.workops.model.spec.ValidationOutcome
import bosca.workops.model.spec.ValidationRecord
import bosca.workops.model.spec.fingerprint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal typealias PlanGuidance = GetEffectiveWorkOpsGuidanceData.WorkOps.Guidance.Effective
internal typealias PlanPolicy = GetEffectiveWorkOpsGuidanceData.WorkOps.Guidance.Effective.Policy

private typealias PlanSpec = GetWorkOpsPlanContextData.WorkOps.Specs.Spec
private typealias PlanRequirement = GetWorkOpsPlanContextData.WorkOps.Specs.Spec.Requirements
private typealias PlanDocument = GetMetadataDocumentContextData.Content.Metadata
private typealias PlanComment = GetWorkOpsSpecCommentsData.WorkOps.SpecComments.ForSpec

/** Guided planning and evidence recording over the existing WorkOps and content APIs. */
object WorkOpsPlanToolRegistrar {
    private const val PAGE_SIZE = 100
    private const val VALIDATION_PREFIX = "## WorkOps validation\n\n```json\n"
    private const val VALIDATION_SUFFIX = "\n```"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; serializersModule = DocumentSerializers }

    internal val tool = Tool(
        name = "workops_plan",
        description = """
            Guided WorkOps planning, review, resumption, and verification. Uses existing specs,
            requirement documents, linked tasks, workflow transitions, and comments.
            Actions: instructions, create, context, update, add_requirement, update_requirement,
            record_validation, verify, next_action, review, review_interfaces, diff. Use id or key to identify an existing spec.
            create requires requestId (a caller-generated UUID), projectId or projectKey, name,
            markdownContent, and requirements. add_requirement also requires requestId.
            Reuse requestId and identical input to retry creation; different input is rejected.
            review records a content baseline after the caller reviews the plan. diff compares the
            latest baseline (or reviewId) with current scope, documents, references, and criteria.
            review_interfaces requires nonempty interfaces [{path, declaration}] and reviewNote
            describing interface review and representative callers. Present and iterate on contracts
            before implementing behavior. Plan edits invalidate the interface review; source-only
            contract edits require updating the plan and reviewing interfaces again.
            next_action recommends review, an unblocked task/start transition, continuation,
            resolving blockers, or verification; it never transitions or executes work.
            Each requirement needs name, markdownContent, and nonempty acceptanceCriteria.
            Requirement writes replace both its document and its linked task's summary/description;
            this explicit edit takes precedence over independently edited task content.
            context returns the plan, a page of requirement bodies/tasks/transitions, references,
            recent comments, and a page of additional generated tasks. Follow next offsets to resume
            large plans. verify checks every page and reports missing, failed, or stale evidence.
            record_validation requires requirementId and checks containing criterion, outcome
            (PASSED, FAILED, NOT_RUN), and evidence. Copy criterion text from context. Record exact
            test commands/results AFTER completing the linked task. Effective policy determines whether
            PASSED checks require interface review and tdd containing red/green/refactor runs, each with command, outcome,
            evidence. RED must be FAILED for the intended missing behavior before implementation;
            GREEN and REFACTOR must be PASSED. Manual observations alone cannot establish TDD.
            When required by effective policy, PASSED validation includes testQuality {review, coverage:[{scope, command, report,
            lineCovered, lineMissed, branchCovered, branchMissed, uncovered, noBranchesReason?}]}.
            Review observable behavior, relevant boundaries/failures, and real integration coverage.
            Report raw measured counters for every affected production scope; effective policy supplies
            the minimum line and branch percentages. Existing stricter module gates must also pass. A report with zero
            branches needs an explicit noBranchesReason; zero executable lines cannot prove coverage.
            These tools record caller-supplied evidence; they do not execute or independently verify
            tests. Verification never changes statuses. Use workops_spec action=transitions/transition
            and workops_task_transitions/workops_task action=transition to track actual work.
            Plan/requirement creation and requirement document/task updates are atomic backend
            operations. Keep the spec body current as requirements change. These operations require
            the matching WorkOps backend schema and migration.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf("instructions", "create", "context", "update", "add_requirement", "update_requirement", "record_validation", "verify", "next_action", "review", "review_interfaces", "diff").map(::JsonPrimitive)))
                })
                for (key in listOf("id", "key", "projectId", "projectKey", "name", "markdownContent", "requirementId", "requestId", "reviewNote")) {
                    put(key, buildJsonObject { put("type", "string") })
                }
                put("acceptanceCriteria", buildJsonObject {
                    put("type", "array"); put("minItems", 1)
                    put("items", buildJsonObject { put("type", "string"); put("minLength", 1) })
                    put("description", "Testable criteria, appended under an Acceptance criteria heading on requirement writes.")
                })
                put("requirements", buildJsonObject {
                    put("type", "array"); put("minItems", 1)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("markdownContent", buildJsonObject { put("type", "string") })
                            put("acceptanceCriteria", buildJsonObject {
                                put("type", "array"); put("minItems", 1)
                                put("items", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("required", JsonArray(listOf("name", "markdownContent", "acceptanceCriteria").map(::JsonPrimitive)))
                    })
                })
                put("checks", buildJsonObject {
                    put("type", "array"); put("minItems", 1)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("criterion", buildJsonObject { put("type", "string") })
                            put("outcome", buildJsonObject {
                                put("type", "string"); put("enum", JsonArray(ValidationOutcome.entries.map { JsonPrimitive(it.name) }))
                            })
                            put("evidence", buildJsonObject { put("type", "string") })
                            put("tdd", buildJsonObject {
                                put("type", "object")
                                put("description", "Required for PASSED: actual red failure before implementation, green pass, and refactor pass.")
                                put("properties", buildJsonObject {
                                    for (phase in listOf("red", "green", "refactor")) put(phase, buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject {
                                            put("command", buildJsonObject { put("type", "string"); put("minLength", 1) })
                                            put("outcome", buildJsonObject { put("type", "string"); put("enum", JsonArray(ValidationOutcome.entries.map { JsonPrimitive(it.name) })) })
                                            put("evidence", buildJsonObject { put("type", "string"); put("minLength", 1) })
                                        })
                                        put("required", JsonArray(listOf("command", "outcome", "evidence").map(::JsonPrimitive)))
                                    })
                                })
                                put("required", JsonArray(listOf("red", "green", "refactor").map(::JsonPrimitive)))
                            })
                        })
                        put("required", JsonArray(listOf("criterion", "outcome", "evidence").map(::JsonPrimitive)))
                    })
                })
                put("interfaces", buildJsonObject {
                    put("type", "array"); put("minItems", 1)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject { put("type", "string"); put("minLength", 1) })
                            put("declaration", buildJsonObject { put("type", "string"); put("minLength", 1) })
                        })
                        put("required", JsonArray(listOf("path", "declaration").map(::JsonPrimitive)))
                    })
                })
                put("testQuality", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("review", buildJsonObject { put("type", "string"); put("minLength", 1) })
                        put("coverage", buildJsonObject {
                            put("type", "array"); put("minItems", 1)
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    for (key in listOf("scope", "command", "report", "uncovered", "noBranchesReason")) put(key, buildJsonObject { put("type", "string"); put("minLength", 1) })
                                    for (key in listOf("lineCovered", "lineMissed", "branchCovered", "branchMissed")) put(key, buildJsonObject { put("type", "integer"); put("minimum", 0) })
                                })
                                put("required", JsonArray(listOf("scope", "command", "report", "uncovered", "lineCovered", "lineMissed", "branchCovered", "branchMissed").map(::JsonPrimitive)))
                            })
                        })
                    })
                    put("required", JsonArray(listOf("review", "coverage").map(::JsonPrimitive)))
                    put("description", "Behavioral test-quality review and measured production coverage. Effective policy determines whether required and sets minimum percentages.")
                })
                for (key in listOf("offset", "limit", "commentOffset", "generationOffset", "reviewId")) {
                    put(key, buildJsonObject { put("type", "integer"); put("minimum", 0) })
                }
            },
            required = listOf("action"),
        ),
    )

    fun registerAll(server: Server, api: WorkOpsApi, content: ContentApi) {
        server.addTool(tool) { request ->
            result { handle(api, content, request.arguments ?: error("Missing arguments")) }
        }
    }

    internal suspend fun result(block: suspend () -> String): CallToolResult = try {
        CallToolResult(content = listOf(TextContent(block())))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
    }

    internal suspend fun handle(api: WorkOpsApi, content: ContentApi, args: Map<String, JsonElement>): String =
        when (val action = args.string("action")) {
            "instructions" -> loadInstructions(api, args)
            "create" -> create(api, args)
            "next_action" -> WorkOpsPlanReview.nextAction(api, content, args.specId(api)).toString()
            "review" -> WorkOpsPlanReview.review(api, content, args.specId(api), args.optionalString("reviewNote")).toString()
            "review_interfaces" -> {
                val interfaces = json.decodeFromJsonElement(ListSerializer(ReviewedInterface.serializer()), args.getValue("interfaces"))
                require(interfaces.isNotEmpty()) { "Interface review needs nonempty interfaces" }
                WorkOpsPlanReview.review(api, content, args.specId(api), args.string("reviewNote"), interfaces).toString()
            }
            "diff" -> WorkOpsPlanReview.diff(api, content, args.specId(api), args["reviewId"]?.let { it.jsonPrimitive.long }).toString()
            "context", "verify" -> context(api, content, args, verify = action == "verify").toString()
            "update" -> {
                val id = args.specId(api)
                val body = args.string("markdownContent").also { require(it.isNotBlank()) { "markdownContent must not be blank" } }
                val spec = api.getPlanContext(id) ?: error("Spec not found: $id")
                val document = content.metadata.getDocumentContext(spec.metadataId) ?: error("Spec document is inaccessible")
                content.metadata.setMarkdown(document.id, document.version, args.optionalString("name") ?: document.document?.title ?: document.name, body)
                buildJsonObject { put("specId", id.toString()); put("metadataId", document.id.toString()); put("updated", true) }.toString()
            }
            "add_requirement" -> {
                val input = requirementInput(args)
                val id = args.specId(api)
                api.getPlanContext(id) ?: error("Spec not found: $id")
                requirementResult(api.addPlanRequirement(id, Uuid.parse(args.string("requestId")), input.toGraphql())).toString()
            }
            "update_requirement" -> {
                val input = requirementInput(args)
                val id = args.specId(api)
                val requirementId = Uuid.parse(args.string("requirementId"))
                val requirement = api.getRequirement(requirementId) ?: error("Requirement not found")
                require(requirement.parentType == WorkOpsRequirementParent.SPEC && requirement.parentId == id) {
                    "Requirement does not belong to this spec"
                }
                writeRequirementContent(api, content, requirementId, input).toString()
            }
            "record_validation" -> recordValidation(api, content, args)
            else -> error("Unknown workops_plan action: $action")
        }

    /** Both MCP instruction entry points resolve the same live project guidance and policy. */
    internal suspend fun loadInstructions(api: WorkOpsApi, args: Map<String, JsonElement>): String =
        json.encodeToString(PlanGuidance.serializer(), api.getEffectiveGuidance(args.projectId(api)))

    private suspend fun create(api: WorkOpsApi, args: Map<String, JsonElement>): String {
        val name = args.string("name").also { require(it.isNotBlank()) { "name must not be blank" } }
        val body = args.string("markdownContent").also { require(it.isNotBlank()) { "markdownContent must not be blank" } }
        val requestId = Uuid.parse(args.string("requestId"))
        val requirements = json.decodeFromJsonElement(ListSerializer(RequirementContentInput.serializer()), args.getValue("requirements"))
        require(requirements.isNotEmpty()) { "A plan needs at least one requirement" }
        requirements.forEach { it.validate() }
        val projectId = args.projectId(api) ?: error("Provide projectId or projectKey")
        val spec = api.createPlan(CreateWorkOpsSpecPlanInput(requestId, projectId, name, body, requirements.map { it.toGraphql() }))
        return buildJsonObject {
            put("specId", spec.id.toString()); put("key", spec.key); put("metadataId", spec.metadataId.toString())
            put("requestId", requestId.toString()); put("atomic", true)
            put("nextStep", "Read context and review the plan. Reuse requestId and identical input if the response is lost.")
        }.toString()
    }

    private fun requirementResult(requirement: AddWorkOpsPlanRequirementData.WorkOps.Specs.AddPlanRequirement) = buildJsonObject {
        put("requirementId", requirement.id.toString()); put("key", requirement.key)
        put("metadataId", requirement.metadataId.toString()); put("taskId", requirement.taskId?.toString()); put("atomic", true)
    }

    private fun RequirementContentInput.toGraphql() = WorkOpsRequirementContentInput(name, markdownContent, acceptanceCriteria)

    /** Explicitly replaces a requirement document and the independently stored linked task description. */
    internal suspend fun writeRequirementContent(api: WorkOpsApi, content: ContentApi, id: Uuid, input: RequirementContentInput): JsonObject {
        input.validate()
        val requirement = api.getRequirement(id) ?: error("Requirement not found: $id")
        val taskId = requirement.taskId ?: error("Requirement has no linked task")
        val task = api.getPlanTask(taskId) ?: error("Linked task is inaccessible: $taskId")
        val document = content.metadata.getDocumentContext(requirement.metadataId) ?: error("Requirement document is inaccessible")
        api.setRequirementContent(id, SetWorkOpsRequirementContentInput(
            content = input.toGraphql(), expectedVersion = requirement.version,
            expectedTaskVersion = task.version, metadataVersion = document.version,
        ))
        return buildJsonObject {
            put("requirementId", requirement.id.toString()); put("key", requirement.key)
            put("metadataId", requirement.metadataId.toString()); put("taskId", taskId.toString())
            put("documentWritten", true); put("taskWritten", true)
        }
    }

    private suspend fun context(api: WorkOpsApi, content: ContentApi, args: Map<String, JsonElement>, verify: Boolean): JsonObject {
        val id = args.specId(api)
        val limit = args.limit()
        val offset = if (verify) 0 else args.offset("offset")
        val spec = api.getPlanContext(id, offset, limit) ?: error("Spec not found: $id")
        val guidance = api.getEffectiveGuidance(spec.projectId)
        val policy = guidance.policy
        val requirements = spec.requirements.toMutableList()
        if (verify) {
            while (requirements.size < spec.requirementCount) {
                val page = api.getPlanContext(id, requirements.size.toLong(), PAGE_SIZE) ?: error("Spec disappeared during verification")
                if (page.requirements.isEmpty()) break
                requirements.addAll(page.requirements)
            }
        }
        val document = content.metadata.getDocumentContext(spec.metadataId)
        var documentsAccessible = document?.document != null
        val comments = allComments(api, id)
        val latest = comments.sortedWith(compareBy<PlanComment> { it.created.toInstant() }.thenBy { it.id })
            .filter { !it.deleted && it.status != CommentStatus.BLOCKED }
            .mapNotNull { validationRecord(it.content) }
            .associateBy { it.requirementId }
        val issues = mutableListOf<String>()
        if (document?.document == null) issues.add("Spec document is missing or inaccessible")
        if (spec.requirementCount == 0L) issues.add("Plan has no requirements")
        if (verify && requirements.size.toLong() != spec.requirementCount) issues.add("Requirements changed or could not all be read; retry verification")
        if (spec.childDoneCount != spec.childCount) issues.add("Child specs are incomplete")
        if (spec.status.category == WorkOpsStatusCategory.CANCELLED) issues.add("Spec is cancelled")
        val requirementResults = requirements.map { requirement ->
            val requirementDocument = content.metadata.getDocumentContext(requirement.metadataId)
            if (requirementDocument?.document == null) documentsAccessible = false
            val criteria = requirementDocument?.document?.let { acceptanceCriteria(it.content) }.orEmpty()
            val record = latest[requirement.id.toString()]
            val requirementIssues = requirementIssues(requirement, requirementDocument, document, criteria, record, guidance)
            issues.addAll(requirementIssues.map { "${requirement.key}: $it" })
            buildJsonObject {
                put("requirement", json.encodeToJsonElement(PlanRequirement.serializer(), requirement))
                put("document", nullableJson(PlanDocument.serializer(), requirementDocument))
                put("acceptanceCriteria", JsonArray(criteria.map(::JsonPrimitive)))
                put("validation", nullableJson(ValidationRecord.serializer(), record))
                put("coverageSummary", JsonArray(record?.testQuality?.coverage.orEmpty().map { report -> buildJsonObject {
                    put("scope", report.scope); put("report", report.report)
                    put("linePercent", report.linePercent())
                    put("branchPercent", report.branchPercent())
                    put("issues", JsonArray(report.issues(policy.minimumLineCoverage, policy.minimumBranchCoverage).map(::JsonPrimitive)))
                } }))
                put("issues", JsonArray(requirementIssues.map(::JsonPrimitive)))
            }
        }
        val interfaceReview = if (!policy.requireInterfaceReview) null else if (verify && documentsAccessible && requirements.size.toLong() == spec.requirementCount) {
            WorkOpsPlanReview.interfaceReview(api, content, id)
        } else if (verify) buildJsonObject { put("reviewId", JsonNull); put("issues", JsonArray(listOf(JsonPrimitive("Interface review cannot be checked until all plan documents are accessible")))) } else null
        if (interfaceReview != null) {
            issues.addAll(interfaceReview.getValue("issues").jsonArray.map { it.jsonPrimitive.content })
            val reviewedId = interfaceReview.getValue("reviewId").jsonPrimitive.longOrNull
            for (requirement in requirements) {
                val record = latest[requirement.id.toString()]
                if (record != null && (reviewedId == null || record.interfaceReviewId != reviewedId)) issues.add("${requirement.key}: Validation must reference the current interface review; re-record evidence after reviewing interfaces")
            }
        }
        val generationOffset = if (verify) 0 else args.offset("generationOffset")
        val generations = api.getPlanGenerations(id, generationOffset, limit).toMutableList()
        if (verify) {
            var next = generations.size.toLong()
            var pageSize = generations.size
            var expectedSize = limit
            while (pageSize == expectedSize) {
                val page = api.getPlanGenerations(id, next, PAGE_SIZE)
                generations.addAll(page); next += page.size; pageSize = page.size
                expectedSize = PAGE_SIZE
            }
        }
        val additionalTasks = generations.flatMap { it.generatedTaskIds }.distinct().map { taskId ->
            val task = api.getPlanTask(taskId)
            if (task == null || task.status.category !in setOf(WorkOpsStatusCategory.DONE, WorkOpsStatusCategory.CANCELLED)) issues.add("Generated task $taskId is incomplete or inaccessible")
            buildJsonObject {
                put("id", taskId.toString())
                put("task", if (task == null) JsonNull else planTaskJson(task))
            }
        }
        val commentOffset = if (verify) 0 else args.offset("commentOffset")
        val visibleComments = if (verify) comments else comments.drop(commentOffset.toInt()).take(limit)
        return buildJsonObject {
            put("spec", json.encodeToJsonElement(PlanSpec.serializer(), spec.copy(requirements = emptyList())))
            put("document", nullableJson(PlanDocument.serializer(), document))
            put("requirements", JsonArray(requirementResults))
            put("offset", offset); put("returnedRequirements", requirements.size)
            put("nextOffset", if (offset + requirements.size < spec.requirementCount) JsonPrimitive(offset + requirements.size) else JsonNull)
            put("comments", json.encodeToJsonElement(ListSerializer(PlanComment.serializer()), visibleComments))
            put("nextCommentOffset", if (!verify && commentOffset + visibleComments.size < comments.size) JsonPrimitive(commentOffset + visibleComments.size) else JsonNull)
            put("taskGenerations", json.encodeToJsonElement(ListSerializer(GetWorkOpsPlanGenerationsData.WorkOps.Specs.Spec.TaskGenerations.serializer()), generations))
            put("generatedTasks", JsonArray(additionalTasks))
            put("nextGenerationOffset", if (!verify && generations.size == limit) JsonPrimitive(generationOffset + generations.size) else JsonNull)
            put("issues", JsonArray(issues.map(::JsonPrimitive)))
            put("guidance", json.encodeToJsonElement(PlanGuidance.serializer(), guidance))
            put("interfaceReview", interfaceReview ?: JsonNull)
            put("readyToComplete", verify && issues.isEmpty())
            put("verificationScope", "Current caller-recorded interface review, TDD evidence, behavioral test-quality review and raw measured coverage counters for requirement criteria, linked/generated task status, and child spec status. Does not read source or coverage files, execute tests, audit test quality, independently establish phase order, or change workflow state.")
        }
    }

    private fun requirementIssues(requirement: PlanRequirement, document: PlanDocument?, specDocument: PlanDocument?, criteria: List<String>, record: ValidationRecord?, guidance: PlanGuidance): List<String> = buildList {
        val policy = guidance.policy
        if (document?.document == null) add("Document is missing or inaccessible")
        if (criteria.isEmpty()) add("Acceptance criteria are missing")
        val task = requirement.task
        if (task == null) add("Linked task is missing or inaccessible")
        if (requirement.status.category != WorkOpsStatusCategory.DONE || task?.let { it.status.category } != WorkOpsStatusCategory.DONE) add("Requirement and linked task must both be done")
        if (record == null) {
            add("Validation evidence is missing")
        } else {
            if (record.taskId != task?.let { it.id.toString() } || record.taskVersion != task.version ||
                record.requirementContentHash != document?.document?.let { document.fingerprint() } ||
                record.specContentHash != specDocument?.document?.let { specDocument.fingerprint() }) add("Validation evidence is stale")
            if (record.guidanceRevision != guidance.revision) add("Validation evidence is stale after guidance changes")
            for (criterion in criteria) {
                val checks = record.checks.filter { it.criterion == criterion }
                if (checks.isEmpty() || checks.any { it.outcome != ValidationOutcome.PASSED || it.evidence.isBlank() }) add("Criterion lacks passing evidence: $criterion")
                if (policy.requireTdd && checks.any { it.outcome == ValidationOutcome.PASSED && (it.tdd?.let { tdd -> tdd.issues().isNotEmpty() } != false) }) add("Criterion lacks a valid RED, GREEN, REFACTOR TDD cycle: $criterion")
            }
            if (record.checks.any { it.outcome != ValidationOutcome.PASSED || it.criterion !in criteria }) add("Validation includes failed, unrun, or unknown checks")
            if (policy.requireTestQuality && record.checks.any { it.outcome == ValidationOutcome.PASSED }) {
                val quality = record.testQuality
                if (quality == null) add("Measured coverage and behavioral test quality review are missing")
                else addAll(quality.issues(policy.minimumLineCoverage, policy.minimumBranchCoverage))
            }
        }
    }

    private suspend fun recordValidation(api: WorkOpsApi, content: ContentApi, args: Map<String, JsonElement>): String {
        val checks = json.decodeFromJsonElement(ListSerializer(ValidationCheck.serializer()), args.getValue("checks"))
        require(checks.isNotEmpty() && checks.all { it.criterion.isNotBlank() && it.evidence.isNotBlank() }) {
            "Each check needs a criterion, outcome, and concrete evidence (or a reason it was not run)"
        }
        val id = args.specId(api)
        val spec = api.getPlanContext(id) ?: error("Spec not found")
        val guidance = api.getEffectiveGuidance(spec.projectId)
        val policy = guidance.policy
        for (check in checks.filter { policy.requireTdd && it.outcome == ValidationOutcome.PASSED }) {
            val tdd = requireNotNull(check.tdd) { "PASSED checks require RED, GREEN, REFACTOR TDD evidence" }
            require(tdd.issues().isEmpty()) { tdd.issues().joinToString("; ") }
        }
        val testQuality = args["testQuality"]?.let { json.decodeFromJsonElement(TestQualityEvidence.serializer(), it) }
        if (policy.requireTestQuality && checks.any { it.outcome == ValidationOutcome.PASSED }) {
            requireNotNull(testQuality) { "PASSED validation requires measured coverage and behavioral test quality review" }
            require(testQuality.issues(policy.minimumLineCoverage, policy.minimumBranchCoverage).isEmpty()) { testQuality.issues(policy.minimumLineCoverage, policy.minimumBranchCoverage).joinToString("; ") }
        }
        val requirement = api.getRequirement(Uuid.parse(args.string("requirementId"))) ?: error("Requirement not found")
        require(requirement.parentType == WorkOpsRequirementParent.SPEC && requirement.parentId == id) { "Requirement does not belong to this spec" }
        val specDocument = content.metadata.getDocumentContext(spec.metadataId) ?: error("Spec document is inaccessible")
        val document = content.metadata.getDocumentContext(requirement.metadataId) ?: error("Requirement document is inaccessible")
        val criteria = document.document?.let { acceptanceCriteria(it.content) }.orEmpty()
        require(criteria.isNotEmpty()) { "Requirement needs an Acceptance criteria list" }
        require(checks.all { it.criterion in criteria }) { "Check criteria must match the text returned by context" }
        val taskId = requirement.taskId ?: error("Requirement has no linked task")
        val task = api.getPlanTask(taskId) ?: error("Linked task is inaccessible")
        val interfaceReview = if (policy.requireInterfaceReview) WorkOpsPlanReview.interfaceReview(api, content, id) else null
        require(interfaceReview == null || checks.none { it.outcome == ValidationOutcome.PASSED } || interfaceReview.getValue("issues").jsonArray.isEmpty()) { "Review current interfaces before recording passing validation" }
        val record = ValidationRecord(
            requirementId = requirement.id.toString(), taskId = taskId.toString(), taskVersion = task.version,
            specContentHash = specDocument.fingerprint(), requirementContentHash = document.fingerprint(), checks = checks,
            interfaceReviewId = interfaceReview?.let { it.getValue("reviewId").jsonPrimitive.longOrNull },
            testQuality = testQuality, guidanceRevision = guidance.revision,
        )
        val comment = api.addSpecComment(id, WorkOpsSpecCommentInput(content = VALIDATION_PREFIX + json.encodeToString(ValidationRecord.serializer(), record) + VALIDATION_SUFFIX))
        return buildJsonObject { put("specId", id.toString()); put("commentId", comment.id); put("validation", json.encodeToJsonElement(ValidationRecord.serializer(), record)) }.toString()
    }

    private suspend fun allComments(api: WorkOpsApi, specId: Uuid): List<PlanComment> {
        val comments = mutableListOf<PlanComment>()
        do {
            val page = api.getSpecComments(specId, comments.size.toLong(), PAGE_SIZE.toLong())
            comments.addAll(page)
        } while (page.size == PAGE_SIZE)
        return comments
    }

    private fun validationRecord(content: String): ValidationRecord? {
        if (!content.startsWith(VALIDATION_PREFIX) || !content.endsWith(VALIDATION_SUFFIX)) return null
        return try {
            json.decodeFromString(ValidationRecord.serializer(), content.removePrefix(VALIDATION_PREFIX).removeSuffix(VALIDATION_SUFFIX))
                .takeIf { it.schema == "bosca.workops.validation.v1" }
        } catch (_: SerializationException) {
            null // Ordinary comments and edited evidence are not valid validation records.
        }
    }

    internal fun acceptanceCriteria(content: JsonElement): List<String> {
        val document = json.decodeFromJsonElement(Content.serializer(), content).document
        val blocks = document.content
        val start = blocks.indexOfFirst { it is HeadingNode && it.attributes.level == 2 && it.text().trim().equals("Acceptance criteria", ignoreCase = true) }
        if (start < 0) return emptyList()
        return blocks.drop(start + 1).takeWhile { it !is HeadingNode || it.attributes.level > 2 }
            .filter { it is BulletListNode || it is OrderedListNode }
            .flatMap { it.content }.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun DocumentNode.text(): String = if (this is TextNode) text else content.joinToString("") { it.text() }

    private fun PlanDocument.fingerprint(): String {
        val doc = document ?: error("Document is missing: $id")
        return bosca.workops.model.spec.ReviewDocument(doc.title, doc.content).fingerprint()
    }

    private fun <T> nullableJson(serializer: KSerializer<T>, value: T?): JsonElement =
        if (value == null) JsonNull else json.encodeToJsonElement(serializer, value)

    private fun planTaskJson(task: IWorkOpsPlanTask) = buildJsonObject {
        put("id", task.id.toString()); put("key", task.key); put("summary", task.summary)
        put("descriptionMarkdown", task.descriptionMarkdown); put("version", task.version)
        put("status", task.status.name); put("statusCategory", task.status.category.name)
        put("resolution", task.resolution?.name)
        put("transitions", JsonArray(task.transitions.map { transitionJson(it) }))
    }

    internal fun transitionJson(transition: IWorkOpsPlanTransition) = buildJsonObject {
        put("id", transition.id.toString()); put("name", transition.name); put("description", transition.description)
        put("toStatus", transition.toState.status.name); put("toStatusCategory", transition.toState.status.category.name)
    }

    private fun requirementInput(args: Map<String, JsonElement>) = json.decodeFromJsonElement(RequirementContentInput.serializer(), buildJsonObject {
        put("name", args.string("name")); put("markdownContent", args.string("markdownContent")); put("acceptanceCriteria", args.getValue("acceptanceCriteria"))
    }).also { it.validate() }

    private fun Map<String, JsonElement>.string(key: String) = optionalString(key) ?: error("Provide $key")
    private fun Map<String, JsonElement>.optionalString(key: String) = get(key)?.let { it.jsonPrimitive.contentOrNull }
    private suspend fun Map<String, JsonElement>.projectId(api: WorkOpsApi): Uuid? {
        val id = optionalString("projectId")
        if (id != null) return Uuid.parse(id)
        val key = optionalString("projectKey") ?: return null
        return api.getProjectByKey(key)?.id ?: error("Project not found")
    }

    private suspend fun Map<String, JsonElement>.specId(api: WorkOpsApi): Uuid {
        val id = optionalString("id")
        if (id != null) return Uuid.parse(id)
        val key = optionalString("key") ?: error("Provide id or key")
        return api.getSpecByKey(key)?.id ?: error("Spec not found")
    }
    private fun Map<String, JsonElement>.offset(key: String): Long = (get(key)?.let { it.jsonPrimitive.long } ?: 0).also { require(it >= 0 && it <= Int.MAX_VALUE) { "$key is out of range" } }
    private fun Map<String, JsonElement>.limit(): Int = (get("limit")?.let { it.jsonPrimitive.int } ?: 50).also { require(it in 1..PAGE_SIZE) { "limit must be between 1 and $PAGE_SIZE" } }

    internal val INSTRUCTIONS = "Load effective guides and policy with workops_plan action=instructions and the projectId or projectKey before planning or implementation. Context, review, next_action, and verification load the current project configuration. Edit guides and policy in Studio → WorkOps → Settings → Guidance."
}
