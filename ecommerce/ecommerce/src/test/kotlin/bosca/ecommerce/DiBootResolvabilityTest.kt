package bosca.ecommerce

import bosca.ecommerce.graphql.EcomController
import bosca.graphql.annotations.TypeController
import bosca.service.annotation.ServiceImplementation
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boot-resolvability guard (a missing provider crashes server startup). Every `@ServiceImplementation` and
 * `@TypeController` in this module is built by a KSP-generated provider that resolves each constructor
 * parameter from the DI registry — and the GraphQL dispatcher eagerly builds every controller at server
 * warmup. A constructor that depends on a **concrete class with no provider** (e.g. `ApplicationConfig`,
 * which is threaded through the bootstrap, never registered) compiles green but throws
 * `MissingProviderException` at boot, bricking startup — invisible to the mock-based unit tests.
 *
 * This asserts statically that every such constructor parameter is DI-resolvable: an interface (some
 * impl provides it) or one of the few framework-supplied concrete types the container injects directly.
 * A concrete, non-allowlisted parameter fails the build instead of the server. (It deliberately does not
 * try to prove a registered impl EXISTS for every interface — that is a different, cross-module concern;
 * it targets the concrete-no-provider class that actually bit us.)
 */
class DiBootResolvabilityTest {

    /**
     * Concrete (non-interface) constructor-parameter types that ARE DI-resolvable, so they must NOT be
     * flagged: framework-supplied instances plus concrete types registered by a `@Provider` /
     * `@ServiceImplementation` in this module or a depended one. (Verified provided — the server boots
     * with every one of these; the crash was `ApplicationConfig`, which is on none of these lists
     * and so stays flagged.) A genuinely new concrete-no-provider dependency is what this should catch.
     */
    private val resolvableConcrete = setOf(
        // Framework-injected without a @Provider.
        "bosca.server.BoscaApplication",
        "bosca.di.ObjectProvider",
        // Framework / cross-module concretes with a registered provider.
        "kotlinx.serialization.json.Json",
        "bosca.security.service.GroupEvaluator",
        "bosca.content.security.MetadataPermissionEvaluator",
        // Provided by this module's Configuration (@Provider fun cartAccessEvaluator).
        "bosca.ecommerce.graphql.CartAccessEvaluator",
    )

    @Test
    fun `every DI-constructed ecom class has only resolvable constructor dependencies`() {
        val classes = diConstructedClasses()
        // Sanity floor: a broken classpath walk would make this vacuously green.
        assertTrue(classes.size > 50, "expected many @ServiceImplementation/@TypeController classes, found ${classes.size}")

        val unresolvable = buildList {
            for (clazz in classes) {
                val ctor = clazz.declaredConstructors
                    .filter { !it.isSynthetic }
                    .maxByOrNull { it.parameterCount } ?: continue
                for (param in ctor.parameterTypes) {
                    if (param.isInterface || param.isEnum || param.isPrimitive) continue
                    if (param.name in resolvableConcrete) continue
                    add("${clazz.simpleName} -> ${param.name}")
                }
            }
        }

        assertTrue(
            unresolvable.isEmpty(),
            "DI-constructed ecom classes with a non-resolvable concrete constructor dependency — no provider " +
                "exists, so the generated provider throws MissingProviderException at server boot (this is the " +
                "ApplicationConfig failure mode). Inject an interface or a framework-supplied type: " +
                unresolvable.sorted(),
        )
    }

    /** Every `@ServiceImplementation` / `@TypeController` on the module classpath (RUNTIME-retained). */
    private fun diConstructedClasses(): List<Class<*>> {
        val root = File(EcomController::class.java.protectionDomain.codeSource.location.toURI())
        val loader = EcomController::class.java.classLoader
        return File(root, "bosca/ecommerce").walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name } // skip synthetic/nested
            .mapNotNull { file ->
                val fqcn = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                runCatching { Class.forName(fqcn, false, loader) }.getOrNull()
            }
            .filter {
                it.isAnnotationPresent(ServiceImplementation::class.java) || it.isAnnotationPresent(TypeController::class.java)
            }
            .filterNot { Modifier.isAbstract(it.modifiers) }
            .toList()
    }
}
