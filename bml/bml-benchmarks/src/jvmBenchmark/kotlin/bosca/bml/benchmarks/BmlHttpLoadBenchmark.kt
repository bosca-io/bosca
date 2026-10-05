package bosca.bml.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Threads

/** Concurrent loopback requests through the production BML routes and a static HTTP control. */
@State(Scope.Benchmark)
open class BmlHttpLoadBenchmark {
    private val server = BmlHttpBenchmark()

    @Setup
    open fun setup() = server.setup()

    @TearDown
    open fun tearDown() = server.tearDown()

    @Benchmark
    @Threads(64)
    open fun staticControl(client: BmlHttpLoadClient): Int = client.exchange(server.port, CONTROL_REQUEST)

    @Benchmark
    @Threads(64)
    open fun privatePage(client: BmlHttpLoadClient): Int = client.exchange(server.port, PRIVATE_REQUEST)

    @Benchmark
    @Threads(64)
    open fun sharedPage(client: BmlHttpLoadClient): Int = client.exchange(server.port, SHARED_REQUEST)

    @Benchmark
    @Threads(64)
    open fun deferredFragment(client: BmlHttpLoadClient): Int = client.exchange(server.port, DEFERRED_REQUEST)

    private companion object {
        val CONTROL_REQUEST = request("GET", "/benchmark-control")
        val PRIVATE_REQUEST = request("GET", "/private")
        val SHARED_REQUEST = request("GET", "/benchmark")
        val DEFERRED_REQUEST = request(
            "POST",
            "/_bml/deferred/benchmark%3Afragment",
            """{"props":{"label":"Bench <&>"},"page":"/deferred","path":"/deferred","query":{},"locale":"en"}"""
                .toByteArray(Charsets.UTF_8),
        )

        fun request(method: String, path: String, body: ByteArray? = null): ByteArray {
            val headers = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                if (body != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                }
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            return if (body == null) headers else headers + body
        }
    }
}
