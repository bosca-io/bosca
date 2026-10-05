package bosca.profiles.web

import java.io.File

fun branding(): Branding = branding(System.getenv())

internal fun branding(environment: Map<String, String>): Branding {
    val name = environment["BRAND_NAME"].cleanOrNull() ?: DEFAULT_NAME
    return Branding(
        name = name,
        logoUrl = environment["BRAND_LOGO_URL"].cleanOrNull().orEmpty(),
        footerHtml = environment["BRAND_FOOTER_HTML_FILE"].cleanOrNull()?.let { File(it).readText() } ?: "© $name",
        primaryColor = environment.hexColor("BRAND_PRIMARY_COLOR", DEFAULT_PRIMARY_COLOR),
        accentColor = environment.hexColor("BRAND_ACCENT_COLOR", DEFAULT_ACCENT_COLOR),
    )
}

private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, String>.hexColor(key: String, fallback: String): String =
    get(key).cleanOrNull()?.takeIf(HEX_COLOR::matches) ?: fallback

private val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

internal const val DEFAULT_NAME = "Bosca"
internal const val DEFAULT_PRIMARY_COLOR = "#00dc82"
internal const val DEFAULT_ACCENT_COLOR = "#06b6d4"
