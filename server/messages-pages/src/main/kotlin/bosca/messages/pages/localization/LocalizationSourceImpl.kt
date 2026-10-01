package bosca.messages.pages.localization

import bosca.localization.service.LocalizationService
import bosca.pages.LocalizationSource
import bosca.server.BoscaApplication
import gg.jte.support.LocalizationSupport

/**
 * [LocalizationSource] implementation that resolves strings from the [LocalizationService].
 *
 * Registered in the DI container so `bosca-core`'s `Page` (and any other renderer) can obtain it via
 * `provide<LocalizationSource>()` without `bosca-core` depending on the localization module.
 */
class LocalizationSourceImpl(
    private val application: BoscaApplication,
    private val localizationService: LocalizationService,
) : LocalizationSource {

    override suspend fun get(languageTag: String): LocalizationSupport =
        MessageLocalizations.load(
            localizationService,
            MessageLocalizationDefaults.projectName(application),
            languageTag,
        )
}
