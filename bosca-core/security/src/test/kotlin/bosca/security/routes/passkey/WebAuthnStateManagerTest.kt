package bosca.security.routes.passkey

import bosca.cache.Cache
import bosca.cache.CacheValue
import io.mockk.every
import bosca.cache.StringCacheKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class WebAuthnStateManagerTest {

    private val cache = mockk<Cache<String>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val stateManager = WebAuthnStateManager(cache, json)

    @Test
    fun `createChallenge stores state in cache and returns challenge`() = runTest {
        val keySlot = slot<StringCacheKey>()
        val valueSlot = slot<String>()
        coEvery { cache.put(capture(keySlot), capture(valueSlot)) } returns Unit

        val principalId = Uuid.random()
        val (stateKey, state) = stateManager.createChallenge(principalId = principalId)

        assertNotNull(stateKey)
        assertNotNull(state.challenge)
        assertEquals(principalId, state.principalId)

        coVerify { cache.put(any(), any()) }
        val storedState = json.decodeFromString<WebAuthnChallengeState>(valueSlot.captured)
        assertEquals(state.challenge, storedState.challenge)
        assertEquals(principalId, storedState.principalId)
    }

    @Test
    fun `createChallenge generates unique challenges`() = runTest {
        coEvery { cache.put(any(), any()) } returns Unit

        val (key1, state1) = stateManager.createChallenge()
        val (key2, state2) = stateManager.createChallenge()

        assertNotEquals(key1, key2)
        assertNotEquals(state1.challenge, state2.challenge)
    }

    @Test
    fun `retrieveAndRemoveChallenge returns state when present`() = runTest {
        val principalId = Uuid.random()
        val state = WebAuthnChallengeState(
            challenge = "test-challenge",
            principalId = principalId,
        )
        val serialized = json.encodeToString(state)

        val cacheValue = mockk<CacheValue>()
        every { cacheValue.value } returns serialized
        every { cacheValue.exists } returns true
        coEvery { cache.remove(any<StringCacheKey>()) } returns cacheValue

        val retrieved = stateManager.retrieveAndRemoveChallenge("some-key")

        assertNotNull(retrieved)
        assertEquals("test-challenge", retrieved.challenge)
        assertEquals(principalId, retrieved.principalId)
        assertEquals(principalId, retrieved.principalId)
    }

    @Test
    fun `retrieveAndRemoveChallenge returns null when not found`() = runTest {
        val cacheValue = mockk<CacheValue>()
        every { cacheValue.value } returns null
        every { cacheValue.exists } returns false
        coEvery { cache.remove(any<StringCacheKey>()) } returns cacheValue

        val retrieved = stateManager.retrieveAndRemoveChallenge("nonexistent-key")
        assertNull(retrieved)
    }

    @Test
    fun `retrieveAndRemoveChallenge returns null when value is null`() = runTest {
        val cacheValue = mockk<CacheValue>()
        every { cacheValue.value } returns null
        every { cacheValue.exists } returns true
        coEvery { cache.remove(any<StringCacheKey>()) } returns cacheValue

        val retrieved = stateManager.retrieveAndRemoveChallenge("expired-key")
        assertNull(retrieved)
    }

    @Test
    fun `retrieveAndRemoveChallenge returns null when cache removal misses`() = runTest {
        coEvery { cache.remove(any<StringCacheKey>()) } returns null

        assertNull(stateManager.retrieveAndRemoveChallenge("missing-key"))
    }

    @Test
    fun `generated challenges are base64url encoded and 32 bytes`() {
        val challenge = WebAuthnStateManager.generateChallenge()
        val decoded = java.util.Base64.getUrlDecoder().decode(challenge)
        assertEquals(32, decoded.size)
    }

    @Test
    fun `generated state keys are base64url encoded and 32 bytes`() {
        val stateKey = WebAuthnStateManager.generateStateKey()
        val decoded = java.util.Base64.getUrlDecoder().decode(stateKey)
        assertEquals(32, decoded.size)
    }
}
