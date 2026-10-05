package bosca.messages.pages.emails.forgotpassword

import bosca.di.provide
import bosca.messages.models.ProfileModel
import bosca.messages.pages.BasePage
import bosca.messages.pages.jte.Templates
import bosca.pages.PageDetails
import bosca.pages.PageTemplate.execute
import bosca.pages.annotations.PageController
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.messages.pages.emails.appOriginFor
import bosca.security.service.AuthWebLinks
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import gg.jte.Content
import gg.jte.support.LocalizationSupport
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import java.util.*

@PageController("/email/forgotpassword/text")
class EmailForgotPasswordText(
    private val templates: Templates,
    profileService: ProfileService,
    organizationService: OrganizationService,
) : BasePage(profileService, organizationService) {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Content? {
        val principal = authenticationContext.principal() ?: throw SecurityException("Invalid Credentials")
        if (!principal.hasGroup("sa")) throw SecurityException("Invalid Credentials")
        val principalId = UUID.parse(call.pathParameters["principal"] ?: error("missing principal"))
        val profile = getProfile(principalId) ?: error("Profile not found")
        val verifyLink = AuthWebLinks.resetPassword(call.application.environment.config.appOriginFor(profile.principal?.verificationOrigin), profile.principal?.verificationToken ?: error("Verification token not found"))
        return templates.emailForgotpasswordText(profile, verifyLink)
    }

    companion object Companion {

        suspend fun BoscaApplication.renderText(localization: LocalizationSupport, profile: ProfileModel): String = with(this) {
            val templates = provide<Templates>()
            execute(
                localization,
                PageDetails("site.name", "site.name"),
            ) {
                val verifyLink = AuthWebLinks.resetPassword(environment.config.appOriginFor(profile.principal?.verificationOrigin), profile.principal?.verificationToken ?: error("Verification token not found"))
                templates.emailForgotpasswordText(profile, verifyLink).render()
            }
        }
    }
}
