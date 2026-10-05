package bosca.security.service

import bosca.cache.Cache
import bosca.cache.CacheValue
import bosca.security.model.CredentialType
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingLinkStoreTest {

    private val cache = mockk<Cache<String>>(relaxed = true)
    private val store = PendingLinkStore(cache, Json)

    @Test
    fun `peek distinguishes missing null and populated cache values`() = runTest {
        coEvery { cache.get(any()) } returns cacheValue(exists = false, value = null)
        assertNull(store.peekPendingLink("missing"))

        coEvery { cache.get(any()) } returns cacheValue(exists = true, value = null)
        assertNull(store.peekPendingLink("null"))

        val link = pendingLink()
        coEvery { cache.get(any()) } returns cacheValue(exists = true, value = Json.encodeToString(link))
        assertEquals(link, store.peekPendingLink("present"))
    }

    @Test
    fun `consume handles absent null and populated entries and email proofs`() = runTest {
        coEvery { cache.remove(any()) } returns null
        assertNull(store.consumePendingLink("absent"))
        assertNull(store.consumeEmailProof("absent"))

        coEvery { cache.remove(any()) } returns cacheValue(exists = true, value = null)
        assertNull(store.consumePendingLink("null"))
        assertNull(store.consumeEmailProof("null"))

        val link = pendingLink()
        coEvery {
            cache.remove(match { it.toRemoteKey().endsWith("pl:present") })
        } returns cacheValue(exists = true, value = Json.encodeToString(link))
        coEvery {
            cache.remove(match { it.toRemoteKey().endsWith("ep:email-token") })
        } returns cacheValue(exists = true, value = "link-token")

        assertEquals(link, store.consumePendingLink("present"))
        assertEquals("link-token", store.consumeEmailProof("email-token"))
    }

    @Test
    fun `put operations serialize pending links and email proof references`() = runTest {
        val link = pendingLink()

        store.putPendingLink("link-token", link)
        store.putEmailProof("email-token", "link-token")
    }

    private fun pendingLink() = PendingLink(
        targetPrincipalId = UUID.random(),
        email = "person@example.com",
        credentialType = CredentialType.OAUTH2,
        credentialAttributes = JsonPrimitive("credential"),
    )

    private fun cacheValue(exists: Boolean, value: String?) = object : CacheValue {
        override val exists = exists
        override val value = value
    }
}
