package bosca.profile.attribute.verification

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.serializers.StringKeySerializer
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Exercises the generic, attribute-type-agnostic verification framework against a fake [VerifiableAttributeType]
 * registered through DI — proving the service never reaches for anything email-specific: it selects the channel
 * from the registry by `typeId`, mints + records the token, and delegates delivery and reactions to the type.
 */
@OptIn(InternalDI::class)
class AttributeVerificationServiceImplTest {

    private val profileService = mockk<ProfileService>()
    private val emailType = mockk<VerifiableAttributeType>(relaxed = true)
    private val cacheManager = mockk<CacheManager>()
    private val rateCache = InMemoryStringCache()

    private lateinit var service: AttributeVerificationServiceImpl

    private val profileId = UUID.random()
    private val principalId = UUID.random()

    private fun emailAttribute(
        value: String,
        verified: Boolean = false,
        id: UUID = UUID.random(),
    ) = ProfileAttribute(
        id = id,
        profile = profileId,
        typeId = "bosca.profiles.email",
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 1,
        source = "signup",
        verified = verified,
        attributes = buildJsonObject { put("email", value) },
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // A new verifiable type is a new @Provider, never a change to the service — register the fake the same way.
        every { emailType.typeId } returns "bosca.profiles.email"
        every { emailType.valueKey } returns "email"
        every { emailType.verificationSource } returns "email"
        provides<VerifiableAttributeType>(name = "bosca.profiles.email") { emailType }

        // Route the delivery rate-limiter to a real in-memory cache so the counter actually ticks.
        coEvery { cacheManager.maybeAddCache("verification:rate-limit", StringKeySerializer, any()) } returns rateCache

        // Run transaction {} bodies inline — no real connection management under test.
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as suspend () -> Any?).invoke()
        }

        service = AttributeVerificationServiceImpl(profileService, cacheManager)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    // --- requestVerification ----------------------------------------------------------------------------

    @Test
    fun `requestVerification stamps a token and delivers the type's challenge to the normalized value`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns listOf(emailAttribute("  Owner@Example.com "))
        coEvery { profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "owner@example.com", any()) } returns
            listOf(emailAttribute("owner@example.com"))

        service.requestVerification(profileId, "bosca.profiles.email")

        // Token recorded against the proven value, then the channel delivers it — both see the normalized value.
        coVerify(exactly = 1) {
            profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "owner@example.com", any())
            emailType.deliverChallenge(profileId, "owner@example.com", any())
        }
    }

    @Test
    fun `requestVerification persists the originating host with the token for multi-host email routing`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns listOf(emailAttribute("owner@example.com"))
        coEvery { profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "owner@example.com", any(), "https://app.example.com") } returns
            listOf(emailAttribute("owner@example.com"))

        service.requestVerification(profileId, "bosca.profiles.email", "https://app.example.com")

        // The origin the request came from is stamped onto the attribute alongside the token, so the email
        // link can later be built to route back to that host.
        coVerify(exactly = 1) {
            profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "owner@example.com", any(), "https://app.example.com")
        }
    }

    @Test
    fun `requestVerification is a no-op when the attribute is already verified`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns listOf(emailAttribute("owner@example.com", verified = true))

        service.requestVerification(profileId, "bosca.profiles.email")

        coVerify(exactly = 0) { profileService.setVerificationToken(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { emailType.deliverChallenge(any(), any(), any()) }
    }

    @Test
    fun `requestVerification is a no-op when no attribute of the type exists`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns emptyList()

        service.requestVerification(profileId, "bosca.profiles.email")

        coVerify(exactly = 0) { profileService.setVerificationToken(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { emailType.deliverChallenge(any(), any(), any()) }
    }

    @Test
    fun `requestVerification is a no-op when the attribute carries no value for the key`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns listOf(
            emailAttribute("owner@example.com").copy(attributes = buildJsonObject { put("other", "x") }),
        )

        service.requestVerification(profileId, "bosca.profiles.email")

        coVerify(exactly = 0) { profileService.setVerificationToken(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { emailType.deliverChallenge(any(), any(), any()) }
    }

    @Test
    fun `requestVerification does not deliver when nothing was stamped`() = runTest {
        // The value verified or vanished between read and stamp — setVerificationToken matched nothing.
        coEvery { profileService.getAttributes(profileId) } returns listOf(emailAttribute("owner@example.com"))
        coEvery { profileService.setVerificationToken(any(), any(), any(), any(), any()) } returns emptyList()

        service.requestVerification(profileId, "bosca.profiles.email")

        coVerify(exactly = 0) { emailType.deliverChallenge(any(), any(), any()) }
    }

    @Test
    fun `requestVerification fails fast for an unregistered type`() = runTest {
        assertFailsWith<IllegalStateException> {
            service.requestVerification(profileId, "bosca.profiles.phone")
        }
    }

    @Test
    fun `requestVerification stops delivering once the per-value challenge cap is reached`() = runTest {
        coEvery { profileService.getAttributes(profileId) } returns listOf(emailAttribute("victim@example.com"))
        coEvery { profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "victim@example.com", any()) } returns
            listOf(emailAttribute("victim@example.com"))

        // The cap is 5 challenges per (typeId, value) window; the 6th request to the same address is suppressed.
        repeat(5) { service.requestVerification(profileId, "bosca.profiles.email") }
        service.requestVerification(profileId, "bosca.profiles.email")

        coVerify(exactly = 5) { emailType.deliverChallenge(profileId, "victim@example.com", any()) }
    }

    // --- onAttributesChanged ----------------------------------------------------------------------------

    @Test
    fun `onAttributesChanged reacts to a changed verified value and re-verifies the new one`() = runTest {
        val attrId = UUID.random()
        val before = listOf(emailAttribute("old@example.com", verified = true, id = attrId))
        val after = listOf(emailAttribute("new@example.com", verified = false, id = attrId))

        coEvery { profileService.getById(profileId) } returns
            Profile(id = profileId, principal = principalId, name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)
        // re-verification path (requestVerification) reads the current attributes + stamps
        coEvery { profileService.getAttributes(profileId) } returns after
        coEvery { profileService.setVerificationToken("bosca.profiles.email", profileId, "email", "new@example.com", any()) } returns after

        service.onAttributesChanged(profileId, before, after)

        coVerify(exactly = 1) {
            // change reaction sees old + new, normalized...
            emailType.onValueChanged(principalId, profileId, "old@example.com", "new@example.com")
            // ...then the new value is re-verified through the same channel.
            emailType.deliverChallenge(profileId, "new@example.com", any())
        }
    }

    @Test
    fun `onAttributesChanged ignores an unchanged verified value`() = runTest {
        val attrId = UUID.random()
        val same = listOf(emailAttribute("owner@example.com", verified = true, id = attrId))

        service.onAttributesChanged(profileId, same, same)

        coVerify(exactly = 0) { emailType.onValueChanged(any(), any(), any(), any()) }
        coVerify(exactly = 0) { profileService.getById(any()) }
    }

    @Test
    fun `onAttributesChanged is a no-op when nothing was verified before`() = runTest {
        val attrId = UUID.random()
        val before = listOf(emailAttribute("old@example.com", verified = false, id = attrId))
        val after = listOf(emailAttribute("new@example.com", verified = false, id = attrId))

        service.onAttributesChanged(profileId, before, after)

        coVerify(exactly = 0) { emailType.onValueChanged(any(), any(), any(), any()) }
        coVerify(exactly = 0) { profileService.getById(any()) }
    }

    @Test
    fun `onAttributesChanged propagates a rejection thrown by the change reaction`() = runTest {
        val attrId = UUID.random()
        val before = listOf(emailAttribute("old@example.com", verified = true, id = attrId))
        val after = listOf(emailAttribute("taken@example.com", verified = false, id = attrId))

        coEvery { profileService.getById(profileId) } returns
            Profile(id = profileId, principal = principalId, name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)
        coEvery { emailType.onValueChanged(principalId, profileId, "old@example.com", "taken@example.com") } throws
            IllegalStateException("address already owned")

        // A throwing reaction must surface (the enclosing transaction rolls the edit back).
        assertFailsWith<IllegalStateException> {
            service.onAttributesChanged(profileId, before, after)
        }
        coVerify(exactly = 0) { emailType.deliverChallenge(any(), any(), any()) }
    }

    // --- confirmVerification ----------------------------------------------------------------------------

    @Test
    fun `confirmVerification redeems the token and runs the type's post-verification reaction`() = runTest {
        val redeemed = emailAttribute("owner@example.com", verified = true)
        // The token's type resolves the source ("email"), which is what the redeem records.
        coEvery { profileService.getByVerificationToken("tok") } returns emailAttribute("owner@example.com")
        coEvery { profileService.verifyByToken("tok", "email") } returns listOf(redeemed)
        coEvery { profileService.getById(redeemed.profile) } returns
            Profile(id = profileId, principal = principalId, name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)

        service.confirmVerification("tok")

        coVerify(exactly = 1) {
            profileService.verifyByToken("tok", "email")
            emailType.onVerified(principalId, profileId, "owner@example.com")
        }
    }

    @Test
    fun `confirmVerification fails when the token matches nothing`() = runTest {
        // A bad/expired/already-redeemed token must surface as a failure, not a silent success, so the
        // verify-link route and passwordVerify can tell the user it didn't work. No pending attribute exists,
        // so the source falls back to the generic marker.
        coEvery { profileService.getByVerificationToken("bad") } returns null
        coEvery { profileService.verifyByToken("bad", "verified") } returns emptyList()

        assertFailsWith<IllegalStateException> {
            service.confirmVerification("bad")
        }
        coVerify(exactly = 0) { emailType.onVerified(any(), any(), any()) }
    }
}

/** Minimal in-memory [Cache] so the delivery rate-limiter's counter ticks for real under test. */
private class InMemoryStringCache : Cache<String> {
    private val map = mutableMapOf<String, String?>()
    override val keySerializer: CacheKeySerializer<String> = StringKeySerializer
    override val estimatedSize: Long get() = map.size.toLong()

    private fun rk(key: CacheKey<String>) = key.toRemoteKey()
    private fun cv(v: String?, e: Boolean) = object : CacheValue {
        override val value = v
        override val exists = e
    }

    override suspend fun get(key: CacheKey<String>): CacheValue {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map[k], true) else cv(null, false)
    }

    override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

    override suspend fun put(key: CacheKey<String>, value: String?) {
        map[rk(key)] = value
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
        entries.forEach { put(it.first, it.second) }
    }

    override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map.remove(k), true) else null
    }

    override suspend fun clear() {
        map.clear()
    }

    override suspend fun evictExpiredItems() {}
}
