package bosca.scripting.engine

/**
 * A restricted ClassLoader that only allows scripts to load classes from explicitly permitted packages.
 *
 * Uses an allowlist model:
 * - Allowed prefixes come from [ScriptingSecurityConfiguration] and are immutable at runtime
 * - A hard-coded always-denied set blocks dangerous classes even if they fall within an allowed prefix
 *   (e.g. java.lang.Runtime is blocked despite java.lang.* being allowed)
 */
class RestrictedClassLoader(
    parent: ClassLoader,
    initialAllowedPrefixes: Collection<String> = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES
) : ClassLoader(parent) {

    private val allowedPrefixes = initialAllowedPrefixes.toSet()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isAlwaysDenied(name)) {
            throw SecurityException("Access to class '$name' is not allowed in Bosca scripts")
        }
        if (!isAllowed(name)) {
            throw SecurityException("Access to class '$name' is not allowed in Bosca scripts")
        }
        return super.loadClass(name, resolve)
    }

    private fun isAlwaysDenied(className: String): Boolean {
        if (className in ALWAYS_DENIED_EXACT) return true
        return ALWAYS_DENIED_PREFIXES.any { className.startsWith(it) }
    }

    private fun isAllowed(className: String): Boolean {
        // Classes without a package (no dot) are compiled script classes
        if (!className.contains('.')) return true
        // JVM/tooling infrastructure injected at the bytecode level (coverage, debugging)
        if (JVM_INFRASTRUCTURE_PREFIXES.any { className.startsWith(it) }) return true
        return allowedPrefixes.any { className.startsWith(it) }
    }

    fun getAllowedPrefixes(): Set<String> = allowedPrefixes.toSet()

    companion object {

        private val ALWAYS_DENIED_PREFIXES = arrayOf(
            "java.lang.ProcessBuilder",
            "java.lang.reflect.",
            "java.lang.ClassLoader",
            "java.io.",
            "java.nio.",
            "java.net.",
            "javax.script.",
            "javax.net.",
            "kotlin.reflect.full.",
            "kotlin.reflect.jvm.",
            "sun.misc.",
            "sun.reflect.",
            "sun.net.",
            "sun.nio.",
            "sun.security.",
            "jdk.internal.",
        )

        // Note: java.lang.System cannot be blocked at the ClassLoader level because the JVM
        // requires it for bootstrap operations (lambdas, string concatenation, etc.).
        // System.exit() and System.getenv() risks should be mitigated via execution timeouts
        // and environment variable hygiene at the deployment level.
        private val ALWAYS_DENIED_EXACT = arrayOf(
            "java.lang.Runtime",
            "java.lang.Thread",
        )

        // JVM infrastructure classes needed for bytecode-level operations (lambdas, invokedynamic,
        // coverage tools, debuggers). These bypass the allowlist but are still checked against
        // ALWAYS_DENIED_PREFIXES above. Only specific sub-packages are allowed rather than broad
        // "jdk." / "sun." prefixes to prevent access to jdk.net.*, jdk.management.*, etc.
        private val JVM_INFRASTRUCTURE_PREFIXES = arrayOf(
            "jdk.dynalink.",
            "jdk.jfr.",
            "sun.invoke.",
            "sun.launcher.",
            "com.intellij.rt.",
            "org.jacoco.",
        )
    }
}
