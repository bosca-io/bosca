package bosca.ecommerce.graphql

import bosca.ecommerce.configuration.EcommerceSchemaRegistrar
import bosca.ecommerce.graphql.scalars.MoneyScalar
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.graphql.language.FieldDefinition
import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.ObjectTypeDefinition
import bosca.graphql.language.ObjectTypeExtension
import bosca.graphql.language.Type
import bosca.graphql.parser.Parser
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.runtimeWiring
import bosca.security.service.AuthenticationContext
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Module-level: the generated [EcommerceSchemaRegistrar] finds and loads the module's
 * `.graphqls` files, the combined SDL parses, and the `Money` scalar coercing assembles. This
 * catches missing/misnamed schema resources, SDL syntax errors, and a broken `Money` coercing
 * without needing the server DI container (the full cross-module merge — which also resolves
 * `Organization`/`Profile` and the platform scalars — runs at server boot, where `Features.*` is
 * available).
 */
class EcomSchemaTest {

    @Test
    fun `module sdl loads and parses with the namespace and company types`() = runBlocking<Unit> {
        val sdl = EcommerceSchemaRegistrar().load()

        assertTrue("scalar Money" in sdl, "Money scalar should be declared")
        assertTrue("type Ecom" in sdl, "Ecom type should be declared")
        assertTrue("ecom: Ecom!" in sdl, "Query.ecom field should be declared")
        assertTrue("ecom: EcomMutation!" in sdl, "Mutation.ecom field should be declared")
        assertTrue("type Company" in sdl, "Company type should be declared")
        assertTrue("type CompaniesMutation" in sdl, "CompaniesMutation type should be declared")
        // Guard each area file is registered in SchemaRegistrar (a file present but unregistered loads in
        // neither the test nor the runtime — only surfaces as a runtime "Unknown type"). One assertion per
        // area type that lives in its own SDL file; a forgotten @Schema entry trips the matching check.
        assertTrue("type EcomShipment" in sdl, "ecom-shipment.graphqls must be registered")
        assertTrue("type EcomContainer" in sdl, "ecom-container.graphqls must be registered")
        assertTrue("type EcomReturn" in sdl && "enum ReturnStatus" in sdl, "ecom-return.graphqls must be registered")
        assertTrue("type IapMutation" in sdl, "iap.graphqls must be registered")

        // Syntactic validation of the full module SDL (all area files); throws on a syntax error.
        Parser.parse(sdl)
    }

    /**
     * Every field declared on the `Ecom` (query) and `EcomMutation` namespace types — across the base
     * type and all `extend type` area files — must have a matching resolver function on its
     * `@TypeController`. A field in the SDL with no resolver only fails at *runtime* ("No default data
     * fetcher"), never at SDL parse — this is the guard that turns that into a build failure. (Caught a
     * real gap: a `containers` namespace added to `EcomMutation`'s SDL without its resolver.)
     */
    @Test
    fun `every Ecom and EcomMutation namespace field has a controller resolver`() = runBlocking<Unit> {
        val document = Parser.parse(EcommerceSchemaRegistrar().load())

        fun fieldsOf(type: String): Set<String> = buildSet {
            document.definitions.filterIsInstance<ObjectTypeDefinition>()
                .filter { it.name == type }
                .forEach { definition -> definition.fields.forEach { add(it.name) } }
            document.definitions.filterIsInstance<ObjectTypeExtension>()
                .filter { it.name == type }
                .forEach { extension -> extension.fields.forEach { add(it.name) } }
        }

        val namespaces = mapOf(
            "Ecom" to EcomController::class.java,
            "EcomMutation" to EcomMutationController::class.java,
        )
        for ((type, controller) in namespaces) {
            val sdlFields = fieldsOf(type)
            assertTrue(sdlFields.isNotEmpty(), "$type should declare fields in the SDL")
            // Java reflection (no kotlin-reflect on the test classpath): @Field method names == field names.
            val resolvers = controller.declaredMethods.map { it.name }.toSet()
            val missing = sdlFields - resolvers
            assertTrue(missing.isEmpty(), "$type SDL fields with no resolver in ${controller.simpleName}: $missing")
        }
    }

