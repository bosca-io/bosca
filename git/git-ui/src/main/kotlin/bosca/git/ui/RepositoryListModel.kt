package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.components.NavigationItem
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryListModel(
    val state: RepositoryState,
    val owner: OwnerOption,
    var draft: RepositoryDraft = RepositoryDraft(),
    var repositories: List<RepositorySummary> = emptyList(),
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    val href: String
        get() = "/repositories?state=${state.queryValue}&owner=${encode(owner.id)}"

    val tabs: List<NavigationItem>
        get() =
            RepositoryState.entries.map {
                NavigationItem(
                    it.label,
                    "/repositories?state=${it.queryValue}&owner=${encode(owner.id)}",
                    it == state,
                )
            }

    suspend fun reload(ctx: RenderContext) {
        repositories =
            ctx.gql
                .execute(
                    GitUiRepositories,
                    GitUiRepositories.Variables(
                        state != RepositoryState.ACTIVE,
                        owner.id.takeUnless { it == "all" },
                    ),
                )
                .git
                .repositories
                .filter {
                    !it.deleted &&
                        (state == RepositoryState.ALL ||
                            it.archived == (state == RepositoryState.ARCHIVED))
                }
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
                        href,
                        it.canManage,
                        it.canExecute,
                    )
                }
    }

    suspend fun create(
        name: String,
        slug: String,
        description: String,
        ownerId: String,
        visibility: String,
        contentType: String,
        branch: String,
        readme: Boolean,
        gitignore: String,
        license: String,
        ctx: RenderContext,
    ) {
        draft =
            RepositoryDraft(
                name,
                slug,
                description,
                ownerId,
                visibility,
                contentType,
                branch,
                readme,
                gitignore,
                license,
            )
        act {
            require(
                name.isNotBlank() && slug.isNotBlank() && ownerId.isNotBlank() && ownerId != "all"
            ) {
                "Choose an owner and enter a name and slug."
            }
            val repo =
                ctx.gql
                    .execute(
                        GitUiCreate,
                        GitUiCreate.Variables(
                            CreateGitRepositoryInput(
                                slug.trim(),
                                name.trim(),
                                description,
                                ownerId,
                                GitVisibility.valueOf(visibility),
                                branch.ifBlank { "main" },
                                GitRepositoryContentType.valueOf(contentType),
                                readme,
                                gitignore.ifBlank { null },
                                license.ifBlank { null },
                            )
                        ),
                    )
                    .git
                    .createRepository
            nextHref = "/repositories/${encode(repo.id)}?return=${encode(href)}"
        }
    }

    suspend fun importRepository(
        name: String,
        slug: String,
        url: String,
        ownerId: String,
        ctx: RenderContext,
    ) {
        draft = RepositoryDraft(name = name, slug = slug, ownerId = ownerId, cloneUrl = url)
        act {
            require(
                name.isNotBlank() &&
                    slug.isNotBlank() &&
                    url.isNotBlank() &&
                    ownerId.isNotBlank() &&
                    ownerId != "all"
            ) {
                "Choose an owner and enter the repository name, slug, and clone URL."
            }
            val repo =
                ctx.gql
                    .execute(
                        GitUiImport,
                        GitUiImport.Variables(url.trim(), ownerId, slug.trim(), name.trim()),
                    )
                    .git
                    .importRepository
            nextHref = "/repositories/${encode(repo.id)}?return=${encode(href)}"
        }
    }

    suspend fun lifecycle(id: String, action: String, confirmed: Boolean, ctx: RenderContext) =
        act {
            require(confirmed) { "Confirm the repository action." }
            when (action) {
                "archive" -> ctx.gql.execute(GitUiArchive, GitUiArchive.Variables(id))
                "restore" -> ctx.gql.execute(GitUiRestore, GitUiRestore.Variables(id))
                "delete" -> ctx.gql.execute(GitUiDelete, GitUiDelete.Variables(id))
                else -> error("Unknown repository action")
            }
            reload(ctx)
            nextHref = href
            message =
                "Repository ${if (action == "delete") "deleted" else if (action == "restore") "restored" else "archived"}."
        }

    private suspend fun act(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Repository action failed."
            failed = true
        }
    }
}
