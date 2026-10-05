package bosca.graphql.server

/**
 * The resolver configuration bound onto a schema (graphql-java's `RuntimeWiring`): per-type field resolvers,
 * per-abstract-type [TypeResolver]s, and per-scalar [Coercing]s. Build it with the [runtimeWiring] DSL.
 */
class RuntimeWiring internal constructor(
    val fieldResolvers: Map<String, Map<String, FieldResolver>>,
    val typeResolvers: Map<String, TypeResolver>,
    val scalarCoercings: Map<String, Coercing>,
) {
    /** Combine with [other], letting [other]'s resolvers win on a per-field/type/scalar clash. */
    internal fun mergedWith(other: RuntimeWiring): RuntimeWiring = RuntimeWiring(
        fieldResolvers = (fieldResolvers.keys + other.fieldResolvers.keys).associateWith { type ->
            fieldResolvers[type].orEmpty() + other.fieldResolvers[type].orEmpty()
        },
        typeResolvers = typeResolvers + other.typeResolvers,
        scalarCoercings = scalarCoercings + other.scalarCoercings,
    )
}

/** Build a [RuntimeWiring] with a DSL: `runtimeWiring { type("Query") { field("me") { ctx -> … } } }`. */
fun runtimeWiring(block: RuntimeWiringBuilder.() -> Unit): RuntimeWiring =
    RuntimeWiringBuilder().apply(block).build()

class RuntimeWiringBuilder {
    private val fieldResolvers = mutableMapOf<String, MutableMap<String, FieldResolver>>()
    private val typeResolvers = mutableMapOf<String, TypeResolver>()
    private val scalarCoercings = mutableMapOf<String, Coercing>()

    /** Wire the fields (and optional [TypeResolver]) of the type named [name]. */
    fun type(name: String, block: TypeWiringBuilder.() -> Unit): RuntimeWiringBuilder = apply {
        val builder = TypeWiringBuilder().apply(block)
        if (builder.fieldResolvers.isNotEmpty()) fieldResolvers.getOrPut(name) { mutableMapOf() }.putAll(builder.fieldResolvers)
        builder.typeResolver?.let { typeResolvers[name] = it }
    }

    /** Merge a pre-built type wiring, with its fields and resolver replacing earlier entries on a clash. */
    fun type(wiring: TypeRuntimeWiring): RuntimeWiringBuilder = apply {
        if (wiring.fieldResolvers.isNotEmpty()) {
            fieldResolvers.getOrPut(wiring.typeName) { mutableMapOf() }.putAll(wiring.fieldResolvers)
        }
        wiring.typeResolver?.let { typeResolvers[wiring.typeName] = it }
    }

    /** Register the [Coercing] for the custom scalar named [name]. */
    fun scalar(name: String, coercing: Coercing): RuntimeWiringBuilder = apply {
        scalarCoercings[name] = coercing
    }

    fun build(): RuntimeWiring = RuntimeWiring(
        fieldResolvers.mapValues { (_, byField) -> byField.toMap() },
        typeResolvers.toMap(),
        scalarCoercings.toMap(),
    )
}

/**
 * The wiring for one GraphQL object or abstract type. This is the stable hand-off used by KSP-generated
 * [bosca.graphql.server.FieldResolver] dispatchers before their wiring is merged into a [RuntimeWiringBuilder].
 */
class TypeRuntimeWiring private constructor(
    val typeName: String,
    val fieldResolvers: Map<String, FieldResolver>,
    val typeResolver: TypeResolver?,
) {
    class Builder internal constructor(private val typeName: String) {
        private val fieldResolvers = linkedMapOf<String, FieldResolver>()
        private var typeResolver: TypeResolver? = null

        /** Bind [resolver] to [fieldName]. */
        fun field(fieldName: String, resolver: FieldResolver): Builder = apply {
            fieldResolvers[fieldName] = resolver
        }

        /** Register the resolver for an interface or union. */
        fun resolveType(resolver: TypeResolver): Builder = apply {
            typeResolver = resolver
        }

        fun build(): TypeRuntimeWiring = TypeRuntimeWiring(typeName, fieldResolvers.toMap(), typeResolver)
    }

    companion object {
        fun newTypeWiring(typeName: String): Builder = Builder(typeName)
    }
}

class TypeWiringBuilder internal constructor() {
    internal val fieldResolvers = mutableMapOf<String, FieldResolver>()
    internal var typeResolver: TypeResolver? = null

    /** Bind [resolver] to the field named [name]. */
    fun field(name: String, resolver: FieldResolver) {
        fieldResolvers[name] = resolver
    }

    /** Register the [TypeResolver] for this (interface/union) type. */
    fun resolveType(resolver: TypeResolver) {
        typeResolver = resolver
    }
}
