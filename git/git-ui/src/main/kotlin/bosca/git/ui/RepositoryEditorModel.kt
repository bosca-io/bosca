package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryEditorModel(
    val page: RepositoryPage,
    val id: String,
    val ref: String,
    val originalPath: String,
    var draftPath: String,
    var draftContent: String,
    val listHref: String,
    var commitMessage: String = "",
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun save(path: String, content: String, commitMessage: String, ctx: RenderContext) {
        draftContent = content
        draftPath = path
        this.commitMessage = commitMessage
        act {
            val validPath = repositoryFilePath(path)
            require(commitMessage.isNotBlank()) { "Enter a commit message." }
            val author = writer(ctx)
            if (originalPath != validPath) {
                val target =
                    ctx.gql.execute(GitUiBlob, GitUiBlob.Variables(id, ref, validPath)).git.blob
                require(target == null) {
                    "A file already exists at the new path. Choose another path."
                }
            }
            ctx.gql.execute(
                GitUiCommitFile,
                GitUiCommitFile.Variables(
                    CommitFileInput(
                        id,
                        ref,
                        validPath,
                        content,
                        commitMessage.trim(),
                        author.first,
                        author.second,
                    )
                ),
            )
            if (originalPath.isNotBlank() && originalPath != validPath) {
                try {
                    ctx.gql.execute(
                        GitUiDeleteFile,
                        GitUiDeleteFile.Variables(
                            id,
                            ref,
                            originalPath,
                            author.first,
                            author.second,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "The new file was committed, but the original could not be deleted. Both copies remain. ${e.message}",
                        e,
                    )
                }
            }
            nextHref =
                repositoryHref(id, ref, validPath.substringBeforeLast('/', ""), validPath, listHref)
        }
    }

    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = act {
        require(confirmed) { "Confirm deletion." }
        val author = writer(ctx)
        val path = repositoryFilePath(originalPath)
        ctx.gql.execute(
            GitUiDeleteFile,
            GitUiDeleteFile.Variables(id, ref, path, author.first, author.second),
        )
        nextHref = repositoryHref(id, ref, path.substringBeforeLast('/', ""), "", listHref)
    }

    suspend fun folder(path: String, ctx: RenderContext) = act {
        draftPath = path
        val folder = repositoryFilePath(path.trimEnd('/'))
        val author = writer(ctx)
        ctx.gql.execute(
            GitUiCommitFile,
            GitUiCommitFile.Variables(
                CommitFileInput(
                    id,
                    ref,
                    "$folder/.gitkeep",
                    "",
                    "Add $folder/",
                    author.first,
                    author.second,
                )
            ),
        )
        nextHref = repositoryHref(id, ref, folder, "", listHref)
    }

    private suspend fun writer(ctx: RenderContext): Pair<String, String> {
        val branches = ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(id)).git.branches
        val repository =
            ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
                ?: error("Repository unavailable.")
        require(!repository.archived) { "Restore this repository before writing." }
        require(
            branches.any { it.name == ref } ||
                (branches.isEmpty() && repository.defaultBranch == ref)
        ) {
            "Choose a branch before writing. Tags and commit SHAs are read-only."
        }
        return resolveGitCommitAuthor(ctx.gql.execute(GitUiProfiles, Unit).profiles.current)
    }

    private suspend fun act(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "The file action failed."
            failed = true
        }
    }
}
