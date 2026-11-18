package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset

@Serializable
class SystemTokensModel(
    val id: Long? = null, val admin: Boolean = false, val currentPrincipalId: String = "", val owner: String = "", val offset: Long = 0,
    var tokens: List<SystemTokenView> = emptyList(), var token: SystemTokenView? = null, var hasNext: Boolean = false,
    var scopes: List<SystemTokenScopesData.Security.ApiTokens.AvailableScopes> = emptyList(),
    var owners: List<SystemTokenOwner> = emptyList(), var draft: SystemTokenDraft = SystemTokenDraft(),
    var rawToken: String = "", var scopeType: String = "*", var scopePath: String = "*",
    var scopeTargets: List<SystemScopeTarget> = emptyList(), var scopeResourcesLoaded: Boolean = false,
    var editorRequest: Long = 0, var editorCompleted: Boolean = false, val feedback: SystemFeedback = SystemFeedback(),
    var scopeEditorOpen: Boolean = false, var scopeEditorCompleted: Boolean = false,
) {
    val title: String get() = token?.name ?: "API tokens"
    val editorOpenRequest get() = if (editorRequest == 0L) "" else "editor-$editorRequest"
    val scopeOpenRequest get() = if (scopeEditorOpen) "scopes-$editorRequest" else ""
    val canEdit: Boolean get() = token?.principalId == currentPrincipalId
    val canCreate: Boolean get() = owner.isBlank() || owner == currentPrincipalId || admin
    val ownerOptions: Map<String, String> get() = linkedMapOf("__self__" to "Current account") + owners.associate { it.principalId to it.name } + listOf(owner, draft.owner).filter { it.isNotBlank() && owners.none { option -> option.principalId == it } }.associateWith { it }
    val scopeOptions: Map<String, String> get() = scopes.associate { it.name to "${it.name} — ${it.description}" } + draft.scopes.filter { chosen -> scopes.none { it.name == chosen } }.associateWith { it }
    val scopePaths: Map<String, String> get() = linkedMapOf("*" to "Any path (*)") + scopeTargets.map { it.namespace }.distinct().associate { "$it/*" to "$it/* (all repositories)" } + scopeTargets.filter { scopeType == "*" || it.type == scopeType }.associate { it.path to it.path }
    val scopeVersions: List<String> get() = listOf("*") + scopeTargets.filter { it.path == scopePath && (scopeType == "*" || it.type == scopeType) }.flatMap { it.versions }.distinct()
    val previousHref: String get() = if (offset > 0) "/system/security/tokens?owner=${systemEncode(owner)}&offset=${(offset - 25).coerceAtLeast(0)}" else ""
    val nextHref: String get() = if (hasNext) "/system/security/tokens?owner=${systemEncode(owner)}&offset=${offset + 25}" else ""
    suspend fun reload(ctx: RenderContext) {
        scopes = ctx.gql.execute(SystemTokenScopes, Unit).security.apiTokens.availableScopes
        loadOwner(owner, ctx)
        if (id != null) {
            token = ctx.gql.execute(SystemToken, SystemToken.Variables(id)).security.apiTokens.token?.tokenView()
            val current = token
            if (current == null) ctx.notFound = true
            else draft = SystemTokenDraft(current.name, current.description.orEmpty(), current.scopes.orEmpty(), current.principalId, current.expiresAt.orEmpty())
        } else {
            val result = if (owner.isBlank()) ctx.gql.execute(SystemMyTokens, SystemMyTokens.Variables(offset)).security.apiTokens.my.map { it.tokenView() }
            else ctx.gql.execute(SystemPrincipalTokens, SystemPrincipalTokens.Variables(owner, offset)).security.apiTokens.forPrincipal.map { it.tokenView() }
            tokens = result.take(25)
            hasNext = result.size > 25
        }
    }
    private suspend fun loadOwner(id: String, ctx: RenderContext) {
        if (!admin || id.isBlank()) return
        val principal = ctx.gql.execute(SystemPrincipal, SystemPrincipal.Variables(id)).security.principals.principal
        val profile = principal?.profiles?.find { it.id == principal.primaryProfileId } ?: principal?.profiles?.firstOrNull { it.deletedAt == null }
        if (profile != null) owners = owners.filterNot { it.principalId == id } + SystemTokenOwner(id, profile.id, profile.name)
    }
    private fun invalidateEditor() { editorRequest++; editorCompleted = false }
    fun openEdit() {
        require(canEdit) { "Only the token owner can edit its metadata and scopes." }
        val current = requireNotNull(token)
        draft = SystemTokenDraft(current.name, current.description.orEmpty(), current.scopes.orEmpty(), current.principalId, current.expiresAt.orEmpty())
        invalidateEditor(); scopeEditorOpen = false; scopeEditorCompleted = false; feedback.failed = false; feedback.message = ""
    }
    fun openCreate() {
        require(canCreate) { "Cannot create tokens for this account." }
        rawToken = ""; draft = SystemTokenDraft(owner = owner, expires = LocalDateTime.now(ZoneOffset.UTC).plusDays(90).withSecond(0).withNano(0).toString()); invalidateEditor()
        scopeEditorOpen = false; scopeEditorCompleted = false
        feedback.failed = false; feedback.message = ""
    }
    fun retain(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String) {
        draft = SystemTokenDraft(name, description, systemChoices(selectedScopes), selectedOwner.takeUnless { it == "__self__" }.orEmpty(), expires)
    }
    private fun openScopeEditor() {
        invalidateEditor(); scopeEditorOpen = true; scopeEditorCompleted = false
    }
    fun openScopes(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String) {
        retain(name, description, selectedScopes, selectedOwner, expires)
        openScopeEditor()
    }
    fun applyScopes(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String) {
        retain(name, description, selectedScopes, selectedOwner, expires)
        invalidateEditor(); scopeEditorOpen = false; scopeEditorCompleted = true
    }
    suspend fun scopeResources(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String, ctx: RenderContext) = feedback.perform {
        openScopeEditor()
        rawToken = ""
        retain(name, description, selectedScopes, selectedOwner, expires)
        require(admin) { "Administrator access required to browse registry scope targets. Custom scope values remain available." }
        loadOwner(draft.owner, ctx)
        val result = ctx.gql.execute(SystemScopeNamespaces, Unit).artifactsAdmin.namespaces
        scopeTargets = result.flatMap { namespace -> namespace.repositories.map { SystemScopeTarget(namespace.name, it.name, it.type.name, it.versions.map { version -> version.version }) } }
        scopeResourcesLoaded = true
    }
    fun selectScopeTarget(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String, type: String, path: String) {
        openScopeEditor()
        retain(name, description, selectedScopes, selectedOwner, expires)
        scopeType = type
        scopePath = path
    }
    suspend fun addScope(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String, type: String, path: String, version: String, actions: String) = feedback.perform {
        openScopeEditor()
        rawToken = ""
        retain(name, description, selectedScopes, selectedOwner, expires)
        require(type in listOf("*", "docker", "helm", "maven", "npm", "raw", "ml")) { "Select a registry type." }
        require(path.isNotBlank() && ':' !in path && version.isNotBlank() && ':' !in version) { "Enter a path and version without colons." }
        val selectedActions = systemChoices(actions)
        require(selectedActions.isNotEmpty() && selectedActions.all { it in listOf("pull", "push", "admin") }) { "Select pull, push, or admin actions." }
        draft = draft.copy(scopes = (draft.scopes + "artifacts:$type:$path:$version:${selectedActions.joinToString(",")}").distinct())
        scopeType = type
        scopePath = path
    }
    suspend fun save(name: String, description: String, selectedScopes: String, selectedOwner: String, expires: String, ctx: RenderContext) = feedback.perform {
        invalidateEditor()
        scopeEditorOpen = false; scopeEditorCompleted = false
        rawToken = ""
        retain(name, description, selectedScopes, selectedOwner, expires)
        require(name.isNotBlank()) { "Enter a token name." }
        if (id != null) {
            require(canEdit) { "Only the token owner can edit its metadata and scopes." }
            ctx.gql.execute(SystemEditToken, SystemEditToken.Variables(id, name.trim(), description, draft.scopes.takeIf(List<String>::isNotEmpty), draft.scopes.isEmpty()))
            reload(ctx)
            feedback.message = "Token updated."
        } else {
            require(draft.owner.isBlank() || draft.owner == currentPrincipalId || admin) { "Administrator access required to create another account's token." }
            val expiration = expires.takeIf(String::isNotBlank)?.let { LocalDateTime.parse(it).atOffset(ZoneOffset.UTC).toString() }
            val input = ApiTokenInput(name = name.trim(), description = description.takeIf(String::isNotBlank), scopes = draft.scopes.takeIf(List<String>::isNotEmpty), expiresAt = expiration)
            rawToken = if (draft.owner.isBlank() || draft.owner == currentPrincipalId) ctx.gql.execute(SystemCreateToken, SystemCreateToken.Variables(input)).security.apiTokens.create.rawToken
            else ctx.gql.execute(SystemCreatePrincipalToken, SystemCreatePrincipalToken.Variables(draft.owner, input)).security.apiTokens.createForPrincipal.rawToken
            reload(ctx)
            feedback.message = "Token created. Copy its value now; it cannot be retrieved again."
        }
        editorCompleted = true
    }
    suspend fun change(tokenId: Long, action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        rawToken = ""
        require(confirmed) { "Confirm the token action." }
        when (action) {
            "revoke" -> check(ctx.gql.execute(SystemRevokeToken, SystemRevokeToken.Variables(tokenId)).security.apiTokens.revoke) { "Token was not revoked." }
            "delete" -> check(ctx.gql.execute(SystemDeleteToken, SystemDeleteToken.Variables(tokenId)).security.apiTokens.delete) { "Token was not deleted." }
            else -> error("Unknown token action.")
        }
        if (action == "delete" && id != null) feedback.nextHref = "/system/security/tokens" else reload(ctx)
        feedback.message = if (action == "delete") "Token deleted." else "Token revoked."
    }
    fun dismissToken() { rawToken = "" }
}

suspend fun systemTokens(): SystemTokensModel {
    val ctx = currentRenderContext()
    val access = ctx.gql.execute(SystemAccess, Unit).security.principals.current
    val parameter = ctx.params["id"]
    val tokenId = parameter?.toLongOrNull()
    if (parameter != null && tokenId == null) ctx.notFound = true
    return SystemTokensModel(tokenId, access.groups.any { it.name in setOf("sa", "administrators") }, access.id, ctx.query["owner"].orEmpty().takeUnless { it == "__self__" }.orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { model ->
        if (model.id == null) model.draft = model.draft.copy(owner = model.owner)
        model.feedback.perform { model.reload(ctx) }
    }
}
