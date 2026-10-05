package bosca.messages.pages.emails

import bosca.configuration.model.AdminOverrides
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(EmailLogoResolver::class.java)

/** Tolerant of fields the web side may add to `admin.overrides` beyond what the server renders. */
private val brandingJson = Json { ignoreUnknownKeys = true }

/** The override logo to render in an email. */
sealed interface EmailLogo {
    /** Inline SVG markup, embedded directly so it renders without an external fetch. */
    data class Svg(val markup: String) : EmailLogo

    /** A raster image (e.g. a PNG `icon`) that can't be inlined; rendered as an `<img>` by content slug. */
    data class Image(val slug: String) : EmailLogo
}

/**
 * Resolves the override email logo from the `admin.overrides` configuration — the same key the web
 * admin/studio apps use. The logo is a content image slug. An **SVG is embedded inline** (most email
 * clients block external images, and inline SVG needs no fetch); a **raster** (e.g. the PNG favicon
 * `icon`) can't be inlined, so it is rendered as an `<img>`. Returns `null` — so the template falls back
 * to the built-in mark — when no override is set or the asset is missing/unreadable/not an image.
 */
class EmailLogoResolver(
    private val configurationService: ConfigurationService,
    private val metadataService: MetadataService,
    private val slugService: SlugService,
    private val objectStorageService: ObjectStorageService,
) {

    suspend fun resolve(): EmailLogo? {
        val logo = configurationService.getValueAs<AdminOverrides>(AdminOverrides.KEY, brandingJson)?.logo ?: return null
        // Prefer the auth `slug` (the field the login screens use — the closest analog to an email), then
        // the full sidebar logo, then the favicon `icon`. Emails are light-background, so prefer light.
        val slug = logo.slug
            ?: (logo.expanded ?: logo.collapsed)?.let { it.light ?: it.dark }
            ?: logo.icon
            ?: return null
        return try {
            val metadata = resolveMetadata(slug) ?: return null
            val contentType = metadata.contentType
            when {
                contentType.contains("svg", ignoreCase = true) ->
                    objectStorageService.getString(objectStorageService.getPath(metadata))
                        .takeIf { it.isNotBlank() }
                        ?.let { EmailLogo.Svg(it) }
                contentType.startsWith("image/", ignoreCase = true) -> EmailLogo.Image(slug)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to load admin.overrides email logo '{}'; falling back to the default mark", slug, e)
            null
        }
    }

    /** Resolves a content image slug (a metadata UUID or a named slug, optionally with an extension). */
    private suspend fun resolveMetadata(slug: String): Metadata? {
        val key = slug.substringBefore(".")
        val uuid = try {
            UUID.parse(key)
        } catch (_: Exception) {
            null
        }
        return if (uuid != null) {
            metadataService.getById(uuid)
        } else {
            slugService.get(key)?.metadataId?.let { metadataService.getById(it) }
        }
    }
}
