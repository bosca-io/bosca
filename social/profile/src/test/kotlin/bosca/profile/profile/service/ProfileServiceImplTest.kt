package bosca.profile.profile.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.profile.events.ProfileCreatedEvent
import bosca.profile.profile.events.ProfileDeletedEvent
import bosca.profile.profile.events.ProfileUpdatedEvent
import bosca.profile.profile.events.ProfileUnlinkedEvent
import bosca.profile.profile.events.dispatch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.repository.ProfileRepository
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

@OptIn(InternalDI::class)
class ProfileServiceImplTest {

    private val repository = mockk<ProfileRepository>()
    private val securityService = mockk<ObjectProvider<SecurityService>>()
    private val securityServiceInstance = mockk<SecurityService>()
    private val organizationService = mockk<ObjectProvider<OrganizationService>>()
    private val organizationServiceInstance = mockk<OrganizationService>()
    private val attributeService = mockk<ProfileAttributeService>()
    private val attributeVerificationService = mockk<bosca.profile.attribute.verification.AttributeVerificationService>(relaxed = true)
    private val slugService = mockk<SlugService>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: ProfileServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }
        provides<JobQueue>(name = "profileQueue") { jobQueue }
        provides<Json> { testJson }
        provides<bosca.profile.attribute.verification.AttributeVerificationService> { attributeVerificationService }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        mockkStatic("bosca.profile.profile.events.ProfileCreatedEventExtKt")
        mockkStatic("bosca.profile.profile.events.ProfileUpdatedEventExtKt")
        mockkStatic("bosca.profile.profile.events.ProfileDeletedEventExtKt")
        mockkStatic("bosca.profile.profile.events.ProfileUnlinkedEventExtKt")
        coEvery { any<bosca.profile.profile.events.ProfileCreatedEvent>().dispatch() } just runs
        coEvery { any<bosca.profile.profile.events.ProfileUpdatedEvent>().dispatch() } just runs
        coEvery { any<bosca.profile.profile.events.ProfileDeletedEvent>().dispatch() } just runs
        coEvery { any<bosca.profile.profile.events.ProfileUnlinkedEvent>().dispatch() } just runs

        coEvery { securityService.get() } returns securityServiceInstance
        coEvery { organizationService.get() } returns organizationServiceInstance
        coEvery { slugService.deleteProfileSlug(any()) } just runs
        coEvery { slugService.getProfileSlug(any()) } returns null

        service = ProfileServiceImpl(
            repository,
            securityService,
            organizationService,
            attributeService,
            slugService,
        )
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        unmockkStatic("bosca.profile.profile.events.ProfileCreatedEventExtKt")
        unmockkStatic("bosca.profile.profile.events.ProfileUpdatedEventExtKt")
        unmockkStatic("bosca.profile.profile.events.ProfileDeletedEventExtKt")
        unmockkStatic("bosca.profile.profile.events.ProfileUnlinkedEventExtKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `getPrimaryProfile returns only an active profile owned by the principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val profile = Profile(
                id = profileId,
                name = "Primary",
                visibility = ProfileVisibility.USER,
                type = ProfileType.GENERIC,
                principal = principalId,
            )
            coEvery { repository.getById(profileId) } returns profile

            assertEquals(profile, service.getPrimaryProfile(Principal(principalId, primaryProfileId = profileId)))
        }
    }

    @Test
    fun `getPrimaryProfile rejects a primary profile owned by another principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            coEvery { repository.getById(profileId) } returns Profile(
                id = profileId,
                name = "Foreign",
                visibility = ProfileVisibility.USER,
                type = ProfileType.GENERIC,
                principal = UUID.random(),
            )

            assertNull(service.getPrimaryProfile(Principal(principalId, primaryProfileId = profileId)))
        }
    }

    @Test
    fun `getPrimaryProfile falls back to the first active owned profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val deleted = Profile(
                id = UUID.random(),
                name = "Deleted",
                visibility = ProfileVisibility.USER,
                type = ProfileType.GENERIC,
                principal = principalId,
                deletedAt = bosca.serialization.OffsetDateTime.now(),
            )
            val active = Profile(
                id = UUID.random(),
                name = "Active",
                visibility = ProfileVisibility.USER,
                type = ProfileType.GENERIC,
                principal = principalId,
            )
            coEvery { repository.getByPrincipal(principalId) } returns listOf(deleted, active)

            assertEquals(active, service.getPrimaryProfile(Principal(principalId)))
        }
    }

    @Test
    fun `add should create profile and slug`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val input = ProfileInput(name = "Test User", visibility = ProfileVisibility.PUBLIC, searchable = false)
            val savedProfile = Profile(id = profileId, name = "Test User", visibility = ProfileVisibility.PUBLIC, searchable = false, type = ProfileType.GENERIC, principal = principalId)

            coEvery { repository.add(any()) } returns savedProfile
            coEvery { slugService.add(any()) } returns Slug(slug = "test-user", profileId = profileId)

            val result = service.add(input, ProfileType.GENERIC, principalId)

            assertEquals(profileId, result.id)
            assertEquals("Test User", result.name)
            coVerify(exactly = 1) {
                repository.add(match {
                    it.name == "Test User" && it.visibility == ProfileVisibility.PUBLIC && !it.searchable && it.principal == principalId
                })
                slugService.add(match { it.slug == "test-user" && it.profileId == profileId })
                slugService.deleteProfileSlug(profileId)
            }
        }
    }

    @Test
    fun `add generates the slug from the name even when a slug is supplied`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "Example Family", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)
            coEvery { repository.add(any()) } returns profile
            coEvery { slugService.add(any()) } answers { firstArg() }

            service.add(ProfileInput(name = profile.name, slug = "example", visibility = profile.visibility), profile.type)

            coVerify(exactly = 1) { slugService.add(Slug(slug = "example-family", profileId = profile.id)) }
            coVerify(exactly = 0) { slugService.add(match { it.slug != "example-family" }) }
        }
    }

    @Test
    fun `generated slug generation keeps its five attempt limit and propagates cancellation`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "Example", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)
            coEvery { repository.add(any()) } returns profile
            val failure = IllegalStateException("database unavailable")
            coEvery { slugService.add(any()) } throws failure
            val input = ProfileInput(name = profile.name, visibility = profile.visibility)

            assertEquals(profile, service.add(input, profile.type))
            coVerify(exactly = 5) { slugService.add(any()) }

            val cancelled = kotlinx.coroutines.CancellationException("cancelled")
            coEvery { slugService.add(any()) } throws cancelled
            assertSame(cancelled, assertFailsWith<kotlinx.coroutines.CancellationException> { service.add(input, profile.type) })
            coVerify(exactly = 6) { slugService.add(any()) }
            coVerify(exactly = 1) { any<ProfileCreatedEvent>().dispatch() }
        }
    }

    @Test
    fun `generated slug retries with the original UUID suffixes and stops after success`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "Example Family", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)
            coEvery { repository.add(any()) } returns profile
            val attempted = mutableListOf<String>()
            coEvery { slugService.add(any()) } answers {
                val slug = firstArg<Slug>()
                attempted += slug.slug
                if (attempted.size < 4) throw IllegalStateException("slug already claimed")
                slug
            }

            assertEquals(profile, service.add(ProfileInput(name = profile.name, visibility = profile.visibility), profile.type))

            val firstFallback = "example-family-${profile.id.toString().take(8)}"
            val secondFallback = "$firstFallback-${profile.id.toString().take(16)}"
            val thirdFallback = "$secondFallback-${profile.id.toString().take(24)}"
            assertEquals(listOf("example-family", firstFallback, secondFallback, thirdFallback), attempted)
            coVerify(exactly = 4) { slugService.add(any()) }
            coVerify(exactly = 1) { any<ProfileCreatedEvent>().dispatch() }
        }
    }

    @Test
    fun `explicit slug accepts the unique fallback from the slug service`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "Example", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)
            coEvery { repository.getById(profile.id) } returns profile
            coEvery { repository.update(any()) } returns profile
            coEvery { slugService.add(any()) } returns Slug(slug = "example123", profileId = profile.id)

            val result = service.edit(profile.id, ProfileInput(name = profile.name, slug = "example", visibility = profile.visibility))

            assertEquals(profile, result)
            coVerify(exactly = 1) { slugService.add(Slug(slug = "example", profileId = profile.id)) }
            coVerify(exactly = 1) { any<ProfileUpdatedEvent>().dispatch() }
        }
    }

    @Test
    fun `editing with the current slug preserves existing mappings`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "Example", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)
            coEvery { repository.getById(profile.id) } returns profile
            coEvery { repository.update(any()) } returns profile
            coEvery { slugService.getProfileSlug(profile.id) } returns "example"

            val result = service.edit(profile.id, ProfileInput(name = profile.name, slug = "example", visibility = profile.visibility))

            assertEquals(profile, result)
            coVerify(exactly = 0) { slugService.deleteProfileSlug(any()) }
            coVerify(exactly = 0) { slugService.add(any()) }
        }
    }

    @Test
    fun `add should create profile with attributes`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val input = ProfileInput(
                name = "Test User",
                visibility = ProfileVisibility.PUBLIC,
                attributes = listOf(
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.email",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 1,
                        source = "test"
                    )
                )
            )
            val savedProfile = Profile(id = profileId, name = "Test User", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC)

            coEvery { repository.add(any()) } returns savedProfile
            coEvery { slugService.add(any()) } returns Slug(slug = "test-user", profileId = profileId)
            coEvery { attributeService.addAttributes(profileId, any()) } returns emptyList()
            // add() now routes attributes through the wrapped addAttributes (which runs the verification trigger).
            coEvery { attributeService.getAttributesByProfile(profileId) } returns emptyList()
            coEvery { repository.setModified(profileId) } returns Unit

            val result = service.add(input, ProfileType.GENERIC)

            assertEquals(profileId, result.id)
            coVerify {
                attributeService.addAttributes(profileId, any())
            }
        }
    }

    @Test
    fun `edit should update profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val principalId = UUID.random()
            val existing = Profile(id = profileId, name = "Old Name", visibility = ProfileVisibility.USER, type = ProfileType.GENERIC, principal = principalId)
            val input = ProfileInput(name = "New Name", visibility = ProfileVisibility.PUBLIC, searchable = false)
            val updated = existing.copy(name = "New Name", visibility = ProfileVisibility.PUBLIC, searchable = false)

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.update(any()) } returns updated
            coEvery { slugService.add(any()) } returns Slug(slug = "new-name", profileId = profileId)
            coEvery { slugService.getProfileSlug(profileId) } returns "old-name"

            val result = service.edit(profileId, input)

            assertEquals("New Name", result.name)
            assertEquals(ProfileVisibility.PUBLIC, result.visibility)
            assertFalse(result.searchable)
            coVerify {
                repository.update(match { it.name == "New Name" && it.visibility == ProfileVisibility.PUBLIC && !it.searchable })
                slugService.add(match { it.slug == "new-name" })
            }
        }
    }

    @Test
    fun `edit preserves searchable when omitted`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val existing = Profile(
                id = profileId,
                name = "Private Search",
                visibility = ProfileVisibility.PUBLIC,
                searchable = false,
                type = ProfileType.GENERIC,
            )
            val input = ProfileInput(name = "Renamed", visibility = ProfileVisibility.PUBLIC)
            val updated = existing.copy(name = "Renamed")

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.update(any()) } returns updated
            coEvery { slugService.getProfileSlug(profileId) } returns "private-search"
            coEvery { slugService.add(any()) } returns Slug(slug = "renamed", profileId = profileId)

            val result = service.edit(profileId, input)

            assertFalse(result.searchable)
            coVerify { repository.update(match { !it.searchable }) }
        }
    }

    @Test
    fun `edit forwards allowProtected to the attribute service`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val existing = Profile(id = profileId, name = "Name", visibility = ProfileVisibility.USER, type = ProfileType.GENERIC)
            val input = ProfileInput(
                name = "Name",
                visibility = ProfileVisibility.USER,
                attributes = listOf(
                    ProfileAttributeInput(
                        typeId = "bosca.profiles.comment.moderator",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 1,
                        source = "manual"
                    )
                )
            )

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.update(any()) } returns existing
            coEvery { slugService.getProfileSlug(profileId) } returns "name"
            coEvery { attributeService.addAttributes(profileId, any(), allowProtected = true) } returns emptyList()
            coEvery { attributeService.getAttributesByProfile(profileId) } returns emptyList()
            coEvery { repository.setModified(profileId) } returns Unit

            service.edit(profileId, input, allowProtected = true)

            coVerify { attributeService.addAttributes(profileId, any(), allowProtected = true) }
        }
    }

    @Test
    fun `edit should not regenerate slug when name unchanged`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val existing = Profile(id = profileId, name = "Same Name", visibility = ProfileVisibility.USER, type = ProfileType.GENERIC)
            val input = ProfileInput(name = "Same Name", visibility = ProfileVisibility.PUBLIC)
            val updated = existing.copy(visibility = ProfileVisibility.PUBLIC)

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.update(any()) } returns updated
            coEvery { slugService.getProfileSlug(profileId) } returns "same-name"

            val result = service.edit(profileId, input)

            assertEquals(ProfileVisibility.PUBLIC, result.visibility)
            coVerify(exactly = 0) { slugService.add(any()) }
        }
    }

    @Test
    fun `edit should fail if profile not found`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val input = ProfileInput(name = "New Name", visibility = ProfileVisibility.PUBLIC)

            coEvery { repository.getById(profileId) } returns null

            assertFailsWith<NoSuchElementException> {
                service.edit(profileId, input)
            }
        }
    }

    @Test
    fun `getBySlug resolves only profile slug mappings`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val profile = Profile(
                id = profileId,
                name = "Ada Lovelace",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC,
            )
            coEvery { slugService.get("ada-lovelace") } returns Slug(
                slug = "ada-lovelace",
                profileId = profileId,
            )
            coEvery { repository.getById(profileId) } returns profile
            coEvery { slugService.get("not-a-profile") } returns Slug(
                slug = "not-a-profile",
                metadataId = UUID.random(),
            )

            assertSame(profile, service.getBySlug("ada-lovelace"))
            assertNull(service.getBySlug("not-a-profile"))
            coVerify(exactly = 1) { repository.getById(profileId) }
        }
    }

    @Test
    fun `getByNames performs a deduplicated case-insensitive lookup`() = runTest {
        val profiles = listOf(
            Profile(
                id = UUID.random(),
                name = "Ada Lovelace",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC,
            ),
            Profile(
                id = UUID.random(),
                name = "Grace Hopper",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC,
            ),
        )
        coEvery {
            repository.getByNames(listOf("ada lovelace", "grace hopper"))
        } returns profiles

        assertEquals(
            profiles,
            service.getByNames(listOf("Ada Lovelace", "GRACE HOPPER", "ada lovelace")),
        )
        assertTrue(service.getByNames(emptyList()).isEmpty())
        coVerify(exactly = 1) {
            repository.getByNames(listOf("ada lovelace", "grace hopper"))
        }
    }

    @Test
    fun `delete should remove profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val principalId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC, principal = principalId)

            coEvery { repository.getById(profileId) } returns profile
            coEvery { repository.deleteById(profileId) } returns Unit

            service.delete(profileId)

            coVerify {
                repository.deleteById(profileId)
            }
            coVerify(exactly = 1) {
                match<ProfileDeletedEvent> {
                    it.id == profileId && it.principalId == principalId
                }.dispatch()
            }
        }
    }

    @Test
    fun `clearPrincipal dispatches durable cleanup with the former principal`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val principalId = UUID.random()
            val existing = Profile(
                id = profileId,
                name = "Test",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC,
                principal = principalId,
            )
            val unlinked = existing.copy(principal = null)
            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.clearPrincipal(profileId) } returns unlinked
            coEvery { securityServiceInstance.getPrincipalById(principalId) } returns Principal(principalId)

            assertEquals(unlinked, service.clearPrincipal(profileId))

            coVerify(exactly = 1) {
                match<ProfileUnlinkedEvent> {
                    it.id == profileId && it.principalId == principalId
                }.dispatch()
            }
        }
    }

    @Test
    fun `markDeleted stamps deletedAt and dispatches an update event`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val principalId = UUID.random()
            val deleted = Profile(
                id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC, principal = principalId,
                deletedAt = java.time.OffsetDateTime.now()
            )
            val existing = deleted.copy(deletedAt = null)

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.markDeleted(profileId) } returns deleted

            val result = service.markDeleted(profileId)

            assertNotNull(result.deletedAt)
            assertTrue(result.isDeleted)
            // A soft-deleted profile is no longer searchable, so the reindex on this update removes it.
            assertFalse(result.isSearchable)
            coVerify(exactly = 1) { repository.markDeleted(profileId) }
            coVerify(exactly = 1) { any<ProfileUpdatedEvent>().dispatch() }
        }
    }

    @Test
    fun `restore clears deletedAt and dispatches an update event`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val restored = Profile(
                id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC, deletedAt = null
            )
            val existing = restored.copy(deletedAt = java.time.OffsetDateTime.now())

            coEvery { repository.getById(profileId) } returns existing
            coEvery { repository.restore(profileId) } returns restored

            val result = service.restore(profileId)

            assertNull(result.deletedAt)
            assertFalse(result.isDeleted)
            assertTrue(result.isSearchable)
            coVerify(exactly = 1) { repository.restore(profileId) }
            coVerify(exactly = 1) { any<ProfileUpdatedEvent>().dispatch() }
        }
    }

    @Test
    fun `getProfilesByEmail should return matching profiles`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC)

            coEvery { attributeService.getProfileIdsByEmail("test@example.com") } returns listOf(profileId)
            coEvery { repository.getById(profileId) } returns profile

            val result = service.getProfilesByEmail("test@example.com")

            assertEquals(1, result.size)
            assertEquals(profileId, result[0].id)
        }
    }

    @Test
    fun `getProfilesByEmail should return empty list when no match`() = runTest {
        withRequestCache {
            coEvery { attributeService.getProfileIdsByEmail("nobody@example.com") } returns emptyList()

            val result = service.getProfilesByEmail("nobody@example.com")

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `getByGroupName delegates straight to the repository with group name and pagination`() = runTest {
        withRequestCache {
            val profile = Profile(
                id = UUID.random(),
                name = "Mention Target",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC,
            )
            coEvery { repository.getByGroupName("messaging", 0L, 25) } returns listOf(profile)

            val result = service.getByGroupName("messaging", 0L, 25)

            assertEquals(1, result.size)
            assertEquals("Mention Target", result[0].name)
            coVerify(exactly = 1) { repository.getByGroupName("messaging", 0L, 25) }
        }
    }

    @Test
    fun `getByGroupName returns empty list when no profiles match`() = runTest {
        withRequestCache {
            coEvery { repository.getByGroupName("orphans", 0L, 50) } returns emptyList()

            val result = service.getByGroupName("orphans", 0L, 50)

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `addAttributes should add attributes and update modified`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrInput = ProfileAttributeInput(
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val attr = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            // Two reads: the pre-edit `before` snapshot, then the full post-edit `after` snapshot.
            coEvery { attributeService.getAttributesByProfile(profileId) } returnsMany listOf(emptyList(), listOf(attr))
            coEvery { attributeService.addAttributes(profileId, listOf(attrInput)) } returns listOf(attr)
            coEvery { repository.setModified(profileId) } returns Unit

            val result = service.addAttributes(profileId, listOf(attrInput))

            assertEquals(1, result.size)
            coVerify {
                attributeService.addAttributes(profileId, listOf(attrInput))
                repository.setModified(profileId)
                // The full before/after snapshots are handed to the verification framework to react to changes.
                attributeVerificationService.onAttributesChanged(profileId, emptyList(), listOf(attr))
            }
        }
    }

    @Test
    fun `addAttributes hands the before and after snapshots to the verification framework`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val before = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true,
                attributes = buildJsonObject { put("email", "old@example.com") }
            )
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "test",
                attributes = buildJsonObject { put("email", "new@example.com") }
            )
            // The attribute service strips verification on the value change (laundering fix).
            val after = before.copy(verified = false, attributes = buildJsonObject { put("email", "new@example.com") })

            // First read = pre-edit `before` snapshot; second = full post-edit `after` snapshot.
            coEvery { attributeService.getAttributesByProfile(profileId) } returnsMany listOf(listOf(before), listOf(after))
            coEvery { attributeService.addAttributes(profileId, listOf(input)) } returns listOf(after)
            coEvery { repository.setModified(profileId) } returns Unit

            service.addAttributes(profileId, listOf(input))

            // The framework gets the before (verified old) + after (unverified new) and decides re-verification.
            coVerify(exactly = 1) { attributeVerificationService.onAttributesChanged(profileId, listOf(before), listOf(after)) }
        }
    }

    @Test
    fun `deleteAttribute should delete and update modified`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()

            coEvery { attributeService.deleteAttribute(attrId) } returns profileId
            coEvery { repository.setModified(profileId) } returns Unit

            service.deleteAttribute(attrId)

            coVerify {
                attributeService.deleteAttribute(attrId)
                repository.setModified(profileId)
            }
        }
    }

    @Test
    fun `deleteAttributeFromProfile should delete attribute for specific profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()

            coEvery { attributeService.deleteAttribute(profileId, attrId) } returns profileId
            coEvery { repository.setModified(profileId) } returns Unit

            service.deleteAttributeFromProfile(profileId, attrId)

            coVerify {
                attributeService.deleteAttribute(profileId, attrId)
                repository.setModified(profileId)
            }
        }
    }

    @Test
    fun `getPermissions should return permissions for principal profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val groupId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC, principal = principalId)
            val principal = Principal(id = principalId)
            val principalGroup = Group(id = groupId, name = "${principalId}.user", description = "User", type = GroupType.PRINCIPAL)

            coEvery { securityServiceInstance.getPrincipalById(principalId) } returns principal
            coEvery { securityServiceInstance.getPrincipalGroups(principalId) } returns listOf(principalGroup)

            val result = service.getPermissions(profile)

            assertEquals(3, result.size)
        }
    }

    @Test
    fun `getPermissions should return empty when no principal`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC, principal = null)

            val result = service.getPermissions(profile)

            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `getPermissions should backfill missing principal group`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val groupId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC, principal = principalId)
            val principal = Principal(id = principalId)
            val newGroup = Group(id = groupId, name = "${principalId}.user", description = "${principalId} User", type = GroupType.PRINCIPAL)

            coEvery { securityServiceInstance.getPrincipalById(principalId) } returns principal
            coEvery { securityServiceInstance.getPrincipalGroups(principalId) } returns emptyList()
            coEvery { securityServiceInstance.getGroupByName("${principalId}.user", GroupType.PRINCIPAL) } returns null
            coEvery { securityServiceInstance.addGroup(any()) } returns newGroup
            coEvery { securityServiceInstance.addPrincipalGroup(principalId, groupId) } returns Unit

            val result = service.getPermissions(profile)

            assertEquals(3, result.size)
            coVerify {
                securityServiceInstance.addGroup(any())
                securityServiceInstance.addPrincipalGroup(principalId, groupId)
            }
        }
    }

    @Test
    fun `getPermissions should use existing group when backfilling`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val groupId = UUID.random()
            val profile = Profile(id = profileId, name = "Test", visibility = ProfileVisibility.PUBLIC, type = ProfileType.GENERIC, principal = principalId)
            val principal = Principal(id = principalId)
            val existingGroup = Group(id = groupId, name = "${principalId}.user", description = "${principalId} User", type = GroupType.PRINCIPAL)

            coEvery { securityServiceInstance.getPrincipalById(principalId) } returns principal
            coEvery { securityServiceInstance.getPrincipalGroups(principalId) } returns emptyList()
            coEvery { securityServiceInstance.getGroupByName("${principalId}.user", GroupType.PRINCIPAL) } returns existingGroup
            coEvery { securityServiceInstance.addPrincipalGroup(principalId, groupId) } returns Unit

            val result = service.getPermissions(profile)

            assertEquals(3, result.size)
            coVerify(exactly = 0) {
                securityServiceInstance.addGroup(any())
            }
            coVerify {
                securityServiceInstance.addPrincipalGroup(principalId, groupId)
            }
        }
    }

    @Test
    fun `getPermissions should delegate to organization service for organization profiles`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val orgId = UUID.random()
            val profile = Profile(id = profileId, name = "Org", visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION)

            val org = mockk<bosca.profile.organization.model.Organization>()
            every { org.id } returns orgId
            val permissions = listOf<EntityPermission>(mockk())

            coEvery { organizationServiceInstance.getOrganizationByProfile(profileId) } returns org
            coEvery { organizationServiceInstance.getPermissions(org) } returns permissions

            val result = service.getPermissions(profile)

            assertEquals(1, result.size)
        }
    }
}
