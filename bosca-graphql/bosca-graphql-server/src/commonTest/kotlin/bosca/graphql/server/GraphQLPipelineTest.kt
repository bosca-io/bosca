package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.language.SourceLocation
import bosca.graphql.parser.GraphQLSyntaxException
import bosca.graphql.parser.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**the [GraphQL] pipeline — query limits, the preparsed cache, and the instrumentation hook lifecycle. */
class GraphQLPipelineTest {

    private val sdl = """
        type Query { a: A list: [A!] }
        type A { b: B }
        type B { c: String }
    """.trimIndent()

    private class Flags {
        var aResolved = false
    }

    private fun executable(flags: Flags) = ExecutableSchema.fromSdl(
        sdl,
        runtimeWiring {
            type("Query") {
                field("a") { flags.aResolved = true; mapOf("b" to mapOf("c" to "x")) }
                field("list") { listOf(mapOf("b" to mapOf("c" to "x"))) }
            }
        },
    )

    /** Records every instrumentation hook as `begin:`/`end:` events, in order. */
    private class Recorder : SimpleInstrumentation() {
        val events = mutableListOf<String>()
        override suspend fun beginParse(query: String): InstrumentationPhase<Document> = phase("parse")
        override suspend fun beginValidation(document: Document): InstrumentationPhase<List<GraphQLError>> = phase("validate")
        override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> = phase("execute")
        override suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<kotlinx.serialization.json.JsonElement> = phase("field:${parameters.fieldName}")
        private fun <T> phase(name: String): InstrumentationPhase<T> {
            events.add("begin:$name")
            return InstrumentationPhase { _, _ -> events.add("end:$name") }
        }
        fun count(event: String) = events.count { it == event }
    }

    @Test
    fun `an over-deep query is rejected before execution`() = runTest {
        val flags = Flags()
        val recorder = Recorder()
        val graphql = GraphQL(executable(flags), instrumentation = Instrumentation.of(MaxQueryDepthInstrumentation(2), recorder))
        val result = graphql.execute(GraphQLRequest("{ a { b { c } } }")) // depth 3
        assertEquals(null, result.data) // request error → no data
        assertTrue(result.errors.single().message.contains("depth 3"))
        assertFalse(flags.aResolved, "the resolver must not run when the query is rejected")
        assertEquals(0, recorder.count("begin:field:a")) // never reached field execution
    }

    @Test
    fun `an over-complex query is rejected before execution`() = runTest {
        val flags = Flags()
        val graphql = GraphQL(executable(flags), instrumentation = MaxQueryComplexityInstrumentation(2))
        val result = graphql.execute(GraphQLRequest("{ a { b { c } } }")) // complexity 3
        assertTrue(result.errors.single().message.contains("complexity 3"))
        assertFalse(flags.aResolved)
    }

    @Test
    fun `request-specific instrumentation rejection does not evict a valid preparsed document`() = runTest {
        val recorder = Recorder()
        val provider = InMemoryPreparsedDocumentProvider()
        val graphql = GraphQL(
            executable(Flags()),
            instrumentation = Instrumentation.of(MaxQueryComplexityInstrumentation(2), recorder),
            preparsedDocumentProvider = provider,
        )

        repeat(2) {
            val result = graphql.execute(GraphQLRequest("{ a { b { c } } }"))
            assertTrue(result.errors.single().message.contains("complexity 3"))
        }

        assertEquals(1, recorder.count("begin:parse"))
        assertEquals(1, provider.size)
    }

    @Test
    fun `a query within the limits executes`() = runTest {
        val flags = Flags()
        val graphql = GraphQL(executable(flags), instrumentation = Instrumentation.of(MaxQueryDepthInstrumentation(5), MaxQueryComplexityInstrumentation(50)))
        val result = graphql.execute(GraphQLRequest("{ a { b { c } } }"))
        assertTrue(result.errors.isEmpty())
        assertEquals("x", (result.data as JsonObject)["a"]!!.jsonObject["b"]!!.jsonObject["c"]!!.jsonPrimitive.content)
        assertTrue(flags.aResolved)
    }

