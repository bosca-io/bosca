package bosca.design.example

/** Local source text for viewer, fallback, language and layout checks. */
object SourceSamples {
    val short: String = "fun greeting() = \"Hello\""
    val medium: String = (1..5).joinToString("\n") { "line $it" }
    val long: String = buildString {
        appendLine("// Application-owned source")
        appendLine("export function greeting(name: string) {")
        appendLine("  return \"Hello, \" + name;")
        appendLine("}")
        appendLine("const markup = \"<script>unsafe()</script>\";")
        for (line in 6..80) appendLine("const row$line = \"${"A long source line remains readable and selectable. ".repeat(4)}\";")
    }.trimEnd()
}