    /**
     * Every *object* type declared in the module SDL must have a `@TypeController` that resolves it.
     * This generalizes the namespace-field check above from the two namespace types to the whole
     * module: graphql-java does no POJO/property reflection in this engine, so a `type Foo` with no
     * controller fails only at *runtime* with "No default data fetcher" — never at SDL parse. Add a
     * type to any `.graphqls` and forget its controller and this turns that production crash into a
     * build failure.
     *
     * Controllers are discovered by their `RUNTIME`-retained `@TypeController` on the compiled
     * classpath; each resolves the type named by `@TypeController(type=...)` when set, else the simple
     * name of its `GraphQLController<T>` argument — exactly the rule the KSP `TypeControllerVisitor`
     * uses, so the `type=`-overridden controllers (EcomAudit, EcomShipment, …) map correctly.
     */
    @Test
    fun `every object type in the module SDL has a TypeController`() = runBlocking<Unit> {
        val document = Parser.parse(EcommerceSchemaRegistrar().load())
        val sdlObjectTypes = document.definitions.filterIsInstance<ObjectTypeDefinition>()
            .map { it.name }
            .toSet()
        // Sanity floor: the module declares dozens of object types; a near-empty set would mean the SDL
        // failed to load and the guard would be vacuously green.
        assertTrue(sdlObjectTypes.size > 50, "expected the module SDL to declare many object types, found ${sdlObjectTypes.size}")

        val controlled = discoverControlledTypeNames()
        // Sanity floor on discovery: if the codeSource walk found nothing (e.g. classpath shape changed),
        // fail with a discovery message rather than a misleading flood of "missing controller".
        assertTrue(controlled.size > 50, "@TypeController discovery looks broken — found only ${controlled.size} controllers")

        val uncontrolled = sdlObjectTypes - controlled
        assertTrue(
            uncontrolled.isEmpty(),
            "SDL object types with no @TypeController (each would throw \"No default data fetcher\" at runtime): " +
                uncontrolled.sorted(),
        )
    }

    /**
     * Authorization drift-guard: every *leaf* mutation field in the module SDL must have a resolver
     * that declares an [AuthenticationContext] parameter — the precondition for gating it (every leaf
     * resolver gates on that context, owner-checking via `cartAccess` or admin-checking via
     * `verifyEcomAdmin`). A new mutation that forgets the parameter *cannot* be gated and would ship a
     * world-readable/writable commerce operation; nothing else fails the build, so this turns that
     * into a compile-time failure.
     *
     * "Leaf" is decided from the SDL, not reflection: a `suspend fun` erases its return type behind a
     * `Continuation`, so the only reliable signal that a field is a *namespace accessor* (which is not
     * gated — its leaves are) vs an operation is the SDL field type. A field whose (unwrapped) type is
     * itself a `*Mutation` object is a namespace accessor and is skipped; everything else is a leaf and
     * must take the context.
     */
    @Test
    fun `every leaf ecom mutation resolver declares an AuthenticationContext parameter`() = runBlocking<Unit> {
        val document = Parser.parse(EcommerceSchemaRegistrar().load())
        val controllersByType = discoverControllersByTypeName()

        fun fieldDefsOf(type: String): List<FieldDefinition> = buildList {
            document.definitions.filterIsInstance<ObjectTypeDefinition>()
                .filter { it.name == type }
                .forEach { addAll(it.fields) }
            document.definitions.filterIsInstance<ObjectTypeExtension>()
                .filter { it.name == type }
                .forEach { addAll(it.fields) }
        }

        fun resolverFor(type: String, field: String): Method? =
            controllersByType[type].orEmpty().firstNotNullOfOrNull { controller ->
                controller.declaredMethods.firstOrNull {
                    !it.isSynthetic && it.isAnnotationPresent(Field::class.java) && it.name == field
                }
            }

        val mutationTypes = document.definitions.filterIsInstance<ObjectTypeDefinition>()
            .map { it.name }
            .filter { it.endsWith("Mutation") }
        assertTrue(mutationTypes.size > 20, "expected many *Mutation types in the SDL, found ${mutationTypes.size}")

        var leavesChecked = 0
        val ungated = mutableListOf<String>()
        for (type in mutationTypes) {
            for (field in fieldDefsOf(type)) {
                // A namespace accessor (returns another *Mutation) is not itself gated — its leaves are.
                if (baseTypeName(field.type).endsWith("Mutation")) continue
                leavesChecked++
                val resolver = resolverFor(type, field.name)
                if (resolver == null) {
                    ungated += "$type.${field.name} (no @Field resolver found)"
                    continue
                }
                if (resolver.parameterTypes.none { it.name == AuthenticationContext::class.java.name }) {
                    ungated += "$type.${field.name}"
                }
            }
        }
        // Sanity floor: a broken discovery/SDL load would check nothing and pass vacuously.
        assertTrue(leavesChecked > 40, "expected to check many leaf mutations, only saw $leavesChecked")
        assertTrue(
            ungated.isEmpty(),
            "leaf ecom mutations missing an AuthenticationContext parameter (cannot be authorization-gated): " +
                ungated.sorted(),
        )
    }

