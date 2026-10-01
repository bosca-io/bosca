package bosca.graphql.dispatcher

import bosca.graphql.server.FieldResolver
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.graphql.server.TypeRuntimeWiring
import bosca.graphql.server.TypeResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DispatchersRegistryTest {

    @AfterTest
    fun tearDown() = DispatchersRegistry.clear()

    @Test
    fun `types is empty initially`() {
        assertTrue(DispatchersRegistry.types.isEmpty())
    }

    @Test
    fun `register adds dispatcher and it becomes retrievable`() = runBlocking {
        val typeWiring = TypeRuntimeWiring.newTypeWiring("TestType").build()
        val dispatcher = mockk<Dispatcher> { every { type } returns typeWiring }
        val registrar = mockk<DispatchersRegistrar> {
            coEvery { dispatchers() } returns mapOf("TestType" to dispatcher)
        }

        DispatchersRegistry.register(RuntimeWiringBuilder(), registrar)

        assertEquals(listOf("TestType"), DispatchersRegistry.types)
        assertEquals(dispatcher, DispatchersRegistry["TestType"])
    }

    @Test
    fun `get throws for unregistered type`() {
        assertFailsWith<IllegalStateException> { DispatchersRegistry["NonExistent"] }
    }

    @Test
    fun `register merges field resolvers when modules extend the same type`() = runBlocking<Unit> {
        val idResolver = FieldResolver { "id-value" }
        val nameResolver = FieldResolver { "name-value" }
        val a = TypeRuntimeWiring.newTypeWiring("MergedType").field("id", idResolver).build()
        val b = TypeRuntimeWiring.newTypeWiring("MergedType").field("name", nameResolver).build()
        val dispatcherA = mockk<Dispatcher> { every { type } returns a }
        val dispatcherB = mockk<Dispatcher> { every { type } returns b }
        val registrarA = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("MergedType" to dispatcherA) }
        val registrarB = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("MergedType" to dispatcherB) }

        DispatchersRegistry.register(RuntimeWiringBuilder(), registrarA, registrarB)

        val fields = DispatchersRegistry["MergedType"].type.fieldResolvers
        assertEquals(setOf("id", "name"), fields.keys)
        assertNotNull(fields["id"])
        assertNotNull(fields["name"])
    }

    @Test
    fun `composite dispatcher merges focused controllers for one type`() {
        val firstResolver = FieldResolver { "first" }
        val secondResolver = FieldResolver { "second" }
        val firstType = TypeRuntimeWiring.newTypeWiring("SharedType").field("first", firstResolver).build()
        val secondType = TypeRuntimeWiring.newTypeWiring("SharedType").field("second", secondResolver).build()
        val first = mockk<Dispatcher> { every { type } returns firstType }
        val second = mockk<Dispatcher> { every { type } returns secondType }

        val composite = CompositeDispatcher(listOf(first, second))

        assertEquals(setOf("first", "second"), composite.type.fieldResolvers.keys)
        assertEquals(firstResolver, composite.type.fieldResolvers["first"])
        assertEquals(secondResolver, composite.type.fieldResolvers["second"])
    }

    @Test
    fun `composite dispatcher takes the last available type resolver across empty contributions`() {
        val resolver = TypeResolver { "Resolved" }
        val withResolver = TypeRuntimeWiring.newTypeWiring("SharedType").resolveType(resolver).build()
        val withoutResolver = TypeRuntimeWiring.newTypeWiring("SharedType").build()
        val first = mockk<Dispatcher> { every { type } returns withResolver }
        val second = mockk<Dispatcher> { every { type } returns withoutResolver }

        val composite = CompositeDispatcher(listOf(first, second))

        assertTrue(composite.type.fieldResolvers.isEmpty())
        assertEquals("Resolved", composite.type.typeResolver?.resolveType(Any()))
    }

    @Test
    fun `composite dispatcher rejects empty or mixed type contributions`() {
        assertFailsWith<IllegalArgumentException> { CompositeDispatcher(emptyList()) }

        val first = mockk<Dispatcher> {
            every { type } returns TypeRuntimeWiring.newTypeWiring("FirstType").build()
        }
        val second = mockk<Dispatcher> {
            every { type } returns TypeRuntimeWiring.newTypeWiring("SecondType").build()
        }

        assertFailsWith<IllegalArgumentException> { CompositeDispatcher(listOf(first, second)) }
    }

    @Test
    fun `register lets a later dispatcher override an earlier field resolver`() = runBlocking {
        val first = FieldResolver { "first" }
        val second = FieldResolver { "second" }
        val a = TypeRuntimeWiring.newTypeWiring("OverrideType").field("field", first).build()
        val b = TypeRuntimeWiring.newTypeWiring("OverrideType").field("field", second).build()
        val dispatcherA = mockk<Dispatcher> { every { type } returns a }
        val dispatcherB = mockk<Dispatcher> { every { type } returns b }
        val registrarA = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("OverrideType" to dispatcherA) }
        val registrarB = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("OverrideType" to dispatcherB) }

        DispatchersRegistry.register(RuntimeWiringBuilder(), registrarA, registrarB)

        assertEquals(second, DispatchersRegistry["OverrideType"].type.fieldResolvers["field"])
    }

    @Test
    fun `register supports multiple registrars`() = runBlocking {
        val a = TypeRuntimeWiring.newTypeWiring("TypeA").build()
        val b = TypeRuntimeWiring.newTypeWiring("TypeB").build()
        val dispatcherA = mockk<Dispatcher> { every { type } returns a }
        val dispatcherB = mockk<Dispatcher> { every { type } returns b }
        val registrarA = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("TypeA" to dispatcherA) }
        val registrarB = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("TypeB" to dispatcherB) }

        DispatchersRegistry.register(RuntimeWiringBuilder(), registrarA, registrarB)

        assertEquals(2, DispatchersRegistry.types.size)
        assertTrue(DispatchersRegistry.types.containsAll(listOf("TypeA", "TypeB")))
    }

    @Test
    fun `clear removes all dispatchers`() = runBlocking {
        val wiring = TypeRuntimeWiring.newTypeWiring("ClearType").build()
        val dispatcher = mockk<Dispatcher> { every { type } returns wiring }
        val registrar = mockk<DispatchersRegistrar> { coEvery { dispatchers() } returns mapOf("ClearType" to dispatcher) }
        DispatchersRegistry.register(RuntimeWiringBuilder(), registrar)

        DispatchersRegistry.clear()

        assertTrue(DispatchersRegistry.types.isEmpty())
    }
}
