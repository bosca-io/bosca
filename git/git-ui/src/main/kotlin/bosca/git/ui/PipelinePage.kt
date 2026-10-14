package bosca.git.ui

import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class PipelinePage(
    val repositories: List<RepositorySummary>,
    val repository: String,
    val pipelines: List<GitUiPipelinesData.Git.Pipelines>,
    val runs: List<GitUiPipelineRunsData.Git.PipelineRuns>,
    val offset: Long,
    val canExecute: Boolean,
    val refs: List<String>,
    val selected: String,
    val ref: String,
    val inputs: List<GitUiPipelineInputsData.Git.PipelineInputs>,
    val executableRepositories: List<String>,
    val failures: List<String>,
    val owner: OwnerOption = OwnerOption("all", "All accessible repositories", ""),
    val baseHref: String = "",
) {
    val groups: List<RepositorySummary>
        get() = repositories.filter { repository ->
            pipelines.any { it.repositoryId == repository.id }
        }

    val repositoryContext: Boolean
        get() = baseHref.isNotBlank()

    val selectedName: String
        get() = pipelines.firstOrNull { it.id == selected }?.name.orEmpty()

    fun href(offset: Long = 0): String =
        if (repositoryContext) "$baseHref&offset=$offset"
        else "/pipelines?owner=${encode(owner.id)}&repo=${encode(repository)}&offset=$offset"

    fun triggerHref(repository: String, pipeline: String): String =
        if (repositoryContext) "$baseHref&pipeline=${encode(pipeline)}"
        else
            "/pipelines?owner=${encode(owner.id)}&repo=${encode(repository)}&pipeline=${encode(pipeline)}"
}
