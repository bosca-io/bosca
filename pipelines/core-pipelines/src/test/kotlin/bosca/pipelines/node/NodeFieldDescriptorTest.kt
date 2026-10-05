package bosca.pipelines.node

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NodeFieldDescriptor.of] derives a typed slot's fields from its serial descriptor, so the pipeline
 * editor can introspect an object's shape on hover without knowing any domain model.
 */
class NodeFieldDescriptorTest {

    @Serializable
    private enum class Color { RED, GREEN }

    @Serializable
    private data class Sample(
        val name: String,
        val count: Int,
        val active: Boolean,
        val nickname: String?,
        val color: Color,
        val tags: List<String>,
    )

    @Serializable
    private data class Inner(val label: String, val weight: Double)

    @Serializable
    private data class Outer(val id: String, val inner: Inner, val items: List<Inner>)

    @Serializable
    private data class TreeNode(val value: String, val children: List<TreeNode>)

    @Serializable
    private class Empty

    @Serializable
    private data class ScalarKinds(
        val byte: Byte,
        val short: Short,
        val long: Long,
        val float: Float,
        val char: Char,
        val values: Map<String, Int>,
        @Contextual val instant: java.time.Instant?,
        val empty: Empty,
    )

    @Test
    fun `derives one field per constructor property with a coarse type label`() {
        val fields = NodeFieldDescriptor.of(Sample.serializer().descriptor)
        assertEquals(
            listOf(
                "name" to "String",
                "count" to "Int",
                "active" to "Boolean",
                "nickname" to "String?",
                "color" to "Enum",
                "tags" to "List<String>",
            ),
            fields.map { it.name to it.type },
        )
    }

    @Test
    fun `leaf fields carry no nested fields`() {
        val fields = NodeFieldDescriptor.of(Sample.serializer().descriptor)
        assertTrue(fields.all { it.fields == null }, "primitives, enums and lists-of-primitives are leaves")
    }

    @Test
    fun `marks nullable fields with a trailing question mark`() {
        val fields = NodeFieldDescriptor.of(Sample.serializer().descriptor)
        assertEquals("String?", fields.single { it.name == "nickname" }.type)
    }

    @Test
    fun `recursively expands an object field and a list-of-object field`() {
        val fields = NodeFieldDescriptor.of(Outer.serializer().descriptor)

        val inner = fields.single { it.name == "inner" }
        assertEquals("Inner", inner.type)
        assertEquals(listOf("label" to "String", "weight" to "Double"), inner.fields?.map { it.name to it.type })

        val items = fields.single { it.name == "items" }
        assertEquals("List<Inner>", items.type, "a list shows its item type")
        assertEquals(listOf("label", "weight"), items.fields?.map { it.name }, "and expands the item's fields")
    }

    @Test
    fun `stops at a cycle rather than recursing forever`() {
        val fields = NodeFieldDescriptor.of(TreeNode.serializer().descriptor)
        val children = fields.single { it.name == "children" }
        assertEquals("List<TreeNode>", children.type)
        assertNull(children.fields, "the self-referential type is not re-expanded")
    }

    @Test
    fun `labels remaining primitive map contextual and empty object kinds`() {
        val fields = NodeFieldDescriptor.of(ScalarKinds.serializer().descriptor).associateBy { it.name }

        assertEquals("Byte", fields.getValue("byte").type)
        assertEquals("Short", fields.getValue("short").type)
        assertEquals("Long", fields.getValue("long").type)
        assertEquals("Float", fields.getValue("float").type)
        assertEquals("Char", fields.getValue("char").type)
        assertEquals("Map", fields.getValue("values").type)
        assertTrue(fields.getValue("instant").type.endsWith("?"))
        assertEquals("Empty", fields.getValue("empty").type)
        assertNull(fields.getValue("empty").fields)
    }

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    @Test
    fun `contextual descriptors without a captured class use the object fallback`() {
        val contextual = buildSerialDescriptor("Uncaptured", SerialKind.CONTEXTUAL)
        val holder = buildClassSerialDescriptor("Holder") {
            element("value", contextual)
        }

        assertEquals("Object", NodeFieldDescriptor.of(holder).single().type)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `anonymous contextual classes use the object fallback`() {
        val anonymous = object {}
        val holder = buildClassSerialDescriptor("Holder") {
            element("value", ContextualSerializer(anonymous::class).descriptor)
        }

        assertNull(anonymous::class.simpleName)
        assertEquals("Object", NodeFieldDescriptor.of(holder).single().type)
    }

    @Test
    fun `nested descriptor expansion stops at the maximum depth`() {
        var descriptor: SerialDescriptor = buildClassSerialDescriptor("Depth9") {
            element<String>("leaf")
        }
        repeat(9) { index ->
            val child = descriptor
            descriptor = buildClassSerialDescriptor("Depth${8 - index}") {
                element("next", child)
            }
        }

        var fields = NodeFieldDescriptor.of(descriptor)
        repeat(8) {
            fields = fields.single().fields.orEmpty()
        }
        assertNull(fields.single().fields)
    }
}
