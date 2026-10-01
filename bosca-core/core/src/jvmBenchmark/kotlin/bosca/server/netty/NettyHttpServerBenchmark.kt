package bosca.server.netty

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/** Loopback HTTP/1.1 latency benchmarks through the complete production Netty pipeline. */
@State(Scope.Benchmark)
open class NettyHttpServerBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.plaintext)

    @Benchmark
    open fun preEncodedPlaintext(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.preEncodedPlaintext)

    @Benchmark
    open fun pathParameter(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.pathParameter)

    @Benchmark
    open fun post1KiB(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.post1KiB)

    @Benchmark
    open fun compressed32KiB(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.compressed)

    @Benchmark
    open fun compressedStreaming32KiB(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.compressedStreaming)

    @Benchmark
    open fun incompressible32KiB(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.incompressible)
}
