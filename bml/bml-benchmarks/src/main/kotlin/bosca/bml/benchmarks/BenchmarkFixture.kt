package bosca.bml.benchmarks

import bml.generated.BenchmarkPage
import bosca.bml.render.BmlPageRenderer

/** The compiled BML fixture used by both in-process and HTTP benchmarks. */
object BenchmarkFixture {
    val page: BmlPageRenderer = BenchmarkPage
}
