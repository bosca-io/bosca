@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.atlas.server

import bosca.atlas.server.graphql.atlasSchemaRegistrars
import bosca.graphql.SchemaRegistry
import bosca.graphql.parser.Parser
import bosca.graphql.validation.OperationValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Checks imported applications against Atlas's executable shared API, including namespace wiring.
 */
class AtlasGraphQLSchemaTest {
    @Test
    fun `shared schema accepts every imported UI operation`() = runTest {
        SchemaRegistry.initialize(*atlasSchemaRegistrars())
        val validator = OperationValidator(SchemaRegistry.registry)
        assertTrue(validator.validate(Parser.parse("query { devices { devices { id } } }")).isNotEmpty(),
            "Internal message-service dependencies must not add a device API namespace to Atlas")
        assertTrue(validator.validate(Parser.parse("mutation { devices { delete(id: \"00000000-0000-0000-0000-000000000000\") } }")).isNotEmpty(),
            "Internal message-service dependencies must not add device mutations to Atlas")
        val root = File(requireNotNull(System.getProperty("workspaceRoot")))
        val directories = listOf("git/git-ui/src/main/graphql", "artifacts/artifacts-ui/src/main/graphql",
            "admin-support/system-ui/src/main/graphql", "workops/workops-ui/src/main/graphql")
        directories.forEach { directory ->
            val files = File(root, directory).listFiles().orEmpty().filter { it.extension == "graphql" }.sortedBy { it.name }
            assertTrue(files.isNotEmpty(), "No UI operations in $directory")
            // The UI generator resolves fragment spreads across the source files in each module.
            val errors = validator.validate(Parser.parse(files.joinToString("\n") { it.readText() }))
            assertTrue(errors.isEmpty(), "$directory: ${errors.joinToString()}")
        }
        val authSource = File(root, "web/packages/auth/src/graphql.ts").readText()
        val declarations =
            Regex("const (\\w+) = `([\\s\\S]*?)`").findAll(authSource).associate {
                it.groupValues[1] to it.groupValues[2]
            }
        assertTrue(declarations.size > 20)
        declarations
            .filterValues {
                it.trimStart().startsWith("query ") || it.trimStart().startsWith("mutation ")
            }
            .forEach { (name, source) ->
                val expanded =
                    Regex("\\$\\{(\\w+)\\}").replace(source) { match ->
                        declarations.getValue(match.groupValues[1])
                    }
                val errors = validator.validate(Parser.parse(expanded))
                assertTrue(errors.isEmpty(), "$name: ${errors.joinToString()}")
            }
    }
}
