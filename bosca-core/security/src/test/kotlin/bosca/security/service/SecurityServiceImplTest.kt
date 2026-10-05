package bosca.security.service

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.withRequestCache
import bosca.community.service.CommunityService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.dispatch
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.CredentialType
import bosca.security.model.HashedEncodedPassword
import bosca.security.model.SimplePasswordAttributes
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredential
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.repository.GroupRepository
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.security.repository.PrincipalExchangeTokenRepository
import bosca.security.repository.PrincipalGroupRepository
import bosca.security.repository.PrincipalRefreshTokenRepository
import bosca.security.repository.PrincipalRepository
import bosca.security.repository.PrincipalEmailRepository
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import com.auth0.jwt.interfaces.Payload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pwHash(value: String): HashedEncodedPassword =
    object : HashedEncodedPassword { override val hash = value }

@OptIn(InternalDI::class)
class SecurityServiceImplTest {

    private val groupRepository = mockk<GroupRepository>()
    private val principalRepository = mockk<PrincipalRepository>()
    private val principalGroupsRepository = mockk<PrincipalGroupRepository>()
    private val principalRefreshTokensRepository = mockk<PrincipalRefreshTokenRepository>()
    private val principalExchangeTokenRepository = mockk<PrincipalExchangeTokenRepository>()
    private val credentialsRepository = mockk<PrincipalCredentialsRepository>()
    private val principalEmailRepository = mockk<PrincipalEmailRepository>(relaxed = true)
    private val thirdPartyTokenVerifier = mockk<ThirdPartyTokenVerifier>(relaxed = true)
    private val pubSubService = testPubSubService()
    private val argonPasswordEncoder = mockk<PasswordEncoder<ArgonPassword>>()
    private val scryptPasswordEncoder = mockk<ObjectProvider<PasswordEncoder<ScryptPassword>>>()
    private val securityConfiguration = mockk<ObjectProvider<SecurityConfiguration>>()
    private val profileService = mockk<ObjectProvider<ProfileService>>()
    private val profileServiceInstance = mockk<ProfileService>()
    private val organizationService = mockk<ObjectProvider<OrganizationService>>()
    private val organizationServiceInstance = mockk<OrganizationService>()
    private val communityService = mockk<ObjectProvider<CommunityService>>()
    private val communityServiceInstance = mockk<CommunityService>()
    private val attributeVerificationService = mockk<bosca.profile.attribute.verification.AttributeVerificationService>(relaxed = true)
    private val attributeVerificationServiceProvider = mockk<ObjectProvider<bosca.profile.attribute.verification.AttributeVerificationService>>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val passwordResetEmailRequests = mutableListOf<PasswordResetEmailRequested>()

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: SecurityServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }
        provides<bosca.profile.attribute.verification.AttributeVerificationService> { attributeVerificationService }
        provides<PubSubService> { pubSubService }

        mockkStatic("bosca.security.events.PasswordResetEmailRequestedExtKt")
        coEvery { any<PasswordResetEmailRequested>().dispatch() } coAnswers {
            passwordResetEmailRequests += firstArg<PasswordResetEmailRequested>()
        }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        coEvery { profileService.get() } returns profileServiceInstance
        coEvery { attributeVerificationServiceProvider.get() } returns attributeVerificationService
        coEvery { profileServiceInstance.getByPrincipal(any()) } returns emptyList()
        coEvery { profileServiceInstance.getPrimaryProfile(any()) } returns null
        coEvery { profileServiceInstance.verifyByToken(any(), any()) } returns emptyList()
        coEvery { profileServiceInstance.setVerificationToken(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { organizationService.get() } returns organizationServiceInstance
        coEvery { communityService.get() } returns communityServiceInstance

        val config = mockk<SecurityConfiguration>()
        every { config.appUrl } returns "https://app"
        every { config.welcomeUrl } returns "https://app/welcome"
        every { config.allowedAppOrigins } returns listOf("https://app.example.com")
        coEvery { securityConfiguration.get() } returns config

        // Default stubs for the mass-invalidation path. Every flow
        // that mutates credentials or identifiers calls
        // `bumpTokenVersion`, which in turn hits these two methods.
        // Stubbing them at the class level keeps the per-test setup
        // focused on the behavior under test; tests that care about
        // the bump itself add their own `coVerify`.
        coEvery { principalRepository.incrementTokenVersion(any()) } returns 1
        coEvery { principalRefreshTokensRepository.deleteByPrincipalId(any()) } returns Unit
        coEvery { principalRepository.revokeSessions(any()) } returns Unit
        coEvery { principalRepository.markHasLoginRevocations(any()) } returns Unit
        // Every successful sign-in stamps principals.last_login.
        coEvery { principalRepository.touchLastLogin(any()) } returns Unit
        coEvery { principalRepository.addLogin(any(), any()) } returns PrincipalLogin(1, UUID.NIL, "test")
        // addPrincipal/attachCredential pre-check identifier ownership; default to "no existing owner".
        coEvery { principalRepository.getPrincipalIdByIdentifierAndType(any(), any()) } returns null
        coEvery { principalRepository.getPrincipalIdByIdentifier(any()) } returns null
        // getPrincipalByEmail falls back to the uniqueness backstop; default to "not registered".
        coEvery { principalEmailRepository.getByEmail(any()) } returns null
        coEvery { principalEmailRepository.getEmailsByPrincipal(any()) } returns emptyList()

        service = newService(pubSubService)
    }

    private fun newService(pubSubService: PubSubService): SecurityServiceImpl = SecurityServiceImpl(
            groupRepository,
            principalRepository,
            principalGroupsRepository,
            principalRefreshTokensRepository,
            principalExchangeTokenRepository,
            credentialsRepository,
            testJson,
            argonPasswordEncoder,
            scryptPasswordEncoder,
            securityConfiguration,
            profileService,
            organizationService,
            communityService,
            attributeVerificationServiceProvider,
            principalEmailRepository,
            thirdPartyTokenVerifier,
            pubSubService,
        )

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        unmockkStatic("bosca.security.events.PasswordResetEmailRequestedExtKt")
    }

    @Test
    fun `addPrincipal should create a new principal and credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = false)
            val credential = SimplePasswordAttributes("user", "pass")
            val groups = listOf(Group(name = "group1", description = "desc", type = GroupType.SYSTEM))

            coEvery { principalRepository.add(any()) } returns principal
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { argonPasswordEncoder.encode("pass") } returns ArgonPassword("hashed")
            coEvery { groupRepository.add(any()) } returns Group(id = UUID.random(), name = "group.user", description = "desc", type = GroupType.PRINCIPAL)
            coEvery { principalGroupsRepository.add(any()) } returns mockk()

            val result = service.addPrincipal(principal, credential, groups, addPrincipalGroup = true)

            assertNotNull(result)
            coVerify {
                // No principal-level verification token is stamped at sign-up: email verification records its
                // token on the email ATTRIBUTE, and principals.verification_token is now exclusively the
                // password-reset token. Stamping one here would linger as a usable reset credential.
                principalRepository.add(match { it.verificationToken == null })
                credentialsRepository.add(match {
                    it.principal == principal.id &&
                            it.type == CredentialType.PASSWORD
                })
                groupRepository.add(match { it.type == GroupType.PRINCIPAL })
                principalGroupsRepository.add(any()) // for the principal group
                principalGroupsRepository.add(any()) // for the provided group
            }
        }
    }

    @Test
    fun `addPrincipal should not generate verification token if already verified`() = runTest {
        withRequestCache {
            val principal = Principal(id = UUID.random(), verified = true)
            val credential = OAuth2CredentialAttributes("google", "sub")

            coEvery { principalRepository.add(any()) } returns principal
            coEvery { credentialsRepository.add(any()) } returns mockk()

            service.addPrincipal(principal, credential, emptyList(), addPrincipalGroup = false)

            coVerify {
                principalRepository.add(match { it.verificationToken == null })
                credentialsRepository.add(match { it.type == CredentialType.OAUTH2 })
            }
        }
    }

    @Test
    fun `group cache keys serialize locally and remotely`() {
        val id = GroupId("administrators", GroupType.SYSTEM)
        val key = GroupCacheKey("security:groups", id)

        assertEquals("security:groups", key.cacheName)
        assertEquals(id, key.key)
        assertEquals(key, GroupCacheKeySerializer.toLocalKey("security:groups", id))
        val remote = key.toRemoteKey(false)
        assertEquals(key, GroupCacheKeySerializer.fromRemoteKey(remote))
        assertTrue(key.toRemoteKey(true).startsWith("gid"))
    }

    @Test
    fun `group cache key rejects names containing the cache separator`() {
        val error = assertFailsWith<IllegalArgumentException> {
            GroupId("invalid::group", GroupType.SYSTEM)
        }

        assertEquals("group name must not contain '::'", error.message)
    }

    @Test
    fun `simple repository projections and configuration delegate`() = runTest {
        withRequestCache {
            val config = mockk<SecurityConfiguration> {
                every { expirationTimeInSeconds } returns 3600
            }
            coEvery { securityConfiguration.get() } returns config
            coEvery { principalRepository.getPrincipals(2, 3) } returns emptyList()
            coEvery { principalRepository.getPrincipals(4, 5, true) } returns emptyList()

            assertEquals(3600, service.getMaxTokenAgeInSeconds())
            assertEquals(emptyList(), service.getPrincipals(2, 3))
            assertEquals(emptyList(), service.getPrincipals(4, 5, true))
        }
    }

    @Test
    fun `credential lookup by type filters mixed credentials`() = runTest {
        val principal = Principal(id = UUID.random())
        val password = PrincipalCredential(
            principal = principal.id,
            attributes = CredentialPasswordAttributes("person@example.com", pwHash("hash")),
        )
        val oauth = PrincipalCredential(
            principal = principal.id,
            attributes = OAuth2CredentialAttributes("subject", null, null, "google"),
        )
        coEvery { credentialsRepository.getByPrincipalId(principal.id) } returns listOf(password, oauth)

        assertEquals(listOf(password), service.getCredentials(principal, CredentialType.PASSWORD))
        assertEquals(listOf(oauth), service.getCredentials(principal, CredentialType.OAUTH2))
        assertEquals(emptyList(), service.getCredentials(principal, CredentialType.PASSKEY))
    }

    @Test
    fun `group and principal list queries delegate and apply defaults`() = runTest {
        withRequestCache {
            val group = Group(UUID.random(), "editors", "Editors", GroupType.SYSTEM)
            val principal = Principal(id = UUID.random())
            val membership = bosca.security.model.PrincipalGroup(principal.id, group.id)
            coEvery { groupRepository.getAll(GroupType.SYSTEM, 1, 2) } returns listOf(group)
            coEvery {
                groupRepository.findByNameOrDescription("edit", GroupType.PRINCIPAL, 3, 4)
            } returns listOf(group)
            coEvery { principalGroupsRepository.getGroupsByPrincipalId(group.id) } returns listOf(membership)
            coEvery { principalRepository.getPrincipalsById(listOf(principal.id)) } returns listOf(principal)
            val principalCache = mockk<Cache<UUID>>(relaxed = true)
            every { principalCache.keySerializer } returns UUIDKeySerializer
            coEvery { cacheManager.getCache<UUID>("security:principal:id") } returns principalCache
            coEvery { principalCache.getBatch(any()) } answers {
                firstArg<List<*>>().map {
                    mockk<CacheValue> {
                        every { exists } returns false
                        every { value } returns null
                    }
                }
            }

            assertEquals(listOf(group), service.getGroups(null, 1, 2))
            assertEquals(
                listOf(group),
                service.findGroups("edit", GroupType.PRINCIPAL, 3, 4),
            )
            assertEquals(listOf(principal), service.getPrincipalsByGroup(group))
            assertEquals(listOf(principal), service.getPrincipalsById(listOf(principal.id)))
        }
    }

    @Test
    fun `group lookup failure and empty principal groups are explicit`() = runTest {
        withRequestCache {
            val groupId = UUID.random()
            coEvery { groupRepository.getById(groupId) } returns null
            coEvery { principalGroupsRepository.getPrincipalGroups(any<UUID>()) } returns emptyList()

            assertFailsWith<IllegalStateException> { service.getGroupById(groupId) }
            assertEquals(emptyList(), service.getPrincipalGroups(UUID.random()))
        }
    }

    @Test
    fun `negative cached principal groups deny memberships without a repository lookup`() = runTest {
        val principalId = UUID.random()
        val principalGroupsCache = mockk<Cache<UUID>>(relaxed = true)
        every { principalGroupsCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("security:principal:groups") } returns principalGroupsCache
        coEvery { principalGroupsCache.get(any()) } returns object : CacheValue {
            override val exists = true
            override val value: String? = null
        }

        withRequestCache {
            assertEquals(emptyList(), service.getPrincipalGroups(principalId))
        }

        coVerify(exactly = 0) { principalGroupsRepository.getPrincipalGroups(principalId) }
    }

    @Test
    fun `group lookup and principal groups return repository values`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val group = Group(UUID.random(), "members", "Members", GroupType.SYSTEM)
            coEvery { groupRepository.getById(group.id) } returns group
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns listOf(group)

            assertEquals(group, service.getGroupById(group.id))
            assertEquals(listOf(group), service.getPrincipalGroups(principalId))
        }
    }

    @Test
    fun `principal groups batch resolves memberships and groups with bounded repository calls`() = runTest {
        val principalId1 = UUID.random()
        val principalId2 = UUID.random()
        val group1 = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        val group2 = Group(UUID.random(), "community.users", "Community users", GroupType.SYSTEM)
        val principalGroupsCache = mockk<Cache<UUID>>(relaxed = true)
        every { principalGroupsCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("security:principal:groups") } returns principalGroupsCache
        coEvery { principalGroupsCache.getBatch(any()) } answers {
            firstArg<List<*>>().map {
                mockk<CacheValue> {
                    every { exists } returns false
                    every { value } returns null
                }
            }
        }
        coEvery {
            principalGroupsRepository.getPrincipalGroups(listOf(principalId1, principalId2))
        } returns listOf(
            bosca.security.model.PrincipalGroup(principalId1, group1.id),
            bosca.security.model.PrincipalGroup(principalId1, group2.id),
            bosca.security.model.PrincipalGroup(principalId2, group1.id),
        )
        coEvery { groupRepository.getByIds(listOf(group1.id, group2.id)) } returns listOf(group1, group2)

        withRequestCache {
            assertEquals(
                mapOf(
                    principalId1 to listOf(group1, group2),
                    principalId2 to listOf(group1),
                ),
                service.getPrincipalGroups(listOf(principalId1, principalId2, principalId1)),
            )
        }

        coVerify(exactly = 1) {
            principalGroupsRepository.getPrincipalGroups(listOf(principalId1, principalId2))
        }
        coVerify(exactly = 1) { groupRepository.getByIds(listOf(group1.id, group2.id)) }
        coVerify(exactly = 0) { principalGroupsRepository.getPrincipalGroups(any<UUID>()) }
    }

    @Test
    fun `principal groups batch returns empty groups without loading group rows`() = runTest {
        val principalId1 = UUID.random()
        val principalId2 = UUID.random()
        val principalGroupsCache = mockk<Cache<UUID>>(relaxed = true)
        every { principalGroupsCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("security:principal:groups") } returns principalGroupsCache
        coEvery { principalGroupsCache.getBatch(any()) } answers {
            firstArg<List<*>>().map {
                mockk<CacheValue> {
                    every { exists } returns false
                    every { value } returns null
                }
            }
        }
        coEvery {
            principalGroupsRepository.getPrincipalGroups(listOf(principalId1, principalId2))
        } returns emptyList()

        withRequestCache {
            assertEquals(
                mapOf(principalId1 to emptyList(), principalId2 to emptyList()),
                service.getPrincipalGroups(listOf(principalId1, principalId2)),
            )
        }

        coVerify(exactly = 0) { groupRepository.getByIds(any()) }
    }

    @Test
    fun `principal groups batch preserves an existing negative cache entry`() = runTest {
        val principalId = UUID.random()
        val principalGroupsCache = mockk<Cache<UUID>>(relaxed = true)
        every { principalGroupsCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("security:principal:groups") } returns principalGroupsCache
        coEvery { principalGroupsCache.getBatch(any()) } returns listOf(
            mockk<CacheValue> {
                every { exists } returns true
                every { value } returns null
            },
        )

        withRequestCache {
            assertEquals(mapOf(principalId to emptyList()), service.getPrincipalGroups(listOf(principalId)))
        }

        coVerify(exactly = 0) { principalGroupsRepository.getPrincipalGroups(any<List<UUID>>()) }
        coVerify(exactly = 0) { groupRepository.getByIds(any()) }
    }

    @Test
    fun `principal groups batch rejects a membership whose group is missing`() = runTest {
        val principalId = UUID.random()
        val groupId = UUID.random()
        val principalGroupsCache = mockk<Cache<UUID>>(relaxed = true)
        every { principalGroupsCache.keySerializer } returns UUIDKeySerializer
        coEvery { cacheManager.getCache<UUID>("security:principal:groups") } returns principalGroupsCache
        coEvery { principalGroupsCache.getBatch(any()) } returns listOf(
            mockk<CacheValue> {
                every { exists } returns false
                every { value } returns null
            },
        )
        coEvery { principalGroupsRepository.getPrincipalGroups(listOf(principalId)) } returns listOf(
            bosca.security.model.PrincipalGroup(principalId, groupId),
        )
        coEvery { groupRepository.getByIds(listOf(groupId)) } returns emptyList()

        withRequestCache {
            val error = assertFailsWith<IllegalStateException> {
                service.getPrincipalGroups(listOf(principalId))
            }
            assertTrue(error.message.orEmpty().contains(groupId.toString()))
        }
    }

    @Test
    fun `principal groups batch accepts an empty request without repository access`() = runTest {
        assertEquals(emptyMap(), service.getPrincipalGroups(emptyList()))

        coVerify(exactly = 0) { principalGroupsRepository.getPrincipalGroups(any<List<UUID>>()) }
        coVerify(exactly = 0) { groupRepository.getByIds(any()) }
    }

    @Test
    fun `group updates and deletes delegate`() = runTest {
        withRequestCache {
            val group = Group(UUID.random(), "editors", "Editors", GroupType.SYSTEM)
            coEvery { groupRepository.update(group) } returns group
            coEvery { groupRepository.deleteById(group.id) } returns Unit

            assertEquals(group, service.editGroup(group))
            service.deleteGroup(group.id)

            coVerify { groupRepository.update(group) }
            coVerify { groupRepository.deleteById(group.id) }
        }
    }

    @Test
    fun `group writes reject names containing the cache separator`() = runTest {
        val invalidGroup = Group(UUID.random(), "invalid::group", "Invalid", GroupType.SYSTEM)

        assertFailsWith<IllegalArgumentException> {
            service.addGroup(invalidGroup)
        }
        assertFailsWith<IllegalArgumentException> {
            service.editGroup(invalidGroup)
        }

        coVerify(exactly = 0) { groupRepository.add(any()) }
        coVerify(exactly = 0) { groupRepository.update(any()) }
    }

    @Test
    fun `editPrincipal should update principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            coEvery { principalRepository.edit(any()) } returns principal

            service.editPrincipal(principal)

            coVerify {
                principalRepository.edit(match { it.id == principal.id })
            }
        }
    }

    @Test
    fun `updatePassword should throw if principal not found`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            assertFailsWith<IllegalStateException> {
                service.updatePassword(principalId, "newpass", null)
            }
        }
    }

    @Test
    fun `updatePassword should create new credential if none exists`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns mockk()

            service.updatePassword(principalId, "newpass", "user")

            coVerify {
                credentialsRepository.add(match {
                    it.principal == principalId &&
                            it.type == CredentialType.PASSWORD
                })
            }
        }
    }

    @Test
    fun `updatePassword should fail if identifier already in use`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val otherPrincipalId = UUID.random()
            val principal = Principal(id = principalId)
            val otherPrincipal = Principal(id = otherPrincipalId)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user", any()) } returns otherPrincipalId
            coEvery { principalRepository.getPrincipalById(otherPrincipalId) } returns otherPrincipal

            assertFailsWith<SecurityException> {
                service.updatePassword(principalId, "newpass", "user")
            }
        }
    }

    @Test
    fun `updatePassword should update existing credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val credential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("old")))
            )

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user", any()) } returns principalId
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updatePassword(principalId, "newpass", null)

            coVerify {
                credentialsRepository.update(match {
                    it.id == 1L && (it.attributes as CredentialPasswordAttributes).password == "hashed"
                })
            }
        }
    }

    @Test
    fun `updatePassword migrates a legacy scrypt credential and ignores unrelated credentials`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val oauth = PrincipalCredential(
                principal = principalId,
                attributes = OAuth2CredentialAttributes("subject", source = "google"),
            )
            val scrypt = PrincipalCredential(
                principal = principalId,
                attributes = ScryptCredentialAttributes(
                    salt = "salt",
                    identifier = "legacy@example.com",
                    passwordHash = "old",
                ),
            ).copy(id = 2L)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(oauth, scrypt)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType(
                    "legacy@example.com",
                    CredentialType.PASSWORD,
                )
            } returns principalId
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updatePassword(principalId, "newpass", null)

            coVerify {
                credentialsRepository.update(match {
                    it.id == 2L &&
                        it.type == CredentialType.PASSWORD &&
                        (it.attributes as CredentialPasswordAttributes).password == "hashed"
                })
            }
        }
    }

    @Test
    fun `updatePassword reports missing profile and missing email when it cannot infer an identifier`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        coEvery { principalRepository.getPrincipalById(principalId) } returns principal
        coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()
        coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")

        withRequestCache {
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns emptyList()
            assertFailsWith<IllegalStateException> {
                service.updatePassword(principalId, "newpass", null)
            }
        }

        withRequestCache {
            val profile = Profile(
                id = UUID.random(),
                type = ProfileType.GENERIC,
                name = "Person",
                visibility = ProfileVisibility.USER,
            )
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profile.id) } returns emptyList()
            assertFailsWith<IllegalStateException> {
                service.updatePassword(principalId, "newpass", null)
            }
        }
    }

    @Test
    fun `updateIdentifier should update existing credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old", pwHash("hash")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new", any()) } returns null
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updateIdentifier(principalId, "new")

            coVerify {
                credentialsRepository.update(match { it.type == CredentialType.PASSWORD })
            }
        }
    }

    @Test
    fun `updateIdentifier should fail if identifier in use by another`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val otherPrincipalId = UUID.random()
            val principal = Principal(id = principalId)
            val otherPrincipal = Principal(id = otherPrincipalId)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old", pwHash("hash")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new", any()) } returns otherPrincipalId
            coEvery { principalRepository.getPrincipalById(otherPrincipalId) } returns otherPrincipal

            assertFailsWith<SecurityException> {
                service.updateIdentifier(principalId, "new")
            }
        }
    }

    @Test
    fun `updateIdentifier rejects a principal without a password credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId)
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(
                    principal = principalId,
                    attributes = OAuth2CredentialAttributes("subject", null, null, "google"),
                )
            )

            assertFailsWith<IllegalStateException> {
                service.updateIdentifier(principalId, "new")
            }
        }
    }

    @Test
    fun `updateIdentifier permits an identifier already owned by the same principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val credential = PrincipalCredential(
                principal = principalId,
                attributes = CredentialPasswordAttributes("old", pwHash("hash")),
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("new", CredentialType.PASSWORD)
            } returns principalId
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updateIdentifier(principalId, "new")

            coVerify { credentialsRepository.update(match { it.attributes.identifier == "new" }) }
        }
    }

    @Test
    fun `updatePassword should use email from profile if no identifier or credential exists`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val profileId = UUID.random()
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
            val attribute = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test",
                attributes = buildJsonObject { put("email", "test@example.com") }
            )

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(attribute)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("test@example.com", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns mockk()

            service.updatePassword(principalId, "newpass", null)

            coVerify {
                credentialsRepository.add(match {
                    it.principal == principalId &&
                            it.attributes.identifier == "test@example.com"
                })
            }
        }
    }

    @Test
    fun `resetPassword should update password and verify principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verificationToken = "token", verified = true)

            coEvery { principalRepository.getByVerificationToken("token") } returns principal
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(
                    id = 1L,
                    principal = principalId,
                    type = CredentialType.PASSWORD,
                    attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("old")))
                )
            )
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user", any()) } returns principalId
            coEvery { credentialsRepository.update(any()) } returns mockk()
            coEvery { principalRepository.edit(any()) } returns principal

            service.resetPassword("token", "newpass")

            coVerify {
                credentialsRepository.update(match {
                    (it.attributes as CredentialPasswordAttributes).password == "hashed"
                })
                principalRepository.edit(match {
                    it.id == principalId && it.verified && it.verificationToken == null
                })
            }
        }
    }

    @Test
    fun `resetPassword should fail for invalid token`() = runTest {
        withRequestCache {
            coEvery { principalRepository.getByVerificationToken("invalid") } returns null

            assertFailsWith<SecurityException> {
                service.resetPassword("invalid", "password")
            }
        }
    }

    @Test
    fun `resetPassword rejects a token belonging to an unverified principal`() = runTest {
        withRequestCache {
            coEvery { principalRepository.getByVerificationToken("token") } returns
                Principal(id = UUID.random(), verified = false)

            assertFailsWith<PrincipalNotVerified> {
                service.resetPassword("token", "new-password")
            }
        }
    }

    @Test
    fun `forgotPassword should generate token and send email`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            val profileId = UUID.random()
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)
            coEvery { principalRepository.getPrincipalIdByIdentifier("user") } returns principalId
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { principalRepository.edit(any()) } returns principal
            service.forgotPassword("user", "https://app.example.com")

            coVerify {
                // The reset token AND the originating host are persisted, so the email link can route back
                // to that host in a multi-host deployment.
                principalRepository.edit(match { it.verificationToken != null && it.verificationOrigin == "https://app.example.com" })
            }
            assertEquals(setOf(profileId), passwordResetEmailRequests.single().recipientIds)
            assertTrue(passwordResetEmailRequests.single().resetUrl.startsWith("https://app.example.com/auth/reset-password?token="))
        }
    }

    @Test
    fun `forgotPassword should silently return if principal not found`() = runTest {
        withRequestCache {
            coEvery { principalRepository.getPrincipalIdByIdentifier("unknown") } returns null
            coEvery { profileServiceInstance.getProfilesByEmail("unknown") } returns emptyList()

            service.forgotPassword("unknown")

            coVerify(exactly = 0) { principalRepository.edit(any()) }
        }
    }

    @Test
    fun `forgotPassword should silently return if principal not verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = false)

            coEvery { principalRepository.getPrincipalIdByIdentifier("user") } returns principalId
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            service.forgotPassword("user")

            coVerify(exactly = 0) { principalRepository.edit(any()) }
        }
    }

    @Test
    fun `forgotPassword silently returns when a verified principal has no profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalIdByIdentifier("person@example.com") } returns principalId
            coEvery { principalRepository.getPrincipalById(principalId) } returns
                Principal(id = principalId, verified = true)
            coEvery { principalRepository.edit(any()) } answers { firstArg() }
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns emptyList()

            service.forgotPassword("person@example.com")

            assertTrue(passwordResetEmailRequests.isEmpty())
        }
    }

    @Test
    fun `verifyWithToken delegates redemption to the verification framework`() = runTest {
        withRequestCache {
            // Confirming an email link is now just confirming a verifiable attribute by its token.
            service.verifyWithToken("the-token")
            coVerify(exactly = 1) { attributeVerificationService.confirmVerification("the-token") }
        }
    }

    @Test
    fun `onEmailVerified opens the login gate and reconciles identity (login unchanged when it matches)`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = false, verificationToken = "tok")
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRepository.edit(any()) } returns principal.copy(verified = true, verificationToken = null)
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                    confidence = 100, priority = 1, source = "signup", verified = true,
                    attributes = buildJsonObject { put("email", "owner@example.com") }
                )
            )
            // Login identifier equals the proven email, so reconciliation leaves it alone.
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("owner@example.com", pwHash("h")))
            )
            coEvery { principalEmailRepository.getByEmail("owner@example.com") } returns principalId

            service.onEmailVerified(principalId)

            coVerify(exactly = 1) {
                // Login gate opened (verified). The principal verification_token is the password-reset token and
                // is deliberately left untouched (the email-verify token lives on the attribute now).
                principalRepository.edit(match { it.verified })
            }
            // ...and the proven email was consulted in the uniqueness backstop. It is hit more than once now —
            // the collision pre-check plus the registration — so assert it was consulted rather than pinning a
            // count to that internal detail.
            coVerify(atLeast = 1) { principalEmailRepository.getByEmail("owner@example.com") }
            // Login already matches a verified email → no identifier move.
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
        }
    }

    @Test
    fun `onEmailVerified ignores a missing principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            service.onEmailVerified(principalId)

            coVerify(exactly = 0) { principalRepository.edit(any()) }
            coVerify(exactly = 0) { principalEmailRepository.add(any(), any()) }
        }
    }

    @Test
    fun `email reconciliation ignores attributes that do not prove a usable email`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            val profile = Profile(
                id = profileId,
                principal = principalId,
                type = ProfileType.GENERIC,
                name = "owner",
                visibility = ProfileVisibility.USER,
            )
            fun attribute(
                type: String,
                verified: Boolean,
                email: String?,
            ) = ProfileAttribute(
                profile = profileId,
                typeId = type,
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test",
                verified = verified,
                attributes = buildJsonObject { email?.let { put("email", it) } },
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                attribute("bosca.profiles.name", verified = true, email = "wrong-type@example.com"),
                attribute("bosca.profiles.email", verified = false, email = "unverified@example.com"),
                attribute("bosca.profiles.email", verified = true, email = null),
                attribute("bosca.profiles.email", verified = true, email = " "),
                attribute("bosca.profiles.email", verified = true, email = "not-an-email"),
                attribute("bosca.profiles.email", verified = true, email = " OWNER@example.com "),
            )
            coEvery { principalEmailRepository.getByEmail("owner@example.com") } returns principalId
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns
                listOf("owner@example.com")
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()

            service.onEmailVerified(principalId)

            coVerify(exactly = 0) {
                principalEmailRepository.getByEmail("wrong-type@example.com")
                principalEmailRepository.getByEmail("unverified@example.com")
                principalEmailRepository.getByEmail("not-an-email")
            }
        }
    }

    @Test
    fun `email reconciliation stops if the principal disappears after verification`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = false)
            val profile = Profile(
                id = profileId,
                principal = principalId,
                type = ProfileType.GENERIC,
                name = "owner",
                visibility = ProfileVisibility.USER,
            )
            val email = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test",
                verified = true,
                attributes = buildJsonObject { put("email", "owner@example.com") },
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returnsMany
                listOf(principal, null)
            coEvery { principalRepository.edit(any()) } answers { firstArg() }
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(email)
            coEvery { principalEmailRepository.getByEmail("owner@example.com") } returns null
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns emptyList()

            service.onEmailVerified(principalId)

            coVerify { principalEmailRepository.add("owner@example.com", principalId) }
            coVerify(exactly = 0) { credentialsRepository.getByPrincipalId(principalId) }
        }
    }

    @Test
    fun `email reconciliation releases a stale login when no proven replacement remains`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns emptyList()
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns
                listOf("old@example.com")
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(
                    principal = principalId,
                    attributes = CredentialPasswordAttributes("old@example.com", pwHash("hash")),
                ),
            )

            service.onEmailVerified(principalId)

            coVerify { principalEmailRepository.delete("old@example.com", principalId) }
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
        }
    }

    @Test
    fun `setPrimaryProfile should update principal with profile id`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId)
            val profile = Profile(
                id = profileId,
                type = ProfileType.GENERIC,
                name = "test",
                visibility = ProfileVisibility.USER,
                principal = principalId,
            )

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getById(profileId) } returns profile
            coEvery { principalRepository.edit(any()) } returns principal.copy(primaryProfileId = profileId)

            service.setPrimaryProfile(principalId, profileId)

            coVerify {
                principalRepository.edit(match {
                    it.id == principalId && it.primaryProfileId == profileId
                })
            }
        }
    }

    @Test
    fun `setPrimaryProfile should fail if principal not found`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()

            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            assertFailsWith<IllegalStateException> {
                service.setPrimaryProfile(principalId, profileId)
            }
        }
    }

    @Test
    fun `setPrimaryProfile rejects a profile owned by another principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId)
            coEvery { profileServiceInstance.getById(profileId) } returns Profile(
                id = profileId,
                type = ProfileType.GENERIC,
                name = "test",
                visibility = ProfileVisibility.USER,
                principal = UUID.random(),
            )

            assertFailsWith<IllegalArgumentException> {
                service.setPrimaryProfile(principalId, profileId)
            }

            coVerify(exactly = 0) { principalRepository.edit(any()) }
        }
    }

    @Test
    fun `setPrimaryProfile rejects a deleted profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId)
            coEvery { profileServiceInstance.getById(profileId) } returns Profile(
                id = profileId,
                type = ProfileType.GENERIC,
                name = "test",
                visibility = ProfileVisibility.USER,
                principal = principalId,
                deletedAt = bosca.serialization.OffsetDateTime.now(),
            )

            assertFailsWith<IllegalArgumentException> {
                service.setPrimaryProfile(principalId, profileId)
            }

            coVerify(exactly = 0) { principalRepository.edit(any()) }
        }
    }

    @Test
    fun `clearPrimaryProfile updates an existing principal and rejects a missing one`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId, primaryProfileId = UUID.random())

        withRequestCache {
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRepository.edit(any()) } answers { firstArg() }

            service.clearPrimaryProfile(principalId)

            coVerify {
                principalRepository.edit(match { it.id == principalId && it.primaryProfileId == null })
            }
        }

        withRequestCache {
            coEvery { principalRepository.getPrincipalById(principalId) } returns null
            assertFailsWith<IllegalStateException> {
                service.clearPrimaryProfile(principalId)
            }
        }
    }

    @Test
    fun `getPrincipalByEmail should return principal when single match found`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER, principal = principalId)

            coEvery { profileServiceInstance.getProfilesByEmail("test@example.com") } returns listOf(profile)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            val result = service.getPrincipalByEmail("test@example.com")

            assertNotNull(result)
            assertEquals(principalId, result.id)
        }
    }

    @Test
    fun `getPrincipalByEmail should return null when no profiles found`() = runTest {
        withRequestCache {
            coEvery { profileServiceInstance.getProfilesByEmail("nobody@example.com") } returns emptyList()

            val result = service.getPrincipalByEmail("nobody@example.com")

            assertNull(result)
        }
    }

    @Test
    fun `getPrincipalByEmail should return null when principal not verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = false)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER, principal = principalId)

            coEvery { profileServiceInstance.getProfilesByEmail("test@example.com") } returns listOf(profile)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            val result = service.getPrincipalByEmail("test@example.com")

            assertNull(result)
        }
    }

    @Test
    fun `getPrincipalByEmail ignores profiles without a principal`() = runTest {
        withRequestCache {
            val profile = Profile(
                id = UUID.random(),
                type = ProfileType.GENERIC,
                name = "orphan",
                visibility = ProfileVisibility.USER,
                principal = null,
            )
            coEvery { profileServiceInstance.getProfilesByEmail("orphan@example.com") } returns listOf(profile)

            assertNull(service.getPrincipalByEmail("orphan@example.com"))
        }
    }

    @Test
    fun `getPrincipalByEmail refuses to resolve a winner when multiple verified accounts share an email`() = runTest {
        withRequestCache {
            // Dirty/legacy state: two verified principals claim the same email. There is no principled basis
            // to choose one, so resolve to NONE (an admin merge fixes the data) rather than route a reset or
            // link challenge to an arbitrarily-selected account.
            val principalId1 = UUID.random()
            val principalId2 = UUID.random()
            val principal1 = Principal(id = principalId1, verified = true)
            val principal2 = Principal(id = principalId2, verified = true)
            val profile1 = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "test1", visibility = ProfileVisibility.USER, principal = principalId1)
            val profile2 = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "test2", visibility = ProfileVisibility.USER, principal = principalId2)

            coEvery { profileServiceInstance.getProfilesByEmail("dup@example.com") } returns listOf(profile1, profile2)
            coEvery { principalRepository.getPrincipalById(principalId1) } returns principal1
            coEvery { principalRepository.getPrincipalById(principalId2) } returns principal2

            val result = service.getPrincipalByEmail("dup@example.com")

            assertNull(result)
        }
    }

    @Test
    fun `getPrincipalByEmail uses the backstop to resolve multiple verified profile claims`() = runTest {
        withRequestCache {
            val ownerId = UUID.random()
            val otherId = UUID.random()
            val owner = Principal(id = ownerId, verified = true)
            val other = Principal(id = otherId, verified = true)
            coEvery { profileServiceInstance.getProfilesByEmail("dup@example.com") } returns listOf(
                Profile(
                    id = UUID.random(),
                    principal = ownerId,
                    type = ProfileType.GENERIC,
                    name = "owner",
                    visibility = ProfileVisibility.USER,
                ),
                Profile(
                    id = UUID.random(),
                    principal = otherId,
                    type = ProfileType.GENERIC,
                    name = "other",
                    visibility = ProfileVisibility.USER,
                ),
            )
            coEvery { principalRepository.getPrincipalById(ownerId) } returns owner
            coEvery { principalRepository.getPrincipalById(otherId) } returns other
            coEvery { principalEmailRepository.getByEmail("dup@example.com") } returns ownerId

            assertEquals(owner, service.getPrincipalByEmail("dup@example.com"))
        }
    }

    @Test
    fun `getPrincipalByEmail dedupes a single principal owning multiple same-email profiles`() = runTest {
        withRequestCache {
            // After a merge re-parents a duplicate's email-bearing profile onto the survivor, the survivor
            // owns two profiles with the same email — that is ONE principal, not a collision.
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            val profile1 = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "a", visibility = ProfileVisibility.USER, principal = principalId)
            val profile2 = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "b", visibility = ProfileVisibility.USER, principal = principalId)

            coEvery { profileServiceInstance.getProfilesByEmail("merged@example.com") } returns listOf(profile1, profile2)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            val result = service.getPrincipalByEmail("merged@example.com")

            assertEquals(principalId, result?.id)
        }
    }

    @Test
    fun `sendVerificationEmail begins email verification through the framework`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = Principal(id = principalId, verified = false)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)

            service.sendVerificationEmail(principalId)

            // Delegated to the generic framework for the email type (the channel mints + sends the link).
            coVerify(exactly = 1) { attributeVerificationService.requestVerification(profileId, "bosca.profiles.email") }
        }
    }

    @Test
    fun `sendVerificationEmail should not send email if already verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            service.sendVerificationEmail(principalId)

            coVerify(exactly = 0) {
                profileServiceInstance.getByPrincipal(any())
            }
        }
    }

    @Test
    fun `sendVerificationEmail should fail if principal not found`() = runTest {
        withRequestCache {
            val principalId = UUID.random()

            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            assertFailsWith<IllegalStateException> {
                service.sendVerificationEmail(principalId)
            }
        }
    }

    @Test
    fun `sendVerificationEmail fails when the principal has no profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns
                Principal(id = principalId, verified = false)
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns emptyList()

            assertFailsWith<IllegalStateException> {
                service.sendVerificationEmail(principalId)
            }
        }
    }

    @Test
    fun `assertEmailChangeAllowed permits a first change and leaves identity untouched`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            // Guard: the new address isn't already a verified email of another principal.
            coEvery { profileServiceInstance.getProfilesByEmail("new@example.com") } returns emptyList()

            // First change in the window is allowed (no throw).
            service.assertEmailChangeAllowed(principalId, "new@example.com")

            // It only rate-limits + guards — the login identifier and backstop are reconciled later, on proof.
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
            coVerify(exactly = 0) { principalEmailRepository.delete(any(), any()) }
        }
    }

    @Test
    fun `assertEmailChangeAllowed ignores blank input and permits the current owner's address`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profile = Profile(
                id = UUID.random(),
                principal = principalId,
                type = ProfileType.GENERIC,
                name = "owner",
                visibility = ProfileVisibility.USER,
            )
            coEvery { profileServiceInstance.getProfilesByEmail("owner@example.com") } returns listOf(profile)
            coEvery { principalRepository.getPrincipalById(principalId) } returns
                Principal(id = principalId, verified = true)

            service.assertEmailChangeAllowed(principalId, " ")
            service.assertEmailChangeAllowed(principalId, " OWNER@example.com ")
        }
    }

    @Test
    fun `onEmailVerified moves the login identifier onto the newly-proven email and releases the stale one`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val verified = Principal(id = principalId, verified = true, verificationToken = null)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)
            val provenAttr = ProfileAttribute(profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "signup", verified = true, attributes = buildJsonObject { put("email", "new@example.com") })

            coEvery { principalRepository.getPrincipalById(principalId) } returns verified
            coEvery { principalRepository.edit(any()) } returns verified
            coEvery { principalRepository.incrementTokenVersion(principalId) } returns 1
            coEvery { principalRefreshTokensRepository.deleteByPrincipalId(principalId) } returns Unit
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(provenAttr)
            // Login identifier is still the OLD email — no longer a verified attribute → reconcile moves it.
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old@example.com", pwHash("h")))
            )
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new@example.com", any()) } returns null
            coEvery { credentialsRepository.update(any()) } returns mockk()
            coEvery { principalEmailRepository.getByEmail("new@example.com") } returns principalId
            // The backstop still holds the OLD email (registered when it was verified) plus the new one.
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns listOf("old@example.com", "new@example.com")

            service.onEmailVerified(principalId)

            coVerify(exactly = 1) {
                // Login moves to the proven address + sessions invalidate...
                credentialsRepository.update(match { it.attributes.identifier == "new@example.com" })
                principalRepository.incrementTokenVersion(principalId)
                // ...and the stale old email (no longer verified) is released from the backstop.
                principalEmailRepository.delete("old@example.com", principalId)
            }
            // The still-verified new email is NOT released.
            coVerify(exactly = 0) { principalEmailRepository.delete("new@example.com", principalId) }
        }
    }

    @Test
    fun `onEmailVerified leaves an independent login identifier alone when a different profile email is verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val verified = Principal(id = principalId, verified = true, verificationToken = null)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)
            // The verified profile email is primary@; the login identifier is an INDEPENDENT alternate email the
            // user signs in with (alt-login@), which was never one of the principal's verified profile emails.
            val provenAttr = ProfileAttribute(profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "signup", verified = true, attributes = buildJsonObject { put("email", "primary@example.com") })

            coEvery { principalRepository.getPrincipalById(principalId) } returns verified
            coEvery { principalRepository.edit(any()) } returns verified
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(provenAttr)
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("alt-login@example.com", pwHash("h")))
            )
            coEvery { principalEmailRepository.getByEmail("primary@example.com") } returns principalId
            // The backstop holds ONLY the verified profile email — alt-login@ was never a verified attribute, so
            // it was never registered. Nothing is released, so there is no "changed login email" to follow.
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns listOf("primary@example.com")

            service.onEmailVerified(principalId)

            // The independent login identifier is left ALONE: changing/proving a DIFFERENT profile email must not
            // silently rename the login or invalidate sessions.
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(principalId) }
            coVerify(exactly = 0) { principalEmailRepository.delete(any(), any()) }
        }
    }

    @Test
    fun `onEmailVerified moves only the password credential whose identifier was the changed email`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val verified = Principal(id = principalId, verified = true, verificationToken = null)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)
            val provenAttr = ProfileAttribute(profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "signup", verified = true, attributes = buildJsonObject { put("email", "new@example.com") })

            coEvery { principalRepository.getPrincipalById(principalId) } returns verified
            coEvery { principalRepository.edit(any()) } returns verified
            coEvery { principalRepository.incrementTokenVersion(principalId) } returns 1
            coEvery { principalRefreshTokensRepository.deleteByPrincipalId(principalId) } returns Unit
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(provenAttr)
            // TWO password credentials, with the USERNAME one listed first so a naive firstOrNull would pick the
            // wrong credential. Only the one whose identifier was the changed login email (old@) must follow.
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("admin", pwHash("h"))).copy(id = 2),
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old@example.com", pwHash("h"))).copy(id = 1)
            )
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new@example.com", any()) } returns null
            coEvery { credentialsRepository.update(any()) } returns mockk()
            coEvery { principalEmailRepository.getByEmail("new@example.com") } returns principalId
            coEvery { principalEmailRepository.getEmailsByPrincipal(principalId) } returns listOf("old@example.com", "new@example.com")

            service.onEmailVerified(principalId)

            // EXACTLY the old@ credential (id 1) is renamed to the proven address — never the username (id 2).
            coVerify(exactly = 1) { credentialsRepository.update(match { it.id == 1L && it.attributes.identifier == "new@example.com" }) }
            coVerify(exactly = 0) { credentialsRepository.update(match { it.id == 2L }) }
        }
    }

    @Test
    fun `onEmailVerified routes to account-link when the proven email is owned by another (OAuth-only) account`() = runTest {
        withRequestCache {
            val duplicateId = UUID.random()
            val survivorId = UUID.random()
            val profileId = UUID.random()
            val duplicate = Principal(id = duplicateId, verified = false)
            val survivor = Principal(id = survivorId, verified = true)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)

            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.edit(any()) } returns duplicate.copy(verified = true)
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                    confidence = 100, priority = 1, source = "signup", verified = true,
                    attributes = buildJsonObject { put("email", "shared@example.com") }
                )
            )
            // The just-proven email is already verified-owned by the SURVIVOR in the uniqueness backstop.
            coEvery { principalEmailRepository.getByEmail("shared@example.com") } returns survivorId
            // The duplicate has a password to move; the survivor is OAuth-only (no password), so a link is
            // possible — redeeming the verification token proved email control, which is one half of the proof.
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(
                PrincipalCredential(principal = duplicateId, attributes = CredentialPasswordAttributes("shared@example.com", pwHash("h")))
            )
            coEvery { credentialsRepository.getByPrincipalId(survivorId) } returns listOf(
                PrincipalCredential(principal = survivorId, attributes = OAuth2CredentialAttributes("google-sub", null, null, "google"))
            )
            // The survivor verified-owns the matched email; the email-proof recipient is pinned to its profile
            // when the link is captured (resolveAccountLinkDeliveryProfileId).
            coEvery { profileServiceInstance.getProfilesByEmail("shared@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = survivorId, type = ProfileType.GENERIC, name = "survivor", visibility = ProfileVisibility.USER)
            )

            // Instead of dead-ending, route the user into the proof-gated link flow (attach the duplicate's
            // credential to the survivor + retire the duplicate, AFTER the survivor's ownership is proven).
            assertFailsWith<AccountLinkRequired> {
                service.onEmailVerified(duplicateId)
            }
            // The verification is rolled back: the duplicate's email is never registered as a SECOND verified owner.
            coVerify(exactly = 0) { principalEmailRepository.add("shared@example.com", duplicateId) }
        }
    }

    @Test
    fun `onEmailVerified keeps EmailAlreadyVerified when the survivor already has a password`() = runTest {
        withRequestCache {
            val duplicateId = UUID.random()
            val survivorId = UUID.random()
            val profileId = UUID.random()
            val duplicate = Principal(id = duplicateId, verified = false)
            val survivor = Principal(id = survivorId, verified = true)
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "p", visibility = ProfileVisibility.USER)

            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.edit(any()) } returns duplicate.copy(verified = true)
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                    confidence = 100, priority = 1, source = "signup", verified = true,
                    attributes = buildJsonObject { put("email", "taken@example.com") }
                )
            )
            coEvery { principalEmailRepository.getByEmail("taken@example.com") } returns survivorId
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(
                PrincipalCredential(principal = duplicateId, attributes = CredentialPasswordAttributes("taken@example.com", pwHash("h")))
            )
            // The survivor ALSO has a password → two genuine password accounts on one address. A second password
            // can't be attached (one login secret per account), so there is nothing safe to link: keep the
            // honest "already verified on another account" error and let the user sign in there / reset.
            coEvery { credentialsRepository.getByPrincipalId(survivorId) } returns listOf(
                PrincipalCredential(principal = survivorId, attributes = CredentialPasswordAttributes("taken@example.com", pwHash("h2")))
            )

            assertFailsWith<EmailAlreadyVerified> {
                service.onEmailVerified(duplicateId)
            }
        }
    }

    @Test
    fun `verify-time account linking rejects a missing or unverified survivor`() = runTest {
        withRequestCache {
            suspend fun verifyAgainst(survivor: Principal?) {
                val duplicateId = UUID.random()
                val survivorId = survivor?.id ?: UUID.random()
                val profileId = UUID.random()
                val duplicate = Principal(id = duplicateId, verified = false)
                val profile = Profile(
                    id = profileId,
                    principal = duplicateId,
                    type = ProfileType.GENERIC,
                    name = "duplicate",
                    visibility = ProfileVisibility.USER,
                )
                coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
                coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
                coEvery { principalRepository.edit(any()) } returns duplicate.copy(verified = true)
                coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(profile)
                coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                    ProfileAttribute(
                        profile = profileId,
                        typeId = "bosca.profiles.email",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 1,
                        source = "signup",
                        verified = true,
                        attributes = buildJsonObject { put("email", "shared-$duplicateId@example.com") },
                    )
                )
                coEvery { principalEmailRepository.getByEmail("shared-$duplicateId@example.com") } returns survivorId

                assertFailsWith<EmailAlreadyVerified> {
                    service.onEmailVerified(duplicateId)
                }
            }

            verifyAgainst(null)
            verifyAgainst(Principal(id = UUID.random(), verified = false))
        }
    }

    @Test
    fun `verify-time account linking rejects a duplicate without a password credential`() = runTest {
        withRequestCache {
            val duplicateId = UUID.random()
            val survivorId = UUID.random()
            val profileId = UUID.random()
            val duplicate = Principal(id = duplicateId, verified = false)
            val survivor = Principal(id = survivorId, verified = true)
            val profile = Profile(
                id = profileId,
                principal = duplicateId,
                type = ProfileType.GENERIC,
                name = "duplicate",
                visibility = ProfileVisibility.USER,
            )
            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.edit(any()) } returns duplicate.copy(verified = true)
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId,
                    typeId = "bosca.profiles.email",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "signup",
                    verified = true,
                    attributes = buildJsonObject { put("email", "shared@example.com") },
                )
            )
            coEvery { principalEmailRepository.getByEmail("shared@example.com") } returns survivorId
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(
                PrincipalCredential(
                    principal = duplicateId,
                    attributes = OAuth2CredentialAttributes("provider-id", null, null, "google"),
                )
            )

            assertFailsWith<EmailAlreadyVerified> {
                service.onEmailVerified(duplicateId)
            }
        }
    }

    @Test
    fun `verify-time account linking fails cleanly if the duplicate disappears during reconciliation`() = runTest {
        withRequestCache {
            val duplicateId = UUID.random()
            val survivorId = UUID.random()
            val profileId = UUID.random()
            val duplicate = Principal(id = duplicateId, verified = false)
            val survivor = Principal(id = survivorId, verified = true)
            val profile = Profile(
                id = profileId,
                principal = duplicateId,
                type = ProfileType.GENERIC,
                name = "duplicate",
                visibility = ProfileVisibility.USER,
            )
            coEvery { principalRepository.getPrincipalById(duplicateId) } returnsMany
                listOf(duplicate, null)
            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.edit(any()) } answers { firstArg() }
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId,
                    typeId = "bosca.profiles.email",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "signup",
                    verified = true,
                    attributes = buildJsonObject { put("email", "shared@example.com") },
                ),
            )
            coEvery { principalEmailRepository.getByEmail("shared@example.com") } returns survivorId

            assertFailsWith<EmailAlreadyVerified> {
                service.onEmailVerified(duplicateId)
            }
        }
    }

    @Test
    fun `assertEmailChangeAllowed rejects taking an address another principal has verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val victimPrincipalId = UUID.random()
            val victimProfileId = UUID.random()
            val victim = Principal(id = victimPrincipalId, verified = true)
            val victimProfile = Profile(id = victimProfileId, type = ProfileType.GENERIC, name = "victim", visibility = ProfileVisibility.USER, principal = victimPrincipalId)

            coEvery { principalRepository.getPrincipalById(victimPrincipalId) } returns victim
            // new@example.com is already a VERIFIED email of a DIFFERENT principal → reject.
            coEvery { profileServiceInstance.getProfilesByEmail("new@example.com") } returns listOf(victimProfile)

            assertFailsWith<CredentialConflict> {
                service.assertEmailChangeAllowed(principalId, "new@example.com")
            }
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
            coVerify(exactly = 0) { principalEmailRepository.delete(any(), any()) }
        }
    }

    @Test
    fun `loginWithCredential should authenticate with valid password`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )
            val secConfig = mockk<SecurityConfiguration>()

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { argonPasswordEncoder.matches("pass", any()) } returns true
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { secConfig.expirationTimeInSeconds } returns 3600L
            every { secConfig.algorithm } returns com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val credential = SimplePasswordAttributes("user", "pass")
            val result = service.loginWithCredential(credential, generateRefreshToken = false)

            assertNotNull(result)
            assertEquals(principalId, result.principalId)
            coVerify(exactly = 1) { principalRepository.touchLastLogin(principalId) }
        }
    }

    @Test
    fun `password login verifies a legacy scrypt row through the storage fallback`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val storedAttributes = ScryptCredentialAttributes(
                salt = "salt",
                identifier = "legacy@example.com",
                passwordHash = "hash",
            )
            val storedCredential = PrincipalCredential(principal = principalId, attributes = storedAttributes)
            val encoder = mockk<PasswordEncoder<ScryptPassword>>()
            val secConfig = mockk<SecurityConfiguration>()

            coEvery {
                credentialsRepository.getByIdentifier("legacy@example.com", CredentialType.PASSWORD)
            } returns emptyList()
            coEvery {
                credentialsRepository.getByIdentifier("legacy@example.com", CredentialType.PASSWORD_SCRYPT)
            } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { scryptPasswordEncoder.get() } returns encoder
            coEvery { encoder.matches("password", ScryptPassword(storedAttributes)) } returns true
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { secConfig.expirationTimeInSeconds } returns 3600L
            every { secConfig.algorithm } returns com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")

            val response = service.loginWithCredential(
                SimplePasswordAttributes("legacy@example.com", "password"),
                generateRefreshToken = false,
            )

            assertEquals(principalId, response.principalId)
            coVerify { encoder.matches("password", ScryptPassword(storedAttributes)) }
        }
    }

    @Test
    fun `storage-only and ceremony credentials cannot use interactive credential login`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val unsupported = listOf(
                ScryptCredentialAttributes("salt", identifier = "legacy", passwordHash = "hash"),
                bosca.security.model.ApiTokenCredentialAttributes(
                    identifier = "sha256:hash",
                    name = "automation",
                    tokenPrefix = "bosca_token_",
                    createdBy = principalId.toString(),
                ),
                bosca.security.model.PasskeyCredentialAttributes(
                    identifier = "credential",
                    name = "Laptop",
                    publicKeyCose = "key",
                    createdAt = "now",
                ),
            )
            unsupported.forEach { attributes ->
                coEvery {
                    credentialsRepository.getByIdentifier(attributes.identifier, attributes.type)
                } returns listOf(PrincipalCredential(principal = principalId, attributes = attributes))
            }
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            unsupported.forEach { attributes ->
                assertFailsWith<SecurityException> {
                    service.loginWithCredential(attributes, generateRefreshToken = false)
                }
            }
        }
    }

    @Test
    fun `loginWithCredential should fail with invalid password`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { argonPasswordEncoder.matches("wrong", any()) } returns false

            val credential = SimplePasswordAttributes("user", "wrong")

            assertFailsWith<SecurityException> {
                service.loginWithCredential(credential, generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `loginWithCredential rejects a wrong password on an unverified account as InvalidPassword, not PrincipalNotVerified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            // Unverified account WITH a password credential: the case that previously leaked the verified-gate
            // code for any password. The verified gate must run AFTER the password proof, so a wrong password is
            // indistinguishable from a wrong password on a verified account (both -> INVALID_CREDENTIALS).
            val principal = Principal(id = principalId, anonymous = false, verified = false)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { argonPasswordEncoder.matches("wrong", any()) } returns false

            val credential = SimplePasswordAttributes("user", "wrong")

            assertFailsWith<InvalidPassword> {
                service.loginWithCredential(credential, generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `loginWithCredential surfaces PrincipalNotVerified only after a correct password on an unverified account`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = false)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { argonPasswordEncoder.matches("pass", any()) } returns true

            val credential = SimplePasswordAttributes("user", "pass")

            // The gate is preserved: a proven-correct password on a still-unverified account is the ONLY way to
            // reach PrincipalNotVerified.
            assertFailsWith<PrincipalNotVerified> {
                service.loginWithCredential(credential, generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `loginWithCredential should fail for missing credentials`() = runTest {
        withRequestCache {
            coEvery { credentialsRepository.getByIdentifier("unknown", CredentialType.PASSWORD) } returns emptyList()
            coEvery { credentialsRepository.getByIdentifier("unknown", CredentialType.PASSWORD_SCRYPT) } returns emptyList()

            val credential = SimplePasswordAttributes("unknown", "pass")

            assertFailsWith<MissingCredentials> {
                service.loginWithCredential(credential, generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `loginWithCredential should fail for anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = true)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            val credential = SimplePasswordAttributes("user", "pass")

            assertFailsWith<SecurityException> {
                service.loginWithCredential(credential, generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `loginWithCredential should generate refresh token when requested`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 41L
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )
            val secConfig = mockk<SecurityConfiguration>()

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { argonPasswordEncoder.matches("pass", any()) } returns true
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { secConfig.expirationTimeInSeconds } returns 3600L
            every { secConfig.algorithm } returns com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")
            coEvery { principalRepository.addLogin(principalId, "password") } returns PrincipalLogin(loginId, principalId, "password")
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val credential = SimplePasswordAttributes("user", "pass")
            val result = service.loginWithCredential(credential, generateRefreshToken = true)

            assertNotNull(result)
            assertNotNull(result.refreshToken)
            assertEquals(loginId, com.auth0.jwt.JWT.decode(result.token.token).getClaim("lid").asLong())
            coVerify {
                principalRefreshTokensRepository.addPrincipalRefreshToken(any(), eq(principalId), any(), any(), eq(loginId))
            }
        }
    }

    @Test
    fun `loginWithRefreshToken should return new login response`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 73L
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val secConfig = mockk<SecurityConfiguration>()

            coEvery { principalRefreshTokensRepository.consumeToken("refresh-token") } returns
                bosca.security.model.RefreshToken(principalId, "refresh-token", loginId)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { secConfig.expirationTimeInSeconds } returns 3600L
            every { secConfig.algorithm } returns com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val result = service.loginWithRefreshToken("refresh-token")

            assertNotNull(result)
            assertEquals(principalId, result.principalId)
            assertNotNull(result.refreshToken)
            assertEquals(loginId, com.auth0.jwt.JWT.decode(result.token.token).getClaim("lid").asLong())
            coVerify {
                principalRefreshTokensRepository.consumeToken("refresh-token")
                principalRefreshTokensRepository.addPrincipalRefreshToken(any(), principalId, any(), any(), loginId)
            }
            coVerify(exactly = 0) { principalRepository.addLogin(any(), any()) }
        }
    }

    @Test
    fun `loginWithRefreshToken should fail for invalid token`() = runTest {
        withRequestCache {
            coEvery { principalRefreshTokensRepository.consumeToken("invalid") } returns null

            // `loginWithRefreshToken` now throws `SecurityException`
            // rather than `IllegalStateException` on an unknown
            // refresh token so the error flows through the normal
            // auth-failure path (401) instead of a 500. Same for
            // the missing/anonymous principal branches below.
            assertFailsWith<SecurityException> {
                service.loginWithRefreshToken("invalid")
            }
        }
    }

    @Test
    fun `authenticateWithPayload should return authenticated principal for valid payload`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val groups = listOf(Group(name = "users", description = "Users", type = GroupType.SYSTEM))
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            val currentVersion = mockk<com.auth0.jwt.interfaces.Claim>()
            val loginClaim = mockk<com.auth0.jwt.interfaces.Claim>()
            every { currentVersion.asInt() } returns 0
            every { loginClaim.asLong() } returns 42L
            every { payload.getClaim("tver") } returns currentVersion
            every { payload.getClaim("lid") } returns loginClaim
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns groups

            val result = service.authenticateWithPayload(payload)

            assertNotNull(result)
            assertEquals(principalId, result.id)
            assertEquals(42L, result.loginId)
            coVerify(exactly = 0) { principalRepository.isLoginRevoked(any()) }
        }
    }

    @Test
    fun `authenticateWithPayload rejects a login found in the revocation cache hierarchy`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(
                id = principalId,
                anonymous = false,
                verified = true,
                hasLoginRevocations = true,
            )
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val versionClaim = mockk<com.auth0.jwt.interfaces.Claim>()
            val loginClaim = mockk<com.auth0.jwt.interfaces.Claim>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { versionClaim.asInt() } returns 0
            every { loginClaim.asLong() } returns 42L
            every { payload.getClaim("tver") } returns versionClaim
            every { payload.getClaim("lid") } returns loginClaim
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRepository.isLoginRevoked(42L) } returns true

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
            coVerify(exactly = 1) { principalRepository.isLoginRevoked(42L) }
        }
    }

    @Test
    fun `authenticateWithPayload accepts an active login after checking the revocation hierarchy`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(
                id = principalId,
                anonymous = false,
                verified = true,
                hasLoginRevocations = true,
            )
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val versionClaim = mockk<com.auth0.jwt.interfaces.Claim>()
            val loginClaim = mockk<com.auth0.jwt.interfaces.Claim>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { versionClaim.asInt() } returns 0
            every { loginClaim.asLong() } returns 43L
            every { payload.getClaim("tver") } returns versionClaim
            every { payload.getClaim("lid") } returns loginClaim
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRepository.isLoginRevoked(43L) } returns false
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns emptyList()

            val first = service.authenticateWithPayload(payload)
            val second = service.authenticateWithPayload(payload)

            assertEquals(43L, first.loginId)
            assertEquals(43L, second.loginId)
            coVerify(exactly = 1) { principalRepository.isLoginRevoked(43L) }
        }
    }

    @Test
    fun `pubsub revocation notification short circuits stale principal and distributed caches`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 44L
            val collected = CountDownLatch(1)
            val notifyingPubSub = mockk<PubSubService>(relaxed = true) {
                every { subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<String>>()) } returns flow {
                    emit(Message("bosca.security.login-revocation", "$principalId:$loginId"))
                    collected.countDown()
                }
            }
            val notifiedService = newService(notifyingPubSub)
            assertTrue(collected.await(5, TimeUnit.SECONDS))

            val principal = Principal(id = principalId, verified = true, hasLoginRevocations = false)
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val versionClaim = mockk<com.auth0.jwt.interfaces.Claim>()
            val loginClaim = mockk<com.auth0.jwt.interfaces.Claim>()
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { versionClaim.asInt() } returns 0
            every { loginClaim.asLong() } returns loginId
            every { payload.getClaim("tver") } returns versionClaim
            every { payload.getClaim("lid") } returns loginClaim
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                notifiedService.authenticateWithPayload(payload)
            }
            coVerify(exactly = 0) { principalRepository.isLoginRevoked(loginId) }
        }
    }

    @Test
    fun `malformed pubsub revocation notification is ignored`() {
        val collected = CountDownLatch(1)
        val malformedPubSub = mockk<PubSubService>(relaxed = true) {
            every { subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<String>>()) } returns flow {
                emit(Message("bosca.security.login-revocation", "not-a-revocation"))
                collected.countDown()
            }
        }

        newService(malformedPubSub)

        assertTrue(collected.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `authenticateWithPayload accepts a legacy login without an id even when the principal has revocations`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(
                id = principalId,
                anonymous = false,
                verified = true,
                hasLoginRevocations = true,
            )
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val versionClaim = mockk<com.auth0.jwt.interfaces.Claim>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { versionClaim.asInt() } returns 0
            every { payload.getClaim("tver") } returns versionClaim
            every { payload.getClaim("lid") } returns null
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns emptyList()

            val result = service.authenticateWithPayload(payload)

            assertEquals(principalId, result.id)
            assertNull(result.loginId)
            coVerify(exactly = 0) { principalRepository.isLoginRevoked(any()) }
        }
    }

    @Test
    fun `authenticateWithPayload should fail for wrong audience`() = runTest {
        withRequestCache {
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("wrong-audience")
            every { payload.issuer } returns "test-issuer"

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }

    @Test
    fun `authenticateWithPayload should fail for wrong issuer`() = runTest {
        withRequestCache {
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "wrong-issuer"

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }

    @Test
    fun `authenticateWithPayload should fail for anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = true)
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }

    @Test
    fun `loginWithJwtToken should fail for anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = true)
            val secConfig = mockk<SecurityConfiguration>()
            val algorithm = com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")
            val verifier = com.auth0.jwt.JWT.require(algorithm)
                .withAudience("test-audience")
                .withIssuer("test-issuer")
                .build()

            val jwtToken = com.auth0.jwt.JWT.create()
                .withAudience("test-audience")
                .withIssuer("test-issuer")
                .withSubject(principalId.toString())
                .withExpiresAt(java.time.Instant.now().plusSeconds(3600))
                .sign(algorithm)

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.verifier } returns verifier
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.loginWithJwtToken(jwtToken)
            }
        }
    }

    @Test
    fun `loginWithJwtToken mints temporal claims when the verified proof omits them`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val secConfig = mockk<SecurityConfiguration>()
            val algorithm = com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret")
            val verifier = com.auth0.jwt.JWT.require(algorithm)
                .withAudience("test-audience")
                .withIssuer("test-issuer")
                .build()
            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.verifier } returns verifier
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { secConfig.expirationTimeInSeconds } returns 3600
            every { secConfig.algorithm } returns algorithm
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            val missingExpiry = com.auth0.jwt.JWT.create()
                .withAudience("test-audience")
                .withIssuer("test-issuer")
                .withSubject(principalId.toString())
                .withIssuedAt(java.time.Instant.now())
                .sign(algorithm)
            val fromMissingExpiry = service.loginWithJwtToken(missingExpiry, generateRefreshToken = false)
            assertTrue(fromMissingExpiry.token.expiresAt > fromMissingExpiry.token.issuedAt)

            val missingIssuedAt = com.auth0.jwt.JWT.create()
                .withAudience("test-audience")
                .withIssuer("test-issuer")
                .withSubject(principalId.toString())
                .withExpiresAt(java.time.Instant.now().plusSeconds(3600))
                .sign(algorithm)
            val fromMissingIssuedAt = service.loginWithJwtToken(missingIssuedAt, generateRefreshToken = false)
            assertTrue(fromMissingIssuedAt.token.expiresAt > fromMissingIssuedAt.token.issuedAt)
        }
    }

    @Test
    fun `updateIdentifier should not update if identifier is same`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("same", pwHash("hash")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifier("same") } returns null

            service.updateIdentifier(principalId, "same")

            coVerify(exactly = 0) {
                credentialsRepository.update(any())
            }
        }
    }

    @Test
    fun `forgotPassword should find principal by email when identifier lookup fails`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true)
            val profileId = UUID.random()
            val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.USER, principal = principalId)
            coEvery { principalRepository.getPrincipalIdByIdentifier("test@example.com") } returns null
            coEvery { profileServiceInstance.getProfilesByEmail("test@example.com") } returns listOf(profile)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            coEvery { principalRepository.edit(any()) } returns principal
            service.forgotPassword("test@example.com")

            coVerify { principalRepository.edit(match { it.verificationToken != null }) }
            assertEquals(setOf(profileId), passwordResetEmailRequests.single().recipientIds)
        }
    }

    // -------------------------------------------------------------------------
    // Token-version mass invalidation
    // -------------------------------------------------------------------------

    @Test
    fun `authenticateWithPayload rejects JWT whose tver claim is below the principal's current version`() = runTest {
        // The canonical "I changed my password, my old browser tab
        // still has a valid-looking JWT" scenario. The token verifies
        // cryptographically and has the right iss/aud/sub, but the
        // `tver` claim is stale relative to the DB row. That must be
        // a hard reject — otherwise the bump is meaningless.
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true, tokenVersion = 3)
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val staleClaim = mockk<com.auth0.jwt.interfaces.Claim>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { staleClaim.asInt() } returns 2
            every { payload.getClaim("tver") } returns staleClaim
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }

    @Test
    fun `authenticateWithPayload accepts a legacy JWT with no tver claim when the principal is still at version 0`() = runTest {
        // Backwards-compat guarantee for the rollout: any JWT minted
        // before this migration carries no `tver` claim. The verifier
        // must treat the missing claim as version 0 so existing
        // signed-in users do NOT get kicked out on deploy. As soon
        // as that principal triggers a bump (password change, etc.)
        // the legacy token naturally loses at the claim check.
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true, tokenVersion = 0)
            val groups = listOf(Group(name = "users", description = "Users", type = GroupType.SYSTEM))
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { payload.getClaim("tver") } returns null
            every { payload.getClaim("lid") } returns null
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns groups

            val result = service.authenticateWithPayload(payload)
            assertEquals(principalId, result.id)
        }
    }

    @Test
    fun `authenticateWithPayload rejects a legacy JWT after the principal has been bumped`() = runTest {
        // A missing claim represents generation zero. Once a legacy sign-out or another
        // principal-wide invalidation advances the generation, the old JWT must stop working.
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true, tokenVersion = 1)
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()
            val missingClaim = mockk<com.auth0.jwt.interfaces.Claim>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { missingClaim.asInt() } returns null
            every { payload.getClaim("tver") } returns missingClaim
            every { payload.getClaim("lid") } returns null
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns emptyList()

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }

    @Test
    fun `updatePassword bumps token version and deletes all refresh tokens`() = runTest {
        // This is the write-side of the contract. The read-side test
        // above asserts that a stale `tver` claim is rejected; this
        // test asserts the bump actually happens as part of the
        // password change and that refresh tokens are wiped in the
        // same transaction so a stale refresh cookie can't be used
        // to mint a new (correctly-versioned) JWT.
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, tokenVersion = 0)
            val credential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("old")))
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user", any()) } returns principalId
            coEvery { argonPasswordEncoder.encode("newpass") } returns ArgonPassword("hashed")
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updatePassword(principalId, "newpass", null)

            coVerify(exactly = 1) {
                principalRepository.incrementTokenVersion(principalId)
                principalRefreshTokensRepository.deleteByPrincipalId(principalId)
            }
        }
    }

    @Test
    fun `updateIdentifier bumps token version when the identifier actually changes`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, tokenVersion = 0)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old", pwHash("hash")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new", any()) } returns null
            coEvery { credentialsRepository.update(any()) } returns mockk()

            service.updateIdentifier(principalId, "new")

            coVerify(exactly = 1) {
                principalRepository.incrementTokenVersion(principalId)
                principalRefreshTokensRepository.deleteByPrincipalId(principalId)
            }
        }
    }

    @Test
    fun `updateIdentifier does NOT bump token version when the identifier is unchanged`() = runTest {
        // A no-op identifier change is not a session-ending event.
        // Bumping here would force every client to re-auth on every
        // profile-save that happened to round-trip the same
        // identifier, which is pure noise.
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("same", pwHash("hash")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)

            service.updateIdentifier(principalId, "same")

            coVerify(exactly = 0) {
                principalRepository.incrementTokenVersion(any())
                principalRefreshTokensRepository.deleteByPrincipalId(any())
            }
        }
    }

    @Test
    fun `legacy signOut without a login id revokes all tokens`() = runTest {
        // Legacy JWTs cannot identify one PrincipalLogin, so sign-out
        // must fall back to the principal-wide generation bump and
        // refresh-token deletion instead of silently doing nothing.
        withRequestCache {
            val principalId = UUID.random()

            service.signOut(principalId, null)

            coVerify(exactly = 1) {
                principalRepository.incrementTokenVersion(principalId)
                principalRefreshTokensRepository.deleteByPrincipalId(principalId)
                principalRepository.revokeSessions(principalId)
            }
        }
    }

    @Test
    fun `signOut with a login id revokes only that login`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 42L
            val config = mockk<SecurityConfiguration>()
            every { config.expirationTimeInSeconds } returns 1800
            coEvery { securityConfiguration.get() } returns config
            coEvery { principalRepository.revokeLogin(principalId, loginId) } returns
                PrincipalLogin(loginId, principalId, "password")
            coEvery { principalRepository.addLoginRevocation(principalId, loginId, any()) } returns Unit
            coEvery { principalRefreshTokensRepository.deleteByLoginId(principalId, loginId) } returns Unit

            service.signOut(principalId, loginId)

            coVerify(exactly = 1) {
                principalRepository.revokeLogin(principalId, loginId)
                principalRepository.addLoginRevocation(principalId, loginId, any())
                principalRepository.markHasLoginRevocations(principalId)
            }
            coVerify(exactly = 1) {
                principalRefreshTokensRepository.deleteByLoginId(principalId, loginId)
            }
            coVerify(exactly = 0) {
                principalRepository.incrementTokenVersion(any())
                principalRepository.revokeSessions(any())
                principalRefreshTokensRepository.deleteByPrincipalId(any())
            }
        }
    }

    @Test
    fun `signOut ignores a login id that does not belong to the principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.revokeLogin(principalId, 45L) } returns null
            coEvery { principalRefreshTokensRepository.deleteByLoginId(principalId, 45L) } returns Unit

            service.signOut(principalId, 45L)

            coVerify(exactly = 0) {
                principalRepository.addLoginRevocation(any(), any(), any())
                principalRepository.markHasLoginRevocations(any())
            }
            coVerify(exactly = 1) { principalRefreshTokensRepository.deleteByLoginId(principalId, 45L) }
        }
    }

    @Test
    fun `revokePrincipalLogin persists and caches one login revocation`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 46L
            val login = PrincipalLogin(loginId, principalId, "password")
            val config = mockk<SecurityConfiguration>()
            every { config.expirationTimeInSeconds } returns 1800
            coEvery { securityConfiguration.get() } returns config
            coEvery { principalRepository.revokeLogin(principalId, loginId) } returns login
            coEvery { principalRepository.addLoginRevocation(principalId, loginId, any()) } returns Unit
            coEvery { principalRefreshTokensRepository.deleteByLoginId(principalId, loginId) } returns Unit

            assertEquals(login, service.revokePrincipalLogin(principalId, loginId))

            coVerify(exactly = 1) {
                principalRepository.addLoginRevocation(principalId, loginId, any())
                principalRepository.markHasLoginRevocations(principalId)
                pubSubService.publish("bosca.security.login-revocation", any(), "$principalId:$loginId")
            }
            coVerify(exactly = 1) {
                principalRefreshTokensRepository.deleteByLoginId(principalId, loginId)
            }
        }
    }

    @Test
    fun `revokePrincipalLogin returns null when the login does not belong to the principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.revokeLogin(principalId, 47L) } returns null
            coEvery { principalRefreshTokensRepository.deleteByLoginId(principalId, 47L) } returns Unit

            assertNull(service.revokePrincipalLogin(principalId, 47L))

            coVerify(exactly = 0) {
                principalRepository.addLoginRevocation(any(), any(), any())
            }
            coVerify(exactly = 1) { principalRefreshTokensRepository.deleteByLoginId(principalId, 47L) }
        }
    }

    @Test
    fun `revocation remains successful when pubsub publication fails`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val loginId = 48L
            val config = mockk<SecurityConfiguration>()
            every { config.expirationTimeInSeconds } returns 1800
            coEvery { securityConfiguration.get() } returns config
            coEvery { principalRepository.revokeLogin(principalId, loginId) } returns
                PrincipalLogin(loginId, principalId, "password")
            coEvery { principalRepository.addLoginRevocation(principalId, loginId, any()) } returns Unit
            coEvery { principalRefreshTokensRepository.deleteByLoginId(principalId, loginId) } returns Unit
            coEvery {
                pubSubService.publish(any(), any<kotlinx.serialization.SerializationStrategy<String>>(), any<String>())
            } throws IllegalStateException("offline")

            service.signOut(principalId, loginId)

            coVerify { principalRepository.addLoginRevocation(principalId, loginId, any()) }
        }
    }

    @Test
    fun `loginWithRefreshToken rejects anonymous principals`() = runTest {
        // Defense-in-depth for #5: even if a refresh token row
        // somehow points at an anonymous (or later re-anonymized)
        // principal, the refresh path must refuse to mint a JWT.
        // Bumping + deleting refresh tokens is the primary defense;
        // this is the read-side backstop in case a future flow
        // forgets to call `bumpTokenVersion`.
        withRequestCache {
            val principalId = UUID.random()
            val anonymous = Principal(id = principalId, anonymous = true)

            coEvery { principalRefreshTokensRepository.consumeToken("refresh") } returns bosca.security.model.RefreshToken(principalId, "refresh")
            coEvery { principalRepository.getPrincipalById(principalId) } returns anonymous

            assertFailsWith<SecurityException> {
                service.loginWithRefreshToken("refresh")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Soft delete (mark deleted) → restore → hard delete
    // -------------------------------------------------------------------------

    @Test
    fun `markPrincipalDeleted sets deleted_at and revokes every session`() = runTest {
        // Marking deleted is a real disable: it stamps deleted_at AND reuses the signOut invalidation path
        // (bump token version + drop refresh tokens) so the account cannot stay logged in.
        withRequestCache {
            val principalId = UUID.random()
            val deleted = Principal(id = principalId, deletedAt = java.time.OffsetDateTime.now())
            coEvery { principalRepository.markDeleted(principalId) } returns deleted

            val result = service.markPrincipalDeleted(principalId)

            assertNotNull(result.deletedAt)
            coVerify(exactly = 1) {
                principalRepository.markDeleted(principalId)
                principalRepository.incrementTokenVersion(principalId)
                principalRefreshTokensRepository.deleteByPrincipalId(principalId)
            }
        }
    }

    @Test
    fun `restorePrincipal clears deleted_at`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val restored = Principal(id = principalId, deletedAt = null)
            coEvery { principalRepository.restore(principalId) } returns restored

            val result = service.restorePrincipal(principalId)

            assertNull(result.deletedAt)
            coVerify(exactly = 1) { principalRepository.restore(principalId) }
            // Restore does NOT re-revoke sessions (they were already killed at mark time).
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(principalId) }
        }
    }

    @Test
    fun `deletePrincipal tears down linked profiles then removes the principal`() = runTest {
        // A full delete routes the principal's profiles through the profile service (cache + delete event)
        // and relies on the DB cascade for the rest of the footprint when the principal row is removed.
        withRequestCache {
            val principalId = UUID.random()
            val profileA = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "a", visibility = ProfileVisibility.USER, principal = principalId)
            val profileB = Profile(id = UUID.random(), type = ProfileType.GENERIC, name = "b", visibility = ProfileVisibility.USER, principal = principalId)

            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profileA, profileB)
            coEvery { profileServiceInstance.delete(any()) } returns Unit
            coEvery { principalRepository.deleteById(principalId) } returns Unit

            service.deletePrincipal(principalId)

            coVerify(exactly = 1) {
                profileServiceInstance.delete(profileA.id)
                profileServiceInstance.delete(profileB.id)
                principalRepository.deleteById(principalId)
            }
        }
    }

    @Test
    fun `loginWithCredential refuses a soft-deleted principal after a correct password`() = runTest {
        // The gate runs AFTER the password proof (enumeration-resistance), so a soft-deleted account with a
        // correct password is refused rather than minting a session.
        withRequestCache {
            val principalId = UUID.random()
            val deleted = Principal(id = principalId, anonymous = false, verified = true, deletedAt = java.time.OffsetDateTime.now())
            val storedCredential = PrincipalCredential(
                id = 1L,
                principal = principalId,
                type = CredentialType.PASSWORD,
                attributesJson = testJson.encodeToJsonElement(CredentialPasswordAttributes("user", pwHash("hashed")))
            )

            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(storedCredential)
            coEvery { principalRepository.getPrincipalById(principalId) } returns deleted
            coEvery { argonPasswordEncoder.matches("pass", any()) } returns true

            assertFailsWith<SecurityException> {
                service.loginWithCredential(SimplePasswordAttributes("user", "pass"), generateRefreshToken = false)
            }
        }
    }

    @Test
    fun `authenticateWithPayload rejects a request made as a soft-deleted principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val deleted = Principal(id = principalId, anonymous = false, verified = true, deletedAt = java.time.OffsetDateTime.now())
            val secConfig = mockk<SecurityConfiguration>()
            val payload = mockk<Payload>()

            coEvery { securityConfiguration.get() } returns secConfig
            every { secConfig.audience } returns "test-audience"
            every { secConfig.issuer } returns "test-issuer"
            every { payload.audience } returns listOf("test-audience")
            every { payload.issuer } returns "test-issuer"
            every { payload.subject } returns principalId.toString()
            every { payload.getClaim("tver") } returns null
            coEvery { principalRepository.getPrincipalById(principalId) } returns deleted

            assertFailsWith<SecurityException> {
                service.authenticateWithPayload(payload)
            }
        }
    }
}
