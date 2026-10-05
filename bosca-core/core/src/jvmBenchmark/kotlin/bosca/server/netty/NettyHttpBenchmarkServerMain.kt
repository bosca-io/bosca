package bosca.server.netty

import java.util.concurrent.CountDownLatch

/** Runs the benchmark server in its own JVM for external load generators such as k6. */
object NettyHttpBenchmarkServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val requestedPort = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        val server = NettyHttpBenchmarkServer(requestedPort)
        server.start()
        println("Bosca Netty benchmark server listening on http://127.0.0.1:${server.port}")

        Runtime.getRuntime().addShutdownHook(
            Thread({ server.stop() }, "netty-http-benchmark-shutdown"),
        )
        CountDownLatch(1).await()
    }

    private const val DEFAULT_PORT = 9090
}
