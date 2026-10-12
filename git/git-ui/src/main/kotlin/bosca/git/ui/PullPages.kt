package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*

internal fun pullHref(id: String, repo: String, number: Int): String =
    "/pulls/${encode(id)}?repo=${encode(repo)}&number=$number"

suspend fun pullListPage(repositoryId: String = "", baseHref: String = ""): PullListPage {
    val ctx = currentRenderContext()
    val allRepositories =
        ctx.gql
            .execute(GitUiRepositories, GitUiRepositories.Variables(false, null))
            .git
            .repositories
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
                )
            }
    val requested = repositoryId.ifBlank { ctx.query["repo"].orEmpty() }
    val metadata =
        if (requested.isBlank()) null
        else
            ctx.gql
                .execute(GitUiRepository, GitUiRepository.Variables(requested))
                .git
                .repositoryById
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
    val rows =
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
    val sourceRepository =
        ctx.query["sourceRepo"]?.takeIf { id -> repositories.any { it.id == id } } ?: repository
    val branches =
        if (sourceRepository.isBlank()) emptyList()
        else
            ctx.gql
                .execute(GitUiBranches, GitUiBranches.Variables(sourceRepository))
                .git
                .branches
                .map { it.name }
    return PullListPage(
        repositories,
        repository,
        status,
        offset,
        rows,
        ctx.query["head"].orEmpty(),
        ctx.query["base"] ?: selected?.defaultBranch ?: "main",
        sourceRepository,
        branches,
        ctx.query["new"] == "true",
        metadata?.canEdit == true && !metadata.archived,
        targetBranches =
            if (sourceRepository == repository) branches
            else if (repository.isNotBlank())
                ctx.gql
                    .execute(GitUiBranches, GitUiBranches.Variables(repository))
                    .git
                    .branches
                    .map { it.name }
            else emptyList(),
        baseHref = baseHref,
        repositoryNames = allRepositories.filterNot { it.deleted }.associate { it.id to it.name },
    )
}

suspend fun pullPage(): PullPage? {
    val ctx = currentRenderContext()
    val repository = ctx.query["repo"] ?: return null.also { ctx.notFound = true }
    val number = ctx.query["number"]?.toIntOrNull() ?: return null.also { ctx.notFound = true }
    val pull =
        ctx.gql
            .execute(GitUiPull, GitUiPull.Variables(repository, number, 2000))
            .git
            .pullRequest
            ?.takeIf { it.id == ctx.params["id"] } ?: return null.also { ctx.notFound = true }
    val repo =
        ctx.gql
            .execute(
                GitUiRepository,
                GitUiRepository.Variables(pull.sourceRepositoryId ?: repository),
            )
            .git
            .repositoryById ?: return null.also { ctx.notFound = true }
    val sha = ctx.gql.commitShaForRef(repo.id, pull.sourceBranch).orEmpty()
    val ownProfiles = ctx.gql.execute(GitUiProfiles, Unit).profiles.current.orEmpty().map { it.id }
    val identities = mutableMapOf<String, String>()
    for (id in
        (listOf(pull.authorId) +
                pull.reviews.map { it.reviewerId } +
                pull.reviews.flatMap { it.comments.map { comment -> comment.authorId } })
            .distinct()) {
        ctx.gql.execute(GitUiProfile, GitUiProfile.Variables(id)).profiles.profile?.let {
            identities[id] = OwnerOption(it.id, it.name, it.slug.orEmpty()).label
        }
    }
    val candidateResults =
        ctx.gql.execute(GitUiPullList, GitUiPullList.Variables(null, 0)).git.allPullRequests
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
    val checks =
        if (sha.isBlank()) emptyList()
        else
            ctx.gql.execute(GitUiStatuses, GitUiStatuses.Variables(repo.id, sha)).git.commitStatuses
    val targetRepository =
        if (repo.id == repository) repo
        else ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(repository)).git.repositoryById
    return PullPage(
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
        ctx.query["view"]?.takeIf { it in setOf("overview", "files", "reviews") } ?: "overview",
        ctx.query["review"] == "true",
        targetRepository?.name.orEmpty(),
        repo.name,
        ctx.gql.execute(GitUiRepositories, GitUiRepositories.Variables(false, null))
            .git.repositories.filterNot { it.deleted }.associate { it.id to it.name },
    )
}
