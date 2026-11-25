package bosca.ai.configuration

import bosca.graphql.language.TypeDefinition
import bosca.graphql.parser.Parser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AiSchemaCompositionTest {
    @Test
    fun `all AI schema resources merge without duplicate type definitions`() = runBlocking {
        val definitions = Parser.parse(AISchemaRegistrar().load()).definitions.filterIsInstance<TypeDefinition>()
        val duplicates = definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate AI schema types: $duplicates")
    }
}
