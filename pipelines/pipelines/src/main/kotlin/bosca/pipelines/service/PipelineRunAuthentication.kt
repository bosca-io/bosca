package bosca.pipelines.service

import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAuthority
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json

internal suspend fun captureRunAuthority(authentication: AuthenticationContext?, security: SecurityService): PipelineRunAuthority {
    if (authentication == null) return PipelineRunAuthority(system = true)
    val principal = authentication.principal()
    val scoped = principal as? ScopedAuthenticatedPrincipal
    return PipelineRunAuthority(
        system = false,
        groupIds = principal?.let { security.getPrincipalGroups(it.id).filter { group -> it.hasGroup(group.id) }.map { group -> group.id } },
        scopes = scoped?.scopes?.sorted(),
        credentialId = scoped?.credentialId,
        sessionBoundCredential = scoped?.sessionBoundCredential == true,
    )
}

/** Restrictions may remain the same or broaden after submission; a run never receives the additional authority. */
internal suspend fun resolveRunAuthentication(
    run: PipelineRun,
    security: SecurityService,
    tokens: ApiTokenService,
    json: Json,
    serviceAccount: String,
): AuthenticationContext {
    val authority = checkNotNull(run.authority) { "Pipeline run has no captured authority; submit a new run" }
    val principalId = run.principalId
    if (authority.system) {
        check(principalId == null && authority.groupIds == null && authority.scopes == null && authority.credentialId == null) {
            "System pipeline authority cannot contain a caller"
        }
        return security.impersonate(serviceAccount)
    }
    if (principalId == null) {
        check(authority.groupIds == null && authority.scopes == null && authority.credentialId == null) {
            "Pipeline authority requires a caller"
        }
        return AuthenticationContext(null, null)
    }
    val principal = checkNotNull(security.getPrincipalById(principalId)) { "Pipeline caller no longer exists" }
    check(principal.deletedAt == null) { "Pipeline caller was deleted" }
    val ceiling = checkNotNull(authority.groupIds) { "Pipeline caller group restrictions are missing" }.toSet()
    val groups = security.getPrincipalGroups(principalId).filter { it.id in ceiling }
    check(groups.map { it.id }.containsAll(ceiling)) { "Pipeline caller group membership changed" }
    val credentialId = authority.credentialId
    if (credentialId == null) {
        check(authority.scopes == null) { "Scoped pipeline authority requires a credential" }
        return ImpersonatedAuthenticationContext(principal, groups)
    }
    // A session-bound credential is retired with its session; the caller and captured limits above still apply.
    if (!authority.sessionBoundCredential) {
        val credential = checkNotNull(tokens.getTokenById(credentialId)) { "Pipeline caller credential no longer exists" }
        check(credential.principal == principalId) { "Pipeline caller credential belongs to another principal" }
        val attributes = json.decodeFromJsonElement(ApiTokenCredentialAttributes.serializer(), credential.attributesJson)
        check(attributes.revokedAt == null && attributes.expiresAt?.let { OffsetDateTime.parse(it).isAfter(OffsetDateTime.now()) } != false) {
            "Pipeline caller credential was revoked or expired"
        }
        val allowedGroups = attributes.allowedGroups?.map(UUID::parse)?.toSet()
        check(allowedGroups == null || allowedGroups.containsAll(ceiling)) { "Pipeline caller credential groups changed" }
        val currentScopes = attributes.scopes
        check(currentScopes == null || authority.scopes?.all { it in currentScopes } == true) { "Pipeline caller credential scopes changed" }
    }
    val scoped = ScopedAuthenticatedPrincipal(principal, groups, authority.scopes, ceiling, credentialId, authority.sessionBoundCredential)
    return object : AuthenticationContext(null, null) { override fun principal() = scoped }
}
