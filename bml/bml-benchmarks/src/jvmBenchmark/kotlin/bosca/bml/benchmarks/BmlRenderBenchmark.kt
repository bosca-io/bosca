package bosca.bml.benchmarks

import bosca.bml.render.RenderContext
import bosca.bml.render.deferredPropsToJson
import bosca.bml.render.parseDeferredRenderRequest
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/** Measures a compiler-generated page and the private-fragment prop wire format. */
@State(Scope.Benchmark)
open class BmlRenderBenchmark {
    private val props = linkedMapOf<String, Any?>(
        "title" to "List <&>",
        "ids" to listOf(41L, 42L, 43L),
        "settings" to linkedMapOf("2" to true, "10" to false),
    )
    private val requestBody =
        """{"props":${deferredPropsToJson(props)},"page":"/benchmark","path":"/benchmark","query":{},"locale":"en"}"""

    @Benchmark
    open fun renderCompiledPage(): Int = runBlocking {
        val context = RenderContext()
        BenchmarkFixture.page.render(context)
        context.writer.length
    }

    @Benchmark
    open fun encodeDeferredProps(): String = deferredPropsToJson(props)

    @Benchmark
    open fun decodeDeferredRequest(): Int =
        parseDeferredRenderRequest(requestBody)?.props?.size ?: error("benchmark request was rejected")
}
