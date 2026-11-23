package bosca.server.graphql

import bosca.features.Features
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.server.RuntimeWiringBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull

class KitSchemaCompositionTest {
    @Test
    fun `production schema composition includes chat and delegation context with optional modules on and off`() = runBlocking {
        mockkObject(Features, DispatchersRegistry)
        coEvery { DispatchersRegistry.register(any(), *anyVararg()) } returns Unit
        every { Features.chat } returns true
        every { Features.workops } returns true
        try {
            for (optional in listOf(false, true)) {
                every { Features.community } returns optional
                every { Features.ecommerce } returns optional
                every { Features.gateway } returns optional
                every { Features.kubernetes } returns optional
                BoscaGraphQLService(true).initialize(RuntimeWiringBuilder())
                for (type in listOf("KitContextSourceKind", "KitContextSelection", "KitDelegationContextInput", "KitDelegations")) {
                    assertNotNull(SchemaRegistry.registry.types[type], "Missing $type with optional modules=$optional")
                }
            }
        } finally {
            unmockkObject(Features, DispatchersRegistry)
        }
    }
}
