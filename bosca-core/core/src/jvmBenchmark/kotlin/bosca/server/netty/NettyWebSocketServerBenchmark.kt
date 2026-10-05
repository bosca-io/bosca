package bosca.server.netty

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Threads

/** Persistent RFC 6455 text-frame round trips through Bosca's complete production pipeline. */
@State(Scope.Benchmark)
open class NettyWebSocketServerBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun textEcho(client: NettyWebSocketBenchmarkClient): Int = client.exchange(server.port)
}

/** 64 concurrent persistent RFC 6455 sessions through Bosca's production pipeline. */
@State(Scope.Benchmark)
open class NettyWebSocketServerLoadBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64Connections(client: NettyWebSocketBenchmarkClient): Int = client.exchange(server.port)
}

/** Persistent RFC 8441 text-frame round trips through Bosca's complete production pipeline. */
@State(Scope.Benchmark)
open class NettyRfc8441ServerBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun textEcho(client: NettyRfc8441BenchmarkClient): Int = client.exchange(server.port)
}

/** 64 persistent RFC 8441 sessions multiplexed over one or four HTTP/2 connections. */
@State(Scope.Benchmark)
open class NettyRfc8441ServerLoadBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64StreamsOnOneConnection(client: NettyRfc8441BenchmarkClient): Int =
        client.exchange(server.port)

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64StreamsOnFourConnections(client: NettyRfc8441FourConnectionBenchmarkClient): Int =
        client.exchange(server.port)
}

/** Minimal Netty RFC 6455 control using the same persistent client as Bosca's latency profile. */
@State(Scope.Benchmark)
open class RawNettyWebSocketServerBenchmark {
    private val server = RawNettyWebSocketBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun textEcho(client: NettyWebSocketBenchmarkClient): Int = client.exchange(server.port)
}

/** Minimal Netty RFC 6455 control with 64 persistent connections. */
@State(Scope.Benchmark)
open class RawNettyWebSocketServerLoadBenchmark {
    private val server = RawNettyWebSocketBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64Connections(client: NettyWebSocketBenchmarkClient): Int = client.exchange(server.port)
}

/** RFC 8441 transport-only control without Bosca routing, middleware, or coroutine dispatch. */
@State(Scope.Benchmark)
open class TransportOnlyRfc8441ServerBenchmark {
    private val server = TransportOnlyRfc8441BenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun textEcho(client: NettyRfc8441BenchmarkClient): Int = client.exchange(server.port)
}

/** RFC 8441 transport-only control with 64 streams over one or four HTTP/2 connections. */
@State(Scope.Benchmark)
open class TransportOnlyRfc8441ServerLoadBenchmark {
    private val server = TransportOnlyRfc8441BenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64StreamsOnOneConnection(client: NettyRfc8441BenchmarkClient): Int =
        client.exchange(server.port)

    @Benchmark
    @Threads(64)
    open fun textEchoAcross64StreamsOnFourConnections(client: NettyRfc8441FourConnectionBenchmarkClient): Int =
        client.exchange(server.port)
}
