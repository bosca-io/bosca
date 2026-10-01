package bosca.messages.pages

import bosca.di.provide
import bosca.messages.models.ProfileModel
import bosca.pages.Page
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import gg.jte.Content
import bosca.server.ServerCall

abstract class BasePage(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
) : Page() {

    suspend fun getProfile(authenticationContext: AuthenticationContext): ProfileModel? {
        val principal = authenticationContext.principal() ?: return null
        val profile = profileService.getByPrincipal(principal.id).first()
        val attributes = profileService.getAttributes(profile.id)
        val organizationMembers = organizationService.getMemberOrganizations(principal.id)
        val organizations = organizationService.getOrganizations(organizationMembers.map { it.organizationId })
        return ProfileModel(
            profile,
            principal.asPrincipal(),
            attributes,
            organizations
        )
    }

    suspend fun getProfile(principalId: UUID): ProfileModel? {
        val profile = profileService.getByPrincipal(principalId).first()
        val principal = provide<SecurityService>().getPrincipalById(principalId)
        val attributes = profileService.getAttributes(profile.id)
        val organizationMembers = organizationService.getMemberOrganizations(profile.principal ?: return null)
        val organizations = organizationService.getOrganizations(organizationMembers.map { it.organizationId })
        return ProfileModel(
            profile,
            principal,
            attributes,
            organizations
        )
    }

    companion object {

        suspend fun getProfile(principalId: UUID): ProfileModel? {
            val profileService = provide<ProfileService>()
            val organizationService = provide<OrganizationService>()
            val profile = profileService.getByPrincipal(principalId).first()
            val principal = provide<SecurityService>().getPrincipalById(principalId)
            val attributes = profileService.getAttributes(profile.id)
            val organizationMembers = organizationService.getMemberOrganizations(profile.principal ?: return null)
            val organizations = organizationService.getOrganizations(organizationMembers.map { it.organizationId })
            return ProfileModel(
                profile,
                principal,
                attributes,
                organizations
            )
        }
    }
}

abstract class SecuredPage(profileService: ProfileService, organizationService: OrganizationService) : BasePage(profileService, organizationService) {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Content? {
        val principal = authenticationContext.principal()
        if (principal == null) {
            call.respondRedirect("/login")
            return null
        }
        if (!principal.hasGroup("administrators")) {
            call.respondRedirect("/login?error=invalid.permissions")
            return null
        }
        return doExecute(call, authenticationContext)
    }

    abstract suspend fun doExecute(call: ServerCall, authenticationContext: AuthenticationContext): Content?
}