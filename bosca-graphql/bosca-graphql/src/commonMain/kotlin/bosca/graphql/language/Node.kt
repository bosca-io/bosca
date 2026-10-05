package bosca.graphql.language

/**
 * The root of the GraphQL document AST (per the spec's "Language" section). Every node carries its
 * source [location]. The hierarchy spans both **executable** definitions (operations, fragments) and
 * the **type-system** definitions/extensions (SDL), so one parser handles queries and schemas alike.
 *
 * The AST is split across files by concern: [Document] & the definition marker interfaces here-adjacent,
 * then `ExecutableDefinitions`, `Selections`, `Types`, `Values`, `TypeSystemDefinitions`, and
 * `TypeSystemExtensions`.
 */
sealed interface Node {
    val location: SourceLocation?
}
