package bosca.collaboration.federation

import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class LocalPeerIdProviderTest {

    private fun fakeConfig(id: UUID): Configuration = Configuration(
        id = id,
        key = LocalPeerIdProvider.LOCAL_PEER_ID_KEY,
        description = "test",
        public = false,
    )

    @Test
    fun `get returns the persisted peer id when one exists`() = runBlocking {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        val storedPeerId = UUID.random()
        coEvery { configurationService.getByKey(LocalPeerIdProvider.LOCAL_PEER_ID_KEY) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns
            buildJsonObject { put("peerId", JsonPrimitive(storedPeerId.toString())) }

        val provider = LocalPeerIdProvider(configurationService)
        assertEquals(storedPeerId, provider.get())
    }

    @Test
    fun `get caches the peer id after first read`() = runBlocking {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        val storedPeerId = UUID.random()
        coEvery { configurationService.getByKey(any()) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns
            buildJsonObject { put("peerId", JsonPrimitive(storedPeerId.toString())) }

        val provider = LocalPeerIdProvider(configurationService)
        repeat(3) { provider.get() }

        // Second and third get() should hit the in-memory cache, not the
        // configuration service.
        coVerify(exactly = 1) { configurationService.getByKey(any()) }
        coVerify(exactly = 1) { configurationService.getValue(configId) }
    }

    @Test
    fun `get auto-generates and persists a new peer id when configuration is absent`() = runBlocking {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        coEvery { configurationService.getByKey(any()) } returns null
        val captured = slot<ConfigurationInput>()
        coEvery { configurationService.setConfiguration(capture(captured)) } returns fakeConfig(UUID.random())

        val provider = LocalPeerIdProvider(configurationService)
        val id = provider.get()
        assertNotNull(id)
        assertEquals(LocalPeerIdProvider.LOCAL_PEER_ID_KEY, captured.captured.key)
        // The generated id was written into the configuration payload as
        // a JSON primitive under the `peerId` key.
        val payload = captured.captured.value
        val storedText = (payload as? kotlinx.serialization.json.JsonObject)
            ?.get("peerId")
            ?.let { (it as? JsonPrimitive)?.content }
        assertEquals(id.toString(), storedText)
    }

    @Test
    fun `get auto-generates when configuration exists but value is JsonNull (cleared)`() {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        coEvery { configurationService.getByKey(any()) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns JsonNull

        val provider = LocalPeerIdProvider(configurationService)
        val id = runBlocking { provider.get() }
        assertNotNull(id)
    }

    @Test
    fun `get auto-generates when configuration value lacks the peerId field`() {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        coEvery { configurationService.getByKey(any()) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns buildJsonObject {
            put("unrelated", JsonPrimitive("value"))
        }

        val provider = LocalPeerIdProvider(configurationService)
        val id = runBlocking { provider.get() }
        assertNotNull(id)
    }

    @Test
    fun `get returns a fresh id even when the stored value is a malformed UUID string`() {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        coEvery { configurationService.getByKey(any()) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns
            buildJsonObject { put("peerId", JsonPrimitive("not a uuid")) }

        val provider = LocalPeerIdProvider(configurationService)
        val id = runBlocking { provider.get() }
        assertNotNull(id)
    }

    @Test
    fun `override updates the cache and persists the new id`() = runBlocking {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        val configId = UUID.random()
        val firstId = UUID.random()
        coEvery { configurationService.getByKey(any()) } returns fakeConfig(configId)
        coEvery { configurationService.getValue(configId) } returns
            buildJsonObject { put("peerId", JsonPrimitive(firstId.toString())) }

        val provider = LocalPeerIdProvider(configurationService)
        assertEquals(firstId, provider.get())

        val newId = UUID.random()
        provider.override(newId)
        // override must invalidate the cache: the next get returns the new id
        // without re-reading from configuration.
        assertEquals(newId, provider.get())
        assertNotEquals(firstId, provider.get())
        coVerify { configurationService.setValue(configId, any()) }
    }

    @Test
    fun `override creates a configuration row when none exists`() = runBlocking {
        val configurationService = mockk<ConfigurationService>(relaxed = true)
        coEvery { configurationService.getByKey(any()) } returns null
        val captured = slot<ConfigurationInput>()
        coEvery { configurationService.setConfiguration(capture(captured)) } returns fakeConfig(UUID.random())

        val provider = LocalPeerIdProvider(configurationService)
        val newId = UUID.random()
        provider.override(newId)
        assertEquals(LocalPeerIdProvider.LOCAL_PEER_ID_KEY, captured.captured.key)
        val storedText = (captured.captured.value as? kotlinx.serialization.json.JsonObject)
            ?.get("peerId")
            ?.let { (it as? JsonPrimitive)?.content }
        assertEquals(newId.toString(), storedText)
    }
}
