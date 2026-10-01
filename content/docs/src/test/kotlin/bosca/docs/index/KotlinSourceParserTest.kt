package bosca.docs.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSourceParserTest {

    private val parser = KotlinSourceParser()

    @Test
    fun `parses service interface with fully qualified types`() {
        val source = """
            package bosca.scripting.service

            import bosca.scripting.model.Script
            import bosca.scripting.model.ScriptInput
            import bosca.scripting.model.ScriptType
            import bosca.serialization.UUID
            import bosca.service.Service

            interface ScriptService : Service {
                suspend fun getAll(): List<Script>
                suspend fun getByType(type: ScriptType): List<Script>
                suspend fun get(id: UUID): Script?
                suspend fun add(input: ScriptInput): Script
                suspend fun edit(id: UUID, input: ScriptInput): Script
                suspend fun delete(id: UUID)
            }
        """.trimIndent()

        val docs = parser.parse("backend/framework/core-scripting/src/main/kotlin/bosca/scripting/service/ScriptService.kt", source)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals("bosca.scripting.service.ScriptService", doc.qualifiedName)
        assertEquals("interface", doc.kind)
        assertEquals("service", doc.category)
        assertEquals("provide<ScriptService>()", doc.accessPattern)
        assertTrue(doc.supertypes.contains("bosca.service.Service"))

        val getAll = doc.methods.find { it.name == "getAll" }!!
        assertEquals("List<bosca.scripting.model.Script>", getAll.returnType)
        assertEquals("suspend fun getAll(): List<bosca.scripting.model.Script>", getAll.signature)

        val getByType = doc.methods.find { it.name == "getByType" }!!
        assertEquals("List<bosca.scripting.model.Script>", getByType.returnType)
        assertEquals(1, getByType.parameters.size)
        assertEquals("bosca.scripting.model.ScriptType", getByType.parameters[0].type)
        assertEquals("suspend fun getByType(type: bosca.scripting.model.ScriptType): List<bosca.scripting.model.Script>", getByType.signature)

        val get = doc.methods.find { it.name == "get" }!!
        assertEquals("bosca.scripting.model.Script?", get.returnType)
        assertEquals("bosca.serialization.UUID", get.parameters[0].type)

        val edit = doc.methods.find { it.name == "edit" }!!
        assertEquals(2, edit.parameters.size)
        assertEquals("bosca.serialization.UUID", edit.parameters[0].type)
        assertEquals("bosca.scripting.model.ScriptInput", edit.parameters[1].type)

        val delete = doc.methods.find { it.name == "delete" }!!
        assertEquals("", delete.returnType)
        assertEquals("bosca.serialization.UUID", delete.parameters[0].type)
    }

    @Test
    fun `parses enum model`() {
        val source = """
            package bosca.scripting.model

            import kotlinx.serialization.Serializable

            @Serializable
            enum class ScriptType {
                GENERAL,
                WORKFLOW,
                AUTOMATION,
            }
        """.trimIndent()

        val docs = parser.parse("backend/framework/core-scripting/src/main/kotlin/bosca/scripting/model/ScriptType.kt", source)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals("model", doc.category)
        assertEquals("enum", doc.kind)
        assertEquals("bosca.scripting.model.ScriptType", doc.qualifiedName)
        assertTrue(doc.enumValues.containsAll(listOf("GENERAL", "WORKFLOW", "AUTOMATION")))
    }

    @Test
    fun `parses data class with fully qualified property types`() {
        val source = """
            package bosca.scripting.model

            import bosca.serialization.OffsetDateTime
            import bosca.serialization.UUID
            import kotlinx.serialization.Contextual
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.JsonElement

            @Serializable
            data class Script(
                @Contextual
                val id: UUID = UUID.NIL,
                val key: String,
                val name: String,
                val type: ScriptType = ScriptType.GENERAL,
                val source: String,
                val version: Int = 1,
                val enabled: Boolean = true,
                @Contextual
                val inputSchema: JsonElement? = null,
                @Contextual
                val created: OffsetDateTime? = null,
            )
        """.trimIndent()

        val docs = parser.parse("backend/framework/core-scripting/src/main/kotlin/bosca/scripting/model/Script.kt", source)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals("model", doc.category)
        assertEquals("data-class", doc.kind)

        val id = doc.properties.find { it.name == "id" }!!
        assertEquals("bosca.serialization.UUID", id.type)

        val type = doc.properties.find { it.name == "type" }!!
        assertEquals("bosca.scripting.model.ScriptType", type.type)

        val inputSchema = doc.properties.find { it.name == "inputSchema" }!!
        assertEquals("kotlinx.serialization.json.JsonElement?", inputSchema.type)

        val created = doc.properties.find { it.name == "created" }!!
        assertEquals("bosca.serialization.OffsetDateTime?", created.type)

        // Built-in types stay unqualified
        val key = doc.properties.find { it.name == "key" }!!
        assertEquals("String", key.type)
        val version = doc.properties.find { it.name == "version" }!!
        assertEquals("Int", version.type)
    }

    @Test
    fun `parses class with multi-line constructor and extracts public methods`() {
        val source = """
            package bosca.content.transition.service

            import bosca.content.metadata.service.MetadataService
            import bosca.content.transition.model.BeginTransitionInput
            import bosca.serialization.UUID

            class Transitioner(
                private val metadataService: MetadataService,
                private val stateService: StateService,
                private val permissionEvaluator: PermissionEvaluator
            ) {
                suspend fun beginTransition(input: BeginTransitionInput): Boolean {
                    return true
                }

                suspend fun cancelLatestJob(metadataId: UUID): Boolean {
                    return true
                }

                private suspend fun doInternal(): Unit {
                }
            }
        """.trimIndent()

        val docs = parser.parseAll("backend/framework/content/src/main/kotlin/bosca/content/transition/service/Transitioner.kt", source)
        val doc = docs.find { it.simpleName == "Transitioner" }!!
        assertEquals(2, doc.methods.size)
        assertEquals("beginTransition", doc.methods[0].name)
        assertEquals("cancelLatestJob", doc.methods[1].name)
        // Private constructor properties should be filtered out
        assertTrue(doc.properties.isEmpty())
    }

    @Test
    fun `resolves supertypes fully`() {
        val source = """
            package bosca.scripting.service

            import bosca.scripting.model.Script
            import bosca.service.Service

            interface ScriptQueryService : Service {
                suspend fun findAll(): List<Script>
            }
        """.trimIndent()

        val docs = parser.parse("backend/framework/scripting/src/main/kotlin/bosca/scripting/service/ScriptQueryService.kt", source)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals("service", doc.category)
        assertTrue(doc.supertypes.any { it.contains("bosca.service.Service") })
    }
}
