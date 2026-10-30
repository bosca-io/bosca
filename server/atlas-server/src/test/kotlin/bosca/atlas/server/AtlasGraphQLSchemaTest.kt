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
 * Checks both imported applications against Atlas's executable shared API, including namespace wiring.
 */
class AtlasGraphQLSchemaTest {
    @Test
    fun `shared schema accepts every Git and artifacts UI operation`() = runTest {
        SchemaRegistry.initialize(*atlasSchemaRegistrars())
        val validator = OperationValidator(SchemaRegistry.registry)
        val root = File(requireNotNull(System.getProperty("workspaceRoot")))
        val operations =
            listOf("git/git-ui/src/main/graphql", "artifacts/artifacts-ui/src/main/graphql").flatMap { directory ->
                File(root, directory).listFiles().orEmpty().filter { it.extension == "graphql" }
            }
        assertTrue(operations.isNotEmpty())
        operations.forEach { file ->
            val errors = validator.validate(Parser.parse(file.readText()))
            assertTrue(errors.isEmpty(), "${file.name}: ${errors.joinToString()}")
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
