package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.parser.GraphQLSyntaxException
import bosca.graphql.parser.Parser
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Parser-only benchmarks. Every invocation starts from source text and returns the resulting AST (or syntax-error
 * location), so validation and execution do not contribute to the measurement.
 */
@State(Scope.Benchmark)
open class GraphQLParserBenchmark {
    private val smallOperation = "{ value }"

    private val representativeOperation =
        """
        query Content(${'$'}limit: Int!, ${'$'}includeDrafts: Boolean! = false) {
          content(limit: ${'$'}limit) {
            id
            title
            author {
              id
              name
            }
            body @include(if: ${'$'}includeDrafts)
            ...Metadata
          }
        }

        fragment Metadata on Content {
          tags {
            id
            name
          }
          created
          modified
        }
        """.trimIndent()

    private val wideOperation =
        "{ ${List(1_000) { index -> "field$index: value(arg: $index)" }.joinToString(" ")} }"

    private val largeOperation =
        "{ ${List(60_000) { index -> "field$index" }.joinToString(" ")} }"

    private val malformedOperation =
        """
        query Broken(${'$'}id: ID!) {
          content(id: ${'$'}id) {
            id
            title
        """.trimIndent()

    @Benchmark
    fun parseSmallOperation(): Document = Parser.parse(smallOperation)

    @Benchmark
    fun parseRepresentativeOperation(): Document = Parser.parse(representativeOperation)

    @Benchmark
    fun parseWideOperation(): Document = Parser.parse(wideOperation)

    @Benchmark
    fun parseLargeOperation(): Document = Parser.parse(largeOperation)

    @Benchmark
    fun rejectMalformedOperation(): Int =
        try {
            Parser.parse(malformedOperation)
            error("Malformed operation unexpectedly parsed")
        } catch (error: GraphQLSyntaxException) {
            error.location.offset
        }
}
