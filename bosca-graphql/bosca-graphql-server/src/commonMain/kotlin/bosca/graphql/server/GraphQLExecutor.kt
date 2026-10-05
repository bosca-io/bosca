package bosca.graphql.server

import bosca.graphql.language.BooleanValue
import bosca.graphql.language.Directive
import bosca.graphql.language.Document
import bosca.graphql.language.EnumTypeDefinition
import bosca.graphql.language.Field
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.FragmentDefinition
import bosca.graphql.language.FragmentSpread
import bosca.graphql.language.InlineFragment
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.OperationDefinition
import bosca.graphql.language.OperationType
import bosca.graphql.language.SelectionSet
import bosca.graphql.language.SourceLocation
import bosca.graphql.language.Type
import bosca.graphql.language.UnionTypeDefinition
import bosca.graphql.language.Variable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The Bosca-native GraphQL execution engine — the graphql-java `Execution`/`AsyncExecutionStrategy`
 * replacement. Runs a parsed operation against an [ExecutableSchema] and produces an [ExecutionResult]:
 *
 * - coerces variables and arguments per field;
 * - collects fields (alias-merging, fragment spreads + inline fragments, `@skip`/`@include`);
 * - resolves fields via `suspend` [FieldResolver]s — query siblings concurrently with structured concurrency
 *   (`coroutineScope`/`async`), mutation top-level fields serially;
 * - completes values (scalars/enums via [Coercing.serialize], lists, abstract-type resolution, `__typename`);
 * - bubbles nulls: a field error nulls the field, propagating up to the nearest nullable position;
 * - maps coercion + resolver exceptions to [GraphQLError]s via the [exceptionHandler].
 */
