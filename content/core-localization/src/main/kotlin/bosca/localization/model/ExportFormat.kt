package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * Target file format for exporting translations to consuming applications.
 *
 * - [ANDROID_XML]: Android / Compose Multiplatform `strings.xml` (with `<plurals>`)
 * - [IOS_STRINGS]: iOS `.strings` key-value format (plain translations only)
 * - [IOS_STRINGSDICT]: iOS `.stringsdict` plist for plural forms
 * - [JSON_I18N]: Nuxt `@nuxtjs/i18n` nested JSON format
 * - [JSON_FLAT]: flat `{"key": "value"}` JSON, ICU placeholders preserved verbatim
 * - [JSON_NESTED]: nested JSON with dot-separated keys expanded into an object tree
 * - [ARB]: Flutter Application Resource Bundle, ICU plural syntax preserved
 * - [XLIFF]: XLIFF 1.2 XML for translation tool interoperability
 */
@Serializable
enum class ExportFormat {
    ANDROID_XML,
    IOS_STRINGS,
    IOS_STRINGSDICT,
    JSON_I18N,
    JSON_FLAT,
    JSON_NESTED,
    ARB,
    XLIFF
}
