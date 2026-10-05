package bosca.profile.attribute.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.attribute.model.ProfileAttributesFilterInput
import bosca.profile.attribute.repository.ProfileAttributeRepository
import bosca.profile.attribute.repository.ProfileAttributeTypeRepository
import bosca.profile.attribute.verification.VerifiableAttributeType
import bosca.profile.model.ProfileVisibility
import bosca.security.service.SecurityException
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

@OptIn(InternalDI::class)
class ProfileAttributeServiceImplTest {

    private val attributeRepository = mockk<ProfileAttributeRepository>()
    private val attributeTypeRepository = mockk<ProfileAttributeTypeRepository>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: ProfileAttributeServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }

        service = ProfileAttributeServiceImpl(attributeRepository, attributeTypeRepository)
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // Attribute Type tests

    @Test
    fun `addAttributeType should create and return attribute type`() = runTest {
        withRequestCache {
            val input = ProfileAttributeTypeInput(
                id = "bosca.test.type",
                name = "Test Type",
                description = "A test type",
                visibility = ProfileVisibility.PUBLIC,
                protected = false
            )
            val expected = ProfileAttributeType(
                id = "bosca.test.type",
                name = "Test Type",
                description = "A test type",
                visibility = ProfileVisibility.PUBLIC,
                protected = false
            )

            coEvery { attributeTypeRepository.add(any()) } returns expected

            val result = service.addAttributeType(input)

            assertEquals(expected, result)
            coVerify {
                attributeTypeRepository.add(match {
                    it.id == "bosca.test.type" && it.name == "Test Type"
                })
            }
        }
    }

    @Test
    fun `editAttributeType should update existing type`() = runTest {
        withRequestCache {
            val existing = ProfileAttributeType(
                id = "bosca.test.type",
                name = "Old Name",
                description = "Old desc",
                visibility = ProfileVisibility.USER,
                protected = false
            )
            val input = ProfileAttributeTypeInput(
                id = "bosca.test.type",
                name = "New Name",
                description = "New desc",
                visibility = ProfileVisibility.PUBLIC,
                protected = true
            )
            val updated = existing.copy(name = "New Name", description = "New desc", visibility = ProfileVisibility.PUBLIC, protected = true)

            coEvery { attributeTypeRepository.getById("bosca.test.type") } returns existing
            coEvery { attributeTypeRepository.update(any()) } returns updated

            val result = service.editAttributeType(input)

            assertEquals("New Name", result.name)
            assertEquals("New desc", result.description)
            assertEquals(ProfileVisibility.PUBLIC, result.visibility)
            assertTrue(result.protected)
            // The protected toggle must reach the repository — it was silently dropped before.
            coVerify { attributeTypeRepository.update(match { it.protected }) }
        }
    }

    @Test
    fun `editAttributeType should fail if type not found`() = runTest {
        withRequestCache {
            val input = ProfileAttributeTypeInput(
                id = "nonexistent",
                name = "Name",
                description = "Desc",
                visibility = ProfileVisibility.PUBLIC,
                protected = false
            )

            coEvery { attributeTypeRepository.getById("nonexistent") } returns null

            assertFailsWith<IllegalStateException> {
                service.editAttributeType(input)
            }
        }
    }

    @Test
    fun `deleteAttributeType should delete type by id`() = runTest {
        withRequestCache {
            coEvery { attributeTypeRepository.deleteById("bosca.test.type") } returns Unit

            service.deleteAttributeType("bosca.test.type")

            coVerify { attributeTypeRepository.deleteById("bosca.test.type") }
        }
    }

    // Attribute tests

    @Test
    fun `addAttributes should add new attributes`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrType = ProfileAttributeType(
                id = "bosca.profiles.email",
                name = "Email",
                description = "Email",
                visibility = ProfileVisibility.USER,
                protected = false
            )
            val input = ProfileAttributeInput(
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test",
                attributes = buildJsonObject { put("email", "test@example.com") }
            )
            val saved = ProfileAttribute(
                id = UUID.random(),
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test",
                attributes = buildJsonObject { put("email", "test@example.com") }
            )

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            // Single-email rule reads existing attributes; none here, so the new email is allowed.
            coEvery { attributeRepository.getByProfile(profileId) } returns emptyList()
            coEvery { attributeRepository.add(any()) } returns saved

            val result = service.addAttributes(profileId, listOf(input))

            assertEquals(1, result.size)
            assertEquals("bosca.profiles.email", result[0].typeId)
            coVerify { attributeRepository.add(any()) }
        }
    }

    @Test
    fun `addAttributes rejects a second email attribute on a profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val existingEmail = ProfileAttribute(
                id = UUID.random(), profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", attributes = buildJsonObject { put("email", "first@example.com") }
            )
            val secondEmail = ProfileAttributeInput(
                typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER, confidence = 100, priority = 1,
                source = "test", attributes = buildJsonObject { put("email", "second@example.com") }
            )

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            // The profile already has one email attribute.
            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(existingEmail)

            assertFailsWith<IllegalArgumentException> {
                service.addAttributes(profileId, listOf(secondEmail))
            }
            // Nothing was written.
            coVerify(exactly = 0) { attributeRepository.add(any()) }
        }
    }

    @Test
    fun `addAttributes rejects protected attribute types when not allowed`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val protectedType = ProfileAttributeType(
                id = "bosca.profiles.protected",
                name = "Protected",
                description = "Protected type",
                visibility = ProfileVisibility.USER,
                protected = true
            )
            val input = ProfileAttributeInput(
                typeId = "bosca.profiles.protected",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeTypeRepository.getById("bosca.profiles.protected") } returns protectedType

            assertFailsWith<SecurityException> {
                service.addAttributes(profileId, listOf(input))
            }
            // The rejection fails the whole call — nothing is written.
            coVerify(exactly = 0) { attributeRepository.add(any()) }
        }
    }

    @Test
    fun `addAttributes writes protected attribute types when allowed`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val protectedType = ProfileAttributeType(
                id = "bosca.profiles.protected",
                name = "Protected",
                description = "Protected type",
                visibility = ProfileVisibility.USER,
                protected = true
            )
            val input = ProfileAttributeInput(
                typeId = "bosca.profiles.protected",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val saved = ProfileAttribute(
                id = UUID.random(),
                profile = profileId,
                typeId = "bosca.profiles.protected",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeTypeRepository.getById("bosca.profiles.protected") } returns protectedType
            coEvery { attributeRepository.add(any()) } returns saved

            val result = service.addAttributes(profileId, listOf(input), allowProtected = true)

            assertEquals(1, result.size)
            assertEquals("bosca.profiles.protected", result[0].typeId)
            coVerify { attributeRepository.add(any()) }
        }
    }

    @Test
    fun `addAttributes should update existing attributes with non-nil id`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(
                id = "bosca.profiles.email",
                name = "Email",
                description = "Email",
                visibility = ProfileVisibility.USER,
                protected = false
            )
            val input = ProfileAttributeInput(
                id = attrId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val updated = ProfileAttribute(
                id = attrId,
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            // Email edits read the existing row to decide whether the address changed; here it was never verified.
            coEvery { attributeRepository.getById(attrId) } returns updated
            coEvery { attributeRepository.edit(any()) } returns updated

            val result = service.addAttributes(profileId, listOf(input))

            assertEquals(1, result.size)
            coVerify { attributeRepository.edit(any()) }
            coVerify(exactly = 0) { attributeRepository.add(any()) }
            // Not verified to begin with, so nothing to strip.
            coVerify(exactly = 0) { attributeRepository.clearVerification(any()) }
        }
    }

    @Test
    fun `addAttributes should fail if attribute type not found`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val input = ProfileAttributeInput(
                typeId = "nonexistent",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeTypeRepository.getById("nonexistent") } returns null

            assertFailsWith<IllegalStateException> {
                service.addAttributes(profileId, listOf(input))
            }
        }
    }

    @Test
    fun `deleteAttribute should delete by id and return profile id`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attribute = ProfileAttribute(
                id = attrId,
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.deleteById(attrId) } returns attribute

            val result = service.deleteAttribute(attrId)

            assertEquals(profileId, result)
            coVerify { attributeRepository.deleteById(attrId) }
        }
    }

    @Test
    fun `deleteAttribute should return NIL uuid if attribute not found`() = runTest {
        withRequestCache {
            val attrId = UUID.random()

            coEvery { attributeRepository.deleteById(attrId) } returns null

            val result = service.deleteAttribute(attrId)

            assertEquals(UUID.NIL, result)
        }
    }

    @Test
    fun `deleteAttribute with profileId should fail if attribute belongs to different profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val otherProfileId = UUID.random()
            val attrId = UUID.random()
            val attribute = ProfileAttribute(
                id = attrId,
                profile = otherProfileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getById(attrId) } returns attribute

            assertFailsWith<IllegalStateException> {
                service.deleteAttribute(profileId, attrId)
            }
        }
    }

    @Test
    fun `deleteAttribute with profileId should succeed for matching profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attribute = ProfileAttribute(
                id = attrId,
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getById(attrId) } returns attribute
            coEvery { attributeRepository.deleteById(attrId) } returns attribute

            val result = service.deleteAttribute(profileId, attrId)

            assertEquals(profileId, result)
            coVerify { attributeRepository.deleteById(attrId) }
        }
    }

    // Filter tests

    @Test
    fun `getAttributesByProfileWithFilter should filter by visibility`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val publicAttr = ProfileAttribute(
                profile = profileId,
                typeId = "type1",
                visibility = ProfileVisibility.PUBLIC,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val userAttr = ProfileAttribute(
                profile = profileId,
                typeId = "type2",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(publicAttr, userAttr)

            val filter = ProfileAttributesFilterInput(attributes = emptyList(), visibility = ProfileVisibility.PUBLIC)
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(1, result.size)
            assertEquals(ProfileVisibility.PUBLIC, result[0].visibility)
        }
    }

    @Test
    fun `getAttributesByProfileWithFilter should filter by typeId`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val emailAttr = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val nameAttr = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.name",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(emailAttr, nameAttr)

            val filter = ProfileAttributesFilterInput(attributes = emptyList(), typeId = "bosca.profiles.email")
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(1, result.size)
            assertEquals("bosca.profiles.email", result[0].typeId)
        }
    }

    @Test
    fun `getAttributesByProfileWithFilter should filter by source`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val oauth2Attr = ProfileAttribute(
                profile = profileId,
                typeId = "type1",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "oauth2"
            )
            val manualAttr = ProfileAttribute(
                profile = profileId,
                typeId = "type2",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "manual"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(oauth2Attr, manualAttr)

            val filter = ProfileAttributesFilterInput(attributes = emptyList(), source = "oauth2")
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(1, result.size)
            assertEquals("oauth2", result[0].source)
        }
    }

    @Test
    fun `getAttributesByProfileWithFilter should filter by confidence`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val highConf = ProfileAttribute(
                profile = profileId,
                typeId = "type1",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val lowConf = ProfileAttribute(
                profile = profileId,
                typeId = "type2",
                visibility = ProfileVisibility.USER,
                confidence = 50,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(highConf, lowConf)

            val filter = ProfileAttributesFilterInput(attributes = emptyList(), confidence = 100)
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(1, result.size)
            assertEquals(100, result[0].confidence)
        }
    }

    @Test
    fun `getAttributesByProfileWithFilter should filter by priority`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val highPri = ProfileAttribute(
                profile = profileId,
                typeId = "type1",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val lowPri = ProfileAttribute(
                profile = profileId,
                typeId = "type2",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 5,
                source = "test"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(highPri, lowPri)

            val filter = ProfileAttributesFilterInput(attributes = emptyList(), priority = 1)
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(1, result.size)
            assertEquals(1, result[0].priority)
        }
    }

    @Test
    fun `getAttributesByProfileWithFilter should return all with empty filter`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attr1 = ProfileAttribute(
                profile = profileId,
                typeId = "type1",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )
            val attr2 = ProfileAttribute(
                profile = profileId,
                typeId = "type2",
                visibility = ProfileVisibility.PUBLIC,
                confidence = 50,
                priority = 2,
                source = "other"
            )

            coEvery { attributeRepository.getByProfile(profileId) } returns listOf(attr1, attr2)

            val filter = ProfileAttributesFilterInput(attributes = emptyList())
            val result = service.getAttributesByProfileWithFilter(profileId, filter)

            assertEquals(2, result.size)
        }
    }

    @Test
    fun `getProfileIdsByEmail should return profile ids`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attribute = ProfileAttribute(
                profile = profileId,
                typeId = "bosca.profiles.email",
                visibility = ProfileVisibility.USER,
                confidence = 100,
                priority = 1,
                source = "test"
            )

            coEvery { attributeRepository.getVerifiedByValue("bosca.profiles.email", "email", "test@example.com") } returns listOf(attribute)

            val result = service.getProfileIdsByEmail("test@example.com")

            assertEquals(1, result.size)
            assertEquals(profileId, result[0])
        }
    }

    @Test
    fun `markVerified marks the matching attribute on each profile and returns the updates`() = runTest {
        withRequestCache {
            val profileA = UUID.random()
            val profileB = UUID.random()
            val updatedA = ProfileAttribute(
                profile = profileA, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email"
            )
            val updatedB = ProfileAttribute(
                profile = profileB, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email"
            )
            // One UPDATE is issued per profile; the value is normalized (lower+trim) before it reaches the repo.
            coEvery { attributeRepository.markVerified("bosca.profiles.email", profileA, "email", "user@example.com", "email") } returns listOf(updatedA)
            coEvery { attributeRepository.markVerified("bosca.profiles.email", profileB, "email", "user@example.com", "email") } returns listOf(updatedB)

            val result = service.markVerified("bosca.profiles.email", listOf(profileA, profileB), "email", "  User@Example.com  ", "email")

            assertEquals(listOf(updatedA, updatedB), result)
            assertTrue(result.all { it.verified && it.verificationSource == "email" })
            coVerify(exactly = 1) { attributeRepository.markVerified("bosca.profiles.email", profileA, "email", "user@example.com", "email") }
            coVerify(exactly = 1) { attributeRepository.markVerified("bosca.profiles.email", profileB, "email", "user@example.com", "email") }
        }
    }

    @Test
    fun `markVerified is a no-op for empty profiles or blank value`() = runTest {
        withRequestCache {
            assertEquals(emptyList(), service.markVerified("bosca.profiles.email", emptyList(), "email", "user@example.com", "email"))
            assertEquals(emptyList(), service.markVerified("bosca.profiles.email", listOf(UUID.random()), "email", "   ", "email"))
            // Never touches the repository when there is nothing to mark.
            coVerify(exactly = 0) { attributeRepository.markVerified(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `setVerificationToken stamps the token on the matching attribute and normalizes the value`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val stamped = ProfileAttribute(
                profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verificationToken = "tok"
            )
            coEvery { attributeRepository.setVerificationToken("bosca.profiles.email", profileId, "email", "user@example.com", "tok", null) } returns listOf(stamped)

            val result = service.setVerificationToken("bosca.profiles.email", profileId, "email", "  User@Example.com ", "tok")

            assertEquals(listOf(stamped), result)
            coVerify(exactly = 1) { attributeRepository.setVerificationToken("bosca.profiles.email", profileId, "email", "user@example.com", "tok", null) }
        }
    }

    @Test
    fun `setVerificationToken is a no-op for a blank value`() = runTest {
        withRequestCache {
            assertEquals(emptyList(), service.setVerificationToken("bosca.profiles.email", UUID.random(), "email", "  ", "tok"))
            coVerify(exactly = 0) { attributeRepository.setVerificationToken(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `verifyByToken redeems the token and returns the verified attributes`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val verified = ProfileAttribute(
                profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email"
            )
            coEvery { attributeRepository.verifyByToken("tok", "email") } returns listOf(verified)

            val result = service.verifyByToken("tok", "email")

            assertEquals(listOf(verified), result)
            coVerify(exactly = 1) { attributeRepository.verifyByToken("tok", "email") }
        }
    }

    @Test
    fun `verifyByToken is a no-op for a blank token`() = runTest {
        withRequestCache {
            assertEquals(emptyList(), service.verifyByToken("  ", "email"))
            coVerify(exactly = 0) { attributeRepository.verifyByToken(any(), any()) }
        }
    }

    @Test
    fun `editing a verified attribute's value strips its verification`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val existing = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email",
                attributes = buildJsonObject { put("email", "old@example.com") }
            )
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "test",
                attributes = buildJsonObject { put("email", "new@example.com") }
            )
            val edited = ProfileAttribute(id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "test", verified = true, attributes = buildJsonObject { put("email", "new@example.com") })
            val cleared = edited.copy(verified = false, verificationSource = null, verificationToken = null)

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            coEvery { attributeRepository.getById(attrId) } returns existing
            coEvery { attributeRepository.edit(any()) } returns edited
            coEvery { attributeRepository.clearVerification(attrId) } returns cleared

            val result = service.addAttributes(profileId, listOf(input))

            // The new value was never proven, so verification must be stripped — no laundering.
            assertEquals(listOf(cleared), result)
            assertFalse(result.single().verified)
            coVerify(exactly = 1) { attributeRepository.clearVerification(attrId) }
        }
    }

    @Test
    fun `editing a verified attribute without changing its content keeps verification`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val emailJson = buildJsonObject { put("email", "keep@example.com") }
            val existing = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email",
                attributes = emailJson
            )
            // Identical content (same value JSON), only a priority bump — verification must be preserved.
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 5, source = "signup", attributes = emailJson
            )
            val edited = existing.copy(priority = 5)

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            coEvery { attributeRepository.getById(attrId) } returns existing
            coEvery { attributeRepository.edit(any()) } returns edited

            val result = service.addAttributes(profileId, listOf(input))

            assertEquals(listOf(edited), result)
            assertTrue(result.single().verified)
            // Content unchanged → verification preserved, no clear.
            coVerify(exactly = 0) { attributeRepository.clearVerification(any()) }
        }
    }

    /** Registers a fake email [VerifiableAttributeType] (valueKey "email") so editPreservingProof keys off it. */
    private fun registerEmailType() {
        val emailType = mockk<VerifiableAttributeType>(relaxed = true)
        every { emailType.typeId } returns "bosca.profiles.email"
        every { emailType.valueKey } returns "email"
        provides<VerifiableAttributeType>(name = "bosca.profiles.email") { emailType }
    }

    @Test
    fun `editing only a sibling key of a verified attribute keeps verification`() = runTest {
        withRequestCache {
            registerEmailType()
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val existing = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email",
                attributes = buildJsonObject { put("email", "keep@example.com"); put("label", "work") }
            )
            // Only the unrelated "label" sibling changes; the proven "email" value is identical.
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup",
                attributes = buildJsonObject { put("email", "keep@example.com"); put("label", "home") }
            )
            val edited = existing.copy(attributes = buildJsonObject { put("email", "keep@example.com"); put("label", "home") })

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            coEvery { attributeRepository.getById(attrId) } returns existing
            coEvery { attributeRepository.edit(any()) } returns edited

            val result = service.addAttributes(profileId, listOf(input))

            assertTrue(result.single().verified)
            // The proven email is unchanged, so proof must survive an unrelated sibling-key edit.
            coVerify(exactly = 0) { attributeRepository.clearVerification(any()) }
        }
    }

    @Test
    fun `editing a verified email to the same value with different case keeps verification`() = runTest {
        withRequestCache {
            registerEmailType()
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val existing = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email",
                attributes = buildJsonObject { put("email", "Keep@Example.com ") }
            )
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup",
                attributes = buildJsonObject { put("email", "keep@example.com") }
            )
            val edited = existing.copy(attributes = buildJsonObject { put("email", "keep@example.com") })

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            coEvery { attributeRepository.getById(attrId) } returns existing
            coEvery { attributeRepository.edit(any()) } returns edited

            val result = service.addAttributes(profileId, listOf(input))

            assertTrue(result.single().verified)
            // Normalized email is identical → not a real value change → proof preserved.
            coVerify(exactly = 0) { attributeRepository.clearVerification(any()) }
        }
    }

    @Test
    fun `editing the verified value strips verification via the registered value key`() = runTest {
        withRequestCache {
            registerEmailType()
            val profileId = UUID.random()
            val attrId = UUID.random()
            val attrType = ProfileAttributeType(id = "bosca.profiles.email", name = "Email", description = "Email", visibility = ProfileVisibility.USER, protected = false)
            val existing = ProfileAttribute(
                id = attrId, profile = profileId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup", verified = true, verificationSource = "email",
                attributes = buildJsonObject { put("email", "old@example.com"); put("label", "work") }
            )
            val input = ProfileAttributeInput(
                id = attrId, typeId = "bosca.profiles.email", visibility = ProfileVisibility.USER,
                confidence = 100, priority = 1, source = "signup",
                attributes = buildJsonObject { put("email", "new@example.com"); put("label", "work") }
            )
            val edited = existing.copy(attributes = buildJsonObject { put("email", "new@example.com"); put("label", "work") })
            val cleared = edited.copy(verified = false, verificationSource = null, verificationToken = null)

            coEvery { attributeTypeRepository.getById("bosca.profiles.email") } returns attrType
            coEvery { attributeRepository.getById(attrId) } returns existing
            coEvery { attributeRepository.edit(any()) } returns edited
            coEvery { attributeRepository.clearVerification(attrId) } returns cleared

            val result = service.addAttributes(profileId, listOf(input))

            assertFalse(result.single().verified)
            coVerify(exactly = 1) { attributeRepository.clearVerification(attrId) }
        }
    }
}
