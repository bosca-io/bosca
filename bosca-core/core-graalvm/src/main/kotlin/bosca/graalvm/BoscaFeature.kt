package bosca.graalvm

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * GraalVM native image feature that registers comprehensive reflection metadata
 * for all kotlinx.serialization `@Serializable` classes discovered by KSP.
 *
 * KSP generates `SerializerRegistrar` implementations in each module that
 * list every `@Serializable` class name. This feature finds those registrars on the
 * classpath, retrieves the class names, and performs exhaustive reflection registration
 * so that kotlinx.serialization's runtime serializer lookup works in native images.
 *
 * At application startup the same registrars populate [SerializerCache][bosca.serialization.SerializerCache] with
 * pre-resolved serializer instances, giving runtime code a reflection-free lookup path.
 */
class BoscaFeature : Feature {

    private val registered = mutableSetOf<String>()
    private val verbose = System.getProperty("bosca.feature.verbose", "false").toBoolean()

    override fun getDescription(): String = "Bosca Feature"

    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        // Register @Serializable classes discovered by KSP
        val classNames = discoverSerializableClassNames(access)
        if (classNames.isNotEmpty()) {
            println("[BoscaFeature] Discovered ${classNames.size} @Serializable classes from KSP registrars")
            for (className in classNames.sorted()) {
                register(className)
            }
        } else {
            println("[BoscaFeature] No SerializerRegistrar implementations found on classpath")
        }

        // Register third-party classes that require reflection for Jackson/other deserialization
        registerTrinoClasses()
        registerCaffeineClasses()

