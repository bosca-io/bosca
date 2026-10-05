package bosca.pages

import gg.jte.support.LocalizationSupport

/**
 * Supplies a [LocalizationSupport] for a given language tag, resolved from the localization
 * service.
 *
 * Declared here in `bosca-core` (a foundation build that cannot depend on the localization module
 * without creating a dependency cycle) and implemented downstream. Pages and email templates obtain
 * an instance through [bosca.di.provide] and load their strings while a database connection is in
 * scope, then render synchronously against the returned map.
 */
interface LocalizationSource {

    /**
     * Resolves the localized strings for [languageTag] (e.g. `en-US`, `es-419`), applying the
     * implementation's locale fallback. Never returns null — an unseeded locale falls back to the
     * project's source language.
     */
    suspend fun get(languageTag: String): LocalizationSupport
}
