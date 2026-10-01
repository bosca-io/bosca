package bosca.graphql.server

import bosca.graphql.parser.Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * request-scoped DataLoader batching. Drives the engine under `runTest` (single-threaded virtual time)
 * so the batching is deterministic — a query that would N+1 must issue exactly one batched load.
 */
class DataLoaderTest {

    private val sdl = """
        type Query {
          users: [User!]!
          user(id: ID!): User
          friendsOf(id: ID!): [User!]!
          boomList: [Item!]!
        }
        type User { id: ID! name: String! bestFriend: User }
        type Item { id: ID! related: User boom: String! }
    """.trimIndent()

    private fun user(id: String, friendId: String? = "${id.toInt() + 1}"): Map<String, Any?> =
        buildMap { put("id", id); put("name", "U$id"); put("friendId", friendId) }

    // ids 1..20 each befriend id+1; 21 is absent (so bestFriend of 20 loads a missing key → null).
    private val store: Map<String, Map<String, Any?>> = (1..20).associate { it.toString() to user(it.toString()) }

    private fun bestFriendId(ctx: ResolverContext) = (ctx.source as Map<*, *>)["friendId"] as String?

    /** A registry + the list of key-batches it actually dispatched, so a test can assert "exactly one batch". */
    private fun registry(): Pair<DataLoaderRegistry, MutableList<List<String>>> {
        val batches = mutableListOf<List<String>>()
        val registry = dataLoaderRegistry {
            loader<String, Map<String, Any?>>("user") { ids ->
                batches += ids
                ids.map { store[it] }
            }
        }
        return registry to batches
    }

    private fun executor() = GraphQLExecutor(
        ExecutableSchema.fromSdl(
            sdl,
            runtimeWiring {
                type("Query") {
                    field("users") { listOf(user("1"), user("2"), user("3")) }
                    field("user") { ctx -> ctx.dataLoader<String, Map<String, Any?>>("user").load(ctx.arg<String>("id")!!) }
                    field("friendsOf") { ctx ->
                        ctx.dataLoader<String, Map<String, Any?>>("user").loadMany(listOf("5", "6", "5")) // 5 repeated → deduped
                    }
                    field("boomList") { listOf(mapOf("id" to "1", "friendId" to "2"), mapOf("id" to "2", "friendId" to "3")) }
                }
                type("User") {
                    field("bestFriend") { ctx ->
                        bestFriendId(ctx)?.let { ctx.dataLoader<String, Map<String, Any?>>("user").load(it) }
                    }
                }
                type("Item") {
                    field("related") { ctx -> ctx.dataLoader<String, Map<String, Any?>>("user").load((ctx.source as Map<*, *>)["friendId"] as String) }
                    field("boom") { throw RuntimeException("kaboom") }
                }
            },
        ),
    )

    private fun query(q: String) = Parser.parse(q)

