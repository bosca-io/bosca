package bosca.server.netty

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Group
import org.openjdk.jmh.annotations.GroupThreads
import org.openjdk.jmh.annotations.Threads

/**
 * Measures how the request dispatcher copes with handlers that block their thread.
 *
 * `blocking10msAcross64Connections` shows the throughput ceiling a blocking call imposes. The
 * `mixed` group runs 32 clients against the blocking route alongside 32 plain-text clients; its
 * `plaintextMixed` result shows whether blocked requests starve unrelated ones.
 */
@State(Scope.Benchmark)
open class NettyHttpServerBlockingLoadBenchmark {
    private val server = NettyHttpBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun blocking10msAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.blocking)

    @Benchmark
    @Group("mixed")
    @GroupThreads(32)
    open fun blockingMixed(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.blocking)

    @Benchmark
    @Group("mixed")
    @GroupThreads(32)
    open fun plaintextMixed(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.plaintext)
}
