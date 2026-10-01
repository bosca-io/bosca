package bosca.security

import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.model.ProfileInput
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptConfiguration
import bosca.security.encryption.ScryptPassword
import bosca.security.model.CredentialType
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.oauth2.FacebookPicture
import bosca.security.oauth2.FacebookPictureData
import bosca.security.repository.ConsumedExchangeToken
import bosca.security.repository.DuplicatedAccount
import bosca.security.routes.oauth2.OAuth2State
import bosca.security.routes.passkey.CredentialDescriptor
import bosca.security.routes.passkey.PasskeyAuthenticateBeginResponse
import bosca.security.routes.passkey.PasskeyAuthenticateCompleteRequest
import bosca.security.routes.passkey.PasskeyAuthenticateCompleteResponse
import bosca.security.routes.passkey.PasskeyDeleteRequest
import bosca.security.routes.passkey.PasskeyErrorResponse
import bosca.security.routes.passkey.PasskeyInfo
import bosca.security.routes.passkey.PasskeyRegisterBeginResponse
import bosca.security.routes.passkey.PasskeyRegisterCompleteRequest
import bosca.security.routes.passkey.PasskeyRegisterCompleteResponse
import bosca.security.routes.passkey.RelyingParty
import bosca.security.routes.passkey.UserEntity
import bosca.security.routes.passkey.WebAuthnChallengeState
import bosca.security.routes.security.ErrorResponse
import bosca.security.routes.security.ForgotPasswordRequest
import bosca.security.routes.security.LoginRequest
import bosca.security.routes.security.ResetPasswordRequest
import bosca.security.routes.security.SignupRequest
import bosca.security.service.OAuth2Provider
import bosca.security.service.PendingLink
import bosca.security.service.WebAuthnConfiguration
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerializableContractsTest {

    private val serializersModule = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
    }
    private val json = Json {
        serializersModule = this@SerializableContractsTest.serializersModule
    }
    private val jsonWithDefaults = Json {
        serializersModule = this@SerializableContractsTest.serializersModule
        encodeDefaults = true
    }

    private fun <T> exerciseSerialization(
        serializer: KSerializer<T>,
        value: T,
        hasRequiredFields: Boolean = true,
    ) {
        val minimal = json.encodeToString(serializer, value)
        val complete = jsonWithDefaults.encodeToString(serializer, value)
        val decodedMinimal = json.decodeFromString(serializer, minimal)
        val decodedComplete = jsonWithDefaults.decodeFromString(serializer, complete)

        assertEquals(complete, jsonWithDefaults.encodeToString(serializer, decodedMinimal))
        assertEquals(complete, jsonWithDefaults.encodeToString(serializer, decodedComplete))
        if (hasRequiredFields) {
            assertFailsWith<SerializationException> {
                json.decodeFromString(serializer, "{}")
            }
        }
    }

    @Test
    fun `password configuration serialization supports omitted and explicit defaults`() {
        exerciseSerialization(
            ScryptConfiguration.serializer(),
            ScryptConfiguration(
                base64SaltSeparator = "separator",
                base64SignerKey = "signer",
            ),
        )
        assertEquals(
            16,
            ScryptConfiguration(
                algorithm = "SCRYPT",
                base64SaltSeparator = "separator",
                base64SignerKey = "signer",
                memCost = 16,
                rounds = 9,
            ).memCost,
        )
        exerciseSerialization(
            ScryptPassword.serializer(),
            ScryptPassword(
                ScryptCredentialAttributes(
                    salt = "salt",
                    identifier = "person@example.com",
                    passwordHash = "hash",
                ),
            ),
        )
        exerciseSerialization(ArgonPassword.serializer(), ArgonPassword("argon"))
    }

    @Test
    fun `passkey request and response serialization covers defaults and required fields`() {
        val descriptor = CredentialDescriptor("credential")
        val relyingParty = RelyingParty("example.com", "Bosca")
        val user = UserEntity("user", "user", "User")

        exerciseSerialization(CredentialDescriptor.serializer(), descriptor)
        exerciseSerialization(RelyingParty.serializer(), relyingParty)
        exerciseSerialization(UserEntity.serializer(), user)
        exerciseSerialization(
            PasskeyRegisterBeginResponse.serializer(),
            PasskeyRegisterBeginResponse("state", "challenge", relyingParty, user, listOf(descriptor)),
        )
        exerciseSerialization(
            PasskeyRegisterCompleteRequest.serializer(),
            PasskeyRegisterCompleteRequest("state", "Laptop", "credential", "attestation", "client-data"),
        )
        exerciseSerialization(
            PasskeyRegisterCompleteResponse.serializer(),
            PasskeyRegisterCompleteResponse("credential", "Laptop"),
        )
        exerciseSerialization(
            PasskeyAuthenticateBeginResponse.serializer(),
            PasskeyAuthenticateBeginResponse("state", "challenge", "example.com"),
        )
        exerciseSerialization(
            PasskeyAuthenticateCompleteRequest.serializer(),
            PasskeyAuthenticateCompleteRequest(
                "state",
                "credential",
                "authenticator",
                "client-data",
                "signature",
            ),
        )
        assertEquals(
            "user",
            PasskeyAuthenticateCompleteRequest(
                "state",
                "credential",
                "authenticator",
                "client-data",
                "signature",
                userHandle = "user",
            ).userHandle,
        )
        exerciseSerialization(
            PasskeyAuthenticateCompleteResponse.serializer(),
            PasskeyAuthenticateCompleteResponse("token", 2, 1),
        )
        assertEquals(
            "refresh",
            PasskeyAuthenticateCompleteResponse("token", 2, 1, refreshToken = "refresh").refreshToken,
        )
        exerciseSerialization(
            PasskeyInfo.serializer(),
            PasskeyInfo("credential", "Laptop", "created", null, listOf("internal")),
        )
        exerciseSerialization(PasskeyDeleteRequest.serializer(), PasskeyDeleteRequest("credential"))
        exerciseSerialization(PasskeyErrorResponse.serializer(), PasskeyErrorResponse("invalid"))
        exerciseSerialization(
            WebAuthnChallengeState.serializer(),
            WebAuthnChallengeState("challenge"),
        )
    }

    @Test
    fun `security route payload serialization covers defaults and required fields`() {
        exerciseSerialization(LoginRequest.serializer(), LoginRequest("person@example.com", "password"))
        exerciseSerialization(ForgotPasswordRequest.serializer(), ForgotPasswordRequest("person@example.com"))
        exerciseSerialization(ResetPasswordRequest.serializer(), ResetPasswordRequest("password", "token"))
        exerciseSerialization(ErrorResponse.serializer(), ErrorResponse(null))
        exerciseSerialization(
            SignupRequest.serializer(),
            SignupRequest(
                identifier = "person@example.com",
                password = "password",
                profile = ProfileInput(name = "Person", visibility = ProfileVisibility.USER),
                organization = null,
            ),
        )
    }

    @Test
    fun `oauth and account-link serialization covers defaults and required fields`() {
        exerciseSerialization(
            OAuth2Provider.serializer(),
            OAuth2Provider(
                type = "google",
                clientId = "client",
                clientSecret = "secret",
                enabled = false,
                callback = "callback",
                adminCallback = "admin-callback",
                scopes = listOf("openid"),
                userInfoUrl = "https://example.com/user",
                authorizeUrl = "https://example.com/authorize",
                accessTokenUrl = "https://example.com/token",
            ),
        )
        exerciseSerialization(
            OAuth2Provider.serializer(),
            OAuth2Provider(
                type = "google",
                clientId = "client",
                clientSecret = "secret",
                supportedClientIds = setOf("mobile"),
                enabled = false,
                callback = "callback",
                adminCallback = "admin-callback",
                scopes = listOf("openid"),
                userInfoUrl = "https://example.com/user",
                authorizeUrl = "https://example.com/authorize",
                accessTokenUrl = "https://example.com/token",
                pkceEnabled = false,
            ),
        )
        exerciseSerialization(
            OAuth2State.serializer(),
            OAuth2State("state", "google", emptyList(), "/", false, "verifier"),
        )
        exerciseSerialization(
            PendingLink.serializer(),
            PendingLink(
                targetPrincipalId = UUID.random(),
                email = "person@example.com",
                credentialType = CredentialType.OAUTH2,
                credentialAttributes = JsonPrimitive("credential"),
            ),
        )
    }

    @Test
    fun `repository projections and nested oauth payloads enforce required fields`() {
        exerciseSerialization(
            ConsumedExchangeToken.serializer(),
            ConsumedExchangeToken(UUID.random(), false, null),
        )
        exerciseSerialization(
            DuplicatedAccount.serializer(),
            DuplicatedAccount("person@example.com", UUID.random()),
        )
        exerciseSerialization(
            FacebookPicture.serializer(),
            FacebookPicture(),
            hasRequiredFields = false,
        )
        assertEquals(
            "picture",
            FacebookPicture(FacebookPictureData("picture")).data.url,
        )
        exerciseSerialization(
            FacebookPictureData.serializer(),
            FacebookPictureData(),
            hasRequiredFields = false,
        )
        assertEquals("picture", FacebookPictureData("picture").url)
    }

    @Test
    fun `webauthn configuration supports both defaults and explicit values`() {
        val defaults = WebAuthnConfiguration()
        val complete = WebAuthnConfiguration(
            rpName = "Custom",
            rpId = "example.com",
            origins = listOf("https://example.com"),
        )

        assertEquals(defaults, json.decodeFromString(WebAuthnConfiguration.serializer(), "{}"))
        assertEquals(
            defaults,
            jsonWithDefaults.decodeFromString(
                WebAuthnConfiguration.serializer(),
                jsonWithDefaults.encodeToString(WebAuthnConfiguration.serializer(), defaults),
            ),
        )
        assertEquals(
            complete,
            json.decodeFromString(
                WebAuthnConfiguration.serializer(),
                json.encodeToString(WebAuthnConfiguration.serializer(), complete),
            ),
        )
    }
}
