package bosca.scripting.engine

interface ScriptSourceValidator {

    /**
     * Validates the script source for dangerous method calls and references.
     *
     * @throws SecurityException if the source contains a blocked call or reference
     */
    fun validate(source: String)
}