    @Test
    fun `an N+1 query issues a single batched load`() = runTest {
        val (registry, batches) = registry()
        val result = executor().execute(query("{ users { id bestFriend { name } } }"), dataLoaders = registry)
        assertTrue(result.errors.isEmpty(), "${result.errors}")

        // The three bestFriend loads collapse into ONE batch.
        assertEquals(1, batches.size)
        assertEquals(listOf("2", "3", "4"), batches.single())

        val users = (result.data as JsonObject)["users"]!!.jsonArray
        assertEquals("U2", users[0].jsonObject["bestFriend"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("U4", users[2].jsonObject["bestFriend"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested loads dispatch one batch per level, and the cache spans levels`() = runTest {
        val (registry, batches) = registry()
        val result = executor().execute(query("{ users { bestFriend { bestFriend { name } } } }"), dataLoaders = registry)
        assertTrue(result.errors.isEmpty(), "${result.errors}")

        // Level 1 loads friends of {1,2,3} = {2,3,4}. Level 2 wants friends of {2,3,4} = {3,4,5}, but 3 and 4 are
        // already cached from level 1 — so the second batch is just the one new key, {5}.
        assertEquals(listOf(listOf("2", "3", "4"), listOf("5")), batches)
    }

    @Test
    fun `the per-request cache loads each key at most once`() = runTest {
        val (registry, batches) = registry()
        val result = executor().execute(query("""{ a: user(id: "4") { name } b: user(id: "4") { name } }"""), dataLoaders = registry)
        assertTrue(result.errors.isEmpty(), "${result.errors}")

        assertEquals(1, batches.size)
        assertEquals(listOf("4"), batches.single()) // "4" requested twice, loaded once
        val data = result.data as JsonObject
        assertEquals("U4", data["a"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("U4", data["b"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `loadMany batches and dedups`() = runTest {
        val (registry, batches) = registry()
        val result = executor().execute(query("""{ friendsOf(id: "1") { name } }"""), dataLoaders = registry)
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        assertEquals(1, batches.size)
        assertEquals(listOf("5", "6"), batches.single()) // "5" repeated → deduped
    }

    @Test
    fun `a reused registry template does not leak cached values across requests`() = runTest {
        val (registry, batches) = registry()
        executor().execute(query("""{ user(id: "4") { name } }"""), dataLoaders = registry)
        executor().execute(query("""{ user(id: "4") { name } }"""), dataLoaders = registry)

        assertEquals(listOf(listOf("4"), listOf("4")), batches)
    }

    @Test
    fun `concurrent requests sharing a registry template keep generated loader state isolated`() = runTest {
        val template = DataLoaderRegistry()
        val executable = ExecutableSchema.fromSdl(
            "type Query { value(id: ID!): String! }",
            runtimeWiring {
                type("Query") {
                    field("value") { context ->
                        val requestLabel = context.context.getAs<String>("request")!!
                        context.dataLoaderRegistry.getOrPutLoader<String, String>("generated") { keys, _ ->
                            keys.map { "$requestLabel-$it" }
                        }.load(context.arg<String>("id")!!)
                    }
                }
            },
        )
        val document = query("""{ value(id: "same-key") }""")

        val results = List(100) { index ->
            async(Dispatchers.Default) {
                GraphQLExecutor(executable).execute(
                    document,
                    context = GraphQLContext(mapOf("request" to "request-$index")),
                    dataLoaders = template,
                )
            }
        }.awaitAll()

        results.forEachIndexed { index, result ->
            assertEquals(
                "request-$index-same-key",
                (result.data as JsonObject)["value"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `default execution registries do not leak lazily installed loaders across requests`() = runTest {
        var batches = 0
        val executable = ExecutableSchema.fromSdl(
            "type Query { label(id: ID!): String! }",
            runtimeWiring {
                type("Query") {
                    field("label") { context ->
                        val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>("generated") { keys, _ ->
                            batches++
                            keys.map { "label-$it" }
                        }
                        loader.load(context.arg<String>("id")!!)
                    }
                }
            },
        )
        val executor = GraphQLExecutor(executable)

        repeat(2) {
            val result = executor.execute(query("""{ label(id: "1") }"""))
            assertEquals("label-1", (result.data as JsonObject)["label"]!!.jsonPrimitive.content)
        }
        assertEquals(2, batches)
    }

    @Test
    fun `subscription events receive independent loader caches`() = runTest {
        var currentValue = ""
        var batches = 0
        val registry = dataLoaderRegistry {
            loader<String, String>("label") { keys ->
                batches++
                keys.map { currentValue }
            }
        }
        val executable = ExecutableSchema.fromSdl(
            """
            type Query { ping: String }
            type Subscription { updates: Item! }
            type Item { id: ID! label: String! }
            """.trimIndent(),
            runtimeWiring {
                type("Subscription") {
                    field("updates") {
                        flow {
                            currentValue = "first"
                            emit(mapOf("id" to "same"))
                            currentValue = "second"
                            emit(mapOf("id" to "same"))
                        }
                    }
                }
                type("Item") {
                    field("label") { context ->
                        context.dataLoader<String, String>("label")
                            .load((context.source as Map<*, *>)["id"] as String)
                    }
                }
            },
        )

        val results = GraphQLExecutor(executable)
            .executeSubscription(query("subscription { updates { label } }"), dataLoaders = registry)
            .toList()

        assertEquals(
            listOf("first", "second"),
            results.map { (it.data as JsonObject)["updates"]!!.jsonObject["label"]!!.jsonPrimitive.content },
        )
        assertEquals(2, batches)
    }

    @Test
    fun `a subscription root resolver may load its source stream through DataLoader`() = runTest {
        val batches = mutableListOf<List<String>>()
        val registry = dataLoaderRegistry {
            loader<String, String>("subscription-source") { keys ->
                batches += keys
                keys.map { "loaded-$it" }
            }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { ping: String } type Subscription { update: String! }",
            runtimeWiring {
                type("Subscription") {
                    field("update") { context ->
                        val source = context.dataLoader<String, String>("subscription-source").load("one")
                        flow { emit(source) }
                    }
                }
            },
        )

        val results = withTimeout(1_000) {
            GraphQLExecutor(executable)
                .executeSubscription(query("subscription { update }"), dataLoaders = registry)
                .toList()
        }

        assertEquals(listOf(listOf("one")), batches)
        assertEquals("loaded-one", (results.single().data as JsonObject)["update"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing key resolves to null`() = runTest {
        val (registry, batches) = registry()
        // bestFriend of user 20 loads "21", which is absent from the store.
        val result = executor().execute(query("""{ user(id: "20") { bestFriend { name } } }"""), dataLoaders = registry)
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        assertEquals(listOf(listOf("20"), listOf("21")), batches)
        assertEquals(JsonNull, (result.data as JsonObject)["user"]!!.jsonObject["bestFriend"])
    }

    @Test
    fun `a batch function failure surfaces as a field error`() = runTest {
        val registry = dataLoaderRegistry {
            loader<String, Map<String, Any?>>("user") { error("db down") }
        }
        val result = executor().execute(query("""{ user(id: "4") { name } }"""), dataLoaders = registry)
        assertEquals(JsonNull, (result.data as JsonObject)["user"])
        assertEquals("Internal server error", result.errors.single().message)
    }

    @Test
    fun `batch cancellation is propagated after queued waiters are released`() = runTest {
        val registry = dataLoaderRegistry {
            loader<String, String>("cancel") { throw CancellationException("cancel batch") }
        }
        registry.gate.launched(1)
        try {
            assertFailsWith<CancellationException> {
                registry.loader<String, String>("cancel").load("one")
            }
        } finally {
            registry.gate.completed()
        }
    }

    @Test
    fun `a non-Exception batch failure releases every queued waiter`() = runTest {
        val failure = AssertionError("batch assertion failed")
        val registry = dataLoaderRegistry {
            loader<String, String>("failing") { throw failure }
        }
        registry.gate.launched(2)
        try {
            val observed = withTimeout(1_000) {
                supervisorScope {
                    val loader = registry.loader<String, String>("failing")
                    val waiters = listOf(
                        async { loader.load("one") },
                        async { loader.load("two") },
                    )
                    waiters.map { waiter ->
                        assertFailsWith<AssertionError> { waiter.await() }
                    }
                }
            }

            assertEquals(listOf(failure.message, failure.message), observed.map { it.message })
        } finally {
            registry.gate.completed()
            registry.gate.completed()
        }
    }

    @Test
    fun `a batch result with the wrong cardinality fails every queued key`() = runTest {
        val registry = dataLoaderRegistry {
            loader<String, String>("short") { listOf("only-one") }
        }
        registry.gate.launched(1)
        try {
            val error = assertFailsWith<IllegalStateException> {
                registry.loader<String, String>("short").loadMany(listOf("one", "two"))
            }
            assertEquals("DataLoader batch returned 1 values for 2 keys", error.message)
        } finally {
            registry.gate.completed()
        }
    }

    @Test
    fun `a batch may await another loader without wedging dispatch`() = runTest {
        lateinit var registry: DataLoaderRegistry
        val batches = mutableListOf<String>()
        registry = dataLoaderRegistry {
            loader<String, String>("inner") { keys ->
                batches += "inner"
                keys.map { "inner-$it" }
            }
            loader<String, String>("outer") { keys ->
                batches += "outer"
                keys.map { key -> registry.loader<String, String>("inner").load(key) }
            }
        }

        registry.gate.launched(1)
        try {
            val value = withTimeout(1_000) {
                registry.loader<String, String>("outer").load("one")
            }
            assertEquals("inner-one", value)
            assertEquals(listOf("outer", "inner"), batches)
        } finally {
            registry.gate.completed()
        }
    }

    @Test
    fun `null-bubbling through a pending load does not wedge the batch gate`() = runTest {
        val (registry, _) = registry()
        // Each Item has a nullable `related` (loads) and a non-null `boom` (throws). boom bubbles → Item nulls →
        // [Item!]! nulls → data is null. The concurrent `related` load coroutines are cancelled cleanly; if the gate
        // wedged, runTest would never complete.
        val result = executor().execute(query("{ boomList { related { name } boom } }"), dataLoaders = registry)
        assertEquals(JsonNull, result.data)
        assertTrue(result.errors.any { it.message == "Internal server error" })
    }

    @Test
    fun `it still batches into one load under a real multi-threaded dispatcher`() = runTest {
        // The virtual-time tests prove determinism; this proves the gate's mutex + up-front wave registration hold
        // under genuine thread interleaving — the N+1 must still collapse to a single batch, never split or wedge.
        repeat(25) { iteration ->
            val (registry, batches) = registry()
            val n = 12
            val people = (1..n).map { user(it.toString()) }
            val executor = GraphQLExecutor(
                ExecutableSchema.fromSdl(
                    sdl,
                    runtimeWiring {
                        type("Query") { field("users") { delay(1); people } }
                        type("User") {
                            field("bestFriend") { ctx ->
                                delay(1)
                                bestFriendId(ctx)?.let { ctx.dataLoader<String, Map<String, Any?>>("user").load(it) }
                            }
                        }
                    },
                ),
            )
            val result = withContext(Dispatchers.Default) {
                executor.execute(query("{ users { bestFriend { name } } }"), dataLoaders = registry)
            }
            assertTrue(result.errors.isEmpty(), "iteration $iteration: ${result.errors}")
            assertEquals(1, batches.size, "iteration $iteration split into ${batches.size} batches: $batches")
            assertEquals(n, batches.single().size)
        }
    }

    @Test
    fun `nested child waves cannot prevent loader dispatch while waiting for field permits`() = runTest {
        val branchCount = 128
        val valuesPerBranch = 4
        val ready = CompletableDeferred<Unit>()
        val readyMutex = Mutex()
        var readyCount = 0
        val registry = dataLoaderRegistry {
            loader<String, String>("value") { keys -> keys.map { "loaded-$it" } }
        }
        val executable = ExecutableSchema.fromSdl(
            """
            type Query { branch(id: ID!): Branch! }
            type Branch { value(key: ID!): String! }
            """.trimIndent(),
            runtimeWiring {
                type("Query") {
                    field("branch") { context ->
                        readyMutex.withLock {
                            readyCount++
                            if (readyCount == branchCount) ready.complete(Unit)
                        }
                        ready.await()
                        context.arg<String>("id")!!
                    }
                }
                type("Branch") {
                    field("value") { context ->
                        val branch = context.source as String
                        val key = context.arg<String>("key")!!
                        context.dataLoader<String, String>("value").load("$branch-$key")
                    }
                }
            },
        )
        val document = query(
            buildString {
                append("{\n")
                repeat(branchCount) { branch ->
                    append("b$branch: branch(id: \"$branch\") {\n")
                    repeat(valuesPerBranch) { value ->
                        append("v$value: value(key: \"$value\")\n")
                    }
                    append("}\n")
                }
                append("}")
            },
        )

        val result = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                GraphQLExecutor(
                    executable,
                    limits = GraphQLExecutionLimits(maxConcurrentFields = branchCount),
                ).execute(document, dataLoaders = registry)
            }
        }

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(branchCount, (result.data as JsonObject).size)
    }

    @Test
    fun `waiters resumed by one batch do not split the next batch into single keys`() = runTest {
        // 96 items with 32 list-item workers resolve in three rounds. Each batch completes 32 waiters at once; they
        // resume one at a time, and the next batch must wait for all of them rather than flush each resumed key.
        val batches = mutableListOf<List<String>>()
        val items = List(96) { mapOf("id" to it.toString()) }
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                """
                type Query { items: [Item!]! }
                type Item { label: String! }
                """.trimIndent(),
                runtimeWiring {
                    type("Query") { field("items") { items } }
                    type("Item") {
                        field("label") { context ->
                            context.dataLoaderRegistry.getOrPutLoader<String, String>("label") { keys, _ ->
                                batches += keys
                                keys.map { "label-$it" }
                            }.load((context.source as Map<*, *>)["id"] as String)
                        }
                    }
                },
            ),
        )

        val result = executor.execute(query("{ items { label } }"), dataLoaders = DataLoaderRegistry())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        // Counting resumed waiters or their parents as parked until they ran produced a first batch of 32 followed by
        // one batch per remaining key (65 batches), then [32, 30, 30, 4] once only waiters were fixed.
        assertEquals(listOf(32, 32, 32), batches.map { it.size })
    }

    @Test
    fun `the no-op gate ignores every signal`() = runTest {
        // The gate the executor uses when a request defines no loaders: every signal is a pure no-op.
        val gate = BatchGate.NONE
        gate.launched(3)
        gate.parkOnChildren()
        gate.parkOnLoad()
        gate.unparkOnLoad()
        gate.unparkOnChildren()
        gate.completed()
    }

    @Test
    fun `a mapped loader resolves present keys and nulls absent ones`() = runTest {
        val batches = mutableListOf<Set<String>>()
        val registry = dataLoaderRegistry {
            mappedLoader<String, String>("name") { ids ->
                batches += ids
                ids.filter { it != "6" }.associateWith { "U$it" } // 6 is absent from the map
            }
        }
        val executor = GraphQLExecutor(
            ExecutableSchema.fromSdl(
                "type Query { label(id: ID!): String }",
                runtimeWiring {
                    type("Query") { field("label") { ctx -> ctx.dataLoader<String, String>("name").load(ctx.arg<String>("id")!!) } }
                },
            ),
        )
        val result = executor.execute(query("""{ a: label(id: "5") b: label(id: "6") }"""), dataLoaders = registry)
        val data = result.data as JsonObject
        assertEquals("U5", data["a"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, data["b"])
        assertEquals(listOf(setOf("5", "6")), batches)
    }

    @Test
    fun `registry dispatch execution surrounds a lazy batch`() = runTest {
        var dispatchCount = 0
        var dispatchActive = false
        var batchObservedDispatch = false
        val registry = DataLoaderRegistry { dispatch ->
            dispatchCount += 1
            dispatchActive = true
            try {
                dispatch()
            } finally {
                dispatchActive = false
            }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { label(id: ID!): String! }",
            runtimeWiring {
                type("Query") {
                    field("label") { context ->
                        val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>("wrapped-label") { keys, _ ->
                            batchObservedDispatch = dispatchActive
                            keys.map { "label-$it" }
                        }
                        loader.load(context.arg<String>("id")!!)
                    }
                }
            },
        )

        val result = GraphQLExecutor(executable).execute(
            query("""{ a: label(id: "1") b: label(id: "2") }"""),
            dataLoaders = registry,
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(batchObservedDispatch)
        assertTrue(dispatchCount > 0)
        assertFalse(dispatchActive)
    }

    @Test
    fun `sequential resolver waves serialize request dispatch contexts`() = runTest {
        val stateMutex = Mutex()
        var activeDispatches = 0
        var maxActiveDispatches = 0
        val batches = mutableListOf<List<String>>()
        val registry = DataLoaderRegistry { dispatch ->
            stateMutex.withLock {
                activeDispatches++
                maxActiveDispatches = maxOf(maxActiveDispatches, activeDispatches)
            }
            try {
                dispatch()
                delay(20)
            } finally {
                stateMutex.withLock { activeDispatches-- }
            }
        }
        val executable = ExecutableSchema.fromSdl(
            "type Query { chain: String! }",
            runtimeWiring {
                type("Query") {
                    field("chain") { context ->
                        val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>("sequential") { keys, _ ->
                            batches += keys
                            keys.map { "loaded-$it" }
                        }
                        "${loader.load("one")},${loader.load("two")}"
                    }
                }
            },
        )

        val result = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                GraphQLExecutor(executable).execute(query("{ chain }"), dataLoaders = registry)
            }
        }

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("loaded-one,loaded-two", (result.data as JsonObject)["chain"]!!.jsonPrimitive.content)
        assertEquals(listOf(listOf("one"), listOf("two")), batches)
        assertEquals(1, maxActiveDispatches)
    }

    @Test
    fun `generated resolvers lazily install one loader and preserve per-key contexts`() = runTest {
        val registry = DataLoaderRegistry()
        val batches = mutableListOf<Pair<List<String>, List<Any?>>>()
        val executable = ExecutableSchema.fromSdl(
            "type Query { label(id: ID!, tag: String!): String! }",
            runtimeWiring {
                type("Query") {
                    field("label") { context ->
                        val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>("generated-label") { keys, keyContexts ->
                            batches += keys to keyContexts
                            keys.map { "label-$it" }
                        }
                        loader.load(context.arg<String>("id")!!, context.arg<String>("tag"))
                    }
                }
            },
        )

        val result = GraphQLExecutor(executable).execute(
            query("""{ a: label(id: "1", tag: "first") b: label(id: "2", tag: "second") }"""),
            dataLoaders = registry,
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, batches.size)
        assertEquals(listOf("1", "2"), batches.single().first)
        assertEquals(listOf<Any?>("first", "second"), batches.single().second)
        val data = result.data as JsonObject
        assertEquals("label-1", data["a"]!!.jsonPrimitive.content)
        assertEquals("label-2", data["b"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generated resolver loaders separate equal source keys by field arguments`() = runTest {
        val registry = DataLoaderRegistry()
        val batches = mutableListOf<Pair<String, List<String>>>()
        val executable = ExecutableSchema.fromSdl(
            """
            type Query { items: [Item!]! }
            type Item { id: ID! label(locale: String!): String! }
            """.trimIndent(),
            runtimeWiring {
                type("Query") {
                    field("items") { listOf(mapOf("id" to "1"), mapOf("id" to "2")) }
                }
                type("Item") {
                    field("label") { context ->
                        val locale = context.arg<String>("locale")!!
                        val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>(
                            "generated-localized-label",
                            context.arguments,
                        ) { keys, _ ->
                            batches += locale to keys
                            keys.map { "$it-$locale" }
                        }
                        loader.load((context.source as Map<*, *>)["id"] as String, locale)
                    }
                }
            },
        )

        val result = GraphQLExecutor(executable).execute(
            query("""{ items { en: label(locale: "en") enAgain: label(locale: "en") fr: label(locale: "fr") } }"""),
            dataLoaders = registry,
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val items = (result.data as JsonObject)["items"]!!.jsonArray
        assertEquals(
            listOf("1-en", "2-en"),
            items.map { it.jsonObject["en"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("1-en", "2-en"),
            items.map { it.jsonObject["enAgain"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("1-fr", "2-fr"),
            items.map { it.jsonObject["fr"]!!.jsonPrimitive.content },
        )
        assertEquals(
            setOf("en" to listOf("1", "2"), "fr" to listOf("1", "2")),
            batches.toSet(),
        )
        assertEquals(2, batches.size)
    }

    @Test
    fun `contextual loader identities snapshot collection-shaped discriminators`() = runTest {
        val registry = DataLoaderRegistry()
        val mutable = mutableListOf("one")
        val original = registry.getOrPutLoader<String, String>("list-discriminator", mutable) { keys, _ -> keys }
        mutable += "two"

        val sameSnapshot = registry.getOrPutLoader<String, String>("list-discriminator", listOf("one")) { _, _ ->
            error("must reuse the immutable discriminator snapshot")
        }
        registry.getOrPutLoader<String, String>("set-discriminator", setOf("one")) { keys, _ -> keys }
        registry.getOrPutLoader<String, String>("array-discriminator", arrayOf("one")) { keys, _ -> keys }

        assertSame(original, sameSnapshot)
    }
}