        println("[BoscaFeature] Registered ${registered.size} classes for reflection")
    }

    /**
     * Registers Trino JDBC internal client classes that Jackson deserializes at runtime.
     * The Trino driver shades Jackson into `io.trino.jdbc.$internal.jackson` and its
     * client model classes need reflection for the `@JsonCreator` constructors to work.
     */
    private fun registerTrinoClasses() {
        val trinoClientClasses = listOf(
            "io.trino.jdbc.\$internal.client.QueryResults",
            "io.trino.jdbc.\$internal.client.QueryError",
            "io.trino.jdbc.\$internal.client.ErrorInfo",
            "io.trino.jdbc.\$internal.client.ErrorLocation",
            "io.trino.jdbc.\$internal.client.Column",
            "io.trino.jdbc.\$internal.client.StageStats",
            "io.trino.jdbc.\$internal.client.StageStats\$Builder",
            "io.trino.jdbc.\$internal.client.StatementStats",
            "io.trino.jdbc.\$internal.client.StatementStats\$Builder",
            "io.trino.jdbc.\$internal.client.Warning",
            "io.trino.jdbc.\$internal.client.Warning\$Code",
            "io.trino.jdbc.\$internal.client.ClientTypeSignature",
            "io.trino.jdbc.\$internal.client.ClientTypeSignatureParameter",
            "io.trino.jdbc.\$internal.client.ClientTypeSignatureParameter\$ParameterKind",
            "io.trino.jdbc.\$internal.client.ClientTypeSignatureParameter\$ClientTypeSignatureParameterDeserializer",
            "io.trino.jdbc.\$internal.client.NamedClientTypeSignature",
            "io.trino.jdbc.\$internal.client.FailureInfo",
            "io.trino.jdbc.\$internal.client.FailureException",
            "io.trino.jdbc.\$internal.client.NodeVersion",
            "io.trino.jdbc.\$internal.client.ServerInfo",
            "io.trino.jdbc.\$internal.client.ClientSelectedRole",
            "io.trino.jdbc.\$internal.client.ClientSelectedRole\$Type",
            "io.trino.jdbc.\$internal.client.RowFieldName",
            "io.trino.jdbc.\$internal.client.QueryDataJacksonModule",
            "io.trino.jdbc.\$internal.client.QueryDataJacksonModule\$Deserializer",
            "io.trino.jdbc.\$internal.client.JsonQueryData",
            "io.trino.jdbc.\$internal.client.TypedQueryData",
            "io.trino.jdbc.\$internal.client.spooling.EncodedQueryData",
            "io.trino.jdbc.\$internal.client.spooling.EncodedQueryData\$Builder",
            "io.trino.jdbc.\$internal.client.spooling.InlineSegment",
            "io.trino.jdbc.\$internal.client.spooling.SpooledSegment",
            "io.trino.jdbc.\$internal.client.spooling.DataAttributes",
            "io.trino.jdbc.\$internal.client.spooling.DataAttributes\$Builder",
            "io.trino.jdbc.\$internal.client.spooling.DataAttribute",
        )
        for (className in trinoClientClasses) {
            register(className)
        }
    }

    /**
     * Registers Caffeine cache internal classes that use `sun.misc.Unsafe` and runtime
     * reflection to instantiate dynamically-named node and buffer implementations.
     * Without these registrations, `NodeFactory.newFactory` throws
     * `IllegalStateException` because `Class.forName` fails for the generated class names.
     */
    private fun registerCaffeineClasses() {
        val caffeineClasses = listOf(
            "com.github.benmanes.caffeine.cache.BLCHeader\$DrainStatusRef",
            "com.github.benmanes.caffeine.cache.BaseMpscLinkedArrayQueueColdProducerFields",
            "com.github.benmanes.caffeine.cache.BaseMpscLinkedArrayQueueConsumerFields",
            "com.github.benmanes.caffeine.cache.BaseMpscLinkedArrayQueueProducerFields",
            "com.github.benmanes.caffeine.cache.BoundedLocalCache",
            "com.github.benmanes.caffeine.cache.StripedBuffer",
        )
        for (className in caffeineClasses) {
            register(className)
        }

        // Node implementation classes — Caffeine generates class names from feature acronyms:
        //   P=padded, S=strong ref, L=linked (access-order), M=maximum, W=write-expiry, A=access-expiry
        // Register all variants that might be selected at runtime based on cache configuration.
        val nodeClasses = listOf(
            "PS", "PSA", "PSAMS", "PSM", "PSMS", "PSMA", "PSMSA",
            "PSW", "PSWMS", "PSAW", "PSMW", "PSMSW", "PSMWA", "PSMSWA",
            "SSL", "SSLMS", "SSLMSA", "SSLMSW", "SSLMSWA", "SSLW",
            "SSMS", "SSMSA", "SSMSW", "SSW",
        )
        for (name in nodeClasses) {
            register("com.github.benmanes.caffeine.cache.$name")
        }
    }

    /**
     * Finds all KSP-generated `SerializerRegistrar` implementations on the classpath,
     * instantiates them, and collects the serializable class names they report.
     */
    private fun discoverSerializableClassNames(access: Feature.BeforeAnalysisAccess): Set<String> {
        val registrarInterface = try {
            Class.forName("bosca.serialization.SerializerRegistrar")
        } catch (_: ClassNotFoundException) {
            println("[BoscaFeature] SerializerRegistrar interface not found")
            return emptySet()
        }

        val getClassNamesMethod = try {
            registrarInterface.getDeclaredMethod("getSerializableClassNames")
        } catch (_: NoSuchMethodException) {
            return emptySet()
        }

        val allClassNames = mutableSetOf<String>()

        // Find all implementations by checking known generated class name pattern
        for (clazz in findImplementations(access, registrarInterface)) {
            try {
                val instance = clazz.getDeclaredConstructor().newInstance()
                @Suppress("UNCHECKED_CAST")
                val names = getClassNamesMethod.invoke(instance) as? List<String> ?: continue
                allClassNames.addAll(names)
                if (verbose) println("[BoscaFeature] Found registrar: ${clazz.name} (${names.size} classes)")
            } catch (e: Exception) {
                println("[BoscaFeature] Warning: Failed to load registrar ${clazz.name}: ${e.message}")
            }
        }

        return allClassNames
    }

    /**
     * Finds all classes on the analysis classpath that implement the given interface.
     */
    private fun findImplementations(access: Feature.BeforeAnalysisAccess, iface: Class<*>): List<Class<*>> {
        val results = mutableListOf<Class<*>>()
        // The access object can find subtype implementations
        try {
            val method = access.javaClass.getMethod("findSubclasses", Class::class.java)
            @Suppress("UNCHECKED_CAST")
            val subtypes = method.invoke(access, iface) as? List<Class<*>>
            if (subtypes != null) {
                results.addAll(subtypes)
            }
        } catch (_: Exception) {
            // Fallback not needed — findSubtypes is available on Feature.BeforeAnalysisAccess
        }
        return results
    }

    /**
     * Performs exhaustive reflection registration for a single class and its
     * serialization-related inner classes (Companion, `$$serializer`).
     *
     * Mirrors the thorough registration strategy that registers every constructor,
     * method, field, and inner class to ensure kotlinx.serialization's various
     * runtime lookup paths all succeed.
     */
    private fun register(className: String) {
        if (registered.contains(className)) return
        registered.add(className)

        val clazz = try {
            Class.forName(className)
        } catch (_: ClassNotFoundException) {
            if (verbose) println("[BoscaFeature]   SKIP (not found): $className")
            return
        }

        if (clazz.isPrimitive) return

        registerClassThoroughly(clazz)

        // Also register the Companion class and $$serializer inner class
        registerCompanion(clazz)
        registerGeneratedSerializer(clazz)

        if (verbose) println("[BoscaFeature]   - $className")
    }

    /**
     * Registers a class with every available GraalVM reflection registration method
     * to ensure all runtime reflection access patterns succeed.
     */
    private fun registerClassThoroughly(clazz: Class<*>) {
        RuntimeReflection.registerClassLookup(clazz.name)
        RuntimeReflection.register(clazz)

        for (constructor in clazz.constructors) {
            RuntimeReflection.registerConstructorLookup(clazz, *constructor.parameterTypes)
            RuntimeReflection.register(constructor)
        }
        for (constructor in clazz.declaredConstructors) {
            RuntimeReflection.registerConstructorLookup(clazz, *constructor.parameterTypes)
            RuntimeReflection.register(constructor)
        }

        for (method in clazz.methods) {
            RuntimeReflection.registerMethodLookup(clazz, method.name, *method.parameterTypes)
            RuntimeReflection.register(method)
        }
        for (method in clazz.declaredMethods) {
            RuntimeReflection.registerMethodLookup(clazz, method.name, *method.parameterTypes)
            RuntimeReflection.register(method)
        }

        for (field in clazz.fields) {
            RuntimeReflection.registerFieldLookup(clazz, field.name)
            RuntimeReflection.register(field)
        }
        for (field in clazz.declaredFields) {
            RuntimeReflection.registerFieldLookup(clazz, field.name)
            RuntimeReflection.register(field)
        }

        for (innerClass in clazz.declaredClasses) {
            RuntimeReflection.register(innerClass)
        }

        RuntimeReflection.registerAllDeclaredConstructors(clazz)
        RuntimeReflection.registerAllConstructors(clazz)
        RuntimeReflection.registerAllDeclaredMethods(clazz)
        RuntimeReflection.registerAllMethods(clazz)
        RuntimeReflection.registerAllDeclaredFields(clazz)
        RuntimeReflection.registerAllFields(clazz)
        RuntimeReflection.registerAllPermittedSubclasses(clazz)
        RuntimeReflection.registerAllNestMembers(clazz)
        RuntimeReflection.registerAllRecordComponents(clazz)
        RuntimeReflection.registerAllSigners(clazz)
        RuntimeReflection.registerAllDeclaredClasses(clazz)
        RuntimeReflection.registerAllClasses(clazz)

        try {
            RuntimeReflection.registerForReflectiveInstantiation(clazz)
        } catch (_: IllegalArgumentException) {
            // Abstract classes or interfaces cannot be instantiated
        }
    }

    private fun registerCompanion(clazz: Class<*>) {
        try {
            val companionField = clazz.getDeclaredField("Companion")
            val companionClass = companionField.type
            registerClassThoroughly(companionClass)
        } catch (_: NoSuchFieldException) {
            // No companion
        }
    }

    private fun registerGeneratedSerializer(clazz: Class<*>) {
        try {
            val serializerClass = Class.forName("${clazz.name}\$\$serializer", false, clazz.classLoader)
            registerClassThoroughly(serializerClass)
        } catch (_: ClassNotFoundException) {
            // No generated serializer — sealed interfaces use SealedClassSerializer
        }
    }
}
