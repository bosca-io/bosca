package bosca.artifacts.ui

import bosca.graphql.language.*
import bosca.graphql.parser.Parser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the generated client's maintained subset against the schemas that actually own its fields.
 */
class ArtifactsUiSchemaContractTest {
    @Test
    fun `client fields arguments inputs and enum values match domain schemas`() {
        val root = File(requireNotNull(System.getProperty("workspaceRoot")))
        val actual = mutableMapOf<String, TypeDefinition>()
        val fields = mutableMapOf<String, MutableMap<String, FieldDefinition>>()
        root
            .walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", "node_modules", ".gradle", ".nuxt") }
            .filter {
                it.isFile &&
                    it.extension == "graphqls" &&
                    "/src/main/resources/" in it.invariantSeparatorsPath
            }
            .forEach { file ->
                val definitions = Parser.parse(file.readText()).definitions
                actual.putAll(
                    definitions.filterIsInstance<TypeDefinition>().associateBy { it.name }
                )
                for (definition in definitions) {
                    val pair =
                        when (definition) {
                            is ObjectTypeDefinition -> definition.name to definition.fields
                            is ObjectTypeExtension -> definition.name to definition.fields
                            else -> continue
                        }
                    fields
                        .getOrPut(pair.first) { mutableMapOf() }
                        .putAll(pair.second.associateBy { it.name })
                }
            }
        val errors = mutableListOf<String>()
        fun same(expected: Any, actual: Any, label: String) {
            if (expected != actual) errors.add("$label: expected $expected, got $actual")
        }
        fun present(value: Boolean, label: String) {
            if (!value) errors.add(label)
        }
        val client =
            Parser.parse(File(root, "artifacts/artifacts-ui/src/main/graphql/schema.graphqls").readText())
        for (type in client.definitions) when (type) {
            is ObjectTypeDefinition ->
                for (field in type.fields) {
                    val contract =
                        assertNotNull(
                            fields[type.name]?.get(field.name),
                            "Missing ${type.name}.${field.name}",
                        )
                    same(
                        render(contract.type),
                        render(field.type),
                        "${type.name}.${field.name} return type",
                    )
                    for (argument in field.arguments) {
                        val expected =
                            assertNotNull(
                                contract.arguments.firstOrNull { it.name == argument.name },
                                "${type.name}.${field.name}(${argument.name})",
                            )
                        same(
                            render(expected.type),
                            render(argument.type),
                            "${type.name}.${field.name}(${argument.name}) type",
                        )
                    }
                    for (required in
                        contract.arguments.filter {
                            it.type is NonNullType && it.defaultValue == null
                        }) {
                        present(
                            field.arguments.any { it.name == required.name },
                            "Missing required argument ${type.name}.${field.name}(${required.name})",
                        )
                    }
                }
            is InputObjectTypeDefinition -> {
                val contract =
                    assertNotNull(actual[type.name] as? InputObjectTypeDefinition, type.name)
                for (field in type.fields) {
                    val expected =
                        assertNotNull(
                            contract.fields.firstOrNull { it.name == field.name },
                            "${type.name}.${field.name}",
                        )
                    same(
                        render(expected.type),
                        render(field.type),
                        "${type.name}.${field.name} type",
                    )
                }
                for (required in
                    contract.fields.filter { it.type is NonNullType && it.defaultValue == null }) {
                    present(
                        type.fields.any { it.name == required.name },
                        "Missing required input ${type.name}.${required.name}",
                    )
                }
            }
            is EnumTypeDefinition -> {
                val contract = assertNotNull(actual[type.name] as? EnumTypeDefinition, type.name)
                same(
                    contract.values.map { it.name }.toSet(),
                    type.values.map { it.name }.toSet(),
                    type.name,
                )
            }
            else -> Unit
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    private fun render(type: Type): String =
        when (type) {
            is NamedType -> type.name
            is ListType -> "[${render(type.type)}]"
            is NonNullType -> "${render(type.type)}!"
        }
}
