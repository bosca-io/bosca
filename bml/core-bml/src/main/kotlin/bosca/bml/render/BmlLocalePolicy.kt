package bosca.bml.render

/**
 * Supplies the site's current locale policy. **The Bosca localization project is
 * the source of truth**: a site binds to one project, and the project's source language (the
 * default) plus its declared target languages define what the site negotiates — there is no
 * separate locale list to configure in BML, so the site and its translators can never drift.
 *
 * The project-backed implementation fetches and caches this like message catalogs (and re-reads
 * it on the same refresh cadence, so adding a language in Bosca reaches the site without a
 * redeploy) — hence `suspend`. [static] pins a fixed policy: tests, and sites with no
 * localization project, which stay single-locale [BmlLocales.DEFAULT].
 */
fun interface BmlLocalePolicy {

    /** The policy to negotiate the current request against — always usable, never throws. */
    suspend fun current(): BmlLocales

    companion object {
        /** A fixed policy: tests and project-less sites (defaults to single-locale `en`). */
        fun static(locales: BmlLocales = BmlLocales()): BmlLocalePolicy = BmlLocalePolicy { locales }
    }
}
