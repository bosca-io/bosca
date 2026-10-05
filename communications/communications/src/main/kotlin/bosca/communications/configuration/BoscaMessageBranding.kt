package bosca.communications.configuration

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Send-time branding for the first-party `bosca-messages` BML project.
 *
 * The logo must be an absolute URL because delivered emails have no origin against which a
 * relative URL can be resolved. Colors are restricted to CSS hex notation before they reach an
 * inline style. Missing or invalid values fall back to the built-in Bosca email presentation.
 */
@Serializable
data class BoscaMessageBranding(
    val title: String = DEFAULT_TITLE,
    val logoUrl: String = "",
    val logoOnly: Boolean = false,
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    val accentColor: String = DEFAULT_ACCENT_COLOR,
) {
    /** Returns values safe to add to the BML template payload. */
    fun normalized(): BoscaMessageBranding = copy(
        title = title.trim().ifEmpty { DEFAULT_TITLE },
        logoUrl = logoUrl.validLogoUrlOrEmpty(),
        primaryColor = primaryColor.validHexColorOr(DEFAULT_PRIMARY_COLOR),
        accentColor = accentColor.validHexColorOr(DEFAULT_ACCENT_COLOR),
    )

    companion object {
        /** Configuration-service key edited through the platform configuration UI. */
        const val KEY = "bosca.messages.branding"

        const val DEFAULT_TITLE = "Bosca"
        const val DEFAULT_PRIMARY_COLOR = "#0e1019"
        const val DEFAULT_ACCENT_COLOR = "#047a52"
    }
}

/** Resolves the current message branding so configuration edits apply without a restart. */
internal suspend fun ConfigurationService.getBoscaMessageBranding(json: Json): BoscaMessageBranding =
    (getValueAs<BoscaMessageBranding>(BoscaMessageBranding.KEY, json) ?: BoscaMessageBranding()).normalized()

private val hexColor = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

private fun String.validHexColorOr(fallback: String): String =
    trim().takeIf(hexColor::matches) ?: fallback

private fun String.validLogoUrlOrEmpty(): String {
    val value = trim()
    if (value.isEmpty()) return ""
    return value.takeIf {
        runCatching {
            val uri = URI(it)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }.orEmpty()
}
