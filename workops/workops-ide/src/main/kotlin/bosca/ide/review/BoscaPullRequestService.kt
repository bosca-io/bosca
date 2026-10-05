package bosca.ide.review

import bosca.ide.server.BoscaConnectionManager
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/** Pull-request, review, diff, and merge operations scoped by Bosca server and repository. */
@Service(Service.Level.PROJECT)
class BoscaPullRequestService(project: Project) {
    private val connections = project.getService(BoscaConnectionManager::class.java)

    fun list(serverId: String, repositoryId: String, status: String?): CompletableFuture<List<BoscaPullRequestSummary>> =
        connections.execute(serverId, LIST_QUERY, variables("repositoryId" to repositoryId, "status" to status))
            .thenApply { data -> data.getAsJsonObject("git").getAsJsonArray("pullRequests").map {
                parseSummary(serverId, repositoryId, it.asJsonObject)
            } }

    fun detail(serverId: String, repositoryId: String, number: Int): CompletableFuture<BoscaPullRequestDetail> =
        connections.execute(serverId, DETAIL_QUERY, variables("repositoryId" to repositoryId, "number" to number))
            .thenCompose { data ->
                val git = data.getAsJsonObject("git")
                val pr = git.getAsJsonObject("pullRequest")
                    ?: throw IllegalArgumentException("Pull request #$number no longer exists")
                val headSha = git.getAsJsonArray("branches")
                    .map { it.asJsonObject }
                    .firstOrNull { it.string("name") == pr.string("sourceBranch") }
                    ?.string("sha")
                val partial = parseDetail(serverId, repositoryId, pr, git, headSha, emptyList())
                if (headSha == null) CompletableFuture.completedFuture(partial)
                else connections.execute(
                    serverId,
                    CHECKS_QUERY,
                    variables("repositoryId" to repositoryId, "sha" to headSha),
                ).thenApply { checks ->
                    val values = checks.getAsJsonObject("git").getAsJsonArray("commitStatuses").map { value ->
                        value.asJsonObject.let { BoscaCommitCheck(it.string("context"), it.string("state"), it.nullableString("description")) }
                    }
                    partial.copy(checks = values)
                }
            }

    fun fileContents(detail: BoscaPullRequestDetail, file: BoscaDiffFile): CompletableFuture<BoscaDiffContents> {
        val oldPath = file.oldPath ?: file.newPath.orEmpty()
        val newPath = file.newPath ?: file.oldPath.orEmpty()
        return connections.execute(
            detail.summary.serverProfileId,
            FILE_CONTENTS_QUERY,
            variables(
                "repositoryId" to detail.summary.repositoryId,
                "base" to detail.summary.targetBranch,
                "head" to detail.summary.sourceBranch,
                "oldPath" to oldPath,
                "newPath" to newPath,
            ),
        ).thenApply { data ->
            val git = data.getAsJsonObject("git")
            val before = git.getAsJsonObject("before")
            val after = git.getAsJsonObject("after")
            BoscaDiffContents(
                file = file,
                oldText = if (file.changeType == "ADD") "" else before?.nullableString("content").orEmpty(),
                newText = if (file.changeType == "DELETE") "" else after?.nullableString("content").orEmpty(),
                binary = before?.get("isBinary")?.asBoolean == true || after?.get("isBinary")?.asBoolean == true,
            )
        }
    }

