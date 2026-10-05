package bosca.graphql.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/**
 * Request-scoped batched loading — the graphql-java `DataLoader` replacement, built on coroutines
 * instead of `CompletableFuture`. Solves N+1: many `load(key)` calls across concurrently-resolving fields collapse
 * into a single [batch] call per execution level, and each key is loaded at most once per request (the cache).
 *
 * Dispatch is driven by the [BatchGate]: the executor reports its concurrent waves, and the gate flushes the queued
 * keys precisely when every live resolver coroutine is parked and at least one is waiting on a load — so a query
 * that would N+1 issues exactly one batched load, with no manual `dispatch()` calls.
 */
class DataLoader<K, V> internal constructor(
    private val gate: CountingBatchGate,
    private val batch: suspend (List<K>, List<Any?>) -> List<V?>,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<K, CompletableDeferred<V?>>()
    private val queue = ArrayDeque<Pair<K, Any?>>()

    /** Load one [key], batched with all other concurrent loads. Returns the cached value if already loaded. */
    suspend fun load(key: K, keyContext: Any? = null): V? {
        val (deferred, queuedKey) = enqueue(key, keyContext)
        return awaitParked(listOf(deferred), queuedKey).single()
    }

    /** Load several keys at once, batched with all other concurrent loads. */
    suspend fun loadMany(keys: List<K>): List<V?> {
        if (keys.isEmpty()) return emptyList()
        val plan = withContext(NonCancellable) { mutex.withLock { keys.map { reserve(it, null) } } }
        return awaitParked(plan.map { it.first }, queuedKey = plan.any { it.second })
    }

    private suspend fun enqueue(key: K, keyContext: Any?): Pair<CompletableDeferred<V?>, Boolean> =
        withContext(NonCancellable) { mutex.withLock { reserve(key, keyContext) } }

    /** Reserve a deferred for [key], queueing it for the next batch if it is new. Caller holds [mutex]. */
    private fun reserve(key: K, keyContext: Any?): Pair<CompletableDeferred<V?>, Boolean> {
        val existing = cache[key]
        if (existing != null) return existing to false
        val deferred = CompletableDeferred<V?>()
        cache[key] = deferred
        queue.addLast(key to keyContext)
        return deferred to true
    }

    /**
     * Park on the gate while awaiting the [deferreds]. A newly-queued key parks "on a load" (it makes the batch
     * eligible to dispatch); a pure cache-wait parks "on children" (it only waits for someone else's load).
     */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun awaitParked(deferreds: List<CompletableDeferred<V?>>, queuedKey: Boolean): List<V?> =
        // A parked load holds no execution permit, so queued resolvers can be admitted and queue their keys before
        // the batch flushes.
        withYieldedExecutionPermit {
            // The waiter is un-parked when its last result completes rather than when it next runs. Waiters resume
            // one at a time, and one still counted as parked after its result arrived lets the gate flush a batch
            // before the other resumed waiters have queued their keys. Whichever of completion or this frame's exit
            // comes first un-parks, exactly once; exit covers a waiter cancelled before its results complete.
            val unparked = AtomicBoolean(false)
            val unpark = {
                if (unparked.compareAndSet(false, true)) {
                    if (queuedKey) gate.unparkOnLoad() else gate.unparkOnChildren()
                }
            }
            var completionHandles = emptyList<DisposableHandle>()
            try {
                if (queuedKey) gate.parkOnLoad() else gate.parkOnChildren()
                val remaining = AtomicInt(deferreds.size)
                completionHandles = deferreds.map { deferred ->
                    deferred.invokeOnCompletion { if (remaining.decrementAndFetch() == 0) unpark() }
                }
                deferreds.awaitAll()
            } finally {
                completionHandles.forEach { it.dispose() }
                unpark()
            }
        }

    /** Flush the queued keys through [batch], fulfilling their deferreds. Invoked by the gate; never directly. */
    internal suspend fun dispatch() {
        val queued = withContext(NonCancellable) {
            mutex.withLock { val drained = queue.toList(); queue.clear(); drained }
        }
        val keys = queued.map { it.first }
        if (keys.isEmpty()) return
        val outcome = try {
            val values = batch(keys, queued.map { it.second })
            if (values.size != keys.size) {
                throw IllegalStateException(
                    "DataLoader batch returned ${values.size} values for ${keys.size} keys",
                )
            }
            Result.success(values)
        } catch (e: CancellationException) {
            completeExceptionally(keys, e)
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        withContext(NonCancellable) {
            mutex.withLock {
                outcome.fold(
                    // every drained key has a reserved deferred in the cache, so getValue is total
                    onSuccess = { values -> keys.forEachIndexed { index, key -> cache.getValue(key).complete(values[index]) } },
                    onFailure = { error -> keys.forEach { cache.getValue(it).completeExceptionally(error) } },
                )
            }
        }
    }

    private suspend fun completeExceptionally(keys: List<K>, error: Throwable) {
        withContext(NonCancellable) {
            mutex.withLock {
                keys.forEach { cache.getValue(it).completeExceptionally(error) }
            }
        }
    }
}

/**
 * The set of named [DataLoader]s available to one request. Built fresh per execution (so the per-key cache never
 * leaks across requests) via [dataLoaderRegistry], and threaded into every [ResolverContext].
 */
class DataLoaderRegistry internal constructor(
    definitions: Map<String, suspend (List<Any?>, List<Any?>) -> List<Any?>>,
    private val dispatchExecution: suspend (suspend () -> Unit) -> Unit = { dispatch -> dispatch() },
) {
    /**
     * Create a fresh, request-scoped registry whose generated loaders will be installed lazily.
     *
     * [dispatchExecution] can establish request-specific coroutine context around each batch dispatch.
     */
    constructor(
        dispatchExecution: suspend (suspend () -> Unit) -> Unit = { dispatch -> dispatch() },
    ) : this(emptyMap(), dispatchExecution)

    internal val gate = CountingBatchGate { dispatchExecution { dispatchAll() } }
    private val mutex = Mutex()
    private val definitions = definitions.toMap()
    private val declaredLoaders: Map<LoaderIdentity, DataLoader<Any?, Any?>> =
        this.definitions.mapKeys { (name, _) -> LoaderIdentity(name, null) }
            .mapValues { (_, batch) -> DataLoader<Any?, Any?>(gate, batch) }
    private val generatedLoaders = mutableMapOf<LoaderIdentity, DataLoader<Any?, Any?>>()

    /** The loader declared under [name]; throws if there is none. Generated loaders are obtained via [getOrPutLoader]. */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> loader(name: String): DataLoader<K, V> =
        (declaredLoaders[LoaderIdentity(name, null)] ?: error("No DataLoader named '$name'")) as DataLoader<K, V>

    /**
     * Return the named loader, creating it once for this request when a generated resolver first needs it.
     * [keyContexts] is positionally aligned with [keys] and carries the arguments/source for each batched field.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <K, V> getOrPutLoader(
        name: String,
        batch: suspend (keys: List<K>, keyContexts: List<Any?>) -> List<V?>,
    ): DataLoader<K, V> = getOrPutLoader(name, null, batch)

    /**
     * Return a generated resolver loader scoped by [cacheDiscriminator].
     *
     * Generated batched fields pass their coerced argument map as the discriminator. Calls with equal arguments
     * share one loader and batch, while aliases that request the same source key with different arguments remain
     * independent. The public [K] stays unchanged so controller [bosca.graphql.Batch] implementations continue to
     * receive their declared key type.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <K, V> getOrPutLoader(
        name: String,
        cacheDiscriminator: Any?,
        batch: suspend (keys: List<K>, keyContexts: List<Any?>) -> List<V?>,
    ): DataLoader<K, V> {
        val identity = LoaderIdentity(name, snapshotIdentity(cacheDiscriminator))
        declaredLoaders[identity]?.let { return it as DataLoader<K, V> }
        return mutex.withLock {
            generatedLoaders.getOrPut(identity) {
                DataLoader(gate) { keys, contexts -> batch(keys as List<K>, contexts) }
            } as DataLoader<K, V>
        }
    }

    /** Create an independent registry with the same declared loaders and dispatch context. */
    internal fun newExecutionRegistry(): DataLoaderRegistry = DataLoaderRegistry(definitions, dispatchExecution)

    internal suspend fun dispatchAll() {
        val loaders = declaredLoaders.values + mutex.withLock { generatedLoaders.values.toList() }
        coroutineScope { loaders.forEach { launch { it.dispatch() } } }
    }

    /** Snapshot collection-shaped argument values so a mutable input cannot corrupt the loader map's hash buckets. */
    private fun snapshotIdentity(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { snapshotIdentity(it.key) to snapshotIdentity(it.value) }
        is List<*> -> value.map(::snapshotIdentity)
        is Set<*> -> value.mapTo(linkedSetOf(), ::snapshotIdentity)
        is Array<*> -> value.map(::snapshotIdentity)
        else -> value
    }

    private data class LoaderIdentity(val name: String, val discriminator: Any?)

    internal companion object {
        /** Shared definition template used by default arguments; execution always clones it before use. */
        val DEFAULT = DataLoaderRegistry()
    }
}

/** Build a [DataLoaderRegistry]: `dataLoaderRegistry { loader("user") { ids -> userRepo.byIds(ids) } }`. */
fun dataLoaderRegistry(block: DataLoaderRegistryBuilder.() -> Unit): DataLoaderRegistry =
    DataLoaderRegistryBuilder().apply(block).build()

class DataLoaderRegistryBuilder internal constructor() {
    private val definitions = mutableMapOf<String, suspend (List<Any?>, List<Any?>) -> List<Any?>>()

    /** A loader whose [batch] returns one value per key, positionally aligned (missing → null). */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> loader(name: String, batch: suspend (List<K>) -> List<V?>) {
        definitions[name] = { keys, _ -> batch(keys as List<K>) }
    }

    /** A loader whose [batch] returns a map; keys absent from the map resolve to null. */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> mappedLoader(name: String, batch: suspend (Set<K>) -> Map<K, V>) {
        definitions[name] = { keys, _ ->
            val typed = keys as List<K>
            val byKey = batch(typed.toSet())
            typed.map { byKey[it] }
        }
    }

    internal fun build(): DataLoaderRegistry = DataLoaderRegistry(definitions)
}

/**
 * The dispatch trigger for [DataLoader]s. The executor calls [launched]/[completed] around each concurrent wave and
 * [parkOnChildren]/[parkOnLoad] (via the loaders) when a coroutine suspends, so the gate can detect the batch frame.
 */
internal interface BatchGate {
    suspend fun launched(count: Int)
    suspend fun completed()
    suspend fun parkOnChildren()

    /** Non-suspending so a load's completion callback can record that its waiter is runnable again. */
    fun unparkOnChildren()
    suspend fun parkOnLoad()

    /** Non-suspending so a load's completion callback can record that its waiter is runnable again. */
    fun unparkOnLoad()

    companion object {
        /** Used when a request has no loaders: every operation is a no-op, so execution is unaffected. */
        val NONE: BatchGate = object : BatchGate {
            override suspend fun launched(count: Int) = Unit
            override suspend fun completed() = Unit
            override suspend fun parkOnChildren() = Unit
            override fun unparkOnChildren() = Unit
            override suspend fun parkOnLoad() = Unit
            override fun unparkOnLoad() = Unit
        }
    }
}

/**
 * Counts live resolver coroutines against parked ones and flushes the loaders when execution settles. A flush fires
 * when every live coroutine is parked (`parkedOnChildren + parkedOnLoad == live`) and at least one is parked on a
 * load — the precise frame where no coroutine can progress without a batch. Counter mutations never suspend, so a
 * null-bubbling cancellation unwinding through the gate can never leave it wedged.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CountingBatchGate(private val dispatch: suspend () -> Unit) : BatchGate {
    private val dispatchMutex = Mutex()
    private val dispatchFrame = DispatchFrame()

    private class Counts(val live: Int, val parkedOnChildren: Int, val parkedOnLoad: Int)

    // Updated by compare-and-set rather than under a coroutine mutex, so un-parking can run inside a completion
    // callback, which cannot suspend.
    private val counts = AtomicReference(Counts(0, 0, 0))

    override suspend fun launched(count: Int) = adjust { Counts(it.live + count, it.parkedOnChildren, it.parkedOnLoad) }
    override suspend fun completed() = adjustAndMaybeFlush { Counts(it.live - 1, it.parkedOnChildren, it.parkedOnLoad) }
    override suspend fun parkOnChildren() = adjustAndMaybeFlush { Counts(it.live, it.parkedOnChildren + 1, it.parkedOnLoad) }
    override fun unparkOnChildren() = adjust { Counts(it.live, it.parkedOnChildren - 1, it.parkedOnLoad) }
    override suspend fun parkOnLoad() = adjustAndMaybeFlush { Counts(it.live, it.parkedOnChildren, it.parkedOnLoad + 1) }
    override fun unparkOnLoad() = adjust { Counts(it.live, it.parkedOnChildren, it.parkedOnLoad - 1) }

    /** Applies [change] atomically and returns the resulting counts. */
    private fun update(change: (Counts) -> Counts): Counts {
        while (true) {
            val current = counts.load()
            val next = change(current)
            if (counts.compareAndSet(current, next)) return next
        }
    }

    /** A counter change that can never trigger a flush (launching adds work; un-parking is progress resuming). */
    private fun adjust(change: (Counts) -> Counts) {
        update(change)
    }

    /** A counter change that flushes the loaders if it settles execution into a load-blocked frame. */
    private suspend fun adjustAndMaybeFlush(change: (Counts) -> Counts) {
        val next = update(change)
        val shouldFlush = next.live > 0 && next.parkedOnLoad > 0 &&
            next.parkedOnChildren + next.parkedOnLoad >= next.live
        // Dispatch is deliberately reentrant. A batch function may await another loader, and a resolver resumed by
        // one dispatch may queue the next wave before the current dispatch call has returned. Each DataLoader drains
        // its queue atomically. Independent resolver waves remain serialized so request-scoped dispatch contexts
        // (notably the single database connection) are never used concurrently.
        if (shouldFlush) {
            if (currentCoroutineContext()[DispatchFrame] != null) {
                dispatch()
            } else {
                dispatchMutex.withLock {
                    withContext(dispatchFrame) { dispatch() }
                }
            }
        }
    }

    private class DispatchFrame : AbstractCoroutineContextElement(DispatchFrame) {
        companion object Key : CoroutineContext.Key<DispatchFrame>
    }
}
