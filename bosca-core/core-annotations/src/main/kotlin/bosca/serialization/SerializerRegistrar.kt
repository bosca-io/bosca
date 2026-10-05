package bosca.serialization

/**
 * Registry interface for KSP-generated classes that provide compile-time
 * serializer bindings for GraalVM native image compatibility.
 *
 * Each module processed by KSP generates an implementation that directly
 * calls `MyClass.serializer()` (a compiled static method, not reflection)
 * and stores the result in [SerializerCache]. This ensures that
 * serializers are available at runtime without kotlinx.serialization's
 * reflective `KClass.serializer()` lookup, which fails in native images.
 *
 * Implementations also expose the fully qualified class names of all
 * registered `@Serializable` types so that [BoscaFeature][bosca.graalvm.BoscaFeature]
 * can perform thorough GraalVM reflection registration at native image build time.
 */
interface SerializerRegistrar {

    /**
     * Populates [SerializerCache] with serializer instances for every
     * `@Serializable` class discovered in this module at compile time.
     */
    fun register()

    /**
     * Returns the fully qualified class names of all `@Serializable` types
     * in this module, used by [BoscaFeature][bosca.graalvm.BoscaFeature] to
     * register comprehensive reflection metadata at native image build time.
     */
    fun getSerializableClassNames(): List<String>
}
