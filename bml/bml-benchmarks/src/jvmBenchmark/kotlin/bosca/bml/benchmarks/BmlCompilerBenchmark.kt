package bosca.bml.benchmarks

import bosca.bml.codegen.BmlCodeGenerator
import bosca.bml.parser.BmlParser
import bosca.bml.parser.Severity
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/** Measures the BML frontend separately from the runtime request path. */
@State(Scope.Benchmark)
open class BmlCompilerBenchmark {
    private val source = buildString {
        appendLine("<page route=\"/catalog\">")
        appendLine("  <script server provides=\"items\">(1..24).toList()</script>")
        appendLine("  <html><head><title>Catalog</title></head><body><main>")
        appendLine("    <h1>Catalog</h1><ul>")
        repeat(24) { index ->
            appendLine("      <li data-index=\"$index\"><strong>Item $index</strong><span>{ items[$index] }</span></li>")
        }
        appendLine("    </ul></main></body></html>")
        appendLine("</page>")
    }
    private lateinit var document: bosca.bml.parser.Document

    @Setup
    open fun setup() {
        val parsed = BmlParser.parse(source)
        check(parsed.diagnostics.none { it.severity == Severity.Error }) { parsed.diagnostics.toString() }
        document = parsed.document
        val generated = generator().generate(document)
        check(generated.diagnostics.none { it.severity == Severity.Error }) { generated.diagnostics.toString() }
    }

    @Benchmark
    open fun parsePage(): Int = BmlParser.parse(source).document.nodes.size

    @Benchmark
    open fun generatePage(): Int = generator().generate(document).source.length

    private fun generator(): BmlCodeGenerator =
        BmlCodeGenerator("bml.benchmark.generated", "CatalogPage", "catalog.bml")
}
