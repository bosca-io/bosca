package bosca.core.security

import bosca.core.security.model.AuthResponse
import bosca.core.security.model.BoscaToken
import bosca.core.security.model.Credential
import bosca.core.security.model.Group
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileAttribute
import bosca.core.security.type.CredentialType
import bosca.core.security.type.GroupType
import bosca.core.security.type.ProfileType
import bosca.core.security.type.ProfileVisibility
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

// ---------------------------------------------------------------------------
// GraphQL-over-HTTP envelope
// ---------------------------------------------------------------------------

@Serializable
internal data class GraphQLRequest(val query: String, val variables: JsonObject)

@Serializable
internal data class GraphQLResponse<T>(
    val data: T? = null,
    val errors: List<GraphQLError>? = null,
)

@Serializable
internal data class GraphQLError(
    val message: String,
    val extensions: JsonObject? = null,
)

/** Serializes [Uuid] as its canonical string form (the GraphQL `UUID` scalar). */
internal object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}

// ---------------------------------------------------------------------------
// Shared response fragments
// ---------------------------------------------------------------------------

@Serializable
internal data class TokenDto(val token: String, val expiresAt: Int, val issuedAt: Int)

@Serializable
internal data class PrincipalLiteDto(
    @Serializable(UuidSerializer::class) val id: Uuid,
    val verified: Boolean,
    @Serializable(UuidSerializer::class) val primaryProfileId: Uuid? = null,
)

@Serializable
internal data class ProfileAttributeDto(
    val typeId: String,
    val attributes: JsonElement? = null,
    val source: String,
    val priority: Int,
    val confidence: Int,
    val visibility: ProfileVisibility,
)

@Serializable
internal data class ProfileDto(
    @Serializable(UuidSerializer::class) val id: Uuid,
    val name: String,
    val type: ProfileType,
    val visibility: ProfileVisibility,
    val slug: String? = null,
    val isPrimary: Boolean,
    val attributes: List<ProfileAttributeDto>,
)

@Serializable
internal data class LoginResponseDto(
    val principal: PrincipalLiteDto,
    val profile: List<ProfileDto>? = null,
    val token: TokenDto,
    val refreshToken: String? = null,
)

// ---------------------------------------------------------------------------
// Per-operation data envelopes (mirror the GraphQL selection paths)
// ---------------------------------------------------------------------------

@Serializable
internal data class LoginEnvelope(val security: LoginSecurity) {
    @Serializable data class LoginSecurity(val login: LoginPayload)
    @Serializable data class LoginPayload(
        val password: LoginResponseDto? = null,
        val refreshToken: LoginResponseDto? = null,
        val exchangeToken: LoginResponseDto? = null,
    )
}

@Serializable
internal data class SignupEnvelope(val security: SignupSecurity) {
    @Serializable data class SignupSecurity(val signup: SignupPayload)
    @Serializable data class SignupPayload(
        val password: SignupPasswordDto? = null,
        val thirdparty: LoginResponseDto? = null,
    )
    @Serializable data class SignupPasswordDto(
        @Serializable(UuidSerializer::class) val id: Uuid,
        val verified: Boolean,
    )
}

@Serializable
internal data class CurrentPrincipalEnvelope(val security: PrincipalSecurity) {
    @Serializable data class PrincipalSecurity(val principals: Principals)
    @Serializable data class Principals(val current: CurrentPrincipalDto)
    @Serializable data class CurrentPrincipalDto(
        @Serializable(UuidSerializer::class) val id: Uuid,
        val verified: Boolean,
        @Serializable(UuidSerializer::class) val primaryProfileId: Uuid? = null,
        val credentials: List<CredentialDto> = emptyList(),
    )
    @Serializable data class CredentialDto(val identifier: String, val type: CredentialType)
}

@Serializable
internal data class CurrentGroupsEnvelope(val security: GroupSecurity) {
    @Serializable data class GroupSecurity(val principals: Principals)
    @Serializable data class Principals(val current: CurrentGroups)
    @Serializable data class CurrentGroups(val groups: List<GroupDto>)
    @Serializable data class GroupDto(
        @Serializable(UuidSerializer::class) val id: Uuid,
        val name: String,
        val description: String,
        val type: GroupType,
    )
}

@Serializable
internal data class CurrentProfilesEnvelope(val profiles: ProfilesCurrent) {
    @Serializable data class ProfilesCurrent(val current: List<ProfileDto>? = null)
}

@Serializable
internal data class EditProfileEnvelope(val profiles: ProfilesEdit) {
    @Serializable data class ProfilesEdit(val edit: ProfileDto? = null)
}

// ---------------------------------------------------------------------------
// DTO -> domain mappers (formerly AuthMappers, sans Apollo)
// ---------------------------------------------------------------------------

internal fun LoginResponseDto.toDomain(): AuthResponse = AuthResponse(
    principal = Principal(
        id = principal.id,
        verified = principal.verified,
        primaryProfileId = principal.primaryProfileId,
    ),
    profile = profile?.map { it.toDomain() },
    token = BoscaToken(token = token.token, expiresAt = token.expiresAt, issuedAt = token.issuedAt),
    refreshToken = refreshToken,
)

internal fun ProfileDto.toDomain(): Profile = Profile(
    id = id,
    name = name,
    type = type,
    visibility = visibility,
    slug = slug,
    isPrimary = isPrimary,
    attributes = attributes.map { it.toDomain() },
)

internal fun ProfileAttributeDto.toDomain(): ProfileAttribute = ProfileAttribute(
    typeId = typeId,
    attributes = attributes,
    source = source,
    priority = priority,
    confidence = confidence,
    visibility = visibility,
)

internal fun CurrentPrincipalEnvelope.CurrentPrincipalDto.toDomain(): Principal = Principal(
    id = id,
    verified = verified,
    primaryProfileId = primaryProfileId,
    credentials = credentials.map { Credential(identifier = it.identifier, type = it.type) },
)

internal fun CurrentGroupsEnvelope.GroupDto.toDomain(): Group =
    Group(id = id, name = name, description = description, type = type)

internal fun SignupEnvelope.SignupPasswordDto.toDomain(): Principal =
    Principal(id = id, verified = verified, primaryProfileId = null)
