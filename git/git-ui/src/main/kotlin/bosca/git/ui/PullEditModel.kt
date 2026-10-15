package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Serializable
class PullEditModel(
    val page: PullPage,
    var title: String = page.pull.title,
    var description: String = page.pull.description.orEmpty(),
    var source: String = page.pull.sourceBranch,
    var target: String = page.pull.targetBranch,
    var sourceBranches: List<String> = emptyList(),
    var targetBranches: List<String> = emptyList(),
    var editing: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    val canChangeBranches: Boolean
        get() = page.pull.status.name in setOf("OPEN", "DRAFT")

    suspend fun open(ctx: RenderContext) {
        title = page.pull.title
        description = page.pull.description.orEmpty()
        source = page.pull.sourceBranch
        target = page.pull.targetBranch
        editing = true
        perform {
            require(page.canEdit) { "Repository edit access is required." }
            if (canChangeBranches) {
                coroutineScope {
                    val targetRequest = async { branches(page.pull.repositoryId, ctx) }
                    val sourceRequest = async {
                        if (page.sourceRepository == page.pull.repositoryId) targetRequest.await()
                        else branches(page.sourceRepository, ctx)
                    }
                    targetBranches = targetRequest.await()
                    sourceBranches = sourceRequest.await()
                }
            }
        }
    }

    suspend fun save(
        title: String,
        description: String,
        source: String,
        target: String,
        ctx: RenderContext,
    ) {
        this.title = title
        this.description = description
        this.source = source
        this.target = target
        editing = true
        perform {
            val repository =
                ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(page.pull.repositoryId))
                    .git.repositoryById ?: error("Repository unavailable.")
            require(repository.canEdit && !repository.archived) { "Repository edit access is required." }
            require(title.isNotBlank()) { "Enter a pull request title." }
            require(source.isNotBlank() && target.isNotBlank()) { "Choose source and target branches." }
            ctx.gql.execute(
                GitUiUpdatePull,
                GitUiUpdatePull.Variables(
                    page.pull.id,
                    UpdateGitPullRequestInput(
                        title = title.trim(),
                        description = description,
                        sourceBranch = source,
                        targetBranch = target,
                        expectedVersion = page.pull.version,
                    ),
                ),
            )
            nextHref = page.href + "&view=" + page.view
        }
    }

    private suspend fun branches(repository: String, ctx: RenderContext): List<String> =
        ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(repository)).git.branches.map { it.name }

    private suspend fun perform(block: suspend () -> Unit) {
        failed = false
        message = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not update pull request."
        }
    }
}
