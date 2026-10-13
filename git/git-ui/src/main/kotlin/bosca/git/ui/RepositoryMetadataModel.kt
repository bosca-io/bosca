package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryMetadataModel(
    val page: RepositorySettingsPage,
    var name: String = page.repository.name,
    var description: String = page.repository.description.orEmpty(),
    var visibility: String = page.repository.visibility.name,
    var contentType: String = page.repository.contentType?.name ?: "GENERAL",
    var branch: String = page.repository.defaultBranch,
    var slug: String = page.repository.slug,
    var renaming: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun save(
        name: String,
        description: String,
        visibility: String,
        contentType: String,
        branch: String,
        ctx: RenderContext,
    ) {
        renaming = false
        this.name = name
        this.description = description
        this.visibility = visibility
        this.contentType = contentType
        this.branch = branch
        perform {
            require(name.isNotBlank() && branch.isNotBlank()) {
                "Name and default branch are required."
            }
            ctx.gql.execute(
                GitUiUpdate,
                GitUiUpdate.Variables(
                    page.repository.id,
                    UpdateGitRepositoryInput(
                        name.trim(),
                        description,
                        GitVisibility.valueOf(visibility),
                        branch.trim(),
                        GitRepositoryContentType.valueOf(contentType),
                    ),
                ),
            )
            nextHref = page.href
        }
    }

    suspend fun rename(slug: String, confirmed: Boolean, ctx: RenderContext) {
        renaming = true
        this.slug = slug
        perform {
            require(confirmed && slug.isNotBlank()) {
                "Enter the new slug and confirm the clone URL change."
            }
            ctx.gql.execute(
                GitUiRenameRepository,
                GitUiRenameRepository.Variables(page.repository.id, slug.trim()),
            )
            nextHref = page.href
        }
    }

    private suspend fun perform(block: suspend () -> Unit) {
        failed = false
        message = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Repository update failed."
        }
    }
}
