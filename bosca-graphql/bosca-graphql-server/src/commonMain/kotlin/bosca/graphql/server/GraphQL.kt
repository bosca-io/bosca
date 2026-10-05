package bosca.graphql.server

import bosca.graphql.language.Document
import bosca.graphql.language.Field
import bosca.graphql.language.preOrder
import bosca.graphql.parser.GraphQLSyntaxException
import bosca.graphql.parser.Parser
import bosca.graphql.validation.OperationValidator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

/**
 * The request pipeline entry point — graphql-java's `GraphQL` facade. Ties together the persisted/
 * preparsed-document cache, the [Instrumentation] hooks (parse → validate → execute → field), the query limits, and
 * the [GraphQLExecutor]:
 *
 * 1. obtain the [Document] from the [preparsedDocumentProvider] (parsing once, under [Instrumentation.beginParse]);
 * 2. validate it (under [Instrumentation.beginValidation]);
 * 3. apply the query limits via [Instrumentation.instrumentExecution], rejecting before execution;
 * 4. execute under [Instrumentation.beginExecution], with the executor firing [Instrumentation.beginField] per field.
 */
class GraphQL(
    private val executable: ExecutableSchema,
    private val instrumentation: Instrumentation = Instrumentation.NONE,
    private val preparsedDocumentProvider: PreparsedDocumentProvider = PreparsedDocumentProvider.NONE,
    exceptionHandler: DataFetcherExceptionHandler = DefaultDataFetcherExceptionHandler,
    private val requestLimits: GraphQLRequestLimits = GraphQLRequestLimits.DEFAULT,
    executionLimits: GraphQLExecutionLimits = GraphQLExecutionLimits.DEFAULT,
) {
    private val executor = GraphQLExecutor(executable, exceptionHandler, instrumentation, executionLimits)
    private val validator = OperationValidator(executable.schema)

    /** Run a query or mutation through the full pipeline. */
    suspend fun execute(request: GraphQLRequest): ExecutionResult {
        val document = when (val prepared = prepare(request)) {
            is Prepared.Failed -> return prepared.result
            is Prepared.Ready -> prepared.document
        }
        val parameters = ExecutionParameters(document, request.operationName, request.variables, executable.schema)

        val rejected = reject(parameters)
        if (rejected != null) {
            return rejected
        }

        val phase = instrumentation.beginExecution(parameters)
        val result = try {
            executor.execute(document, request.operationName, request.variables, request.rootValue, request.context, request.dataLoaders)
        } catch (e: Throwable) {
            phase.completeExceptionally(e)
            throw e
        }
        phase.onCompleted(result, null)
        return result
    }

    /** Run a subscription through the full pipeline, returning the response stream. */
    suspend fun executeSubscription(request: GraphQLRequest): Flow<ExecutionResult> {
        val document = when (val prepared = prepare(request)) {
            is Prepared.Failed -> return flowOf(prepared.result)
            is Prepared.Ready -> prepared.document
        }
        val parameters = ExecutionParameters(document, request.operationName, request.variables, executable.schema)

        val rejected = reject(parameters)
        if (rejected != null) {
            return flowOf(rejected)
        }

        return flow {
            val phase = instrumentation.beginExecution(parameters)
            var lastResult: ExecutionResult? = null
            try {
                executor.executeSubscription(
                    document,
                    request.operationName,
                    request.variables,
                    request.rootValue,
                    request.context,
                    request.dataLoaders,
                ).collect { result ->
                    lastResult = result
                    emit(result)
                }
            } catch (e: Throwable) {
                phase.completeExceptionally(e)
                throw e
            }
            phase.onCompleted(lastResult, null)
        }
    }

    private sealed interface Prepared {
        data class Ready(val document: Document) : Prepared
        data class Failed(val result: ExecutionResult) : Prepared
    }

    /** Obtain the parsed + validated document; returns [Prepared.Failed] on a parse or validation error. */
    private suspend fun prepare(request: GraphQLRequest): Prepared {
        if (request.query.length > requestLimits.maxQueryCharacters) {
            return Prepared.Failed(
                ExecutionResult.ofErrors(
                    listOf(GraphQLError("Query exceeds the maximum length of ${requestLimits.maxQueryCharacters} characters")),
                ),
            )
        }
        val preparsed = obtainDocument(request.query)
        if (preparsed.errors.isNotEmpty()) return Prepared.Failed(ExecutionResult.ofErrors(preparsed.errors))
        val document = preparsed.document ?: return Prepared.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("No document was produced for the request"))))
        if (!request.introspectionEnabled && document.preOrder().filterIsInstance<Field>().any { it.name == "__schema" || it.name == "__type" }) {
            return Prepared.Failed(ExecutionResult.ofErrors(listOf(GraphQLError("Introspection is disabled"))))
        }
        return Prepared.Ready(document)
    }

    /** Apply the query limits; returns a rejection result if any limit is exceeded, else null. */
    private suspend fun reject(parameters: ExecutionParameters): ExecutionResult? =
        instrumentation.instrumentExecution(parameters).takeIf { it.isNotEmpty() }?.let { ExecutionResult.ofErrors(it) }

    /** Parse then validate, caching both together (the "preparsed" document) so a repeated query skips both. */
    private suspend fun obtainDocument(query: String): PreparsedDocument =
        preparsedDocumentProvider.document(query) { source ->
            val parsePhase = instrumentation.beginParse(source)
            val parseResult = parsePhase.completeCatching {
                Parser.parse(source, requestLimits.parserLimits)
            }
            val parseError = parseResult.exceptionOrNull()
            if (parseError is GraphQLSyntaxException) {
                return@document PreparsedDocument.ofErrors(listOf(GraphQLError(parseError.reason, listOf(parseError.location))))
            }
            val document = parseResult.getOrThrow()
            document.limitError(requestLimits)?.let { return@document PreparsedDocument.ofErrors(listOf(it)) }
            val validationErrors = validate(document)
            if (validationErrors.isEmpty()) PreparsedDocument.of(document) else PreparsedDocument(document, validationErrors)
        }

    /** Run the foundation [OperationValidator] under [Instrumentation.beginValidation]. */
    private suspend fun validate(document: Document): List<GraphQLError> {
        val phase = instrumentation.beginValidation(document)
        return phase.completeCatching {
            validator.validate(document).map { error ->
                val location = error.location
                GraphQLError(error.message, if (location != null) listOf(location) else emptyList())
            }
        }
            .getOrThrow()
    }
}

/** One request to [GraphQL]: the [query] plus the usual execution inputs. */
data class GraphQLRequest(
    val query: String,
    val operationName: String? = null,
    val variables: Map<String, Any?> = emptyMap(),
    val rootValue: Any? = null,
    val context: GraphQLContext = GraphQLContext.EMPTY,
    val dataLoaders: DataLoaderRegistry = DataLoaderRegistry.DEFAULT,
    val introspectionEnabled: Boolean = true,
)
