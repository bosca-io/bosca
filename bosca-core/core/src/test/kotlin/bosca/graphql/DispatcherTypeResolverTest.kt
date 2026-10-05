package bosca.graphql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DispatcherTypeResolverTest {

    private val resolver = DispatcherTypeResolver()

    @Test
    fun `resolveType returns the runtime simple name`() {
        data class TestClass(val value: String)
        assertEquals("TestClass", resolver.resolveType(TestClass("hello")))
    }

    @Test
    fun `resolveType returns null for null`() {
        assertNull(resolver.resolveType(null))
    }
}
