package bosca.profile.configuration

/** Public application origins used to build links in social notifications. */
data class SocialNotificationConfiguration(
    val applicationUrl: String,
    val profileApplicationUrl: String = applicationUrl,
) {
    /** Builds an absolute link while avoiding duplicate path separators. */
    fun url(path: String): String = "${applicationUrl.trimEnd('/')}/${path.trimStart('/')}"

    /** Builds an absolute profile-application link while avoiding duplicate path separators. */
    fun profileUrl(path: String): String = "${profileApplicationUrl.trimEnd('/')}/${path.trimStart('/')}"
}
