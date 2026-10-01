package bosca.server.netty

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Threads

/** Concurrent loopback throughput benchmarks using 64 persistent HTTP/1.1 connections. */
@State(Scope.Benchmark)
open class NettyHttpServerLoadBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.plaintext)

    @Benchmark
    @Threads(64)
    open fun preEncodedPlaintextAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.preEncodedPlaintext)

    @Benchmark
    @Threads(64)
    open fun post1KiBAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.post1KiB)

    @Benchmark
    @Threads(64)
    open fun compressed32KiBAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.compressed)

    @Benchmark
    @Threads(64)
    open fun compressedStreaming32KiBAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.compressedStreaming)

    @Benchmark
    @Threads(64)
    open fun incompressible32KiBAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.incompressible)
}
