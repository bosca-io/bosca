package bosca.git.ui

import bosca.git.ui.graphql.GitUiProfilesData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Resolves commit metadata using Studio's profile, slug and neutral fallback rules. */
internal fun resolveGitCommitAuthor(
    profiles: List<GitUiProfilesData.Profiles.Current>?
): Pair<String, String> {
    val profile = profiles?.firstOrNull { it.isPrimary } ?: profiles?.firstOrNull()
    val name =
        profile?.name?.trim()?.takeIf(String::isNotBlank)
            ?: profile?.slug?.trim()?.takeIf(String::isNotBlank)
            ?: "Studio User"
    val emailAttributes =
        profile?.attributes?.firstOrNull { it.typeId == "bosca.profiles.email" }?.attributes
            as? JsonObject
    val emailValue = emailAttributes?.get("email") as? JsonPrimitive
    val email =
        emailValue?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotBlank)
            ?: "studio@bosca.io"
    return name to email
}
