package bosca.git.server

import bosca.git.ui.server.installGitUiServer
import bosca.jmx.JmxModule
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import kotlinx.coroutines.runBlocking

internal fun startGitServer(args: Array<String>) {
    val configPath = args.firstOrNull { it.startsWith("-config=") }?.substringAfter('=')
    val config = configPath?.let(ApplicationConfig::loadFromFile)
        ?: ApplicationConfig.loadFromClasspath("application.yaml")
    val apiPort = config.property("bosca.server.port").getString().toInt()
    val uiConfig = ApplicationConfig.load(
        """{"bosca":{"server":${config.property("git.ui.server").getString()}}}""".byteInputStream(),
    )
    val uiPort = uiConfig.property("bosca.server.port").getString().toInt()
    require(apiPort in 0..65535 && uiPort in 0..65535) { "Git listener ports must be between 0 and 65535" }
    require(apiPort == 0 || apiPort != uiPort) { "Git API and UI must use different ports" }

    val application = BoscaApplication(config)
    runBlocking {
        try {
            application.module()
            val workerThreads = config.propertyOrNull("bosca.server.worker-threads")
                ?.getString()?.toIntOrNull()?.takeIf { it > 0 }
                ?: minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            application.install(JmxModule(apiPort, workerThreads))
            application.freezeMiddleware()
        } catch (failure: Throwable) {
            application.shutdown()
            throw failure
        }
    }
    GitServer(NettyServerEngine(application, apiPort)) { boundApiPort ->
        val uiApplication = BoscaApplication(uiConfig, ownsServices = false)
        val prefix = config.propertyOrNull("git.ui.auth-cookie-prefix")
            ?.getString()?.takeIf(String::isNotBlank)
        try {
            uiApplication.installGitUiServer("http://127.0.0.1:$boundApiPort/graphql", prefix)
            uiApplication.freezeMiddleware()
            NettyServerEngine(uiApplication, uiPort)
        } catch (failure: Throwable) {
            runBlocking { uiApplication.shutdown() }
            throw failure
        }
    }.start()
}
