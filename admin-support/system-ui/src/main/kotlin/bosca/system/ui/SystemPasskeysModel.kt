package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemPasskeysModel(
    val admin: Boolean = false,
    var passkeys: List<SystemPasskeysData.Security.Passkeys.Current> = emptyList(),
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun reload(ctx: RenderContext) { passkeys = ctx.gql.execute(SystemPasskeys, Unit).security.passkeys.current }
    suspend fun delete(credentialId: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm passkey removal." }
        check(ctx.gql.execute(SystemDeletePasskey, SystemDeletePasskey.Variables(credentialId)).security.passkeys.delete) { "Passkey was not removed." }
        passkeys = passkeys.filterNot { it.credentialId == credentialId }
        feedback.message = "Passkey removed. Sign in again to continue."
        feedback.sessionRevoked = true
        feedback.nextHref = "/login?redirect=%2Fsystem%2Fsecurity%2Fpasskeys&unauthorized=true&passkeyRemoved=true"
    }
}

suspend fun systemPasskeys(): SystemPasskeysModel {
    val ctx = currentRenderContext()
    return SystemPasskeysModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
