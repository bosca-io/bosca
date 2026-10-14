@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server

import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.server.config.ApplicationConfig
import bosca.service.Service
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class BoscaApplicationOwnershipTest {
    @BeforeTest
    fun setUp() = ProviderRegistry.clear()

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `secondary application keeps owner providers and closes only its own lifecycle`() = runTest {
        val config = ApplicationConfig.load("".byteInputStream())
        val owner = BoscaApplication(config)
        var serviceStops = 0
        provides<Service>(singleton = true) { object : Service {
            override suspend fun shutdown() { serviceStops++ }
        } }
        val secondary = BoscaApplication(config, ownsServices = false)
        val hooks = mutableListOf<String>()
        secondary.onShutdown { hooks += "first" }
        secondary.onShutdown { hooks += "second" }

        assertSame(owner, provide<BoscaApplication>())
        assertSame(owner.json, provide<Json>())
        assertSame(owner.protobuf, provide<ProtoBuf>())
        secondary.shutdown()
        assertEquals(0, serviceStops)
        assertEquals(listOf("second", "first"), hooks)
        assertFalse(secondary.coroutineContext.job.isActive)
        owner.shutdown()
        assertEquals(1, serviceStops)
    }
}
