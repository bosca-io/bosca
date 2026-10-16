package bosca.git.service

import bosca.git.model.GitHubCommitStatus
import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineRunStatus
import bosca.serialization.UUID

/** Builds commit statuses from persisted selections and explicit rerun lineage. */
internal class GitHubCiStatuses(
    private val runs: PipelineRunService,
    private val pipelines: PipelineService,
) {
    suspend fun forRun(event: PipelineEvent, includeGate: Boolean = true): Pair<String, List<GitHubCommitStatus>>? {
        val original = runs.findById(event.pipelineRunId) ?: return null
        require(original.repositoryId == event.repositoryId && original.pipelineId == event.pipelineId) {
            "CI status event does not match its pipeline run"
        }
        val pipeline = pipelines.findById(original.pipelineId) ?: return null
        require(pipeline.repositoryId == event.repositoryId) { "CI pipeline does not belong to the paired repository" }
        val rootId = original.rerunOf
        val root = if (rootId == null) original else runs.findById(rootId) ?: return null
        val current = runs.findLatestAttempt(root.id) ?: return null
        val status = GitHubCommitStatus(
            state = state(current.status),
            context = "bosca/run/${pipeline.filePath.removePrefix(".bosca/pipelines/")}/${root.triggerType.name.lowercase()}/${root.number}",
            description = "Run #${current.number}: ${current.status.name.lowercase()}",
        )
        return original.commitSha to (listOf(status) + if (includeGate) forCommit(event.repositoryId, original.commitSha) else emptyList())
    }

    suspend fun forCommit(repositoryId: UUID, commitSha: String): List<GitHubCommitStatus> {
        val evaluations = pipelines.findEvaluationsByCommit(repositoryId, commitSha)
            .filter { it.ref.startsWith("refs/heads/") }
        if (evaluations.isEmpty()) return emptyList()
        // A fresh evaluation can recover an earlier selection/authorization error on the same ref.
        // Equivalent pushes on the same ref replace earlier work; a skip or another ref cannot.
        val decisions = evaluations.groupBy { it.ref }.values.map { it.last() }
        val byFile = evaluations.groupBy { it.ref }.values.flatMap { occurrences ->
            occurrences.flatMap { it.selections }.groupBy { it.filePath }.values.map { selections ->
                selections.lastOrNull { it.runId != null } ?: selections.last()
            }
        }.groupBy({ it.filePath }, { it.runId })
        val files = mutableListOf<GitHubCommitStatus>()
        var selected = 0
        var cancelled = false
        for ((file, ids) in byFile.toSortedMap()) {
            val required = ids.filterNotNull().distinct()
            if (required.isNotEmpty()) selected++
            val states = required.map { id ->
                val run = runs.findLatestAttempt(id)
                if (run == null) "error" else {
                    require(run.repositoryId == repositoryId && run.commitSha == commitSha) {
                        "CI evaluation does not match its selected run"
                    }
                    if (run.status == PipelineRunStatus.CANCELLED) cancelled = true
                    state(run.status)
                }
            }
            val result = combine(states)
            files += GitHubCommitStatus(
                state = result,
                context = "bosca/ci/${file.removePrefix(".bosca/pipelines/")}",
                description = if (required.isEmpty()) "Not applicable to this push" else "Applicable CI: $result",
            )
        }
        val result = combine(files.map { it.state } + decisions.map {
            when {
                it.error != null -> "error"
                !it.completed -> "pending"
                else -> "success"
            }
        })
        val description = when (result) {
            "error" -> "CI evaluation or execution could not complete"
            "failure" -> if (cancelled) "Required CI was cancelled before completion" else "Applicable CI failed"
            "pending" -> "Evaluating or running applicable CI"
            else -> if (selected == 0) "No CI pipelines apply to this commit" else "All applicable CI passed ($selected pipelines)"
        }
        // Until selection is complete, even a file already evaluated on another ref is not a pass.
        val selectionState = combine(decisions.map { if (it.error != null) "error" else if (!it.completed) "pending" else "success" })
        return listOf(GitHubCommitStatus(result, "bosca/ci", description)) + files.map {
            it.copy(state = combine(listOf(it.state, selectionState)))
        }
    }

    private fun combine(states: List<String>): String = when {
        "error" in states -> "error"
        "failure" in states -> "failure"
        "pending" in states -> "pending"
        else -> "success"
    }

    private fun state(status: PipelineRunStatus): String = when (status) {
        PipelineRunStatus.QUEUED, PipelineRunStatus.RUNNING -> "pending"
        PipelineRunStatus.SUCCESS -> "success"
        PipelineRunStatus.FAILURE -> "failure"
        PipelineRunStatus.CANCELLED -> "failure"
        PipelineRunStatus.SKIPPED -> "success"
    }
}
