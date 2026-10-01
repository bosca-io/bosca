package bosca.messages.configuration

import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.service.MetadataService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.service.LanguagesService
import bosca.localization.service.LocalizationService
import bosca.messages.pages.emails.EmailLogoResolver
import bosca.messages.pages.jte.StaticTemplates
import bosca.messages.pages.jte.Templates
import bosca.messages.pages.localization.LocalizationSourceImpl
import bosca.messages.pages.localization.MessageLocalizationDefaults
import bosca.messages.pages.localization.MessageLocalizationInstaller
import bosca.pages.LocalizationSource
import bosca.server.BoscaApplication
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService

@Providers
class PagesConfiguration {

    @Provider(singleton = true)
    fun templates(): Templates = StaticTemplates()

    @Provider
    fun localizationSource(
        application: BoscaApplication,
        localizationService: LocalizationService
    ): LocalizationSource = LocalizationSourceImpl(application, localizationService)

    @Provider
    fun emailLogoResolver(
        configurationService: ConfigurationService,
        metadataService: MetadataService,
        slugService: SlugService,
        objectStorageService: ObjectStorageService
    ): EmailLogoResolver = EmailLogoResolver(
        configurationService,
        metadataService,
        slugService,
        objectStorageService
    )

    @Provider(name = "messages-localization-installer")
    fun messageLocalizationInstaller(
        application: BoscaApplication,
        languagesService: LanguagesService,
        localizationService: LocalizationService
    ): PackageInstaller = MessageLocalizationInstaller(
        languagesService,
        localizationService,
        MessageLocalizationDefaults.projectName(application)
    )

    @Provider(name = "messages-localization")
    fun messageLocalizationPackage(): PackageInstallation = PackageInstallation(
        key = "messages-localization",
        name = "Message Localization",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.1.0",
                installerNames = listOf("messages-localization-installer")
            )
        )
    )
}
