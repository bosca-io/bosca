package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException

internal fun runHref(id: String): String = "/pipelines/${encode(id)}"

internal fun activeRun(status: GitPipelineRunStatus): Boolean =
    status == GitPipelineRunStatus.QUEUED || status == GitPipelineRunStatus.RUNNING

/** Loads the catalog, run history and manual input definitions from the selected repository. */
suspend fun pipelinePage(repositoryId: String = "", baseHref: String = ""): PipelinePage {
    val ctx = currentRenderContext()
    val repo = repositoryId.ifBlank { ctx.query["repo"].orEmpty() }
    val list =
        if (repo.isBlank() || baseHref.isBlank()) repositoryList()
        else
            RepositoryListModel(
                RepositoryState.ACTIVE,
                OwnerOption("all", "All accessible repositories", ""),
            )
    val metadata =
        if (repo.isBlank()) null
        else ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(repo)).git.repositoryById
    if (metadata != null) list.repositories = listOf(repositorySummary(metadata))
    require(repo.isBlank() || metadata != null) { "Repository unavailable." }
    val refs =
        if (repo.isBlank()) emptyList()
        else
            ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(repo)).git.branches.map {
                "refs/heads/${it.name}"
            } +
                ctx.gql.execute(GitUiTags, GitUiTags.Variables(repo)).git.tags.map {
                    "refs/tags/${it.name}"
                }
    val pipelines = mutableListOf<GitUiPipelinesData.Git.Pipelines>()
    val executable = mutableListOf<String>()
    val failures = mutableListOf<String>()
    for (repository in list.repositories.filter { repo.isBlank() || it.id == repo }) {
        try {
            pipelines.addAll(
                ctx.gql
                    .execute(GitUiPipelines, GitUiPipelines.Variables(repository.id))
                    .git
                    .pipelines
            )
            val capability =
                if (metadata?.id == repository.id) metadata.canExecute
                else
                    ctx.gql
                        .execute(GitUiRepository, GitUiRepository.Variables(repository.id))
                        .git
                        .repositoryById
                        ?.canExecute == true
            if (capability) executable.add(repository.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add("${repository.name}: ${e.message ?: "Could not load pipelines."}")
        }
    }
    val selected = ctx.query["pipeline"]?.takeIf { id -> pipelines.any { it.id == id } }.orEmpty()
    val ref =
        ctx.query["runRef"]?.takeIf { it in refs }
            ?: ctx.query["ref"]?.let { requested ->
                refs.firstOrNull {
                    it == requested || it == "refs/heads/$requested" || it == "refs/tags/$requested"
                }
            }
            ?: metadata?.defaultBranch?.let { "refs/heads/$it" }.orEmpty()
    val inputs =
        if (selected.isBlank() || ref.isBlank()) emptyList()
        else
            ctx.gql
                .execute(GitUiPipelineInputs, GitUiPipelineInputs.Variables(selected, ref))
                .git
                .pipelineInputs
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val runs =
        if (repo.isBlank()) emptyList()
        else
            ctx.gql
                .execute(GitUiPipelineRuns, GitUiPipelineRuns.Variables(repo, offset))
                .git
                .pipelineRuns
    return PipelinePage(
        list.repositories,
        repo,
        pipelines,
        runs,
        offset,
        metadata?.canExecute == true,
        refs,
        selected,
        ref,
        inputs,
        executable,
        failures,
        list.owner,
        baseHref,
    )
}

/** Loads the run and its repository capability before rendering controls. */
suspend fun pipelineRunPage(): PipelineRunModel? {
    val ctx = currentRenderContext()
    val run =
        ctx.gql.execute(GitUiRun, GitUiRun.Variables(ctx.params["id"].orEmpty())).git.pipelineRun
            ?: return null.also { ctx.notFound = true }
    val repository =
        ctx.gql
            .execute(GitUiRepository, GitUiRepository.Variables(run.repositoryId))
            .git
            .repositoryById ?: return null.also { ctx.notFound = true }
    val pipelineName = ctx.gql.execute(GitUiPipelineNames, GitUiPipelineNames.Variables(run.repositoryId))
        .git.pipelines.firstOrNull { it.id == run.pipelineId }?.name.orEmpty()
    return PipelineRunModel(
        run, repository.canExecute, repository.canEdit, repository.canManage,
        repositoryName = repository.name,
        pipelineName = pipelineName,
    ).also { it.expandFailure() }
}

internal fun pipelineDuration(seconds: Long?): String =
    seconds?.let {
        when {
            it >= 3600 -> "${it / 3600}h ${(it % 3600) / 60}m"
            it >= 60 -> "${it / 60}m ${it % 60}s"
            else -> "${it}s"
        }
    } ?: "—"
