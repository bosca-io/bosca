@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.server

import bosca.git.server.graphql.gitSchemaRegistrars
import bosca.graphql.SchemaRegistry
import bosca.graphql.parser.Parser
import bosca.graphql.validation.OperationValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Checks generated operations against the executable standalone API, including namespace wiring.
 */
class GitGraphQLSchemaTest {
    @Test
    fun `standalone schema accepts every Git UI operation`() = runTest {
        SchemaRegistry.initialize(*gitSchemaRegistrars())
        val validator = OperationValidator(SchemaRegistry.registry)
        val root = File(requireNotNull(System.getProperty("workspaceRoot")))
        val operations =
            File(root, "git/git-ui/src/main/graphql").listFiles().orEmpty().filter {
                it.extension == "graphql"
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
