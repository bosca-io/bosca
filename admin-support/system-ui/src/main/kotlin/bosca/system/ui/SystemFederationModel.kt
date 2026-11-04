package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
class SystemFederationModel(
    val admin: Boolean = false,
    var peers: List<SystemFederationPeersData.Collaboration.Federation.Peers> = emptyList(),
    var draft: SystemFederationDraft = SystemFederationDraft(),
    var mode: String = "", var targetId: String = "", var targetName: String = "",
    var nextSecret: String = "", var dialogRequest: Long = 0,
    val feedback: SystemFeedback = SystemFeedback(),
) {
    suspend fun reload(ctx: RenderContext) {
        if (admin) peers = ctx.gql.execute(SystemFederationPeers, Unit).collaboration.federation.peers
    }

    suspend fun refresh(ctx: RenderContext) = feedback.perform { reload(ctx) }

    fun prepare(action: String, id: String) {
        require(action in setOf("register", "secret", "deactivate")) { "Unknown federation action." }
        mode = action; targetId = id; targetName = peers.firstOrNull { it.id == id }?.name.orEmpty()
        draft = SystemFederationDraft(); nextSecret = ""; dialogRequest++
        feedback.failed = false; feedback.message = ""
    }

    fun cancel() { feedback.failed = false; feedback.message = ""; mode = ""; draft = SystemFederationDraft(); nextSecret = "" }

    suspend fun register(name: String, natsUrl: String, apiUrl: String, secret: String, ctx: RenderContext) = feedback.perform {
        draft = SystemFederationDraft(name, natsUrl, apiUrl, secret); mode = "register"
        require(admin) { "Administrator access is required." }
        require(name.isNotBlank()) { "Enter a peer name." }
        require(secret.isNotBlank()) { "Enter a shared secret." }
        absoluteUrl(natsUrl, false); absoluteUrl(apiUrl, true)
        ctx.gql.execute(SystemRegisterFederationPeer, SystemRegisterFederationPeer.Variables(RegisterFederationPeerInput(name.trim(), natsUrl.trim(), apiUrl.trim(), secret.trim())))
        cancel(); reload(ctx)
        feedback.message = "Federation peer registered."
    }

    suspend fun setSecret(id: String, secret: String, ctx: RenderContext) = feedback.perform {
        targetId = id; nextSecret = secret; mode = "secret"
        require(admin) { "Administrator access is required." }
        check(ctx.gql.execute(SystemSetFederationSecret, SystemSetFederationSecret.Variables(id, secret.trim().takeIf(String::isNotEmpty))).collaboration.federation.setSharedSecret) { "Federation peer no longer exists." }
        cancel()
        feedback.message = if (secret.isBlank()) "Federation shared secret cleared." else "Federation shared secret updated."
    }

    suspend fun deactivate(id: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        targetId = id; mode = "deactivate"
        require(admin && confirmed) { "Confirm peer deactivation." }
        check(ctx.gql.execute(SystemDeactivateFederationPeer, SystemDeactivateFederationPeer.Variables(id)).collaboration.federation.deactivatePeer) { "Federation peer was not deactivated." }
        cancel(); reload(ctx)
        feedback.message = "Federation peer deactivated."
    }

    private fun absoluteUrl(value: String, http: Boolean) {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        val server = uri?.rawAuthority?.let { authority -> runCatching { URI("http", authority, "/", null, null).toURL() }.getOrNull() }
        require(uri?.isAbsolute == true && !server?.host.isNullOrBlank()) { "Enter an absolute ${if (http) "API" else "NATS"} URL with a protocol and host." }
        require(!http || uri.scheme.lowercase() in setOf("http", "https")) { "API URL must use HTTP or HTTPS." }
    }
}

suspend fun systemFederation(): SystemFederationModel {
    val ctx = currentRenderContext()
    return SystemFederationModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