    fun allFileContents(detail: BoscaPullRequestDetail): CompletableFuture<List<BoscaDiffContents>> {
        val futures = detail.files.map { fileContents(detail, it) }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map { it.join() } }
    }

    fun update(serverId: String, id: String, title: String, description: String) = mutation(
        serverId,
        UPDATE_MUTATION,
        variables("id" to id, "input" to JsonObject().apply {
            addProperty("title", title)
            addProperty("description", description)
        }),
    )

    fun assign(serverId: String, id: String, profileId: String) = mutation(
        serverId, ASSIGN_MUTATION, variables("id" to id, "profileId" to profileId)
    )

    fun unassign(serverId: String, id: String, profileId: String) = mutation(
        serverId, UNASSIGN_MUTATION, variables("id" to id, "profileId" to profileId)
    )

    fun markReady(serverId: String, id: String) = mutation(serverId, READY_MUTATION, variables("id" to id))
    fun close(serverId: String, id: String) = mutation(serverId, CLOSE_MUTATION, variables("id" to id))
    fun reopen(serverId: String, id: String) = mutation(serverId, REOPEN_MUTATION, variables("id" to id))
    fun merge(serverId: String, id: String, strategy: String, dependencies: Boolean) = mutation(
        serverId,
        if (dependencies) MERGE_DEPENDENCIES_MUTATION else MERGE_MUTATION,
        variables("id" to id, "strategy" to strategy),
    )

    fun submitReview(serverId: String, pullRequestId: String, status: String, body: String?): CompletableFuture<BoscaReview> =
        connections.execute(serverId, SUBMIT_REVIEW_MUTATION, variables("input" to JsonObject().apply {
            addProperty("pullRequestId", pullRequestId)
            addProperty("status", status)
            body?.let { addProperty("body", it) }
        })).thenApply { data -> parseReview(data.getAsJsonObject("git").getAsJsonObject("submitReview")) }

    fun addReviewComment(
        serverId: String,
        pullRequestId: String,
        reviewId: String,
        filePath: String,
        oldLine: Int?,
        newLine: Int?,
        commitSha: String,
        content: String,
    ) = mutation(serverId, ADD_COMMENT_MUTATION, variables("input" to JsonObject().apply {
        addProperty("pullRequestId", pullRequestId)
        addProperty("reviewId", reviewId)
        addProperty("filePath", filePath)
        oldLine?.let { addProperty("oldLineNumber", it) }
        newLine?.let { addProperty("newLineNumber", it) }
        addProperty("commitSha", commitSha)
        addProperty("content", content)
    }))

    fun resolveThread(serverId: String, pullRequestId: String, filePath: String, line: Int) = mutation(
        serverId,
        RESOLVE_THREAD_MUTATION,
        variables("id" to pullRequestId, "path" to filePath, "line" to line),
    )

    private fun mutation(serverId: String, query: String, variables: JsonObject): CompletableFuture<Boolean> =
        connections.execute(serverId, query, variables).thenApply { true }

    private fun parseDetail(
        serverId: String,
        repositoryId: String,
        pr: JsonObject,
        git: JsonObject,
        headSha: String?,
        checks: List<BoscaCommitCheck>,
    ) = BoscaPullRequestDetail(
        summary = parseSummary(serverId, repositoryId, pr),
        description = pr.nullableString("description").orEmpty(),
        assignees = pr.getAsJsonArray("assignees").map { value ->
            value.asJsonObject.let { BoscaPullRequestAssignee(it.string("id"), it.string("name")) }
        },
        dependencies = pr.getAsJsonArray("dependencies").map { "#${it.asJsonObject.get("number").asInt} ${it.asJsonObject.string("title")}" },
        dependents = pr.getAsJsonArray("dependents").map { "#${it.asJsonObject.get("number").asInt} ${it.asJsonObject.string("title")}" },
        conflictingFiles = pr.getAsJsonArray("conflictingFiles").map { it.asString },
        reviews = pr.getAsJsonArray("reviews").map { parseReview(it.asJsonObject) },
        files = pr.getAsJsonArray("diff").map { parseFile(it.asJsonObject) },
        headSha = headSha,
        checks = checks,
        pipelineRuns = git.getAsJsonArray("pipelineRuns").map { it.asJsonObject }
            .filter { headSha != null && it.string("commitSha") == headSha }
            .map { BoscaPullRequestPipelineRun(it.get("number").asInt, it.string("status"), it.string("ref")) },
        mergeStrategies = git.getAsJsonObject("repositoryById").getAsJsonObject("configuration")
            .getAsJsonArray("mergeStrategies").map { it.asString },
    )

    private fun parseSummary(serverId: String, repositoryId: String, json: JsonObject) = BoscaPullRequestSummary(
        serverProfileId = serverId,
        repositoryId = repositoryId,
        number = json.get("number").asInt,
        id = json.string("id"),
        title = json.string("title"),
        status = json.string("status"),
        authorId = json.string("authorId"),
        sourceBranch = json.string("sourceBranch"),
        targetBranch = json.string("targetBranch"),
        mergeable = json.get("mergeable").asBoolean,
        updated = json.string("updated"),
    )

    private fun parseReview(json: JsonObject) = BoscaReview(
        id = json.string("id"),
        reviewerId = json.string("reviewerId"),
        status = json.string("status"),
        body = json.nullableString("body"),
        created = json.string("created"),
        dismissedAt = json.nullableString("dismissedAt"),
        comments = json.getAsJsonArray("comments")?.map { parseComment(it.asJsonObject) }.orEmpty(),
    )

    private fun parseComment(json: JsonObject) = BoscaReviewComment(
        id = json.string("id"),
        reviewId = json.string("reviewId"),
        authorId = json.string("authorId"),
        filePath = json.string("filePath"),
        oldLineNumber = json.nullableInt("oldLineNumber"),
        newLineNumber = json.nullableInt("newLineNumber"),
        commitSha = json.string("commitSha"),
        content = json.string("content"),
        outdated = json.get("outdated").asBoolean,
        resolved = json.get("resolved").asBoolean,
        created = json.string("created"),
    )

    private fun parseFile(json: JsonObject) = BoscaDiffFile(
        oldPath = json.nullableString("oldPath"),
        newPath = json.nullableString("newPath"),
        changeType = json.string("changeType"),
        hunks = json.getAsJsonArray("hunks").map { value ->
            value.asJsonObject.let { hunk ->
                BoscaDiffHunk(
                    oldStart = hunk.get("oldStart").asInt,
                    oldCount = hunk.get("oldCount").asInt,
                    newStart = hunk.get("newStart").asInt,
                    newCount = hunk.get("newCount").asInt,
                    lines = hunk.getAsJsonArray("lines").map { line ->
                        line.asJsonObject.let {
                            BoscaDiffLine(it.string("type"), it.nullableInt("oldLineNumber"), it.nullableInt("newLineNumber"), it.string("content"))
                        }
                    },
                )
            }
        },
    )

    private fun variables(vararg values: Pair<String, Any?>) = JsonObject().apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> add(key, JsonNull.INSTANCE)
                is JsonElement -> add(key, value)
                is Int -> addProperty(key, value)
                is Long -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }

    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.nullableInt(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt

    companion object {
        private const val SUMMARY = "id number title status authorId sourceBranch targetBranch mergeable updated"
        private const val REVIEW = "id reviewerId status body created dismissedAt comments { id reviewId authorId filePath oldLineNumber newLineNumber commitSha content outdated resolved created }"
        private const val DIFF = "oldPath newPath changeType hunks { oldStart oldCount newStart newCount lines(offset: 0, limit: 10000) { type oldLineNumber newLineNumber content } }"
        private const val LIST_QUERY = """
            query BoscaIdePullRequests(${ '$' }repositoryId: UUID!, ${ '$' }status: GitPullRequestStatus) {
              git { pullRequests(repositoryId: ${ '$' }repositoryId, status: ${ '$' }status, offset: 0, limit: 200) { $SUMMARY } }
            }
        """
        private const val DETAIL_QUERY = """
            query BoscaIdePullRequest(${ '$' }repositoryId: UUID!, ${ '$' }number: Int!) {
              git {
                pullRequest(repositoryId: ${ '$' }repositoryId, number: ${ '$' }number) {
                  $SUMMARY description conflictingFiles
                  assignees { id name }
                  dependencies { number title }
                  dependents { number title }
                  reviews { $REVIEW }
                  diff { $DIFF }
                }
                branches(repositoryId: ${ '$' }repositoryId) { name sha }
                repositoryById(id: ${ '$' }repositoryId) { configuration { mergeStrategies } }
                pipelineRuns(repositoryId: ${ '$' }repositoryId, offset: 0, limit: 100) { number status ref commitSha }
              }
            }
        """
        private const val CHECKS_QUERY = "query BoscaIdePrChecks(${'$'}repositoryId: UUID!, ${'$'}sha: String!) { git { commitStatuses(repositoryId: ${'$'}repositoryId, commitSha: ${'$'}sha) { context state description } } }"
        private const val FILE_CONTENTS_QUERY = """
            query BoscaIdePrFileContents(
              ${ '$' }repositoryId: UUID!,
              ${ '$' }base: String!,
              ${ '$' }head: String!,
              ${ '$' }oldPath: String!,
              ${ '$' }newPath: String!
            ) {
              git {
                before: blob(repositoryId: ${ '$' }repositoryId, ref: ${ '$' }base, path: ${ '$' }oldPath) { content isBinary }
                after: blob(repositoryId: ${ '$' }repositoryId, ref: ${ '$' }head, path: ${ '$' }newPath) { content isBinary }
              }
            }
        """
        private const val UPDATE_MUTATION = "mutation BoscaIdeUpdatePr(${'$'}id: UUID!, ${'$'}input: UpdateGitPullRequestInput!) { git { updatePullRequest(id: ${'$'}id, input: ${'$'}input) { id } } }"
        private const val ASSIGN_MUTATION = "mutation BoscaIdeAssignPr(${'$'}id: UUID!, ${'$'}profileId: UUID!) { git { assignPullRequest(id: ${'$'}id, profileId: ${'$'}profileId) { id } } }"
        private const val UNASSIGN_MUTATION = "mutation BoscaIdeUnassignPr(${'$'}id: UUID!, ${'$'}profileId: UUID!) { git { unassignPullRequest(id: ${'$'}id, profileId: ${'$'}profileId) { id } } }"
        private const val READY_MUTATION = "mutation BoscaIdeReadyPr(${'$'}id: UUID!) { git { markPullRequestReady(id: ${'$'}id) { id } } }"
        private const val CLOSE_MUTATION = "mutation BoscaIdeClosePr(${'$'}id: UUID!) { git { closePullRequest(id: ${'$'}id) { id } } }"
        private const val REOPEN_MUTATION = "mutation BoscaIdeReopenPr(${'$'}id: UUID!) { git { reopenPullRequest(id: ${'$'}id) { id } } }"
        private const val MERGE_MUTATION = "mutation BoscaIdeMergePr(${'$'}id: UUID!, ${'$'}strategy: GitMergeStrategy!) { git { mergePullRequest(id: ${'$'}id, strategy: ${'$'}strategy) { id } } }"
        private const val MERGE_DEPENDENCIES_MUTATION = "mutation BoscaIdeMergePrDependencies(${'$'}id: UUID!, ${'$'}strategy: GitMergeStrategy!) { git { mergePullRequestWithDependencies(id: ${'$'}id, strategy: ${'$'}strategy) { id } } }"
        private const val SUBMIT_REVIEW_MUTATION = "mutation BoscaIdeSubmitReview(${'$'}input: SubmitGitReviewInput!) { git { submitReview(input: ${'$'}input) { $REVIEW } } }"
        private const val ADD_COMMENT_MUTATION = "mutation BoscaIdeAddReviewComment(${'$'}input: AddGitReviewCommentInput!) { git { addReviewComment(input: ${'$'}input) { id } } }"
        private const val RESOLVE_THREAD_MUTATION = "mutation BoscaIdeResolveReviewThread(${'$'}id: UUID!, ${'$'}path: String!, ${'$'}line: Int!) { git { resolveReviewThread(pullRequestId: ${'$'}id, filePath: ${'$'}path, lineNumber: ${'$'}line) } }"
    }
}
