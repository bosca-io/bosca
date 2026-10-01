package bosca.configuration

import bosca.graphql.scalars.UploadedFile
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphQLModuleTest {

    @Test
    fun `setAtPath sets top-level variable`() {
        val variables = mutableMapOf<String, Any?>("file" to null, "id" to "abc")
        val file = UploadedFile("test.txt", "text/plain", ByteArrayInputStream(ByteArray(0)))
        GraphQLModule.setAtPath(variables, "variables.file", file)
        assertEquals(file, variables["file"])
        assertEquals("abc", variables["id"])
    }

    @Test
    fun `setAtPath sets nested variable`() {
        val inner = mutableMapOf<String, Any?>("avatar" to null, "name" to "test")
        val variables = mutableMapOf<String, Any?>("input" to inner)
        val file = UploadedFile("avatar.png", "image/png", ByteArrayInputStream(ByteArray(0)))
        GraphQLModule.setAtPath(variables, "variables.input.avatar", file)
        assertEquals(file, (variables["input"] as Map<*, *>)["avatar"])
        assertEquals("test", (variables["input"] as Map<*, *>)["name"])
    }

    @Test
    fun `setAtPath handles immutable nested maps by converting to mutable`() {
        val inner: Map<String, Any?> = mapOf("avatar" to null)
        val variables = mutableMapOf<String, Any?>("input" to inner)
        val file = UploadedFile("photo.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(0)))
        GraphQLModule.setAtPath(variables, "variables.input.avatar", file)
        assertEquals(file, (variables["input"] as Map<*, *>)["avatar"])
    }

    @Test
    fun `setAtPath ignores paths not starting with variables`() {
        val variables = mutableMapOf<String, Any?>("file" to null)
        val file = UploadedFile("test.txt", "text/plain", ByteArrayInputStream(ByteArray(0)))
        GraphQLModule.setAtPath(variables, "other.file", file)
        assertNull(variables["file"])
    }

    @Test
    fun `setAtPath ignores paths with missing nested key`() {
        val variables = mutableMapOf<String, Any?>("x" to "hello")
        val file = UploadedFile("test.txt", "text/plain", ByteArrayInputStream(ByteArray(0)))
        GraphQLModule.setAtPath(variables, "variables.missing.nested", file)
        assertEquals("hello", variables["x"])
    }
}
