package bosca.git.ui

import bosca.git.ui.graphql.GitUiRepositoryData

internal fun repositorySummary(repo: GitUiRepositoryData.Git.RepositoryById): RepositorySummary =
    RepositorySummary(
        repo.id,
        repo.name,
        repo.description.orEmpty(),
        repo.defaultBranch,
        repo.visibility.name,
        repo.archived,
        repo.slug,
        repo.contentType?.name ?: "GENERAL",
        repo.diskSizeBytes,
        repo.updated,
        "/repositories",
        repo.canManage,
        repo.canExecute,
    )
