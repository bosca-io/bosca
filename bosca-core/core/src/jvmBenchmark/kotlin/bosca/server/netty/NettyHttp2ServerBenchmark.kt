package bosca.server.netty

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Threads

/** Loopback HTTP/2 latency through the complete production Netty pipeline. */
@State(Scope.Benchmark)
open class NettyHttp2ServerBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/** Throughput from 64 concurrent HTTP/2 streams multiplexed over one or four persistent connections. */
@State(Scope.Benchmark)
open class NettyHttp2ServerLoadBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnOneConnection(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnFourConnections(client: NettyHttp2FourConnectionBenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}
