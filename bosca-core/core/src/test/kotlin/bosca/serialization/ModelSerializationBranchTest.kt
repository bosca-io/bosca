@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.serialization

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.SourceStatus
import bosca.di.ProviderRegistry
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLSubscriptionRequest
import bosca.graphql.GraphQLSubscriptionResponse
import bosca.graphql.persistedqueries.PersistedQuery
import bosca.installer.model.PackageInstallationHistory
import bosca.initialization.CacheResource
import bosca.initialization.DatabaseResource
import bosca.initialization.HealthResponse
import bosca.initialization.ReadyResponse
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.search.IndexStorageSystem
import bosca.security.model.CredentialType
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LoginResponse
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.model.PrincipalGroup
import bosca.security.model.PrincipalLogin
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import bosca.security.model.Token
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.slug.model.Slug
import java.time.OffsetDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ModelSerializationBranchTest {
    private lateinit var json: Json
    private lateinit var completeJson: Json

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        json = BoscaApplication(ApplicationConfig.load("".byteInputStream())).json
        completeJson = Json(json) {
            encodeDefaults = true
            explicitNulls = true
        }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private inline fun <reified T> roundTrip(full: T, minimal: T) {
        assertEquals(full, json.decodeFromString<T>(json.encodeToString(full)))
        assertEquals(minimal, json.decodeFromString<T>(json.encodeToString(minimal)))
        assertEquals(full, completeJson.decodeFromString<T>(completeJson.encodeToString(full)))
        assertEquals(minimal, completeJson.decodeFromString<T>(completeJson.encodeToString(minimal)))
    }

    private inline fun <reified T> rejectsMissingRequiredFields() {
        assertFailsWith<SerializationException> { json.decodeFromString<T>("{}") }
    }

    @Test
    fun `security models serialize present and defaulted fields`() {
        val time = OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val principal = Principal(
            id = Uuid.random(),
            created = time,
            modified = time.plusDays(1),
            verified = true,
            anonymous = false,
            attributes = JsonPrimitive("attributes"),
            verificationToken = "verify",
            verificationOrigin = "https://example.com",
            primaryProfileId = Uuid.random(),
            tokenVersion = 3,
            deletedAt = time.plusDays(2),
            hasLoginRevocations = true,
        )
        roundTrip(principal, Principal())

        val group = Group(Uuid.random(), "administrators", "Administrators", GroupType.SYSTEM)
        roundTrip(group, Group(name = "users", description = "Users", type = GroupType.PRINCIPAL))
        rejectsMissingRequiredFields<Group>()

        val token = Token(20, 10, "jwt")
        roundTrip(token, Token(2, 1, "minimal-jwt"))
        rejectsMissingRequiredFields<Token>()
        roundTrip(
            LoginResponse(Uuid.random(), "refresh", token, accountCreated = true, originator = "studio"),
            LoginResponse(Uuid.random(), null, token),
        )
        rejectsMissingRequiredFields<LoginResponse>()

        val attributes = JsonObject(mapOf("identifier" to JsonPrimitive("user@example.com"), "password" to JsonPrimitive("hash")))
        roundTrip(
            PrincipalCredential(7, Uuid.random(), CredentialType.PASSWORD, attributes, "web", "mobile"),
            PrincipalCredential(principal = Uuid.random(), type = CredentialType.PASSWORD, attributesJson = attributes),
        )
        rejectsMissingRequiredFields<PrincipalCredential>()

        roundTrip(
            OAuth2CredentialAttributes("oauth", "local", "tokens", "google"),
            OAuth2CredentialAttributes("oauth"),
        )
        rejectsMissingRequiredFields<OAuth2CredentialAttributes>()
        roundTrip(
            ScryptCredentialAttributes("salt", "local", "user@example.com", "hash"),
            ScryptCredentialAttributes("salt", identifier = "user@example.com", passwordHash = "hash"),
        )
        rejectsMissingRequiredFields<ScryptCredentialAttributes>()
        roundTrip(
            ApiTokenCredentialAttributes(
                identifier = "sha256:hash",
                name = "Automation",
                description = "Deployment",
                tokenPrefix = "bosca_token_",
                scopes = listOf("read"),
                allowedGroups = listOf(Uuid.random().toString()),
                expiresAt = "2025-01-01T00:00:00Z",
                lastUsedAt = "2024-01-01T00:00:00Z",
                lastUsedIp = "127.0.0.1",
                revokedAt = "2024-02-01T00:00:00Z",
                createdBy = Uuid.random().toString(),
            ),
            ApiTokenCredentialAttributes(
                identifier = "sha256:minimal",
                name = "Minimal",
                tokenPrefix = "bosca_token_",
                createdBy = Uuid.random().toString(),
            ),
        )
        rejectsMissingRequiredFields<ApiTokenCredentialAttributes>()
    }

    @Test
    fun `login authorization and initialization models serialize their required and optional states`() {
        val time = OffsetDateTime.parse("2024-05-01T00:00:00Z")
        val principalId = Uuid.random()
        roundTrip(
            PrincipalLogin(7, principalId, "passkey", time.plusHours(1), time),
            PrincipalLogin(principalId = principalId, method = "password"),
        )
        rejectsMissingRequiredFields<PrincipalLogin>()

        val principal = Principal(id = principalId, created = time, modified = time)
        val group = Group(Uuid.random(), "administrators", "Administrators", GroupType.SYSTEM)
        val authenticated = AuthenticatedPrincipal(principal, listOf(group), loginId = 7)
        roundTrip(authenticated, AuthenticatedPrincipal(principal, emptyList()))
        rejectsMissingRequiredFields<AuthenticatedPrincipal>()
        val decodedAuthenticated = json.decodeFromString<AuthenticatedPrincipal>(json.encodeToString(authenticated))
        assertEquals(principal, decodedAuthenticated.asPrincipal())
        assertTrue(decodedAuthenticated.hasGroup(group))
        assertEquals(7, decodedAuthenticated.loginId)
        val decodedWithoutLogin = completeJson.decodeFromString<AuthenticatedPrincipal>(
            completeJson.encodeToString(AuthenticatedPrincipal(principal, emptyList())),
        )
        assertEquals(null, decodedWithoutLogin.loginId)

        val entityId = Uuid.random()
        val permission = Permission(group.id, PermissionAction.VIEW)
        roundTrip(permission, Permission(Uuid.random(), PermissionAction.EDIT))
        rejectsMissingRequiredFields<Permission>()
        roundTrip(
            PermissionInput(PermissionAction.MANAGE, entityId, group.id),
            PermissionInput(PermissionAction.LIST, Uuid.random(), Uuid.random()),
        )
        rejectsMissingRequiredFields<PermissionInput>()
        roundTrip(PrincipalGroup(principalId, group.id), PrincipalGroup(Uuid.random(), Uuid.random()))
        rejectsMissingRequiredFields<PrincipalGroup>()
        roundTrip(
            SignupToken(SignupTokenType.ORGANIZATION, "organization-token"),
            SignupToken(SignupTokenType.COMMUNITY_GROUP, "community-token"),
        )
        rejectsMissingRequiredFields<SignupToken>()

        roundTrip(ReadyResponse("ready"), ReadyResponse("live"))
        rejectsMissingRequiredFields<ReadyResponse>()
        roundTrip(CacheResource("metadata"), CacheResource("content"))
        rejectsMissingRequiredFields<CacheResource>()
        roundTrip(
            DatabaseResource("primary", 10, 4, 2, true),
            DatabaseResource("read-only", 5, 0, 0, false),
        )
        rejectsMissingRequiredFields<DatabaseResource>()
        roundTrip(
            HealthResponse(
                databases = listOf(DatabaseResource("primary", 10, 4, 2, true)),
                caches = listOf(CacheResource("metadata")),
                ok = true,
            ),
            HealthResponse(emptyList(), emptyList(), false),
        )
        rejectsMissingRequiredFields<HealthResponse>()
    }

    @Test
    fun `collection models serialize present defaulted and required fields`() {
        val time = OffsetDateTime.parse("2024-02-01T00:00:00Z")
        val fullKey = CollectionCacheKeyId(
            Uuid.random(),
            "published",
            Uuid.random(),
            10,
            25,
            "en-US",
            listOf("article"),
            false,
            false,
        )
        roundTrip(fullKey, CollectionCacheKeyId(Uuid.random()))
        rejectsMissingRequiredFields<CollectionCacheKeyId>()

        val fullCollection = Collection(
            id = Uuid.random(),
            name = "Collection",
            languageTag = "en",
            type = CollectionType.FOLDER,
            description = "Description",
            attributes = JsonPrimitive("attributes"),
            systemAttributes = JsonPrimitive("system"),
            labels = listOf("featured"),
            created = time,
            modified = time.plusDays(1),
            ready = time.plusDays(2),
            etag = "etag",
            enabled = false,
            ordering = JsonPrimitive("ordering"),
            workflowStateId = "published",
            workflowStatePendingId = "pending",
            workflowStateValid = time.plusDays(3),
            deleteWorkflowId = "delete",
            public = true,
            publicList = true,
            publicSupplementary = true,
            locked = true,
            itemsLocked = true,
            deleted = true,
            templateMetadataId = Uuid.random(),
            templateMetadataVersion = 2,
            searchable = false,
        )
        val minimalCollection = Collection(name = "Minimal", languageTag = "en", workflowStateId = "pending")
        roundTrip(fullCollection, minimalCollection)
        rejectsMissingRequiredFields<Collection>()

        val fullVariant = CollectionLanguageVariant(
            Uuid.random(),
            "fr",
            "Variant",
            "Description",
            JsonPrimitive("attributes"),
            time,
            "published",
            "pending",
            time.plusDays(1),
            "delete",
            true,
            true,
            true,
            false,
        )
        val minimalVariant = CollectionLanguageVariant(Uuid.random(), "en", "Minimal")
        roundTrip(fullVariant, minimalVariant)
        rejectsMissingRequiredFields<CollectionLanguageVariant>()

        val collectionId = Uuid.random()
        val metadataId = Uuid.random()
        roundTrip(
            CollectionMetadataRelationship(collectionId, metadataId, "child", JsonPrimitive("attributes")),
            CollectionMetadataRelationship(collectionId, metadataId, "child"),
        )
        rejectsMissingRequiredFields<CollectionMetadataRelationship>()
        roundTrip(
            CollectionLanguageVariantMetadataRelationship(collectionId, metadataId, "fr", "child", JsonPrimitive("attributes")),
            CollectionLanguageVariantMetadataRelationship(collectionId, metadataId, "en", "child"),
        )
        rejectsMissingRequiredFields<CollectionLanguageVariantMetadataRelationship>()
    }

    @Test
    fun `content profile slug and installer models serialize both masks`() {
        val time = OffsetDateTime.parse("2024-03-01T00:00:00Z")
        roundTrip(
            Profile(
                Uuid.random(),
                ProfileType.ORGANIZATION,
                Uuid.random(),
                Uuid.random(),
                "Organization",
                ProfileVisibility.PUBLIC,
                true,
                time,
                time.plusDays(1),
                time.plusDays(2),
            ),
            Profile(type = ProfileType.GENERIC, name = "Minimal", visibility = ProfileVisibility.USER),
        )
        rejectsMissingRequiredFields<Profile>()

        roundTrip(
            Slug("article", Uuid.random(), Uuid.random(), "en", Uuid.random()),
            Slug("minimal"),
        )
        rejectsMissingRequiredFields<Slug>()

        roundTrip(
            PackageInstallationHistory(Uuid.random(), "core", "2", time),
            PackageInstallationHistory(key = "core", version = "1"),
        )
        rejectsMissingRequiredFields<PackageInstallationHistory>()

        roundTrip(IndexStorageSystem(Uuid.random(), "primary"), IndexStorageSystem())
    }

    @Test
    fun `GraphQL and relationship models serialize optional masks`() {
        val variables = JsonObject(mapOf("id" to JsonPrimitive(1)))
        val extensions = JsonObject(mapOf("trace" to JsonPrimitive(true)))
        roundTrip(
            GraphQLRequest("query Named { value }", extensions, "Named", variables),
            GraphQLRequest(),
        )
        roundTrip(
            GraphQLSubscriptionRequest("subscribe", "one", variables),
            GraphQLSubscriptionRequest("subscribe"),
        )
        rejectsMissingRequiredFields<GraphQLSubscriptionRequest>()
        roundTrip(
            GraphQLSubscriptionResponse("one", "next", variables),
            GraphQLSubscriptionResponse(type = "complete"),
        )
        rejectsMissingRequiredFields<GraphQLSubscriptionResponse>()
        roundTrip(
            PersistedQuery("studio", "{ value }", "hash"),
            PersistedQuery(query = "{ value }", sha256 = "hash"),
        )
        rejectsMissingRequiredFields<PersistedQuery>()

        val id1 = Uuid.random()
        val id2 = Uuid.random()
        roundTrip(
            MetadataRelationship(id1, id2, "related", JsonPrimitive("attributes")),
            MetadataRelationship(id1, id2, "related"),
        )
        rejectsMissingRequiredFields<MetadataRelationship>()
    }

    @Test
    fun `metadata serialization exercises all optional field masks`() {
        val time = OffsetDateTime.parse("2024-04-01T00:00:00Z")
        val full = Metadata(
            id = Uuid.random(),
            version = 2,
            activeVersion = 2,
            parentId = Uuid.random(),
            name = "Full",
            type = MetadataType.VARIANT,
            contentType = "text/plain",
            contentLength = 100,
            languageTag = "fr",
            labels = listOf("featured"),
            attributes = JsonPrimitive("attributes"),
            systemAttributes = JsonPrimitive("system"),
            deleted = true,
            public = true,
            publicContent = true,
            publicSupplementary = true,
            created = time,
            modified = time.plusDays(1),
            uploaded = time.plusDays(2),
            ready = time.plusDays(3),
            workflowStateId = "published",
            workflowStatePendingId = "pending",
            workflowStateValid = time.plusDays(4),
            sourceId = Uuid.random(),
            sourceIdentifier = "source",
            sourceUrl = "https://example.com",
            sourceStatus = SourceStatus.IMPORTED,
            deleteWorkflowId = "delete",
            permissionMutation = 2,
            etag = "etag",
            locked = true,
            syncVariantCollections = false,
            syncVariantRelationships = false,
            searchable = false,
            commentsEnabled = true,
            commentRepliesEnabled = true,
        )
        val minimal = Metadata(
            name = "Minimal",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
        )

        roundTrip(full, minimal)
        rejectsMissingRequiredFields<Metadata>()
    }
}
