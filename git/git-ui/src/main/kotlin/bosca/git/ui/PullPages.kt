package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

internal fun pullHref(id: String, repo: String, number: Int): String =
    "/pulls/${encode(id)}?repo=${encode(repo)}&number=$number"

suspend fun pullListPage(
    repositoryId: String = "",
    baseHref: String = "",
    repositoryMetadata: GitUiRepositoryData.Git.RepositoryById? = null,
): PullListPage = coroutineScope {
    val ctx = currentRenderContext()
    val repositoriesRequest = async {
        ctx.gql.execute(GitUiRepositories, GitUiRepositories.Variables(false, null)).git.repositories
    }
    val requested = repositoryId.ifBlank { ctx.query["repo"].orEmpty() }
    val metadata = repositoryMetadata ?: if (requested.isBlank()) null
        else ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(requested)).git.repositoryById
    val allRepositories = repositoriesRequest.await()
    var repositories =
        allRepositories
            .filter { !it.deleted && !it.archived }
            .map {
                RepositorySummary(
                    it.id,
                    it.name,
                    it.description.orEmpty(),
                    it.defaultBranch,
                    it.visibility.name,
                    it.archived,
                    it.slug,
                    it.contentType?.name ?: "GENERAL",
                    it.diskSizeBytes,
                    it.updated,
                    "/repositories",
                    it.canManage,
                    it.canExecute,
                )
            }
    val repository = metadata?.id.orEmpty()
    if (metadata != null && repositories.none { it.id == metadata.id })
        repositories = repositories + repositorySummary(metadata)
    val selected = repositories.firstOrNull { it.id == repository }
    val status =
        ctx.query["status"]?.takeIf {
            it == "ALL" || GitPullRequestStatus.entries.any { v -> v.name == it }
        } ?: "OPEN"
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val filter = status.takeUnless { it == "ALL" }?.let(GitPullRequestStatus::valueOf)
    val rowsRequest = async {
        if (repository.isBlank())
            ctx.gql
                .execute(GitUiPullList, GitUiPullList.Variables(filter, offset))
                .git
                .allPullRequests
                .map {
                    PullLink(
                        it.id,
                        it.repositoryId,
                        it.number,
                        it.title,
                        it.status.name,
                        it.sourceBranch,
                        it.targetBranch,
                        it.updated,
                    )
                }
        else
            ctx.gql
                .execute(
                    GitUiRepositoryPullList,
                    GitUiRepositoryPullList.Variables(repository, filter, offset),
                )
                .git
                .pullRequests
                .map {
                    PullLink(
                        it.id,
                        it.repositoryId,
                        it.number,
                        it.title,
                        it.status.name,
                        it.sourceBranch,
                        it.targetBranch,
                        it.updated,
                    )
                }
    }
    val sourceRepository =
        ctx.query["sourceRepo"]?.takeIf { id -> repositories.any { it.id == id } } ?: repository
    val branchesRequest = async {
        if (sourceRepository.isBlank()) emptyList()
        else
            ctx.gql
                .execute(GitUiBranches, GitUiBranches.Variables(sourceRepository))
                .git
                .branches
                .map { it.name }
    }
    val targetBranchesRequest = async {
        if (sourceRepository == repository) branchesRequest.await()
        else if (repository.isNotBlank())
            ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(repository)).git.branches.map { it.name }
        else emptyList()
    }
    PullListPage(
        repositories,
        repository,
        status,
        offset,
        rowsRequest.await(),
        ctx.query["head"].orEmpty(),
        ctx.query["base"] ?: selected?.defaultBranch ?: "main",
        sourceRepository,
        branchesRequest.await(),
        ctx.query["new"] == "true",
        metadata?.canEdit == true && !metadata.archived,
        targetBranches = targetBranchesRequest.await(),
        baseHref = baseHref,
        repositoryNames = allRepositories.filterNot { it.deleted }.associate { it.id to it.name },
        deleteBranchOnMerge = metadata?.configuration?.deleteBranchOnMerge ?: true,
    )
}

