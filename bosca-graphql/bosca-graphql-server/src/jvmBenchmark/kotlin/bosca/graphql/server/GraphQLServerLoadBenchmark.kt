package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.parser.Parser
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared server load tests. JMH supplies 64 calling threads; every invocation has independent request state while
 * sharing only immutable source, schema, parsed document, executor, and coercion tables.
 */
@State(Scope.Benchmark)
open class GraphQLServerLoadBenchmark {
    private val parserOperation =
        """
        query Content(${'$'}limit: Int!, ${'$'}includeDrafts: Boolean! = false) {
          content(limit: ${'$'}limit) {
            id
            title
            author { id name }
            body @include(if: ${'$'}includeDrafts)
            ...Metadata
          }
        }
        fragment Metadata on Content {
          tags { id name }
          created
          modified
        }
        """.trimIndent()
    private lateinit var executor: GraphQLExecutor
    private lateinit var document: Document
    private lateinit var cachedGraphQL: GraphQL
    private lateinit var cachedRequest: GraphQLRequest
    private lateinit var dataLoaderExecutor: GraphQLExecutor
    private lateinit var dataLoaderDocument: Document
    private val dataLoaderTemplate = DataLoaderRegistry()
    private val requestSequence = AtomicLong()

    @Setup
    open fun setup() {
        val scalarExecutable = ExecutableSchema.fromSdl(
            "type Query { value: Int! }",
            runtimeWiring { type("Query") { field("value") { 42 } } },
        )
        executor = GraphQLExecutor(scalarExecutable)
        cachedGraphQL = GraphQL(
            executable = scalarExecutable,
            preparsedDocumentProvider = InMemoryPreparsedDocumentProvider(),
        )
        document = Parser.parse("{ value }")
        cachedRequest = GraphQLRequest("{ value }")
        dataLoaderExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { value(id: ID!): String! }",
                runtimeWiring {
                    type("Query") {
                        field("value") { context ->
                            val request = context.context.getAs<Long>("request")!!
                            context.dataLoaderRegistry.getOrPutLoader<String, String>("request-value") { keys, _ ->
                                keys.map { "$request-$it" }
                            }.load(context.arg<String>("id")!!)
                        }
                    }
                },
            ),
        )
        dataLoaderDocument = Parser.parse("""{ value(id: "shared-key") }""")
        runBlocking {
            check(executor.execute(document).errors.isEmpty())
            check(cachedGraphQL.execute(cachedRequest).errors.isEmpty())
        }
    }

    @Benchmark
    @Threads(64)
    open fun parseOperationAcrossRequests(): Document = Parser.parse(parserOperation)

    @Benchmark
    @Threads(64)
    open fun executeScalarAcrossRequests(): ExecutionResult = runBlocking {
        executor.execute(document)
    }

    @Benchmark
    @Threads(64)
    open fun executeCachedGraphQLAcrossRequests(): ExecutionResult = runBlocking {
        cachedGraphQL.execute(cachedRequest)
    }

    @Benchmark
    @Threads(64)
    open fun executeGeneratedDataLoaderAcrossRequests(): ExecutionResult {
        val request = requestSequence.incrementAndGet()
        val result = runBlocking {
            dataLoaderExecutor.execute(
                dataLoaderDocument,
                context = GraphQLContext(mapOf("request" to request)),
                dataLoaders = dataLoaderTemplate,
            )
        }
        val value = (result.data as JsonObject)["value"]!!.jsonPrimitive.content
        check(value == "$request-shared-key") { "cross-request DataLoader value: expected $request-shared-key, got $value" }
        return result
    }
}
