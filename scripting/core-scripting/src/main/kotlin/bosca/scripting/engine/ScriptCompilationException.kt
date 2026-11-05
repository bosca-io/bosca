package bosca.scripting.engine

/** Compiler diagnostics for invalid source, distinct from transport and infrastructure failures. */
class ScriptCompilationException(val diagnostics: List<String>) : IllegalArgumentException(diagnostics.joinToString("\n"))
