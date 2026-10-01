package bosca.security.oauth2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FacebookUser(
    override val id: String,
    override val name: String? = null,
    override val email: String? = null,
    @SerialName("picture")
    val pictureObject: FacebookPicture? = FacebookPicture()
) : ThirdPartyUser {

    override val givenName: String?
        get() {
            val value = name ?: return null
            return value.substringBefore(" ").trim()
        }

    override val familyName: String?
        get() {
            val value = name ?: return null
            return value.substringAfter(" ", missingDelimiterValue = "").trim()
        }

    override val picture: String?
        get() {
            val value = pictureObject ?: return null
            return value.data.url
        }

    // Facebook's Graph API only returns an email address when it has already been verified,
    // so the presence of an email is itself the verification signal.
    override val emailVerified: Boolean
        get() = !email.isNullOrBlank()
}

@Serializable
data class FacebookPicture(
    val data: FacebookPictureData = FacebookPictureData()
)

@Serializable
data class FacebookPictureData(
    val url: String = ""
)
