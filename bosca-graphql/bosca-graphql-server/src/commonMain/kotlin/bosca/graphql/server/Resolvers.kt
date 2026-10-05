package bosca.graphql.server

import bosca.graphql.schema.GraphQLSchema

/**
 * A request-scoped key/value bag threaded into every [ResolverContext] (graphql-java's `GraphQLContext`) —
 * where the transport puts auth, the server call, the coroutine scope, etc., for resolvers to read.
 */
class GraphQLContext(val values: Map<String, Any?> = emptyMap()) {
    operator fun get(key: String): Any? = values[key]

    inline fun <reified T> getAs(key: String): T? = values[key] as? T

    companion object {
        val EMPTY = GraphQLContext()
    }
}

/**
 * The environment a [FieldResolver] runs in (graphql-java's `DataFetchingEnvironment`): the [source] (parent)
 * object, the coerced [arguments], the [fieldName] being resolved, the [parentType] it sits on, the [schema]
 * for type lookups, the request-scoped [context], and the request's [dataLoaders] for batched loading.
 */
class ResolverContext(
    val source: Any?,
    val arguments: Map<String, Any?>,
    val fieldName: String,
    val parentType: String,
    val schema: GraphQLSchema,
    val context: GraphQLContext = GraphQLContext.EMPTY,
    val dataLoaders: DataLoaderRegistry = DataLoaderRegistry(),
) {
    /** The parent/source value cast to [T], or null when absent or of another type. */
    inline fun <reified T> sourceAs(): T? = source as? T

    /** The argument [name], or null if absent. */
    fun argument(name: String): Any? = arguments[name]

    /** The argument [name] cast to [T], or null if absent or of another type. */
    inline fun <reified T> arg(name: String): T? = arguments[name] as? T

    /** Compatibility spelling used by generated Bosca controllers. */
    inline fun <reified T> getArgument(name: String): T? = arg(name)

    /** Compatibility view of the request context used by generated Bosca controllers. */
    val graphQlContext: GraphQLContext get() = context

    /** Compatibility view of the request-scoped loader registry. */
    val dataLoaderRegistry: DataLoaderRegistry get() = dataLoaders

    /** The request-scoped [DataLoader] registered under [name] — batch related loads to avoid N+1. */
    fun <K, V> dataLoader(name: String): DataLoader<K, V> = dataLoaders.loader(name)
}

/**
 * Resolves a single field to its value — `suspend`, because resolution is the I/O boundary (the engine awaits
 * it, and resolves sibling fields concurrently). This is the seam Bosca's KSP-generated controllers plug into.
 */
fun interface FieldResolver {
    suspend fun resolve(context: ResolverContext): Any?
}

/** Determines the concrete object type name for a runtime value of an interface/union type. */
fun interface TypeResolver {
    fun resolveType(value: Any?): String?
}