suspend fun pullPage(): PullPage? = supervisorScope {
    val ctx = currentRenderContext()
    val id = ctx.params["id"] ?: return@supervisorScope null.also { ctx.notFound = true }
    val pull =
        ctx.gql
            .execute(GitUiPull, GitUiPull.Variables(id))
            .git
            .pullRequest
            ?.takeIf { it.id == id } ?: return@supervisorScope null.also { ctx.notFound = true }
    val repository = pull.repositoryId
    val number = pull.number
    val view = ctx.query["view"]?.takeIf { it in setOf("overview", "files", "commits", "reviews") } ?: "overview"
    val merged = pull.status == GitPullRequestStatus.MERGED
    val sourceId = if (merged) repository else pull.sourceRepositoryId ?: repository
    val repositoryRequest = async {
        ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(sourceId)).git.repositoryById
    }
    val profilesRequest = async { ctx.gql.execute(GitUiProfiles, Unit).profiles.current.orEmpty() }
    val candidatesRequest = async {
        if (view == "overview") ctx.gql.execute(GitUiPullList, GitUiPullList.Variables(null, 0)).git.allPullRequests
        else emptyList()
    }
    val repositoriesRequest = async {
        if (view == "overview") ctx.gql.execute(GitUiRepositories, GitUiRepositories.Variables(false, null)).git.repositories
            .filterNot { it.deleted }.associate { it.id to it.name }
        else emptyMap()
    }
    val identitiesRequest = async {
        val ownProfiles = profilesRequest.await().associate { it.id to OwnerOption(it.id, it.name, it.slug.orEmpty()).label }
        (listOf(pull.authorId) + pull.reviews.map { it.reviewerId } +
            pull.reviews.flatMap { it.comments.map { comment -> comment.authorId } })
            .distinct().loadConcurrently { profileId ->
                val label = ownProfiles[profileId] ?: ctx.gql.execute(GitUiProfile, GitUiProfile.Variables(profileId))
                    .profiles.profile?.let { OwnerOption(it.id, it.name, it.slug.orEmpty()).label }
                label?.let { profileId to it }
            }.filterNotNull().toMap()
    }
    val refsRequest = async {
        if (merged) null
        else ctx.gql.execute(
            GitUiPullHeads,
            GitUiPullHeads.Variables(repository, sourceId, "refs/heads/${pull.sourceBranch}", "refs/heads/${pull.targetBranch}"),
        ).git
    }
    val repo = repositoryRequest.await() ?: run {
        listOf(profilesRequest, candidatesRequest, repositoriesRequest, identitiesRequest, refsRequest).forEach { it.cancel() }
        return@supervisorScope null.also { ctx.notFound = true }
    }
    val targetRepositoryRequest = async {
        if (repo.id == repository) repo
        else ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(repository)).git.repositoryById
    }
    val sourceNameRequest = async {
        if (merged && pull.sourceRepositoryId != null && pull.sourceRepositoryId != repository)
            ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(pull.sourceRepositoryId.orEmpty()))
                .git.repositoryById?.name.orEmpty()
        else repo.name
    }
    val refs = refsRequest.await()
    val sha = if (merged) pull.mergeSha.orEmpty()
        else refs?.sourceHead?.firstOrNull()?.sha.orEmpty()
    val checksRequest = async {
        if (view != "overview" || sha.isBlank()) emptyList()
        else ctx.gql.execute(GitUiStatuses, GitUiStatuses.Variables(repo.id, sha)).git.commitStatuses
    }
    val diffUnavailable = when {
        merged && sha.isBlank() -> "The completed merge commit is not recorded."
        merged && pull.mergeBaseSha.isNullOrBlank() -> "The original target commit for this merge is not recorded."
        merged -> ""
        sha.isBlank() -> "Source branch '${pull.sourceBranch}' is no longer available."
        refs?.targetHead.isNullOrEmpty() -> "Target branch '${pull.targetBranch}' is no longer available."
        else -> ""
    }
    val selectedSha = ctx.query["commit"]?.takeIf { view == "files" && it.isNotBlank() }
    val commitOffset = ctx.query["commitOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
    val commits = if (view == "commits" && diffUnavailable.isBlank()) {
        val result = ctx.gql.execute(GitUiPullCommits, GitUiPullCommits.Variables(id, commitOffset))
            .git.pullRequestById ?: error("Pull request unavailable.")
        require(result.version == pull.version) { "Pull request changed. Reload it before continuing." }
        result.commits
    } else emptyList()
    val diff = if (view == "files" && diffUnavailable.isBlank()) {
        val comparison = ctx.gql.execute(GitUiPullDiffPage, GitUiPullDiffPage.Variables(repository, number, 0, selectedSha))
            .git.pullRequest ?: error("Pull request unavailable.")
        require(comparison.version == pull.version) { "Pull request changed. Reload it before continuing." }
        comparison.diff.map { it.view() }
    } else emptyList()
    val selectedCommit = if (selectedSha != null && diffUnavailable.isBlank())
        ctx.gql.execute(GitUiCommit, GitUiCommit.Variables(repository, selectedSha)).git.commit
            ?: error("Commit unavailable.")
        else null
    val ownProfiles = profilesRequest.await().map { it.id }
    val identities = identitiesRequest.await()
    val candidateResults = candidatesRequest.await()
    val candidates =
        candidateResults
            .filter { it.id != pull.id }
            .map {
                PullLink(
                    it.id,
                    it.repositoryId,
                    it.number,
                    it.title,
                    it.status.name,
                    it.sourceBranch,
                    it.targetBranch,
                    it.updated,
                )
            }
    val checks = checksRequest.await()
    val targetRepository = targetRepositoryRequest.await()
    PullPage(
        pull,
        pullHref(pull.id, repository, number),
        repo.cloneUrl,
        sha,
        ownProfiles,
        repositoryMarkdown(
            pull.description.orEmpty(),
            repo.id,
            sha,
            "README.md",
            repo.cloneUrl,
            "/repositories",
        ),
        checks,
        candidates,
        identities,
        targetRepository?.let { it.canEdit && !it.archived } == true,
        candidateResults.size,
        view,
        ctx.query["review"] == "true",
        targetRepository?.name.orEmpty(),
        sourceNameRequest.await(),
        repositoriesRequest.await(),
        diff,
        diffUnavailable,
        commits = commits.take(25),
        commitOffset = commitOffset,
        hasMoreCommits = commits.size > 25,
        selectedCommit = selectedCommit,
    ).withDiscussionContext(ctx.gql)
}
