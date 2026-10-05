package bosca.pipelines

import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.entityReference
import bosca.pipelines.node.entityRef
import bosca.pipelines.node.uuid
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class EntityReferenceTest {

    @Serializable
    private data class VersionedEvent(@Contextual val id: Uuid, val version: Int)

    @Serializable
    private data class TaskStyleEvent(@Contextual val taskId: Uuid)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    @Test
    fun `extracts id and version from a typed event via its carried serializer`() {
        val id = Uuid.random()
        val value = PipelineValue.of(VersionedEvent(id, 7), VersionedEvent.serializer())
        val ref = value.entityReference(json)
        assertEquals(id, ref?.id)
        assertEquals(7, ref?.version)
    }

    @Test
    fun `extracts id from plain JSON without a version`() {
        val id = Uuid.random()
        val value = PipelineValue.ofJson(buildJsonObject { put("id", id.toString()) })
        val ref = value.entityReference(json)
        assertEquals(id, ref?.id)
        assertNull(ref?.version)
    }

    @Test
    fun `tries id field candidates in order`() {
        val id = Uuid.random()
        val value = PipelineValue.of(TaskStyleEvent(id), TaskStyleEvent.serializer())
        assertNull(value.entityReference(json), "default id field must not match taskId")
        val ref = value.entityReference(json, idFields = listOf("taskId", "id"))
        assertEquals(id, ref?.id)
    }

    @Test
    fun `returns null when no candidate field is present or parseable`() {
        assertNull(PipelineValue.ofJson(buildJsonObject { put("name", "x") }).entityReference(json))
        assertNull(PipelineValue.ofJson(buildJsonObject { put("id", "not-a-uuid") }).entityReference(json))
        assertNull(PipelineValue.ofJson(JsonPrimitive("scalar")).entityReference(json))
    }

    // --- uuid(): the bare-identifier read used by the "Get X" resolver nodes ---

    @Test
    fun `uuid reads a UUID-typed value via its carried serializer`() {
        val id = Uuid.random()
        assertEquals(id, PipelineValue.of(id, UUIDSerializer()).uuid(json))
    }

    @Test
    fun `uuid reads a bare UUID string`() {
        val id = Uuid.random()
        assertEquals(id, PipelineValue.ofJson(JsonPrimitive(id.toString())).uuid(json))
    }

    @Test
    fun `uuid returns null for an object, a non-UUID string, and JSON null`() {
        val id = Uuid.random()
        assertNull(PipelineValue.ofJson(buildJsonObject { put("id", id.toString()) }).uuid(json), "an object is not a bare UUID")
        assertNull(PipelineValue.ofJson(JsonPrimitive("not-a-uuid")).uuid(json))
        assertNull(PipelineValue.ofJson(JsonNull).uuid(json))
    }

    // --- entityRef(): accepts the entity object (with version) OR a bare UUID ---

    @Test
    fun `entityRef takes the id and version from an entity object`() {
        val id = Uuid.random()
        val ref = PipelineValue.of(VersionedEvent(id, 4), VersionedEvent.serializer()).entityRef(json)
        assertEquals(id, ref?.id)
        assertEquals(4, ref?.version, "a passed entity's version is honored")
    }

    @Test
    fun `entityRef takes a bare UUID with no version`() {
        val id = Uuid.random()
        val ref = PipelineValue.ofJson(JsonPrimitive(id.toString())).entityRef(json)
        assertEquals(id, ref?.id)
        assertNull(ref?.version, "a bare UUID resolves the latest version")
    }

    @Test
    fun `entityRef returns null when the value is neither an entity nor a UUID`() {
        assertNull(PipelineValue.ofJson(JsonPrimitive("not-a-uuid")).entityRef(json))
        assertNull(PipelineValue.ofJson(buildJsonObject { put("name", "x") }).entityRef(json))
    }
}
