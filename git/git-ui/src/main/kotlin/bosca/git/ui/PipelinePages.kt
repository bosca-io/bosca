package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal fun runHref(id: String): String = "/pipelines/${encode(id)}"

internal fun activeRun(status: GitPipelineRunStatus): Boolean =
    status == GitPipelineRunStatus.QUEUED || status == GitPipelineRunStatus.RUNNING

/** Loads the catalog, run history and manual input definitions from the selected repository. */
suspend fun pipelinePage(
    repositoryId: String = "",
    baseHref: String = "",
    repository: GitUiRepositoryData.Git.RepositoryById? = null,
    repositoryRequest: Deferred<GitUiRepositoryData.Git.RepositoryById?>? = null,
): PipelinePage = coroutineScope {
    val ctx = currentRenderContext()
    val repo = repositoryId.ifBlank { ctx.query["repo"].orEmpty() }
    val listRequest = async {
        if (repo.isBlank() || baseHref.isBlank()) repositoryList()
        else RepositoryListModel(RepositoryState.ACTIVE, OwnerOption("all", "All accessible repositories", ""))
    }
    val metadataRequest = async {
        when {
            repository != null -> repository
            repositoryRequest != null -> repositoryRequest.await()
            repo.isBlank() -> null
            else -> ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(repo)).git.repositoryById
        }
    }
    val catalogRequest = if (repo.isBlank()) null else async {
        try {
            Result.success(ctx.gql.execute(GitUiPipelines, GitUiPipelines.Variables(repo, if (baseHref.isBlank()) 1 else 10, baseHref.isNotBlank())).git.pipelines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure<List<GitUiPipelinesData.Git.Pipelines>>(e)
        }
    }
    val refsRequest = async {
        if (repo.isBlank()) emptyList()
        else {
            val refs = ctx.gql.execute(GitUiRefs, GitUiRefs.Variables(repo)).git
            refs.branches.map { "refs/heads/${it.name}" } + refs.tags.map { "refs/tags/${it.name}" }
        }
    }
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val runsRequest = async {
        if (repo.isBlank()) emptyList()
        else ctx.gql.execute(GitUiPipelineRuns, GitUiPipelineRuns.Variables(repo, offset)).git.pipelineRuns
    }
    val metadata = metadataRequest.await()
    require(repo.isBlank() || metadata != null) { "Repository unavailable." }
    val list = listRequest.await()
    if (metadata != null) list.repositories = listOf(repositorySummary(metadata))
    val catalogs = list.repositories.filter { repo.isBlank() || it.id == repo }.loadConcurrently { item ->
        try {
            Triple(
                catalogRequest?.await()?.getOrThrow()
                    ?: ctx.gql.execute(GitUiPipelines, GitUiPipelines.Variables(item.id, 1, false)).git.pipelines,
                item.id.takeIf { item.canExecute },
                null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple(emptyList(), null, "${item.name}: ${e.message ?: "Could not load pipelines."}")
        }
    }
    val pipelines = catalogs.flatMap { it.first }
    val refs = refsRequest.await()
    val selected = ctx.query["pipeline"]?.takeIf { id -> pipelines.any { it.id == id } }.orEmpty()
    val ref = ctx.query["runRef"]?.takeIf { it in refs }
        ?: ctx.query["ref"]?.let { requested ->
            refs.firstOrNull { it == requested || it == "refs/heads/$requested" || it == "refs/tags/$requested" }
        }
        ?: metadata?.defaultBranch?.let { "refs/heads/$it" }.orEmpty()
    val inputs = if (selected.isBlank() || ref.isBlank()) emptyList()
        else ctx.gql.execute(GitUiPipelineInputs, GitUiPipelineInputs.Variables(selected, ref)).git.pipelineInputs
    PipelinePage(
        list.repositories, repo, pipelines, runsRequest.await(), offset, metadata?.canExecute == true,
        refs, selected, ref, inputs, catalogs.mapNotNull { it.second }, catalogs.mapNotNull { it.third },
        list.owner, baseHref,
    )
}

/** Loads the run and its repository capability before rendering controls. */
suspend fun pipelineRunPage(): PipelineRunModel? = coroutineScope {
    val ctx = currentRenderContext()
    val run =
        ctx.gql.execute(GitUiRun, GitUiRun.Variables(ctx.params["id"].orEmpty())).git.pipelineRun
            ?: return@coroutineScope null.also { ctx.notFound = true }
    val nameRequest = async {
        ctx.gql.execute(GitUiPipelineNames, GitUiPipelineNames.Variables(run.repositoryId))
            .git.pipelines.firstOrNull { it.id == run.pipelineId }?.name.orEmpty()
    }
    val repository =
        ctx.gql
            .execute(GitUiRepository, GitUiRepository.Variables(run.repositoryId))
            .git
            .repositoryById ?: return@coroutineScope null.also { ctx.notFound = true }
    PipelineRunModel(
        run, repository.canExecute, repository.canEdit, repository.canManage,
        repositoryName = repository.name,
        pipelineName = nameRequest.await(),
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
