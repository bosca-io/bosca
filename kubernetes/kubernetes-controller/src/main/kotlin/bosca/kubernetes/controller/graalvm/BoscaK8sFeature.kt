package bosca.kubernetes.controller.graalvm

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * GraalVM native-image feature that registers fabric8 kubernetes-client model classes
 * for reflection. Mirrors Quarkus's `KubernetesClientProcessor` and Spring Native's
 * `Fabric8NativeConfiguration` — both reference implementations for native fabric8.
 *
 * Without this, native-image strips Jackson-introspectable metadata from every fabric8
 * DTO and the first deserialization fails with "cannot deserialize from Object value
 * (no delegate- or property-based Creator): this appears to be a native image..."
 *
 * Discovery order (each step adds to a single deduped registration set):
 *
 *  1. Hardcoded baseline (kubeconfig DTOs + Quarkus's helper list) — guaranteed coverage
 *     of the kubeconfig parse path that fails immediately on cluster registration.
 *  2. SPI scan of `META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource` —
 *     ~316 top-level K8s resources contributed across model jars.
 *  3. Jar enumeration of fabric8 model jars — catches nested DTOs and anything not
 *     mentioned in the SPI file.
 *  4. `findAnnotatedClasses(@JsonDeserialize)` / `findAnnotatedMethods(@JsonCreator)`
 *     filtered to `io.fabric8.*` — catches outliers and walks `using`/`builder`
 *     references on `@JsonDeserialize` to register custom (de)serializer classes.
 *  5. Runtime-init of `io.fabric8.kubernetes.client.utils.Utils` — its static init
 *     reads OS state we don't want frozen into the image.
 *
 * Classloader: uses `access.applicationClassLoader` everywhere — Features run in the
 * native-image driver JVM where `Thread.currentThread().contextClassLoader` and
 * `System.getProperty("java.class.path")` reflect the *driver* classpath, not the
 * *image* classpath. The application classloader is the one that sees fabric8 jars.
 *
 * Self-check: prints a loud warning if `io.fabric8.kubernetes.api.model.Config` isn't
 * in the registered set when `beforeAnalysis` returns — that's the single class whose
 * absence reliably reproduces the kubeconfig deserialization failure.
 */
class BoscaK8sFeature : Feature {

    private val registered = mutableSetOf<String>()
    private val verbose = System.getProperty("bosca.k8s.feature.verbose", "false").toBoolean()

    override fun getDescription(): String = "Bosca Kubernetes Feature (fabric8 reflection)"

    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        println("[BoscaK8sFeature] beforeAnalysis start (classloader=${access.applicationClassLoader.javaClass.name})")

        val baselineCount = registerBaseline(access)
        val spiCount = registerFromServiceFiles(access, SPI_KUBERNETES_RESOURCE)
        val jarCount = registerFromFabric8Jars(access)
        val annotationCount = registerJacksonAnnotatedFabric8Classes(access)
        registerRuntimeInitialized(access)

        println(
            "[BoscaK8sFeature] Registered ${registered.size} fabric8 classes for reflection " +
                "(baseline=$baselineCount, spi=$spiCount, jarScan=$jarCount, annotations=$annotationCount)"
        )

        // Self-check: if Config isn't registered the build will silently produce an
        // image that fails on first kubeconfig parse. Make that failure visible at
        // build time instead.
        if (CRITICAL_CLASSES.any { it !in registered }) {
            val missing = CRITICAL_CLASSES.filterNot { it in registered }
            println("[BoscaK8sFeature] !!! CRITICAL: missing registrations for: $missing")
            println("[BoscaK8sFeature] !!! The native image will fail to parse kubeconfig at runtime.")
        }
    }

    /**
     * Hardcoded baseline that always registers regardless of discovery results.
     * Covers the kubeconfig DTOs (not in any SPI file) plus Quarkus's explicit helper
     * list. If discovery silently no-ops, this still keeps the kubeconfig parse path
     * working.
     */
    private fun registerBaseline(access: Feature.BeforeAnalysisAccess): Int {
        var count = 0
        for (name in KUBECONFIG_DTOS + EXPLICIT_HELPERS) {
            if (register(access, name)) count++
        }
        return count
    }

    /**
     * Reads every `META-INF/services/<spiName>` file from the application classpath,
     * parses the listed class names (ignoring comments and blanks), registers each.
     */
    private fun registerFromServiceFiles(access: Feature.BeforeAnalysisAccess, spiName: String): Int {
        val cl = access.applicationClassLoader
        val urls = try {
            cl.getResources("META-INF/services/$spiName").toList()
        } catch (e: Exception) {
            println("[BoscaK8sFeature] Could not enumerate $spiName: ${e.message}")
            return 0
        }
        if (urls.isEmpty()) {
            println("[BoscaK8sFeature] No SPI files found for $spiName on application classpath")
        }
        var count = 0
        for (url in urls) {
            url.openStream().bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val name = raw.substringBefore('#').trim()
                    if (name.isEmpty()) continue
                    if (register(access, name)) count++
                }
            }
        }
        return count
    }

    /**
     * Walks fabric8 model + client jars and enumerates `.class` entries in fabric8
     * model packages.
     *
     * Jar discovery: derive jar paths from SPI URLs returned by the application
     * classloader (`META-INF/services/<spiName>` lives inside each model jar; the URL
     * is `jar:file:/path/to/jar!/META-INF/...`, which we parse). Also probe a known
     * fabric8 class's `ProtectionDomain.codeSource.location` to catch jars that don't
     * publish SPI files (kubernetes-client itself).
     *
     * This sidesteps `NativeImageClassLoader` not exposing URLs the way `URLClassLoader`
     * does — `java.class.path` is the driver JVM's classpath, not the image classpath,
     * so it's useless here.
     */
    private fun registerFromFabric8Jars(access: Feature.BeforeAnalysisAccess): Int {
        val jarPaths = collectFabric8JarPaths(access)
        if (jarPaths.isEmpty()) {
            println("[BoscaK8sFeature] No fabric8 jars discovered for jar scan")
            return 0
        }
        var count = 0
        for (jar in jarPaths) {
            try {
                JarFile(jar).use { jf ->
                    for (entry in jf.entries()) {
                        val name = entry.name
                        if (!name.endsWith(".class")) continue
                        if (!isFabric8ModelEntry(name)) continue
                        val className = name.removeSuffix(".class").replace('/', '.')
                        if (shouldSkip(className)) continue
                        if (register(access, className)) count++
                    }
                }
            } catch (e: Exception) {
                println("[BoscaK8sFeature] Failed to scan ${jar.name}: ${e.message}")
            }
        }
        println("[BoscaK8sFeature] Jar scan: ${jarPaths.size} fabric8 jars walked")
        return count
    }

    private fun collectFabric8JarPaths(access: Feature.BeforeAnalysisAccess): List<File> {
        val collected = linkedSetOf<File>()
        val cl = access.applicationClassLoader

        // Discover via SPI URLs — every fabric8 model jar publishes
        // META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource.
        for (spi in listOf(
            SPI_KUBERNETES_RESOURCE,
            "io.fabric8.kubernetes.client.ServiceToURLProvider",
            "io.fabric8.kubernetes.client.http.HttpClient\$Factory",
        )) {
            val urls = try { cl.getResources("META-INF/services/$spi").toList() } catch (_: Exception) { emptyList() }
            for (url in urls) {
                jarFileOf(url)?.let { collected.add(it) }
            }
        }

        // Also probe known fabric8 classes' code-source locations — covers
        // kubernetes-client.jar and similar jars that don't publish SPI files
        // we look up, by piggy-backing on classes already loaded above.
        for (probe in listOf(
            "io.fabric8.kubernetes.client.Config",
            "io.fabric8.kubernetes.client.KubernetesClient",
            "io.fabric8.kubernetes.api.model.Pod",
            "io.fabric8.kubernetes.api.model.Node",
        )) {
            val c = try { Class.forName(probe, false, cl) } catch (_: Throwable) { null } ?: continue
            val location = c.protectionDomain?.codeSource?.location ?: continue
            jarFileOf(location)?.let { collected.add(it) }
        }

        // Fallback: walk URLClassLoader chain if exposed (covers older GraalVMs).
        if (collected.isEmpty()) {
            var loader: ClassLoader? = cl
            while (loader != null) {
                if (loader is URLClassLoader) {
                    for (url in loader.urLs) {
                        jarFileOf(url)?.takeIf { isFabric8Jar(it.name) }?.let { collected.add(it) }
                    }
                }
                loader = loader.parent
            }
        }

        return collected.toList()
    }

    /** Parses a jar URL like `jar:file:/path/x.jar!/...` or `file:/path/x.jar` into a File. */
    private fun jarFileOf(url: URL): File? {
        val spec = url.toString()
        val raw = when {
            spec.startsWith("jar:") -> spec.substring(4).substringBefore("!/")
            else -> spec
        }
        return try {
            val f = File(URI(raw))
            if (f.isFile && f.extension == "jar") f else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isFabric8Jar(jarFileName: String): Boolean =
        jarFileName.startsWith("kubernetes-model-") ||
            jarFileName.startsWith("kubernetes-client-") ||
            jarFileName == "kubernetes-client.jar"

    private fun isFabric8ModelEntry(entryName: String): Boolean =
        entryName.startsWith("io/fabric8/kubernetes/api/model/") ||
            entryName.startsWith("io/fabric8/kubernetes/internal/") ||
            entryName.startsWith("io/fabric8/kubernetes/client/Config\$") ||
            entryName.startsWith("io/fabric8/kubernetes/client/VersionInfo") ||
            entryName.startsWith("io/fabric8/kubernetes/client/utils/OpenIDConnectionUtils\$")

    /**
     * Mirrors `bosca.graalvm.BoscaFeature.registerJacksonAnnotatedClasses` but filtered
     * to `io.fabric8.*`. Best-effort: `findAnnotatedClasses`/`findAnnotatedMethods` live
     * on `BeforeAnalysisAccess`'s internal impl (`FeatureImpl$FeatureAccessImpl`) in
     * GraalVM 25.x, not on the public interface, and the impl module isn't exported —
     * so reflection access is blocked. We fall through silently when that happens; the
     * jar scan + SPI + hardcoded baseline cover the actual deserialization needs.
     */
    private fun registerJacksonAnnotatedFabric8Classes(access: Feature.BeforeAnalysisAccess): Int {
        var count = 0
        count += findAndRegisterAnnotated(access, "com.fasterxml.jackson.databind.annotation.JsonDeserialize", isMethod = false)
        count += findAndRegisterAnnotated(access, "com.fasterxml.jackson.annotation.JsonCreator", isMethod = true)
        return count
    }

    private fun findAndRegisterAnnotated(
        access: Feature.BeforeAnalysisAccess,
        annotationFqn: String,
        isMethod: Boolean,
    ): Int {
        @Suppress("UNCHECKED_CAST")
        val annotation = try {
            Class.forName(annotationFqn) as Class<out Annotation>
        } catch (_: ClassNotFoundException) {
            return 0
        }
        val methodName = if (isMethod) "findAnnotatedMethods" else "findAnnotatedClasses"
        val found: List<*> = try {
            val m = access.javaClass.getMethod(methodName, Class::class.java)
            (m.invoke(access, annotation) as? List<*>) ?: return 0
        } catch (_: Throwable) {
            // Module access blocked in GraalVM 25.x; best-effort, silent fall-through.
            return 0
        }
        var count = 0
        for (item in found) {
            val clazz: Class<*>? = when (item) {
                is Class<*> -> item
                is java.lang.reflect.Method -> item.declaringClass
                else -> null
            }
            if (clazz == null) continue
            if (!clazz.name.startsWith("io.fabric8.")) continue
            if (shouldSkip(clazz.name)) continue
            if (register(access, clazz.name)) count++
            if (!isMethod) {
                for (referenced in deserializeReferencedClasses(clazz)) {
                    if (register(access, referenced)) count++
                }
            }
        }
        return count
    }

    /**
     * Pulls the `using = X.class` and `builder = Y.class` targets from a
     * `@JsonDeserialize` annotation. Skips Jackson's `None` sentinels.
     */
    private fun deserializeReferencedClasses(clazz: Class<*>): List<String> {
        val annotation = clazz.declaredAnnotations.firstOrNull {
            it.annotationClass.qualifiedName == "com.fasterxml.jackson.databind.annotation.JsonDeserialize"
        } ?: return emptyList()
        val results = mutableListOf<String>()
        for (attr in listOf("using", "builder", "as", "contentAs", "keyAs", "contentUsing", "keyUsing")) {
            val target = try {
                annotation.javaClass.getMethod(attr).invoke(annotation) as? Class<*>
            } catch (_: Exception) {
                null
            } ?: continue
            val name = target.name
            if (name.endsWith("\$None")) continue
            if (name == "java.lang.Void") continue
            results.add(name)
        }
        return results
    }

    private fun registerRuntimeInitialized(access: Feature.BeforeAnalysisAccess) {
        val cls = loadClass(access, "io.fabric8.kubernetes.client.utils.Utils") ?: return
        RuntimeClassInitialization.initializeAtRunTime(cls)
    }

    private fun shouldSkip(name: String): Boolean =
        name.endsWith("Fluent") ||
            name.endsWith("FluentImpl") ||
            name.endsWith("Doneable") ||
            name.endsWith("Predicates") ||
            name.endsWith("Visitors") ||
            name.endsWith("package-info")

    private fun register(access: Feature.BeforeAnalysisAccess, className: String): Boolean {
        if (!registered.add(className)) return false
        val clazz = loadClass(access, className) ?: return false
        if (clazz.isPrimitive) return false
        registerClassThoroughly(clazz)
        if (verbose) println("[BoscaK8sFeature]   + $className")
        return true
    }

    private fun loadClass(access: Feature.BeforeAnalysisAccess, className: String): Class<*>? {
        // Prefer the image classloader (findClassByName) — it sees image classes; fall
        // back to Class.forName via the application classloader for inner classes etc.
        val byName = try { access.findClassByName(className) } catch (_: Throwable) { null }
        if (byName != null) return byName
        return try {
            Class.forName(className, false, access.applicationClassLoader)
        } catch (_: ClassNotFoundException) {
            if (verbose) println("[BoscaK8sFeature]   SKIP (not found): $className")
            null
        } catch (_: NoClassDefFoundError) {
            if (verbose) println("[BoscaK8sFeature]   SKIP (missing dep): $className")
            null
        }
    }

    /**
     * Mirrors `bosca.graalvm.BoscaFeature.registerClassThoroughly`. Critical:
     * `registerForReflectiveInstantiation` is the primitive that retains the
     * default-constructor *instantiation pathway* native-image otherwise strips,
     * even when constructor *metadata* is registered.
     */
    private fun registerClassThoroughly(clazz: Class<*>) {
        RuntimeReflection.registerClassLookup(clazz.name)
        RuntimeReflection.register(clazz)

        for (constructor in clazz.declaredConstructors) {
            RuntimeReflection.registerConstructorLookup(clazz, *constructor.parameterTypes)
            RuntimeReflection.register(constructor)
        }
        for (method in clazz.declaredMethods) {
            RuntimeReflection.registerMethodLookup(clazz, method.name, *method.parameterTypes)
            RuntimeReflection.register(method)
        }
        for (field in clazz.declaredFields) {
            RuntimeReflection.registerFieldLookup(clazz, field.name)
            RuntimeReflection.register(field)
        }
        for (innerClass in clazz.declaredClasses) {
            RuntimeReflection.register(innerClass)
        }

        RuntimeReflection.registerAllDeclaredConstructors(clazz)
        RuntimeReflection.registerAllDeclaredMethods(clazz)
        RuntimeReflection.registerAllDeclaredFields(clazz)
        RuntimeReflection.registerAllDeclaredClasses(clazz)

        try {
            RuntimeReflection.registerForReflectiveInstantiation(clazz)
        } catch (_: IllegalArgumentException) {
            // Abstract classes and interfaces cannot be reflectively instantiated.
        }
    }

    companion object {
        private const val SPI_KUBERNETES_RESOURCE = "io.fabric8.kubernetes.api.model.KubernetesResource"

        /**
         * The kubeconfig DTOs — NOT listed in the KubernetesResource SPI file (kubeconfig
         * isn't a Kubernetes API resource) but still required by `Config.fromKubeconfig`.
         */
        private val KUBECONFIG_DTOS = listOf(
            "io.fabric8.kubernetes.api.model.Config",
            "io.fabric8.kubernetes.api.model.Cluster",
            "io.fabric8.kubernetes.api.model.NamedCluster",
            "io.fabric8.kubernetes.api.model.Context",
            "io.fabric8.kubernetes.api.model.NamedContext",
            "io.fabric8.kubernetes.api.model.AuthInfo",
            "io.fabric8.kubernetes.api.model.NamedAuthInfo",
            "io.fabric8.kubernetes.api.model.AuthProviderConfig",
            "io.fabric8.kubernetes.api.model.ExecConfig",
            "io.fabric8.kubernetes.api.model.ExecEnvVar",
            "io.fabric8.kubernetes.api.model.Preferences",
            "io.fabric8.kubernetes.api.model.NamedExtension",
        )

        private val EXPLICIT_HELPERS = listOf(
            "io.fabric8.kubernetes.api.model.AnyType",
            "io.fabric8.kubernetes.api.model.IntOrString",
            "io.fabric8.kubernetes.internal.KubernetesDeserializer",
            "io.fabric8.kubernetes.client.VersionInfo",
            "io.fabric8.kubernetes.client.impl.KubernetesClientImpl",
            "io.fabric8.kubernetes.client.Config\$ExecCredential",
            "io.fabric8.kubernetes.client.Config\$ExecCredentialSpec",
            "io.fabric8.kubernetes.client.Config\$ExecCredentialStatus",
            "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils\$OpenIdConfiguration",
            "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils\$OAuthToken",
        )

        /**
         * Classes whose absence causes the kubeconfig parse path to fail at runtime
         * with the "no delegate- or property-based Creator" Jackson error. Used by the
         * self-check at the end of `beforeAnalysis`.
         */
        private val CRITICAL_CLASSES = listOf(
            "io.fabric8.kubernetes.api.model.Config",
            "io.fabric8.kubernetes.api.model.Cluster",
            "io.fabric8.kubernetes.api.model.NamedCluster",
            "io.fabric8.kubernetes.api.model.Context",
            "io.fabric8.kubernetes.api.model.NamedContext",
            "io.fabric8.kubernetes.api.model.AuthInfo",
            "io.fabric8.kubernetes.api.model.NamedAuthInfo",
        )
    }
}
