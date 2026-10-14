package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class PullCreateModel(
    var page: PullListPage,
    var title: String = "",
    var description: String = "",
    var source: String = page.source,
    var target: String = page.target,
    var draft: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var editing: Boolean = false,
) {
    suspend fun selectRepositories(
        repository: String,
        sourceRepository: String,
        title: String,
        description: String,
        source: String,
        target: String,
        draft: Boolean,
        ctx: RenderContext,
    ) {
        retainDraft(title, description, source, target, draft)
        editing = true
        failed = false
        message = ""
        try {
            require(page.repositories.any { it.id == repository }) {
                "Choose an available repository."
            }
            val metadata =
                ctx.gql
                    .execute(GitUiRepository, GitUiRepository.Variables(repository))
                    .git
                    .repositoryById ?: error("Repository unavailable.")
            val sourceId =
                if (
                    sourceRepository.isBlank() ||
                        (repository != page.repository && sourceRepository == page.repository)
                )
                    repository
                else sourceRepository
            require(page.repositories.any { it.id == sourceId }) {
                "Choose an available source repository."
            }
            val targetBranches =
                ctx.gql
                    .execute(GitUiBranches, GitUiBranches.Variables(repository))
                    .git
                    .branches
                    .map { it.name }
            val branches =
                if (sourceId == repository) targetBranches
                else
                    ctx.gql
                        .execute(GitUiBranches, GitUiBranches.Variables(sourceId))
                        .git
                        .branches
                        .map { it.name }
            this.source = source.takeIf { it in branches }.orEmpty()
            this.target =
                target.takeIf { it in targetBranches }
                    ?: metadata.defaultBranch.takeIf { it in targetBranches }.orEmpty()
            page =
                page.copy(
                    repository = repository,
                    sourceRepository = sourceId,
                    branches = branches,
                    targetBranches = targetBranches,
                    canEdit = metadata.canEdit && !metadata.archived,
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not load repository branches."
        }
    }

    private fun retainDraft(
        title: String,
        description: String,
        source: String,
        target: String,
        draft: Boolean,
    ) {
        this.title = title
        this.description = description
        this.source = source
        this.target = target
        this.draft = draft
    }

    suspend fun create(
        title: String,
        description: String,
        source: String,
        target: String,
        draft: Boolean,
        ctx: RenderContext,
    ) {
        retainDraft(title, description, source, target, draft)
        failed = false
        message = ""
        editing = true
        try {
            require(page.repository.isNotBlank()) { "Choose a target repository first." }
            val repository =
                ctx.gql
                    .execute(GitUiRepository, GitUiRepository.Variables(page.repository))
                    .git
                    .repositoryById ?: error("Repository unavailable.")
            require(repository.canEdit && !repository.archived) {
                "Repository edit access is required to create a pull request."
            }
            require(title.isNotBlank() && source.isNotBlank() && target.isNotBlank()) {
                "Enter a title, source branch and target branch."
            }
            val pull =
                ctx.gql
                    .execute(
                        GitUiCreatePull,
                        GitUiCreatePull.Variables(
                            CreateGitPullRequestInput(
                                page.repository,
                                title.trim(),
                                description,
                                source,
                                target,
                                page.sourceRepository.takeUnless { it == page.repository },
                                draft,
                            )
                        ),
                    )
                    .git
                    .createPullRequest
            nextHref = pullHref(pull.id, pull.repositoryId, pull.number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not create pull request."
        }
    }
}
