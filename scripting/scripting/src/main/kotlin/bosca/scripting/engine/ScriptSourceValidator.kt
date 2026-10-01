package bosca.scripting.engine

/**
 * Pre-compilation source validator that uses an allowlist model for [System] method calls
 * and blocks dangerous references that cannot be caught at the ClassLoader level.
 *
 * [java.lang.System] cannot be blocked by [RestrictedClassLoader] because the JVM requires it
 * for bootstrap operations (lambdas, string concatenation, etc.). Instead of denying specific
 * dangerous methods (which is trivially bypassable via aliasing, backticks, or method references),
 * this validator only allows explicitly safe [System] usages and rejects everything else.
 *
 * Validation phases:
 * 1. Block import aliasing of [System] (e.g. `import java.lang.System as Sys`)
 * 2. Block method references to [System] (e.g. `System::exit`)
 * 3. Allowlist check: every `System` occurrence must be followed by `.safeMethod`
 * 4. Block [Runtime] references (defense-in-depth alongside ClassLoader blocking)
 */
class ScriptSourceValidatorImpl : ScriptSourceValidator {

    private val ALLOWED_SYSTEM_METHODS = setOf(
        "currentTimeMillis",
        "nanoTime",
        "lineSeparator",
        "identityHashCode",
        "arraycopy",
    )

    private val SYSTEM_IMPORT_ALIAS = Regex("""import\s+.*\bSystem\s+as\s+""")
    private val SYSTEM_METHOD_REF = Regex("""\bSystem\s*::\s*""")
    private val SYSTEM_USAGE = Regex("""\bSystem\b""")
    private val DOT_METHOD = Regex("""^\s*\.\s*(\w+)\b""")
    private val DOT_BACKTICK_METHOD = Regex("""^\s*\.\s*`([^`]+)`""")
    private val RUNTIME_USAGE = Regex("""\bRuntime\b""")

    /**
     * Validates the script source for dangerous method calls and references.
     *
     * @throws SecurityException if the source contains a blocked call or reference
     */
    override fun validate(source: String) {
        validateNoImportAliasing(source)
        validateNoMethodReferences(source)
        validateSystemUsages(source)
        validateNoRuntimeReference(source)
    }

    private fun validateNoImportAliasing(source: String) {
        if (SYSTEM_IMPORT_ALIAS.containsMatchIn(source)) {
            throw SecurityException("Script source contains blocked import alias of System")
        }
    }

    private fun validateNoMethodReferences(source: String) {
        val match = SYSTEM_METHOD_REF.find(source) ?: return
        throw SecurityException(
            "Script source contains blocked method reference '${match.value.replace(Regex("\\s+"), "")}'"
        )
    }

    private fun validateSystemUsages(source: String) {
        for (match in SYSTEM_USAGE.findAll(source)) {
            val after = source.substring(match.range.last + 1)

            val dotMatch = DOT_METHOD.find(after)
            if (dotMatch != null) {
                val methodName = dotMatch.groupValues[1]
                if (methodName in ALLOWED_SYSTEM_METHODS) continue
                throw SecurityException(
                    "Script source contains blocked call 'System.$methodName'"
                )
            }

            val backtickMatch = DOT_BACKTICK_METHOD.find(after)
            if (backtickMatch != null) {
                val methodName = backtickMatch.groupValues[1]
                if (methodName in ALLOWED_SYSTEM_METHODS) continue
                throw SecurityException(
                    "Script source contains blocked call 'System.`$methodName`'"
                )
            }

            throw SecurityException(
                "Script source contains blocked bare reference to 'System'"
            )
        }
    }

    private fun validateNoRuntimeReference(source: String) {
        if (RUNTIME_USAGE.containsMatchIn(source)) {
            throw SecurityException("Script source contains blocked reference to 'Runtime'")
        }
    }
}
