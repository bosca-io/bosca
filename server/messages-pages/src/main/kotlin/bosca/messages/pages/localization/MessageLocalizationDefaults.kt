package bosca.messages.pages.localization

import bosca.server.BoscaApplication

/**
 * Shared constants for the localization project that backs UI pages and transactional emails.
 *
 * Both [MessageLocalizations] (the runtime read path) and [MessageLocalizationInstaller]
 * (the CSV defaults seeder) read these so the project name and source language stay in lockstep.
 */
object MessageLocalizationDefaults {

    /** Default name of the localization project; overridable via `localization.messages.project`. */
    const val DEFAULT_PROJECT_NAME = "Bosca"

    /** Source language for the project and the tail of every locale fallback chain. */
    const val SOURCE_LANGUAGE = "en"

    /** Classpath location of the bundled `key,language,text` seed CSV. */
    const val SEED_RESOURCE = "/localization/messages.csv"

    /** Authored source strings extracted from the first-party BML message templates. */
    const val BML_MESSAGE_SEED_RESOURCE = "/localization/bml-messages.csv"

    /** Every bundled localization seed installed into the shared project. */
    val SEED_RESOURCES: List<String> = listOf(SEED_RESOURCE, BML_MESSAGE_SEED_RESOURCE)

    /** Resolves the configured project name, falling back to [DEFAULT_PROJECT_NAME]. */
    fun projectName(application: BoscaApplication): String =
        application.environment.config.propertyOrNull("localization.messages.project")?.getString()
            ?: DEFAULT_PROJECT_NAME
}