@OptIn(ExperimentalAtomicApi::class)
class GraphQLExecutor(
    private val executable: ExecutableSchema,
    private val exceptionHandler: DataFetcherExceptionHandler = DefaultDataFetcherExceptionHandler,
    private val instrumentation: Instrumentation = Instrumentation.NONE,
    private val limits: GraphQLExecutionLimits = GraphQLExecutionLimits.DEFAULT,
) {
    private val coercion = Coercion(executable)

    suspend fun execute(
        document: Document,
        operationName: String? = null,
        variables: Map<String, Any?> = emptyMap(),
        rootValue: Any? = null,
        context: GraphQLContext = GraphQLContext.EMPTY,
        dataLoaders: DataLoaderRegistry = DataLoaderRegistry.DEFAULT,
    ): ExecutionResult {
        val operations = document.definitions.filterIsInstance<OperationDefinition>()
        val operation = (if (operationName != null) operations.firstOrNull { it.name == operationName } else operations.singleOrNull())
            ?: return ExecutionResult.ofErrors(
                listOf(GraphQLError(if (operationName != null) "No operation named '$operationName'" else "Must provide an operation name when the document defines multiple operations")),
            )

        if (operation.operation == OperationType.SUBSCRIPTION) {
            return ExecutionResult.ofErrors(listOf(GraphQLError("Subscriptions cannot be run with execute(); use executeSubscription()")))
        }
        val rootType = executable.schema.rootType(operation.operation)
            ?: return ExecutionResult.ofErrors(listOf(GraphQLError("Schema has no ${operation.operation.name.lowercase()} root type")))

        val coercedVariables = try {
            coercion.coerceVariables(operation, variables)
        } catch (e: CoercionException) {
            return ExecutionResult.ofErrors(listOf(GraphQLError(e.reason, locationOf(e.location), e.path)))
        }

        val fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        val executionDataLoaders = dataLoaders.newExecutionRegistry()
        val gate = gateFor(executionDataLoaders)
        val execution = Execution(coercedVariables, context, fragments, gate, executionDataLoaders)
        gate.launched(1) // the root selection set runs in this coroutine; count it so the batch gate stays balanced
        val data = try {
            execution.executeSelectionSet(operation.selectionSet, rootType.name, rootValue, emptyList(), serial = operation.operation == OperationType.MUTATION)
        } catch (_: ExecutionAborted) {
            JsonNull
        } catch (_: NullBubble) {
            JsonNull // a non-null top-level field errored → the whole data is null
        } finally {
            gate.completed()
        }
        return ExecutionResult(data, execution.resultErrors())
    }

    /**
     * Run a subscription — the graphql-java `SubscriptionExecutionStrategy` replacement. The single root
     * field's resolver returns the **source event stream** as a [Flow]; this maps each event to an [ExecutionResult]
     * by completing it against the field's sub-selection, yielding an ordered response stream.
     *
     * A subscribe-time failure (wrong operation, no root type, bad variables, the field not returning a [Flow], a
     * thrown resolver) yields a single-element flow carrying the error. Per-event errors are reported within that
     * event's result per spec. Cancelling the returned flow's collection tears down the source flow (structured
     * concurrency). Each event gets an independent loader cache initialized from [dataLoaders]' declared loaders.
     */
    suspend fun executeSubscription(
        document: Document,
        operationName: String? = null,
        variables: Map<String, Any?> = emptyMap(),
        rootValue: Any? = null,
        context: GraphQLContext = GraphQLContext.EMPTY,
        dataLoaders: DataLoaderRegistry = DataLoaderRegistry.DEFAULT,
    ): Flow<ExecutionResult> {
        val operations = document.definitions.filterIsInstance<OperationDefinition>()
        val operation = (if (operationName != null) operations.firstOrNull { it.name == operationName } else operations.singleOrNull())
            ?: return flowOf(ExecutionResult.ofErrors(listOf(GraphQLError(if (operationName != null) "No operation named '$operationName'" else "Must provide an operation name when the document defines multiple operations"))))

        if (operation.operation != OperationType.SUBSCRIPTION) {
            return flowOf(ExecutionResult.ofErrors(listOf(GraphQLError("executeSubscription() requires a subscription operation; use execute() for queries and mutations"))))
        }
        val rootType = executable.schema.rootType(OperationType.SUBSCRIPTION)
            ?: return flowOf(ExecutionResult.ofErrors(listOf(GraphQLError("Schema has no subscription root type"))))

        val coercedVariables = try {
            coercion.coerceVariables(operation, variables)
        } catch (e: CoercionException) {
            return flowOf(ExecutionResult.ofErrors(listOf(GraphQLError(e.reason, locationOf(e.location), e.path))))
        }

        val fragments = document.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        val subscribeDataLoaders = dataLoaders.newExecutionRegistry()
        val subscribeGate = gateFor(subscribeDataLoaders)
        val subscribe = Execution(coercedVariables, context, fragments, subscribeGate, subscribeDataLoaders)
        subscribeGate.launched(1)
        val outcome = try {
            subscribe.subscribe(operation, rootType.name, rootValue)
        } finally {
            subscribeGate.completed()
        }
        return when (outcome) {
            is SubscribeOutcome.Failed -> flowOf(outcome.result)
            is SubscribeOutcome.Stream -> outcome.source.map { event ->
                // Each event is an independent execution with its own errors (and batch frame).
                val eventDataLoaders = dataLoaders.newExecutionRegistry()
                Execution(coercedVariables, context, fragments, gateFor(eventDataLoaders), eventDataLoaders)
                    .subscriptionEventResult(outcome.responseKey, outcome.fieldDef, outcome.fields, event)
            }
        }
    }

    /** The no-op gate when a request has no loaders, else the request's real batch gate. */
    private fun gateFor(dataLoaders: DataLoaderRegistry): BatchGate = dataLoaders.gate

    /** The outcome of the subscribe step: either the resolved source stream, or a request error to emit once. */
    private sealed interface SubscribeOutcome {
        data class Stream(val responseKey: String, val fieldDef: FieldDefinition, val fields: List<Field>, val source: Flow<*>) : SubscribeOutcome
        data class Failed(val result: ExecutionResult) : SubscribeOutcome
    }

    /** A field error has nulled a value; per non-null rules it propagates until a nullable position absorbs it. */
    private class NullBubble : Exception()

    /** A response-work limit was exceeded, so the remaining execution must stop immediately. */
    private class ExecutionAborted : Exception()

    /** Distinguishes a completed null result from an unfilled result slot in the bounded worker scheduler. */
    private class WorkResult<out T>(val value: T)

    /** Request-local field-collection cache key. */
    private data class FieldCollectionKey(val selectionSet: SelectionSet, val objectType: String)

    private inner class Execution(
        val variables: CoercedVariables,
        val context: GraphQLContext,
        val fragments: Map<String, FragmentDefinition>,
        val gate: BatchGate,
        val dataLoaders: DataLoaderRegistry,
    ) {
        private val errors = mutableListOf<GraphQLError>()
        private val errorsLock = Mutex()
        private val fieldWork = ExecutionWorkLimiter(limits.maxConcurrentFields)
        private val listItemWork = ExecutionWorkLimiter(limits.maxConcurrentListItems)
        private val responseNodes = AtomicInt(0)
        private val responseLimitPath = AtomicReference<List<Any>?>(null)
        private val subSelectionCache = AtomicReference<Map<List<Field>, SelectionSet>>(emptyMap())
        private val fieldCollectionCache =
            AtomicReference<Map<FieldCollectionKey, Map<String, List<Field>>>>(emptyMap())

        private suspend fun record(error: GraphQLError) = errorsLock.withLock { errors += error }

        fun resultErrors(): List<GraphQLError> {
            val limitPath = responseLimitPath.load() ?: return errors
            return errors + GraphQLError(
                "Response exceeds the maximum node count of ${limits.maxResponseNodes}",
                path = limitPath,
            )
        }

        private suspend fun fieldError(exception: Throwable, path: List<Any>, location: SourceLocation?): GraphQLError =
            exceptionHandler.handle(exception, path, location, context)

        suspend fun executeSelectionSet(selectionSet: SelectionSet, objectType: String, objectValue: Any?, path: List<Any>, serial: Boolean): JsonElement {
            val grouped = collectFields(selectionSet, objectType)
            val entries = if (serial) {
                grouped.map { (responseName, fields) -> responseName to executeField(objectType, objectValue, fields, path + responseName) }
            } else {
                executeInBatches(grouped.entries.toList(), fieldWork) { (responseName, fields) ->
                    responseName to executeField(objectType, objectValue, fields, path + responseName)
                }
            }
            return JsonObject(entries.toMap())
        }

        /** Resolve a subscription's single root field to its source event [Flow] (CreateSourceEventStream). */
        suspend fun subscribe(operation: OperationDefinition, rootTypeName: String, rootValue: Any?): SubscribeOutcome {
            val grouped = collectFields(operation.selectionSet, rootTypeName)
            if (grouped.size != 1) {
                return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("A subscription must select exactly one root field, but selects ${grouped.size}"))))
            }
            val (responseKey, fields) = grouped.entries.single().let { it.key to it.value }
            val field = fields.first()
            if (field.name == "__typename") {
                return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("A subscription root field cannot be '__typename'"))))
            }
            val fieldDef = executable.schema.field(rootTypeName, field.name)
                ?: return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("Cannot query field '${field.name}' on type '$rootTypeName'", locationOf(field.location), listOf(responseKey)))))

            val arguments = try {
                coercion.coerceArguments(rootTypeName, field, variables)
            } catch (e: CoercionException) {
                return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(GraphQLError(e.reason, locationOf(field.location), listOf(responseKey)))))
            }

            val source = try {
                executable.resolver(rootTypeName, field.name).resolve(ResolverContext(rootValue, arguments, field.name, rootTypeName, executable.schema, context, dataLoaders))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(fieldError(e, listOf(responseKey), field.location))))
            }

            val flow = source as? Flow<*>
            if (flow == null) {
                return SubscribeOutcome.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("Subscription field '${field.name}' must resolve to a Flow"))))
            }
            return SubscribeOutcome.Stream(responseKey, fieldDef, fields, flow)
        }

        /** Run one subscription event (ExecuteSubscriptionEvent): complete [event] against the root field's selection. */
        suspend fun subscriptionEventResult(responseKey: String, fieldDef: FieldDefinition, fields: List<Field>, event: Any?): ExecutionResult {
            gate.launched(1)
            val data = try {
                // Absorb a bubbled error at the (nullable) root field, exactly as executeField would for a query.
                val completed = try {
                    completeValue(fieldDef.type, fields, event, listOf(responseKey))
                } catch (e: NullBubble) {
                    if (fieldDef.type is NonNullType) throw e else JsonNull
                }
                JsonObject(mapOf(responseKey to completed))
            } catch (_: ExecutionAborted) {
                JsonNull
            } catch (_: NullBubble) {
                JsonNull // a non-null root field errored for this event → the event's data is null
            } finally {
                gate.completed()
            }
            return ExecutionResult(data, resultErrors())
        }

        private suspend fun executeField(objectType: String, objectValue: Any?, fields: List<Field>, path: List<Any>): JsonElement {
            val field = fields.first()
            if (field.name == "__typename") return JsonPrimitive(objectType)
            if (instrumentation === Instrumentation.NONE) {
                return resolveAndComplete(objectType, objectValue, fields, field, path)
            }

            val phase = instrumentation.beginField(FieldParameters(objectType, field.name, path))
            val result = try {
                resolveAndComplete(objectType, objectValue, fields, field, path)
            } catch (e: Throwable) {
                phase.completeExceptionally(e)
                throw e
            }
            phase.onCompleted(result, null)
            return result
        }

        private suspend fun resolveAndComplete(objectType: String, objectValue: Any?, fields: List<Field>, field: Field, path: List<Any>): JsonElement {
            val fieldDef = executable.schema.field(objectType, field.name) ?: run {
                // Validation should reject this earlier; defensively report it and null just this field.
                record(GraphQLError("Cannot query field '${field.name}' on type '$objectType'", locationOf(field.location), path))
                return JsonNull
            }

            val arguments = try {
                coercion.coerceArguments(objectType, field, variables)
            } catch (e: CoercionException) {
                record(GraphQLError(e.reason, locationOf(field.location), path))
                return nullOrBubble(fieldDef.type is NonNullType)
            }

            val result = try {
                executable.resolver(objectType, field.name).resolve(ResolverContext(objectValue, arguments, field.name, objectType, executable.schema, context, dataLoaders))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(fieldError(e, path, field.location))
                return nullOrBubble(fieldDef.type is NonNullType)
            }

            return try {
                completeValue(fieldDef.type, fields, result, path)
            } catch (e: NullBubble) {
                if (fieldDef.type is NonNullType) throw e else JsonNull
            }
        }

        private suspend fun completeValue(type: Type, fields: List<Field>, result: Any?, path: List<Any>): JsonElement {
            if (type is NonNullType) {
                val completed = completeValue(type.type, fields, result, path)
                if (completed is JsonNull) {
                    record(GraphQLError("Cannot return null for non-null field at path $path", emptyList(), path))
                    throw NullBubble()
                }
                return completed
            }
            consumeResponseNode(path)
            if (result == null) return JsonNull
            // NonNullType is handled above, so here type is a list or a named type.
            return if (type is ListType) completeList(type.type, fields, result, path) else completeNamed((type as NamedType).name, fields, result, path)
        }

        private suspend fun completeList(inner: Type, fields: List<Field>, result: Any?, path: List<Any>): JsonElement {
            val iterable = result as? Iterable<*>
            if (iterable == null) {
                record(GraphQLError("Expected a list at path $path", emptyList(), path))
                throw NullBubble()
            }
            val items = mutableListOf<Any?>()
            for (item in iterable) {
                if (items.size >= limits.maxListItems) {
                    abort("List exceeds the maximum item count of ${limits.maxListItems}", path)
                }
                items += item
            }
            val completed = executeInBatches(items.withIndex().toList(), listItemWork) { indexed ->
                try {
                    completeValue(inner, fields, indexed.value, path + indexed.index)
                } catch (e: NullBubble) {
                    if (inner is NonNullType) throw e else JsonNull // a null non-null element nulls the whole list
                }
            }
            return JsonArray(completed)
        }

        private suspend fun completeNamed(typeName: String, fields: List<Field>, result: Any, path: List<Any>): JsonElement {
            executable.coercing(typeName)?.let { coercing ->
                return try {
                    coercing.serialize(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CoercingException) {
                    bubble("Could not serialize value as '$typeName'", path)
                } catch (_: Exception) {
                    bubble("Could not serialize value as '$typeName'", path)
                }
            }
            return when (val type = executable.schema.type(typeName)) {
                is EnumTypeDefinition -> {
                    val name = result.toString()
                    if (type.values.any { it.name == name }) JsonPrimitive(name)
                    else bubble("Could not serialize value as enum '$typeName'", path)
                }
                is ObjectTypeDefinition -> executeSelectionSet(subSelection(fields), typeName, result, path, serial = false)
                // Interface or union — the only remaining output composite a (schema-validated) field type can be
                // here, so the abstract types fold into this branch instead of an unreachable `else`: resolve the
                // concrete type via the type resolver, then complete against it.
                else -> {
                    val concrete = try {
                        executable.typeResolver(typeName).resolveType(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: bubble("Could not resolve the concrete type of '$typeName'", path)
                    if (executable.schema.possibleTypes(typeName).none { it.name == concrete }) {
                        bubble("Could not resolve the concrete type of '$typeName'", path)
                    }
                    executeSelectionSet(subSelection(fields), concrete, result, path, serial = false)
                }
            }
        }

        /** Record a field error at [path] and unwind the current field via a null bubble. */
        private suspend fun bubble(message: String, path: List<Any>): Nothing {
            record(GraphQLError(message, emptyList(), path))
            throw NullBubble()
        }

        /** Consume one response node or abort the request once its actual output work exceeds the configured budget. */
        private fun consumeResponseNode(path: List<Any>) {
            if (responseNodes.fetchAndIncrement() >= limits.maxResponseNodes) {
                responseLimitPath.compareAndSet(expectedValue = null, newValue = path)
                throw ExecutionAborted()
            }
        }

        private suspend fun abort(message: String, path: List<Any>): Nothing {
            record(GraphQLError(message, path = path))
            throw ExecutionAborted()
        }

        /**
         * Execute a work-conserving set of bounded workers. Each worker takes another item as soon as it finishes its
         * current item, so one slow resolver cannot hold back unrelated work in a fixed admission wave. A recursive
         * task yields its permit before awaiting child work and reacquires it before returning, preserving the
         * request-wide concurrency bound without deadlocking nested selections.
         */
        @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
        private suspend fun <T, R> executeInBatches(
            items: List<T>,
            limiter: ExecutionWorkLimiter,
            block: suspend (T) -> R,
        ): List<R> {
            if (items.isEmpty()) return emptyList()
            // The root selection set already owns the gate's root frame. With only one item there is no sibling
            // concurrency to admit, so allocating a child coroutine and permit only adds overhead. Nested work still
            // carries an ExecutionPermit and therefore continues through the request-wide limiter.
            if (items.size == 1 && currentCoroutineContext()[ExecutionPermit] == null) {
                return listOf(block(items.single()))
            }
            // Parking only after admission keeps a wave that is waiting for permits live, so the gate cannot flush
            // a loader batch before this wave has queued its keys. Waiting cannot stall: loader-parked and
            // child-parked tasks yield their permits, so every permit belongs to a task that is running.
            return withYieldedExecutionPermit {
                coroutineScope {
                    val workerCount = limiter.acquireAvailable(items.size)
                    val nextItem = AtomicInt(0)
                    val results = arrayOfNulls<WorkResult<R>>(items.size)
                    gate.launched(workerCount)
                    // The last worker to finish un-parks this task rather than waiting for it to next run: parents
                    // resume one at a time, and one still counted as parked lets the gate flush a batch before the
                    // others have queued their keys. Whichever of the last worker or this frame's exit comes first
                    // un-parks, once. A worker may finish before this task parks; the counts then net to runnable.
                    val unparked = AtomicBoolean(false)
                    val unpark = { if (unparked.compareAndSet(false, true)) gate.unparkOnChildren() }
                    val remaining = AtomicInt(workerCount)
                    val workers = List(workerCount) {
                        val permit = ExecutionPermit(limiter)
                        async(permit, start = CoroutineStart.ATOMIC) {
                            try {
                                while (true) {
                                    val index = nextItem.fetchAndIncrement()
                                    if (index >= items.size) break
                                    results[index] = WorkResult(block(items[index]))
                                }
                            } finally {
                                try {
                                    gate.completed()
                                } finally {
                                    limiter.release()
                                    if (remaining.decrementAndFetch() == 0) unpark()
                                }
                            }
                        }
                    }
                    gate.parkOnChildren()
                    try {
                        workers.awaitAll()
                    } finally {
                        unpark()
                    }
                    results.map { it!!.value }
                }
            }
        }


        /** Merge the sub-selection sets of all merged fields into one for the child object. */
        private fun subSelection(fields: List<Field>): SelectionSet {
            while (true) {
                val current = subSelectionCache.load()
                current[fields]?.let { return it }
                val selection = SelectionSet(fields.mapNotNull { it.selectionSet }.flatMap { it.selections })
                if (subSelectionCache.compareAndSet(current, current + (fields to selection))) return selection
            }
        }

        // ---- field collection ----

        private fun collectFields(selectionSet: SelectionSet, objectType: String): Map<String, List<Field>> {
            val key = FieldCollectionKey(selectionSet, objectType)
            fieldCollectionCache.load()[key]?.let { return it }
            val grouped = linkedMapOf<String, MutableList<Field>>()
            val visitedFragments = mutableSetOf<String>()

            fun collect(set: SelectionSet) {
                for (selection in set.selections) {
                    when (selection) {
                        is Field -> {
                            if (shouldInclude(selection.directives)) {
                                grouped.getOrPut(selection.alias ?: selection.name) { mutableListOf() }.add(selection)
                            }
                        }
                        is FragmentSpread -> {
                            if (shouldInclude(selection.directives) && visitedFragments.add(selection.name)) {
                                val fragment = fragments[selection.name]
                                if (fragment != null && typeApplies(fragment.typeCondition.name, objectType)) {
                                    collect(fragment.selectionSet)
                                }
                            }
                        }
                        is InlineFragment -> {
                            val condition = selection.typeCondition
                            val applies = condition == null || typeApplies(condition.name, objectType)
                            if (shouldInclude(selection.directives) && applies) {
                                collect(selection.selectionSet)
                            }
                        }
                    }
                }
            }

            collect(selectionSet)
            val collected = grouped.mapValues { (_, fields) -> fields.toList() }
            while (true) {
                val current = fieldCollectionCache.load()
                current[key]?.let { return it }
                if (fieldCollectionCache.compareAndSet(current, current + (key to collected))) return collected
            }
        }

        private fun shouldInclude(directives: List<Directive>): Boolean {
            directives.firstOrNull { it.name == "skip" }?.let { if (booleanDirectiveArg(it) == true) return false }
            directives.firstOrNull { it.name == "include" }?.let { if (booleanDirectiveArg(it) == false) return false }
            return true
        }

        private fun booleanDirectiveArg(directive: Directive): Boolean? =
            when (val value = directive.arguments.firstOrNull { it.name == "if" }?.value) {
                is BooleanValue -> value.value
                is Variable -> variables.values[value.name] as? Boolean
                else -> null
            }

        private fun nullOrBubble(nonNull: Boolean): JsonElement = if (nonNull) throw NullBubble() else JsonNull
    }

    private fun typeApplies(condition: String, objectType: String): Boolean =
        condition == objectType || executable.schema.possibleTypes(condition).any { it.name == objectType }

    private fun locationOf(location: SourceLocation?): List<SourceLocation> = if (location != null) listOf(location) else emptyList()

    /** A request-scoped admission controller that reserves permits before child coroutines are allocated. */
    internal class ExecutionWorkLimiter(private val maxConcurrency: Int) {
        private val semaphore = Semaphore(maxConcurrency)

        suspend fun acquire() = semaphore.acquire()

        suspend fun acquireAvailable(remaining: Int): Int {
            semaphore.acquire()
            var acquired = 1
            val limit = minOf(remaining, maxConcurrency)
            while (acquired < limit && semaphore.tryAcquire()) acquired++
            return acquired
        }

        fun release() = semaphore.release()
    }

    /** Marks the one request-wide work permit owned by an admitted resolver/list-item coroutine. */
    internal class ExecutionPermit(
        val limiter: ExecutionWorkLimiter,
    ) : AbstractCoroutineContextElement(ExecutionPermit) {
        companion object Key : CoroutineContext.Key<ExecutionPermit>
    }
}

/**
 * Temporarily release the current task's request-wide permit while it awaits work it does not run itself: descendant
 * fields, or a data loader batch. Reacquisition is non-cancellable because the task's completion path owns and must
 * release that permit.
 */
internal suspend fun <T> withYieldedExecutionPermit(block: suspend () -> T): T {
    val permit = currentCoroutineContext()[GraphQLExecutor.ExecutionPermit]
    if (permit == null) return block()
    permit.limiter.release()
    return try {
        block()
    } finally {
        withContext(NonCancellable) { permit.limiter.acquire() }
    }
}
