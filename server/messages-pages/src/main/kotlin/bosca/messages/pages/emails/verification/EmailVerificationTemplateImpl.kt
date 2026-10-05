package bosca.messages.pages.emails.verification

import bosca.localization.service.LocalizationService
import bosca.messages.models.ProfileModel
import bosca.messages.pages.BasePage
import bosca.messages.pages.emails.EmailLogo
import bosca.messages.pages.emails.EmailLogoResolver
import bosca.messages.pages.emails.verification.EmailVerificationHtml.Companion.renderHtml
import bosca.messages.pages.emails.verification.EmailVerificationText.Companion.renderText
import bosca.messages.pages.localization.MessageLocalizationDefaults
import bosca.messages.pages.localization.MessageLocalizations
import bosca.profile.profile.service.ProfileService
import bosca.security.messages.EmailVerificationTemplate
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import gg.jte.support.LocalizationSupport
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EmailVerificationTemplateImpl(
    private val application: BoscaApplication,
    private val profiles: ProfileService,
    private val localizationService: LocalizationService,
    private val logoResolver: EmailLogoResolver
) : EmailVerificationTemplate {

    private lateinit var profileModel: ProfileModel
    private lateinit var localization: LocalizationSupport
    private var logo: EmailLogo? = null

    override suspend fun initialize(profileId: UUID) {
        val profile = profiles.getById(profileId)
        profileModel = BasePage.getProfile(profile.principal ?: error("missing principal id")) ?: error("profile not found")
        val languageTag = profileModel.attributes.find { it.typeId == "bosca.profiles.locale" }?.attributes?.jsonObject?.get("locale")?.jsonPrimitive?.content ?: "en-US"
        localization = MessageLocalizations.load(localizationService, MessageLocalizationDefaults.projectName(application), languageTag)
        logo = logoResolver.resolve()
    }

    override suspend fun getSubject(): String {
        return localization.localize("email.verification.subject").toString()
    }

    override suspend fun getHtml(): String = application.renderHtml(localization, profileModel, logo)

    override suspend fun getText(): String = application.renderText(localization, profileModel)
}