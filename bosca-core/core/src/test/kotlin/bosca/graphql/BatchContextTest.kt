package bosca.graphql

import kotlin.test.Test
import kotlin.test.assertEquals

class BatchContextTest {

    @Test
    fun fieldsArePreserved() {
        val args = mapOf("limit" to 10 as Any, "offset" to 0 as Any)
        val ctx = BatchContext(arguments = args, context = "test-context")
        assertEquals(args, ctx.arguments)
        assertEquals("test-context", ctx.context)
    }

    @Test
    fun emptyArguments() {
        val ctx = BatchContext(arguments = emptyMap(), context = 42)
        assertEquals(emptyMap(), ctx.arguments)
        assertEquals(42, ctx.context)
    }
}