    /** Unwrap a NonNull/List SDL type node to its base `TypeName`. */
    private fun baseTypeName(type: Type): String = when (type) {
        is NonNullType -> baseTypeName(type.type)
        is ListType -> baseTypeName(type.type)
        is NamedType -> type.name
    }

    /** The set of GraphQL type names resolved by a `@TypeController` on the module classpath. */
    private fun discoverControlledTypeNames(): Set<String> = discoverControllersByTypeName().keys

    /**
     * Every `@TypeController` on the module classpath, grouped by the GraphQL type name it resolves. A
     * single GraphQL type may have more than one controller (e.g. `extend type CartMutation` resolvers
     * split across files), so the value is a list.
     */
    private fun discoverControllersByTypeName(): Map<String, List<Class<*>>> {
        val root = File(EcomController::class.java.protectionDomain.codeSource.location.toURI())
        val loader = EcomController::class.java.classLoader
        return File(root, "bosca/ecommerce/graphql").walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name } // skip synthetic/nested
            .mapNotNull { file ->
                val fqcn = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                runCatching { Class.forName(fqcn, false, loader) }.getOrNull()
            }
            .filter { it.isAnnotationPresent(TypeController::class.java) }
            .groupBy { graphqlTypeName(it) }
    }

    /** Mirror of the KSP `TypeControllerVisitor`: `type=` override, else the `GraphQLController<T>` arg's simple name. */
    private fun graphqlTypeName(controller: Class<*>): String {
        val explicit = controller.getAnnotation(TypeController::class.java).type
        if (explicit.isNotEmpty()) return explicit
        val graphqlController = controller.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .firstOrNull { (it.rawType as? Class<*>)?.name == "bosca.graphql.GraphQLController" }
            ?: error("${controller.simpleName} is @TypeController but does not implement GraphQLController<T>")
        return when (val arg = graphqlController.actualTypeArguments.first()) {
            is Class<*> -> arg.simpleName
            is ParameterizedType -> (arg.rawType as Class<*>).simpleName
            else -> error("cannot resolve target type for ${controller.simpleName}")
        }
    }

    @Test
    fun `money scalar coerces in a schema that uses it`() {
        val schema = ExecutableSchema.fromSdl(
            "scalar Money\ntype Query { price: Money! }",
            runtimeWiring { scalar("Money", MoneyScalar.Type) },
        )

        assertNotNull(schema.schema.type("Money"), "Money scalar should be in the schema")
        assertNotNull(schema.schema.field("Query", "price"), "field using Money should resolve")
    }
}
