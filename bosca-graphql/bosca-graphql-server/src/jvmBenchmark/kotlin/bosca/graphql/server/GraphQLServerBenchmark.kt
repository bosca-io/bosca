package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.parser.Parser
import bosca.graphql.validation.OperationValidator
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Repeatable engine microbenchmarks. These use parsed documents and call [GraphQLExecutor] directly so parser,
 * validation, transport, database, and network work cannot hide execution-engine regressions.
 */
@State(Scope.Benchmark)
open class GraphQLServerBenchmark {
    private lateinit var scalarExecutor: GraphQLExecutor
    private lateinit var scalarDocument: Document
    private lateinit var nestedExecutor: GraphQLExecutor
    private lateinit var nestedDocument: Document
    private lateinit var dataLoaderExecutor: GraphQLExecutor
    private lateinit var dataLoaderDocument: Document
    private lateinit var multiLoaderExecutor: GraphQLExecutor
    private lateinit var multiLoaderDocument: Document
    private lateinit var unevenExecutor: GraphQLExecutor
    private lateinit var unevenDocument: Document
    private lateinit var wideFieldExecutor: GraphQLExecutor
    private lateinit var wideFieldDocument: Document
    private lateinit var wideListExecutor: GraphQLExecutor
    private lateinit var wideListDocument: Document
    private lateinit var validator: OperationValidator
    private lateinit var conflictingDocument: Document

    private val users = List(32) { index ->
        mapOf(
            "id" to index.toString(),
            "name" to "user-$index",
            "friend" to mapOf("id" to "friend-$index", "name" to "friend-name-$index"),
        )
    }
    private val names: Map<String, String> =
        users.associate { it.getValue("id") as String to it.getValue("name") as String }

