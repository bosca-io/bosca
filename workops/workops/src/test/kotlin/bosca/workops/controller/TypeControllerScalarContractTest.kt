package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import io.mockk.mockkClass
import kotlinx.serialization.json.Json
import java.lang.reflect.ParameterizedType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Schema-level contract test for the large family of one-to-one GraphQL scalar field adapters.
 *
 * These methods are intentionally tiny, but they are the public GraphQL mapping between WorkOps domain
 * models and the schema. Discovering the controllers from the compiled test classpath keeps this test in
 * lock-step with newly added type controllers: every non-suspending `@Field` that accepts exactly its
 * controller's model must remain invocable, and the minimum count prevents discovery failures from
 * silently turning the contract into a no-op.
 */
class TypeControllerScalarContractTest {

    @Test
    fun `all scalar type-controller fields are invocable`() {
        val failures = mutableListOf<String>()
        var controllers = 0
        var fields = 0

        controllerClasses().forEach { controllerClass ->
            val modelClass = controllerClass.graphQLModelClass() ?: return@forEach
            val scalarFields = controllerClass.declaredMethods.filter { method ->
                method.isAnnotationPresent(Field::class.java) &&
                    method.parameterTypes.contentEquals(arrayOf(modelClass))
            }
            if (scalarFields.isEmpty()) return@forEach

            controllers++
            val controller = runCatching { controllerClass.instantiateRelaxed() }
                .getOrElse { error("Could not construct ${controllerClass.name}: ${it.message}") }
            val model = mockkClass(modelClass.kotlin, relaxed = true)
            scalarFields.forEach { method ->
                fields++
                runCatching { method.invoke(controller, model) }
                    .onFailure { failure ->
                        failures += "${controllerClass.simpleName}.${method.name}: " +
                            (failure.cause ?: failure).let { "${it::class.simpleName}: ${it.message}" }
                    }
            }
        }

        assertTrue(controllers >= 30, "Expected at least 30 model type controllers, found $controllers")
        assertTrue(fields >= 250, "Expected at least 250 scalar field mappings, found $fields")
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    private fun controllerClasses(): List<Class<*>> =
        javaClass.classLoader.getResources(CONTROLLER_PACKAGE_PATH).toList()
            .filter { it.protocol == "file" }
            .flatMap { resource ->
                Files.list(Path.of(resource.toURI())).use { paths -> paths.toList() }
            }
            .filter { it.extension == "class" && '$' !in it.name }
            .map { path ->
                Class.forName("$CONTROLLER_PACKAGE.${path.name.removeSuffix(".class")}")
            }
            .filter { it.isAnnotationPresent(TypeController::class.java) }
            .distinctBy { it.name }

    private fun Class<*>.graphQLModelClass(): Class<*>? {
        val graphQLType = genericInterfaces.filterIsInstance<ParameterizedType>()
            .firstOrNull { it.rawType == GraphQLController::class.java }
            ?: return null
        return graphQLType.actualTypeArguments.singleOrNull() as? Class<*>
    }

    private fun Class<*>.instantiateRelaxed(): Any {
        val constructor = declaredConstructors.single()
        val arguments = constructor.parameterTypes.map(::constructorArgument).toTypedArray()
        return constructor.newInstance(*arguments)
    }

    private fun constructorArgument(type: Class<*>): Any = when (type) {
        Json::class.java -> Json
        String::class.java -> ""
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> mockkClass(type.kotlin, relaxed = true)
    }

    private companion object {
        const val CONTROLLER_PACKAGE = "bosca.workops.controller"
        const val CONTROLLER_PACKAGE_PATH = "bosca/workops/controller"
    }
}
