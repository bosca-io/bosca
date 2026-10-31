package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactPermissionsModel(
    val admin: Boolean,
    val namespaceId: String,
    val namespaces: List<ArtifactNamespaceView> = emptyList(),
    val groups: List<ArtifactChoice> = emptyList(),
    var permissions: List<ArtifactPermissionView> = emptyList(),
    val feedback: ArtifactFeedback = ArtifactFeedback(),
) {
    val actions: List<String> get() = listOf("VIEW", "EDIT", "DELETE", "MANAGE", "LIST")
    suspend fun reload(ctx: RenderContext) {
        permissions = if (namespaceId.isBlank()) emptyList() else ctx.gql.execute(ArtifactsPermissions, ArtifactsPermissions.Variables(namespaceId)).artifactsAdmin.namespace?.permissions.orEmpty().map { ArtifactPermissionView(it.groupId, it.group.name, it.action.name) }
    }
    suspend fun grant(groupId: String, action: String, ctx: RenderContext) = feedback.perform {
        require(namespaceId.isNotBlank() && groupId.isNotBlank() && action in actions) { "Choose a namespace, group, and action." }
        ctx.gql.execute(ArtifactsGrant, ArtifactsGrant.Variables(PermissionInput(PermissionAction.valueOf(action), namespaceId, groupId)))
        reload(ctx)
        feedback.message = "Permission granted."
    }
    suspend fun revoke(groupId: String, action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm permission removal." }
        ctx.gql.execute(ArtifactsRevoke, ArtifactsRevoke.Variables(PermissionInput(PermissionAction.valueOf(action), namespaceId, groupId)))
        reload(ctx)
        feedback.message = "Permission removed."
    }
}

suspend fun artifactPermissions(): ArtifactPermissionsModel {
    val ctx = currentRenderContext()
    if (!artifactAdministrator(ctx.gql)) return ArtifactPermissionsModel(false, "")
    val namespaces = ctx.gql.execute(ArtifactsNamespaces, Unit).artifactsAdmin.namespaces.map { it.view() }
    val groups = mutableListOf<ArtifactChoice>()
    var offset = 0L
    do {
        val batch = ctx.gql.execute(ArtifactsGroups, ArtifactsGroups.Variables(offset)).security.groups.all
        groups += batch.map { ArtifactChoice(it.id, it.name) }
        offset += batch.size
    } while (batch.size == 100)
    return ArtifactPermissionsModel(true, ctx.query["namespaceId"].orEmpty(), namespaces, groups).also { it.reload(ctx) }
}
