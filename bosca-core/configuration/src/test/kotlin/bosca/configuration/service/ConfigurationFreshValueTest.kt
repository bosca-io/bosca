package bosca.configuration.service

import bosca.cache.CacheKeySerializer
import bosca.cache.ServiceCache
import bosca.cache.service.ServiceCacheImpl
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationValue
import bosca.configuration.repository.ConfigurationRepository
import bosca.configuration.repository.ConfigurationValueRepository
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class ConfigurationFreshValueTest {
    @Test fun `fresh reads bypass caches and propagate missing values and decryption failures`() = runTest {
        bosca.di.provides<kotlinx.serialization.json.Json>(singleton = true) { kotlinx.serialization.json.Json }
        mockkStatic("bosca.cache.ServiceCacheKt")
        try {
            every { ServiceCache<Any, Any>(any(), any<CacheKeySerializer<Any>>(), any(), any(), any()) } returns mockk<ServiceCacheImpl<Any, Any>>(relaxed = true)
            val repository = mockk<ConfigurationRepository>()
            val values = mockk<ConfigurationValueRepository>()
            val encryption = mockk<EncryptionService>()
            val service = ConfigurationServiceImpl(repository, values, mockk(), encryption)
            val record = Configuration(UUID.random(), "guidance", "Guidance", false)
            coEvery { repository.getConfigurationByKey(record.key) } returns record
            coEvery { values.getById(record.id) } returns ConfigurationValue(record.id, "\"current\"".toByteArray(), byteArrayOf(1))
            coEvery { encryption.decrypt(any(), record.id) } returns "\"current\"".toByteArray()
            assertEquals(JsonPrimitive("current"), service.getFreshValue(record.key))
            coEvery { repository.getConfigurationByKey("missing") } returns null
            assertNull(service.getFreshValue("missing"))
            coEvery { values.getById(record.id) } returns null
            assertFailsWith<IllegalStateException> { service.getFreshValue(record.key) }
            coEvery { values.getById(record.id) } returns ConfigurationValue(record.id, null, byteArrayOf(1))
            assertFailsWith<IllegalStateException> { service.getFreshValue(record.key) }
            coEvery { values.getById(record.id) } returns ConfigurationValue(record.id, byteArrayOf(1), byteArrayOf(1))
            val cancellation = kotlinx.coroutines.CancellationException("cancelled")
            coEvery { encryption.decrypt(any(), record.id) } throws cancellation
            assertSame(cancellation, assertFailsWith<kotlinx.coroutines.CancellationException> { service.getFreshValue(record.key) })
        } finally { unmockkStatic("bosca.cache.ServiceCacheKt") }
    }
}