    @Test
    fun `the preparsed cache parses each query once`() = runTest {
        val recorder = Recorder()
        val provider = InMemoryPreparsedDocumentProvider()
        val graphql = GraphQL(executable(Flags()), instrumentation = recorder, preparsedDocumentProvider = provider)
        repeat(3) { graphql.execute(GraphQLRequest("{ a { b { c } } }")) }
        assertEquals(1, recorder.count("begin:parse")) // parsed once, then served from cache
        assertEquals(1, provider.size)
    }

    @Test
    fun `a preloaded persisted document is served without parsing`() = runTest {
        val recorder = Recorder()
        val query = "{ a { b { c } } }"
        val provider = InMemoryPreparsedDocumentProvider(preload = mapOf(query to Parser.parse(query)))
        val graphql = GraphQL(executable(Flags()), instrumentation = recorder, preparsedDocumentProvider = provider)
        val result = graphql.execute(GraphQLRequest(query))
        assertTrue(result.errors.isEmpty())
        assertEquals(0, recorder.count("begin:parse")) // came straight from the allowlist
    }

    @Test
    fun `the hooks fire around parse, validate, execute, and each field`() = runTest {
        val recorder = Recorder()
        val graphql = GraphQL(executable(Flags()), instrumentation = recorder)
        graphql.execute(GraphQLRequest("{ a { b { c } } }"))

        assertEquals(1, recorder.count("begin:parse"))
        assertEquals(1, recorder.count("begin:validate"))
        assertEquals(1, recorder.count("begin:execute"))
        listOf("a", "b", "c").forEach { assertEquals(1, recorder.count("begin:field:$it"), "field $it") }
        // ordering: parse → validate → execute → fields, and every begin has a matching end
        val order = recorder.events.filter { it.startsWith("begin:") }
        assertTrue(order.indexOf("begin:parse") < order.indexOf("begin:validate"))
        assertTrue(order.indexOf("begin:validate") < order.indexOf("begin:execute"))
        assertTrue(order.indexOf("begin:execute") < order.indexOf("begin:field:a"))
        assertEquals(recorder.count("begin:execute"), recorder.count("end:execute"))
        assertEquals(recorder.count("begin:field:a"), recorder.count("end:field:a"))
    }

