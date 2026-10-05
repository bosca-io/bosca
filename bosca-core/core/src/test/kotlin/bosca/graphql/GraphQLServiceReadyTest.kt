package bosca.graphql

import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestGraphQLService : GraphQLService(mockk<SchemaRoot>(relaxed = true), false) {
    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) = Unit
}

private class CountingGraphQLService : GraphQLService(mockk<SchemaRoot>(relaxed = true), false) {
    var initializationCount = 0

    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        initializationCount++
        delay(10)
    }
}

private class FailingGraphQLService : GraphQLService(mockk<SchemaRoot>(relaxed = true), false) {
    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        error("initialization failed")
    }
}

class GraphQLServiceReadyTest {

    @BeforeTest
    fun setup() = runTest {
        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { hello: String }
            """.trimIndent()
        })
    }

    @Test
    fun `isReady returns false initially`() {
        assertFalse(TestGraphQLService().isReady())
    }

    @Test
    fun `isReady returns true after warmup`() = runTest {
        val service = TestGraphQLService()
        service.warmup()
        assertTrue(service.isReady())
    }

    @Test
    fun `concurrent warmups initialize the engine once`() = runTest {
        val service = CountingGraphQLService()

        List(3) { async { service.warmup() } }.awaitAll()

        assertEquals(1, service.initializationCount)
        assertTrue(service.isReady())
    }

    @Test
    fun `initialization failures are propagated and leave service unready`() = runTest {
        val service = FailingGraphQLService()

        val failure = assertFailsWith<IllegalStateException> { service.warmup() }

        assertEquals("initialization failed", failure.message)
        assertFalse(service.isReady())
    }
}
