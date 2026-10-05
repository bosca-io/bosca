package bosca.profile.attribute.model

import bosca.db.annotation.ColumnName
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

@Serializable
data class ProfileAttribute(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    val profile: UUID,
    @ColumnName("type_id")
    val typeId: String,
    val visibility: ProfileVisibility,
    val confidence: Int,
    val priority: Int,
    val source: String,
    @Contextual
    val attributes: JsonElement? = null,
    /**
     * Cached Personalization Signals: a `List<PersonalizationSignal { key, value }>` computed
     * at write-time by the recommendations write-time pipeline and read back, keyed, by the recommender.
     * Opaque JSON here — the recommendations domain owns the shape — so this model carries no dependency
     * on it. Null when no signal definition matched this attribute's type.
     */
    @Contextual
    val signals: JsonElement? = null,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    @ColumnName("expiration")
    val expires: OffsetDateTime? = null,
    /** Whether control of this attribute's value (e.g. an email) has been proven. Server-set only. */
    val verified: Boolean = false,
    /**
     * One-time token for a pending verification. INTERNAL ONLY — never exposed via GraphQL (no SDL field
     * and no resolver), so a client can neither read nor set it.
     */
    @ColumnName("verification_token")
    val verificationToken: String? = null,
    /** How control was proven once [verified] (e.g. "email", "google", "admin"). */
    @ColumnName("verification_source")
    val verificationSource: String? = null,
    /**
     * The web origin the verification was requested from (e.g. `https://app.example.com`), captured so the
     * email link routes back to the right host in a multi-host deployment. Raw/unvalidated as stored;
     * validated against the redirect allow-list when the link is built. INTERNAL ONLY — never exposed
     * via GraphQL.
     */
    @ColumnName("verification_origin")
    val verificationOrigin: String? = null
)

fun ProfileAttribute.getAttributeString(name: String): String? {
    return attributes?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull
}

fun List<ProfileAttribute>.getAttributeString(typeId: String, name: String): String? {
    return find { it.typeId == typeId }?.getAttributeString(name)
}