    @Test
    fun `operation completion callbacks run once and all chained callbacks observe success`() = runTest {
        val completionFailure = IllegalStateException("operation completion failed")
        val secondCompletionFailure = IllegalArgumentException("second operation completion failed")
        var throwingCalls = 0
        var secondThrowingCalls = 0
        var repeatedThrowingCalls = 0
        var followingCalls = 0
        var followingResult: ExecutionResult? = null
        var followingError: Throwable? = null
        val throwing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, _ ->
                    throwingCalls++
                    throw completionFailure
                }
        }
        val secondThrowing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, _ ->
                    secondThrowingCalls++
                    throw secondCompletionFailure
                }
        }
        val repeatedThrowing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, _ ->
                    repeatedThrowingCalls++
                    throw completionFailure
                }
        }
        val following = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { result, error ->
                    followingCalls++
                    followingResult = result
                    followingError = error
                }
        }

        val thrown = assertFailsWith<IllegalStateException> {
            GraphQL(
                executable(Flags()),
                instrumentation = Instrumentation.of(throwing, secondThrowing, repeatedThrowing, following),
            )
                .execute(GraphQLRequest("{ a { b { c } } }"))
        }

        assertSame(completionFailure, thrown)
        assertEquals(1, throwingCalls)
        assertEquals(1, secondThrowingCalls)
        assertEquals(1, repeatedThrowingCalls)
        assertEquals(1, followingCalls)
        assertTrue(followingResult != null)
        assertEquals(null, followingError)
        assertTrue(secondCompletionFailure in thrown.suppressedExceptions)
    }

    @Test
    fun `operation completion failure cannot replace the execution failure`() = runTest {
        val executionFailure = IllegalArgumentException("execution failed")
        val completionFailure = IllegalStateException("operation completion failed")
        var completionCalls = 0
        var rethrowingCalls = 0
        var observedError: Throwable? = null
        val throwing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, error ->
                    completionCalls++
                    observedError = error
                    throw completionFailure
                }
        }
        val rethrowing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, error ->
                    rethrowingCalls++
                    throw error!!
                }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { value: Int }",
            runtimeWiring { type("Query") { field("value") { error("resolver failed") } } },
        )
        val exceptionHandler = DataFetcherExceptionHandler { _, _, _, _ -> throw executionFailure }

        val thrown = assertFailsWith<IllegalArgumentException> {
            GraphQL(
                executable,
                instrumentation = Instrumentation.of(throwing, rethrowing),
                exceptionHandler = exceptionHandler,
            )
                .execute(GraphQLRequest("{ value }"))
        }

        assertSame(executionFailure, thrown)
        assertSame(executionFailure, observedError)
        assertEquals(1, completionCalls)
        assertEquals(1, rethrowingCalls)
        assertTrue(completionFailure in thrown.suppressedExceptions)
    }

    @Test
    fun `chained begin failure completes every phase that already started`() = runTest {
        val beginFailure = IllegalStateException("second begin failed")
        val completionFailure = IllegalArgumentException("first completion failed")
        var firstCompletions = 0
        var secondCompletions = 0
        var followingBegins = 0
        var observedError: Throwable? = null
        val first = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { result, error ->
                    firstCompletions++
                    assertEquals(null, result)
                    assertSame(beginFailure, error)
                    throw completionFailure
                }
        }
        val second = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { result, error ->
                    secondCompletions++
                    assertEquals(null, result)
                    observedError = error
                }
        }
        val failing = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                throw beginFailure
        }
        val following = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> {
                followingBegins++
                return noopPhase()
            }
        }

        val thrown = assertFailsWith<IllegalStateException> {
            GraphQL(
                executable(Flags()),
                instrumentation = Instrumentation.of(first, second, failing, following),
            ).execute(GraphQLRequest("{ a { b { c } } }"))
        }

        assertSame(beginFailure, thrown)
        assertEquals(1, firstCompletions)
        assertEquals(1, secondCompletions)
        assertSame(beginFailure, observedError)
        assertEquals(0, followingBegins)
        assertTrue(completionFailure in thrown.suppressedExceptions)
    }

    @Test
    fun `completion rethrowing an execution failure does not self suppress`() {
        val failure = IllegalStateException("execution failed")
        var completionCalls = 0
        val phase = InstrumentationPhase<ExecutionResult> { _, error ->
            completionCalls++
            throw error!!
        }

        phase.completeExceptionally(failure)

        assertEquals(1, completionCalls)
        assertTrue(failure.suppressedExceptions.isEmpty())
    }

    @Test
    fun `parse completion is not mistaken for a syntax failure`() = runTest {
        val completionFailure = GraphQLSyntaxException("completion failed", SourceLocation(1, 1, 0))
        var completionCalls = 0
        val instrumentation = object : SimpleInstrumentation() {
            override suspend fun beginParse(query: String): InstrumentationPhase<Document> =
                InstrumentationPhase { _, _ ->
                    completionCalls++
                    throw completionFailure
                }
        }

        val thrown = assertFailsWith<GraphQLSyntaxException> {
            GraphQL(executable(Flags()), instrumentation = instrumentation)
                .execute(GraphQLRequest("{ a { b { c } } }"))
        }

        assertSame(completionFailure, thrown)
        assertEquals(1, completionCalls)
    }

    @Test
    fun `parse completion failure cannot replace a syntax failure`() = runTest {
        val completionFailure = IllegalStateException("parse completion failed")
        var completionCalls = 0
        var observedError: Throwable? = null
        val instrumentation = object : SimpleInstrumentation() {
            override suspend fun beginParse(query: String): InstrumentationPhase<Document> =
                InstrumentationPhase { result, error ->
                    completionCalls++
                    assertEquals(null, result)
                    observedError = error
                    throw completionFailure
                }
        }

        val result = GraphQL(executable(Flags()), instrumentation = instrumentation)
            .execute(GraphQLRequest("{ a {"))

        assertEquals(null, result.data)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(1, completionCalls)
        assertTrue(observedError is GraphQLSyntaxException)
        assertTrue(completionFailure in observedError!!.suppressedExceptions)
    }

    @Test
    fun `field completion callbacks run once and all chained callbacks observe success`() = runTest {
        val completionFailure = IllegalStateException("field completion failed")
        var throwingCalls = 0
        var followingCalls = 0
        var followingResult: kotlinx.serialization.json.JsonElement? = null
        var followingError: Throwable? = null
        val throwing = object : SimpleInstrumentation() {
            override suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<kotlinx.serialization.json.JsonElement> =
                InstrumentationPhase { _, _ ->
                    throwingCalls++
                    throw completionFailure
                }
        }
        val following = object : SimpleInstrumentation() {
            override suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<kotlinx.serialization.json.JsonElement> =
                InstrumentationPhase { result, error ->
                    followingCalls++
                    followingResult = result
                    followingError = error
                }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { value: Int! }",
            runtimeWiring { type("Query") { field("value") { 42 } } },
        )

        val thrown = assertFailsWith<IllegalStateException> {
            GraphQL(executable, instrumentation = Instrumentation.of(throwing, following))
                .execute(GraphQLRequest("{ value }"))
        }

        assertSame(completionFailure, thrown)
        assertEquals(1, throwingCalls)
        assertEquals(1, followingCalls)
        assertTrue(followingResult != null)
        assertEquals(null, followingError)
    }

    @Test
    fun `field completion failure cannot replace the field failure`() = runTest {
        val fieldFailure = IllegalArgumentException("field failed")
        val completionFailure = IllegalStateException("field completion failed")
        var completionCalls = 0
        var observedError: Throwable? = null
        val instrumentation = object : SimpleInstrumentation() {
            override suspend fun beginField(parameters: FieldParameters): InstrumentationPhase<kotlinx.serialization.json.JsonElement> =
                InstrumentationPhase { _, error ->
                    completionCalls++
                    observedError = error
                    throw completionFailure
                }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { value: Int }",
            runtimeWiring { type("Query") { field("value") { error("resolver failed") } } },
        )
        val exceptionHandler = DataFetcherExceptionHandler { _, _, _, _ -> throw fieldFailure }

        val thrown = assertFailsWith<IllegalArgumentException> {
            GraphQL(executable, instrumentation = instrumentation, exceptionHandler = exceptionHandler)
                .execute(GraphQLRequest("{ value }"))
        }

        assertSame(fieldFailure, thrown)
        assertSame(fieldFailure, observedError)
        assertEquals(1, completionCalls)
        assertTrue(completionFailure in thrown.suppressedExceptions)
    }

    @Test
    fun `field instrumentation observes a resolver cancellation`() = runTest {
        var completedWith: Throwable? = null
        val instrumentation = object : SimpleInstrumentation() {
            override suspend fun beginField(
                parameters: FieldParameters,
            ): InstrumentationPhase<kotlinx.serialization.json.JsonElement> =
                InstrumentationPhase { _, error -> completedWith = error }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { cancelled: String }",
            runtimeWiring {
                type("Query") {
                    field("cancelled") { throw CancellationException("cancelled") }
                }
            },
        )

        assertFailsWith<CancellationException> {
            GraphQL(executable, instrumentation = instrumentation).execute(GraphQLRequest("{ cancelled }"))
        }
        assertTrue(completedWith is CancellationException)
    }

    @Test
    fun `a syntax error is reported without executing`() = runTest {
        val flags = Flags()
        val recorder = Recorder()
        val graphql = GraphQL(executable(flags), instrumentation = recorder)
        val result = graphql.execute(GraphQLRequest("{ a { b "))
        assertEquals(null, result.data)
        assertTrue(result.errors.isNotEmpty())
        assertFalse(flags.aResolved)
        assertEquals(0, recorder.count("begin:execute"))
    }

    @Test
    fun `a single chained instrumentation is used directly`() = runTest {
        val recorder = Recorder()
        val graphql = GraphQL(executable(Flags()), instrumentation = Instrumentation.of(recorder))
        graphql.execute(GraphQLRequest("{ a { b { c } } }"))
        assertEquals(1, recorder.count("begin:execute"))
    }

    @Test
    fun `the pipeline runs subscriptions and rejects over-deep ones`() = runTest {
        val executable = ExecutableSchema.fromSdl(
            "type Query { ping: String } type Subscription { ticks: Int }",
            runtimeWiring { type("Subscription") { field("ticks") { flowOf(1, 2) } } },
        )
        val recorder = Recorder()
        val ok = GraphQL(executable, instrumentation = recorder)
            .executeSubscription(GraphQLRequest("subscription { ticks }"))
            .toList()
        assertEquals(listOf(1, 2), ok.map { (it.data as JsonObject)["ticks"]!!.jsonPrimitive.content.toInt() })
        assertEquals(1, recorder.count("begin:execute"))
        assertEquals(1, recorder.count("end:execute"))

        val limited = GraphQL(executable, instrumentation = MaxQueryDepthInstrumentation(0))
        val rejected = limited.executeSubscription(GraphQLRequest("subscription { ticks }")).toList()
        assertTrue(rejected.single().errors.single().message.contains("depth"))
    }

    @Test
    fun `subscription execution instrumentation preserves stream failures when completion fails`() = runTest {
        val failure = IllegalStateException("stream failed")
        val completionFailure = IllegalArgumentException("completion failed")
        var completionCalls = 0
        var completedError: Throwable? = null
        val instrumentation = object : SimpleInstrumentation() {
            override suspend fun beginExecution(parameters: ExecutionParameters): InstrumentationPhase<ExecutionResult> =
                InstrumentationPhase { _, error ->
                    completionCalls++
                    completedError = error
                    throw completionFailure
                }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { ping: String } type Subscription { ticks: Int }",
            runtimeWiring {
                type("Subscription") {
                    field("ticks") {
                        flow {
                            emit(1)
                            throw failure
                        }
                    }
                }
            },
        )

        val stream = GraphQL(executable, instrumentation = instrumentation)
            .executeSubscription(GraphQLRequest("subscription { ticks }"))
        val thrown = assertFailsWith<IllegalStateException> { stream.toList() }

        assertTrue(thrown === failure)
        assertTrue(completedError === failure)
        assertEquals(1, completionCalls)
        assertTrue(completionFailure in thrown.suppressedExceptions)
    }

    @Test
    fun `request scoped introspection can be disabled including through fragments`() = runTest {
        val graphql = GraphQL(executable(Flags()))
        val queries = listOf(
            "{ __schema { queryType { name } } }",
            "query Meta { ...RootMeta } fragment RootMeta on Query { alias: __type(name: \"Query\") { name } }",
        )

        queries.forEach { query ->
            val result = graphql.execute(GraphQLRequest(query, introspectionEnabled = false))
            assertEquals(null, result.data)
            assertEquals("Introspection is disabled", result.errors.single().message)
        }

        val ordinary = graphql.execute(GraphQLRequest("{ a { b { c } } }", introspectionEnabled = false))
        assertTrue(ordinary.errors.isEmpty(), ordinary.errors.toString())
    }

    @Test
    fun `query limits accept plain Boolean request variables`() = runTest {
        val graphql = GraphQL(executable(Flags()), instrumentation = MaxQueryComplexityInstrumentation(10))
        val result = graphql.execute(
            GraphQLRequest(
                "query Conditional(\$include: Boolean!) { a @include(if: \$include) { b { c } } }",
                variables = mapOf("include" to false),
            ),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(JsonObject(emptyMap()), result.data)
    }
}
