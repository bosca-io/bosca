package bosca.configuration.model

import kotlinx.serialization.Serializable

/**
 * Tenant branding overrides, shared with the web admin/studio apps under the `admin.overrides`
 * configuration key (see `web/projects/studio/app/composables/useAppOverrides.ts`). A single setting
 * drives every surface, so the server models the same shape; only the fields the server renders are
 * read. Deserialize with an `ignoreUnknownKeys` [kotlinx.serialization.json.Json] so the web side can
 * evolve the shape without breaking server rendering.
 *
 * Logo values are content image slugs, resolved against the `/content/image/<slug>` endpoint.
 */
@Serializable
data class AdminOverrides(
    val title: String? = null,
    val hideTitle: Boolean = false,
    val logo: AdminOverridesLogo? = null,
) {
    companion object {
        const val KEY = "admin.overrides"
    }
}

/**
 * The web apps read several logo sub-fields depending on surface: the auth/login screens use [slug],
 * the in-app sidebar uses [expanded] / [collapsed], and the favicon uses [icon]. All are content image
 * slugs. Every field is modelled so the server can pick whichever the deployment has configured.
 */
@Serializable
data class AdminOverridesLogo(
    val slug: String? = null,
    val icon: String? = null,
    val expanded: AdminLogoVariant? = null,
    val collapsed: AdminLogoVariant? = null,
)

@Serializable
data class AdminLogoVariant(
    val dark: String? = null,
    val light: String? = null,
)
