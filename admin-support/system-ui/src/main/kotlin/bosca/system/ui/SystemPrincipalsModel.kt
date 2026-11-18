package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemPrincipalsModel(
    val id: String = "", val admin: Boolean = false, val offset: Long = 0, val includeDeleted: Boolean = false,
    var principals: List<SystemPrincipalsData.Security.Principals.All> = emptyList(),
    var principal: SystemPrincipalData.Security.Principals.Principal? = null,
    var hasNext: Boolean = false, val feedback: SystemFeedback = SystemFeedback(),
    val query: String = "",
) {
    val title: String get() = principal?.credentials?.firstOrNull()?.identifier ?: if (id.isBlank()) "Principals" else "Principal"
    private fun pageHref(offset: Long) = "/system/security/principals?offset=$offset&includeDeleted=$includeDeleted&q=${systemEncode(query)}"
    val previousHref: String get() = if (offset > 0) pageHref((offset - 25).coerceAtLeast(0)) else ""
    val nextHref: String get() = if (hasNext) pageHref(offset + 25) else ""
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        if (id.isNotBlank()) {
            principal = ctx.gql.execute(SystemPrincipal, SystemPrincipal.Variables(id)).security.principals.principal
            if (principal == null) ctx.notFound = true
        } else {
            val result = ctx.gql.execute(SystemPrincipals, SystemPrincipals.Variables(offset, includeDeleted, query.takeIf(String::isNotBlank))).security.principals.all
            principals = result.take(25)
            hasNext = result.size > 25
        }
    }
    suspend fun change(action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access required." }
        require(confirmed) { "Confirm the account action." }
        when (action) {
            "mark" -> ctx.gql.execute(SystemMarkPrincipalDeleted, SystemMarkPrincipalDeleted.Variables(id))
            "restore" -> ctx.gql.execute(SystemRestorePrincipal, SystemRestorePrincipal.Variables(id))
            "delete" -> {
                require(principal?.deletedAt != null) { "Mark this principal deleted before permanent deletion." }
                ctx.gql.execute(SystemDeletePrincipal, SystemDeletePrincipal.Variables(id))
                feedback.nextHref = "/system/security/principals"
                return@perform
            }
            else -> error("Unknown account action.")
        }
        reload(ctx)
        feedback.message = if (action == "restore") "Principal restored." else "Principal marked deleted."
    }
}

suspend fun systemPrincipals(): SystemPrincipalsModel {
    val ctx = currentRenderContext()
    return SystemPrincipalsModel(ctx.params["id"].orEmpty(), systemAdministrator(ctx), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["includeDeleted"] == "true", query = ctx.query["q"].orEmpty().trim()).also { model -> model.feedback.perform { model.reload(ctx) } }
}
