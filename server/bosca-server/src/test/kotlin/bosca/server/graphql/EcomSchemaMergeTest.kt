package bosca.server.graphql

import bosca.configuration.configuration.ConfigurationSchemaRegistrar
import bosca.ecommerce.configuration.EcommerceSchemaRegistrar
import bosca.graphql.SchemaRegistrar
import bosca.graphql.language.TypeDefinition
import bosca.graphql.parser.Parser
import bosca.server.configuration.BoscaSchemaRegistrar
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the full-schema merge against type-name and schema-resource collisions that only surface at
 * server boot (`BoscaGraphQLService.initialize` -> `SchemaRegistry.initialize` -> `SchemaParser.parse`
 * over every registrar's SDL), not in the per-module schema tests. Two regressions this catches:
 *
 *  - ecom must NOT define a `Subscription` object type — `Subscription` is the GraphQL root
 *    subscription operation type (defined in the server's `query.graphqls`, extended by every
 *    module), so an object type of that name redefines it. The recurring-purchase type is
 *    `EcomSubscription`.
 *  - ecom must NOT ship a `graphql/configurations.graphqls` resource — schema files load by bare
 *    classpath path (`graphql/<name>`), so it would collide with the configuration module's
 *    same-named file and both registrars would load the same SDL. ecom's file is
 *    `commerce-configurations.graphqls`.
 *
 * The focused registrar subset intentionally has unresolved references to types supplied by other modules,
 * so this test parses the SDL and checks duplicate definitions without attempting full-schema validation.
 */
class EcomSchemaMergeTest {

    @Test
    fun `root, configuration, and ecommerce schemas merge without redefinition`() = runBlocking {
        val registrars: List<SchemaRegistrar> = listOf(
            BoscaSchemaRegistrar(),
            ConfigurationSchemaRegistrar(),
            EcommerceSchemaRegistrar(),
        )
        val sdl = buildString {
            for (registrar in registrars) {
                appendLine(registrar.load())
            }
        }
        val document = Parser.parse(sdl)
        val duplicateTypes = document.definitions
            .filterIsInstance<TypeDefinition>()
            .groupBy { it.name }
            .filterValues { it.size > 1 }

        assertTrue(duplicateTypes.isEmpty(), "Duplicate type definitions: ${duplicateTypes.keys}")
    }
}
