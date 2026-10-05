package bosca.graphql.server

import bosca.graphql.parser.Parser
import bosca.graphql.parser.ParserLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecurityLimitsTest {

    private fun structuralError(query: String, limits: GraphQLRequestLimits): String? =
        Parser.parse(query).limitError(limits)?.message

    @Test
    fun `request limits reject every oversized document structure`() {
        assertEquals(
            "Document exceeds the maximum definition count of 1",
            structuralError("{ a } fragment F on Query { a }", GraphQLRequestLimits(maxDefinitions = 1)),
        )
        assertEquals(
            "Selection set exceeds the maximum width of 1",
            structuralError("{ a b }", GraphQLRequestLimits(maxSelectionSetWidth = 1)),
        )
        assertEquals(
            "Document exceeds the maximum selection count of 1",
            structuralError("{ a { b } }", GraphQLRequestLimits(maxSelections = 1)),
        )
        assertEquals(
            "Document exceeds the maximum selection depth of 1",
            structuralError("{ ... { a { b } } }", GraphQLRequestLimits(maxSelectionDepth = 1)),
        )
        assertEquals(
            "Document exceeds the maximum fragment-spread count of 1",
            structuralError(
                "{ ...F ...F } fragment F on Query { a }",
                GraphQLRequestLimits(maxFragmentSpreads = 1),
            ),
        )
        assertNull(Parser.parse("scalar Safe").limitError(GraphQLRequestLimits.DEFAULT))
    }

    @Test
    fun `default request limits accept documents well beyond the legacy parser ceiling`() {
        val source = List(60_000) { index -> "field$index" }.joinToString(" ", prefix = "{ ", postfix = " }")
        val limits = GraphQLRequestLimits.DEFAULT
        val document = Parser.parse(source, limits.parserLimits)

        assertTrue(source.length < limits.maxQueryCharacters)
        assertNull(document.limitError(limits))
    }

    @Test
    fun `request limit values must be positive`() {
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxQueryCharacters = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxDefinitions = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxSelections = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxSelectionSetWidth = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxSelectionDepth = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLRequestLimits(maxFragmentSpreads = 0) }
    }

    @Test
    fun `pipeline rejects source parser and structural limits before resolution`() = runTest {
        var resolved = false
        val executable = ExecutableSchema.fromSdl(
            "type Query { a: Int b: Int }",
            runtimeWiring {
                type("Query") {
                    field("a") { resolved = true; 1 }
                    field("b") { resolved = true; 2 }
                }
            },
        )

        val sourceLimited = GraphQL(executable, requestLimits = GraphQLRequestLimits(maxQueryCharacters = 4))
            .execute(GraphQLRequest("{ a }"))
        assertEquals("Query exceeds the maximum length of 4 characters", sourceLimited.errors.single().message)

        val parserLimited = GraphQL(
            executable,
            requestLimits = GraphQLRequestLimits(parserLimits = ParserLimits(maxTokens = 2, maxNestingDepth = 10)),
        ).execute(GraphQLRequest("{ a }"))
        assertTrue(parserLimited.errors.single().message.contains("maximum token count of 2"))

        val structurallyLimited = GraphQL(
            executable,
            requestLimits = GraphQLRequestLimits(maxSelections = 1),
        ).execute(GraphQLRequest("{ a b }"))
        assertEquals("Document exceeds the maximum selection count of 1", structurallyLimited.errors.single().message)
        assertTrue(!resolved)
    }

    @Test
    fun `preparsed cache is bounded and refreshes recency on hits`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxEntries = 2)
        val document = Parser.parse("{ a }")
        var parses = 0
        suspend fun load(query: String): PreparsedDocument = provider.document(query) {
            parses++
            PreparsedDocument.of(document)
        }

        load("one")
        load("two")
        load("one")
        load("three")
        load("two")

        assertEquals(4, parses)
        assertEquals(2, provider.size)
    }

    @Test
    fun `preparsed cache entries can be invalidated explicitly`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        var parses = 0
        suspend fun load() = provider.document("query") {
            parses++
            PreparsedDocument.of(Parser.parse("{ a }"))
        }

        load()
        provider.invalidate("query")
        provider.invalidate("missing")
        load()

        assertEquals(2, parses)
        assertEquals(1, provider.size)
    }

    @Test
    fun `preparsed cache retains validation failures and recovers after parse exceptions`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        var failedParses = 0
        repeat(2) {
            val result = provider.document("invalid") {
                failedParses++
                PreparsedDocument.ofErrors(listOf(GraphQLError("invalid")))
            }
            assertEquals("invalid", result.errors.single().message)
        }
        assertEquals(1, failedParses)
        assertEquals(1, provider.size)

        assertFailsWith<IllegalStateException> {
            provider.document("throws") { error("parse implementation failed") }
        }
        val recovered = provider.document("throws") { PreparsedDocument.of(Parser.parse("{ a }")) }
        assertTrue(recovered.errors.isEmpty())
        assertEquals(2, provider.size)
    }

    @Test
    fun `concurrent preparsed cache misses share one parse`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        val release = CompletableDeferred<Unit>()
        var parses = 0
        val requests = List(8) {
            async {
                provider.document("same") {
                    parses++
                    release.await()
                    PreparsedDocument.of(Parser.parse("{ a }"))
                }
            }
        }
        yield()
        assertEquals(1, parses)
        release.complete(Unit)
        val results = requests.awaitAll()
        assertTrue(results.all { it === results.first() })
        assertEquals(1, provider.size)
    }

    @Test
    fun `concurrent preparsed cache hits reuse the published snapshot`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        val expected = provider.document("hot") {
            PreparsedDocument.of(Parser.parse("{ a }"))
        }

        List(64) {
            async(Dispatchers.Default) {
                repeat(100) {
                    val actual = provider.document("hot") {
                        error("a published cache hit must not parse again")
                    }
                    assertSame(expected, actual)
                }
            }
        }.awaitAll()

        assertEquals(1, provider.size)
    }

    @Test
    fun `concurrent distinct cache misses preserve the entry bound`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxEntries = 16)

        List(128) { index ->
            async(Dispatchers.Default) {
                provider.document("query-$index") {
                    PreparsedDocument.of(Parser.parse("{ a }"))
                }
            }
        }.awaitAll()

        assertEquals(16, provider.size)
    }

    @Test
    fun `preparsed cache bounds preload and requires a positive capacity`() {
        val document = Parser.parse("{ a }")
        val provider = InMemoryPreparsedDocumentProvider(
            preload = linkedMapOf("one" to document, "two" to document, "three" to document),
            maxEntries = 2,
        )
        assertEquals(2, provider.size)
        assertFailsWith<IllegalArgumentException> { InMemoryPreparsedDocumentProvider(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { InMemoryPreparsedDocumentProvider(maxCachedQueryBytes = 0) }
        assertFailsWith<IllegalArgumentException> { InMemoryPreparsedDocumentProvider(maxConcurrentParses = 0) }
    }

    @Test
    fun `preparsed cache is bounded by query source weight`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxEntries = 10, maxCachedQueryBytes = 6)
        val document = PreparsedDocument.of(Parser.parse("{ a }"))
        var parses = 0
        suspend fun load(query: String) = provider.document(query) {
            parses++
            document
        }

        load("1234")
        load("5678")
        load("1234")
        load("oversized")
        load("oversized")

        assertEquals(5, parses)
        assertEquals(1, provider.size)
    }

    @Test
    fun `preparsed cache bounds concurrent distinct parses`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxConcurrentParses = 2)
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val requests = List(6) { index ->
            async {
                provider.document("query-$index") {
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        release.await()
                        PreparsedDocument.of(Parser.parse("{ a }"))
                    } finally {
                        active--
                    }
                }
            }
        }

        yield()
        assertEquals(2, active)
        release.complete(Unit)
        requests.awaitAll()
        assertEquals(2, maxActive)
    }

    @Test
    fun `a cache miss waiting for parse admission reuses the document completed ahead of it`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxConcurrentParses = 1)
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker = async {
            provider.document("blocker") {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
                PreparsedDocument.of(Parser.parse("{ a }"))
            }
        }
        blockerEntered.await()

        var parses = 0
        val requests = List(2) {
            async {
                provider.document("same") {
                    parses++
                    PreparsedDocument.of(Parser.parse("{ a }"))
                }
            }
        }
        yield()
        releaseBlocker.complete(Unit)
        blocker.await()
        val results = requests.awaitAll()

        assertEquals(1, parses)
        assertTrue(results.all { it === results.first() })
    }

    @Test
    fun `a cache miss waiting for parse admission joins an owner still in flight`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider(maxConcurrentParses = 2)
        val blockerEntered = List(2) { CompletableDeferred<Unit>() }
        val releaseBlocker = List(2) { CompletableDeferred<Unit>() }
        val blockers = List(2) { index ->
            async {
                provider.document("blocker-$index") {
                    blockerEntered[index].complete(Unit)
                    releaseBlocker[index].await()
                    PreparsedDocument.of(Parser.parse("{ a }"))
                }
            }
        }
        blockerEntered.forEach { it.await() }

        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        var parses = 0
        val requests = List(2) {
            async {
                provider.document("same") {
                    parses++
                    ownerEntered.complete(Unit)
                    releaseOwner.await()
                    PreparsedDocument.of(Parser.parse("{ a }"))
                }
            }
        }
        yield()

        releaseBlocker[0].complete(Unit)
        blockers[0].await()
        ownerEntered.await()
        releaseBlocker[1].complete(Unit)
        blockers[1].await()
        yield()
        releaseOwner.complete(Unit)
        val results = requests.awaitAll()

        assertEquals(1, parses)
        assertTrue(results.all { it === results.first() })
    }

    @Test
    fun `cancelled preparsed owner never leaves a stale in-flight parse`() = runTest {
        val provider = InMemoryPreparsedDocumentProvider()
        val entered = CompletableDeferred<Unit>()
        val owner = async {
            provider.document("cancelled") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        owner.cancelAndJoin()
        val recovered = provider.document("cancelled") {
            PreparsedDocument.of(Parser.parse("{ a }"))
        }

        assertTrue(recovered.errors.isEmpty())
        assertEquals(1, provider.size)
    }

    @Test
    fun `execution limit values must be positive`() {
        assertFailsWith<IllegalArgumentException> { GraphQLExecutionLimits(maxListItems = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLExecutionLimits(maxConcurrentFields = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLExecutionLimits(maxConcurrentListItems = 0) }
        assertFailsWith<IllegalArgumentException> { GraphQLExecutionLimits(maxResponseNodes = 0) }
    }

    @Test
    fun `execution aborts oversized lists and response trees`() = runTest {
        val executable = ExecutableSchema.fromSdl(
            "type Query { values: [Int!]! a: Int b: Int }",
            runtimeWiring {
                type("Query") {
                    field("values") { listOf(1, 2, 3) }
                    field("a") { 1 }
                    field("b") { 2 }
                }
            },
        )

        val listResult = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxListItems = 2),
        ).execute(Parser.parse("{ values }"))
        assertEquals(JsonNull, listResult.data)
        assertEquals("List exceeds the maximum item count of 2", listResult.errors.single().message)

        val responseResult = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxResponseNodes = 1),
        ).execute(Parser.parse("{ a b }"))
        assertEquals(JsonNull, responseResult.data)
        assertEquals(1, responseResult.errors.count { it.message.contains("maximum node count of 1") })
    }

    @Test
    fun `concurrent response budget overruns report one error`() = runTest {
        val fieldNames = List(128) { "f$it" }
        val executable = ExecutableSchema.fromSdl(
            "type Query { ${fieldNames.joinToString(" ") { "$it: Int" }} }",
            runtimeWiring {
                type("Query") {
                    fieldNames.forEachIndexed { index, name -> field(name) { index } }
                }
            },
        )
        val result = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxResponseNodes = 1),
        ).execute(Parser.parse("{ ${fieldNames.joinToString(" ")} }"))

        assertEquals(JsonNull, result.data)
        assertEquals(1, result.errors.count { it.message.contains("maximum node count of 1") })
    }

    @Test
    fun `subscription events enforce runtime response limits`() = runTest {
        val executable = ExecutableSchema.fromSdl(
            "type Query { ping: String } type Subscription { values: [Int!]! }",
            runtimeWiring {
                type("Subscription") {
                    field("values") { flowOf(listOf(1, 2, 3)) }
                }
            },
        )
        val result = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxListItems = 2),
        ).executeSubscription(Parser.parse("subscription { values }")).toList().single()

        assertEquals(JsonNull, result.data)
        assertEquals("List exceeds the maximum item count of 2", result.errors.single().message)
    }

    @Test
    fun `field and list completion concurrency is bounded`() = runTest {
        var activeFields = 0
        var maxActiveFields = 0
        var activeItems = 0
        var maxActiveItems = 0
        val executable = ExecutableSchema.fromSdl(
            """
            type Query { a: Int b: Int c: Int d: Int items: [Item!]! }
            type Item { value: Int! }
            """.trimIndent(),
            runtimeWiring {
                type("Query") {
                    for (name in listOf("a", "b", "c", "d")) {
                        field(name) {
                            activeFields++
                            maxActiveFields = maxOf(maxActiveFields, activeFields)
                            try {
                                delay(10)
                                1
                            } finally {
                                activeFields--
                            }
                        }
                    }
                    field("items") { (1..5).map { mapOf("value" to it) } }
                }
                type("Item") {
                    field("value") { context ->
                        activeItems++
                        maxActiveItems = maxOf(maxActiveItems, activeItems)
                        try {
                            delay(10)
                            (context.source as Map<*, *>)["value"]
                        } finally {
                            activeItems--
                        }
                    }
                }
            },
        )
        val limits = GraphQLExecutionLimits(maxConcurrentFields = 2, maxConcurrentListItems = 2)
        val executor = GraphQLExecutor(executable, limits = limits)

        val fields = executor.execute(Parser.parse("{ a b c d }"))
        assertTrue(fields.errors.isEmpty(), fields.errors.toString())
        assertEquals(2, maxActiveFields)

        val items = executor.execute(Parser.parse("{ items { value } }"))
        assertTrue(items.errors.isEmpty(), items.errors.toString())
        assertEquals(5, ((items.data as JsonObject)["items"]!!.jsonArray).size)
        assertEquals(2, maxActiveItems)
    }

    @Test
    fun `bounded field workers remain work conserving when slow fields are uneven`() = runTest {
        val fieldNames = List(40) { "f$it" }
        val executable = ExecutableSchema.fromSdl(
            "type Query { ${fieldNames.joinToString(" ") { "$it: Int!" }} }",
            runtimeWiring {
                type("Query") {
                    fieldNames.forEachIndexed { index, name ->
                        field(name) {
                            if (index % 8 == 0) delay(10)
                            index
                        }
                    }
                }
            },
        )
        val result = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxConcurrentFields = 8),
        ).execute(Parser.parse("{ ${fieldNames.joinToString(" ")} }"))

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(testScheduler.currentTime <= 20, "work took ${testScheduler.currentTime}ms of virtual time")
    }

    @Test
    fun `field concurrency is request-wide across recursively completed list items`() = runTest {
        var activeFields = 0
        var maxActiveFields = 0
        val executable = ExecutableSchema.fromSdl(
            """
            type Query { groups: [Group!]! }
            type Group { a: Int! b: Int! c: Int! d: Int! }
            """.trimIndent(),
            runtimeWiring {
                type("Query") {
                    field("groups") { List(4) { emptyMap<String, Any?>() } }
                }
                type("Group") {
                    for (name in listOf("a", "b", "c", "d")) {
                        field(name) {
                            activeFields++
                            maxActiveFields = maxOf(maxActiveFields, activeFields)
                            try {
                                delay(10)
                                1
                            } finally {
                                activeFields--
                            }
                        }
                    }
                }
            },
        )
        val result = GraphQLExecutor(
            executable,
            limits = GraphQLExecutionLimits(maxConcurrentFields = 2, maxConcurrentListItems = 4),
        ).execute(Parser.parse("{ groups { a b c d } }"))

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(2, maxActiveFields)
        assertEquals(4, ((result.data as JsonObject)["groups"]!!.jsonArray).size)
    }
}