    @Setup
    open fun setup() {
        scalarExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { value: Int! }",
                runtimeWiring { type("Query") { field("value") { 42 } } },
            ),
        )
        scalarDocument = Parser.parse("{ value }")

        val nestedSchema = ExecutableSchema.fromSdl(
            """
            type Query { users: [User!]! }
            type User { id: ID! name: String! friend: User! }
            """.trimIndent(),
            runtimeWiring { type("Query") { field("users") { users } } },
        )
        nestedExecutor = GraphQLExecutor(nestedSchema)
        nestedDocument = Parser.parse("{ users { id name friend { id name } } }")

        dataLoaderExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                """
                type Query { users: [User!]! }
                type User { id: ID! loadedName: String! }
                """.trimIndent(),
                runtimeWiring {
                    type("Query") { field("users") { users } }
                    type("User") {
                        field("loadedName") { context ->
                            val id = (context.source as Map<*, *>)["id"] as String
                            context.dataLoader<String, String>("names").load(id)
                        }
                    }
                },
            ),
        )
        dataLoaderDocument = Parser.parse("{ users { id loadedName } }")

        // Five loader-backed fields per user; each batch waits 1 ms, standing in for a cache or database round trip.
        val loadedFields = List(LOADER_COUNT) { "loaded$it" }
        multiLoaderExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                """
                type Query { users: [User!]! }
                type User { id: ID! ${loadedFields.joinToString(" ") { "$it: String!" }} }
                """.trimIndent(),
                runtimeWiring {
                    type("Query") { field("users") { users } }
                    type("User") {
                        loadedFields.forEach { name ->
                            field(name) { context ->
                                val id = (context.source as Map<*, *>)["id"] as String
                                context.dataLoaderRegistry.getOrPutLoader<String, String>(name) { keys, _ ->
                                    delay(1)
                                    keys.map { "$name-$it" }
                                }.load(id)
                            }
                        }
                    }
                },
            ),
        )
        multiLoaderDocument = Parser.parse("{ users { id ${loadedFields.joinToString(" ")} } }")

        val unevenFields = List(40) { "f$it" }
        unevenExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { ${unevenFields.joinToString(" ") { "$it: Int!" }} }",
                runtimeWiring {
                    type("Query") {
                        unevenFields.forEachIndexed { index, name ->
                            field(name) {
                                if (index % 8 == 0) delay(10)
                                index
                            }
                        }
                    }
                },
            ),
            limits = GraphQLExecutionLimits(maxConcurrentFields = 8),
        )
        unevenDocument = Parser.parse("{ ${unevenFields.joinToString(" ")} }")

        val wideFields = List(512) { "f$it" }
        wideFieldExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { ${wideFields.joinToString(" ") { "$it: Int!" }} }",
                runtimeWiring {
                    type("Query") {
                        wideFields.forEachIndexed { index, name -> field(name) { index } }
                    }
                },
            ),
            limits = GraphQLExecutionLimits(maxConcurrentFields = 64),
        )
        wideFieldDocument = Parser.parse("{ ${wideFields.joinToString(" ")} }")

        val wideList = (0 until 2_048).toList()
        wideListExecutor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { values: [Int!]! }",
                runtimeWiring { type("Query") { field("values") { wideList } } },
            ),
            limits = GraphQLExecutionLimits(maxConcurrentListItems = 64),
        )
        wideListDocument = Parser.parse("{ values }")

        val validationSchema = ExecutableSchema.fromSdl(
            "type Query { value(arg: Int): Int }",
            runtimeWiring { type("Query") { field("value") { 1 } } },
        )
        validator = OperationValidator(validationSchema.schema)
        conflictingDocument = Parser.parse(
            "{ ${List(1_000) { index -> "same: value(arg: $index)" }.joinToString(" ")} }",
        )

        runBlocking {
            check(scalarExecutor.execute(scalarDocument).errors.isEmpty())
            check(nestedExecutor.execute(nestedDocument).errors.isEmpty())
            check(
                dataLoaderExecutor.execute(
                    dataLoaderDocument,
                    dataLoaders = newDataLoaderRegistry(),
                ).errors.isEmpty(),
            )
            check(multiLoaderExecutor.execute(multiLoaderDocument, dataLoaders = DataLoaderRegistry()).errors.isEmpty())
            check(unevenExecutor.execute(unevenDocument).errors.isEmpty())
            check(wideFieldExecutor.execute(wideFieldDocument).errors.isEmpty())
            check(wideListExecutor.execute(wideListDocument).errors.isEmpty())
        }
        check(validator.validate(conflictingDocument).isNotEmpty())
    }

    @Benchmark
    open fun coroutineBridge(): Int = runBlocking { 1 }

    @Benchmark
    open fun executeScalar(): ExecutionResult = runBlocking {
        scalarExecutor.execute(scalarDocument)
    }

    @Benchmark
    open fun executeNestedResponse(): ExecutionResult = runBlocking {
        nestedExecutor.execute(nestedDocument)
    }

    @Benchmark
    open fun executeDataLoaderResponse(): ExecutionResult = runBlocking {
        dataLoaderExecutor.execute(dataLoaderDocument, dataLoaders = newDataLoaderRegistry())
    }

    @Benchmark
    open fun executeMultiLoaderResponse(): ExecutionResult = runBlocking {
        multiLoaderExecutor.execute(multiLoaderDocument, dataLoaders = DataLoaderRegistry())
    }

    @Benchmark
    open fun executeUnevenConcurrentFields(): ExecutionResult = runBlocking {
        unevenExecutor.execute(unevenDocument)
    }

    @Benchmark
    open fun executeWideCpuFields(): ExecutionResult = runBlocking(Dispatchers.Default) {
        wideFieldExecutor.execute(wideFieldDocument)
    }

    @Benchmark
    open fun executeWideCpuList(): ExecutionResult = runBlocking(Dispatchers.Default) {
        wideListExecutor.execute(wideListDocument)
    }

    @Benchmark
    open fun validateWideConflictingSelection(): Int =
        validator.validate(conflictingDocument).size

    private fun newDataLoaderRegistry(): DataLoaderRegistry = dataLoaderRegistry {
        loader<String, String>("names") { keys -> keys.map(names::get) }
    }

    private companion object {
        const val LOADER_COUNT = 5
    }
}
