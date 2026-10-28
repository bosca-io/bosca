package bosca.atlas.server

import bosca.atlas.ui.server.installAtlasUiServer
import bosca.jmx.JmxModule
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import kotlinx.coroutines.runBlocking

internal fun startAtlasServer(args: Array<String>) {
    val configPath = args.firstOrNull { it.startsWith("-config=") }?.substringAfter('=')
    val config = configPath?.let(ApplicationConfig::loadFromFile)
        ?: ApplicationConfig.loadFromClasspath("atlas-server.yaml")
    val apiPort = config.property("bosca.server.port").getString().toInt()
    val roles = AtlasConfiguration.from(config)
    val uiConfig = if (roles.uiEnabled) ApplicationConfig.load(
        """{"bosca":{"server":${config.property("atlas.ui.server").getString()}}}""".byteInputStream(),
    ) else null
    val uiPort = uiConfig?.property("bosca.server.port")?.getString()?.toInt()
    require(apiPort in 0..65535 && (uiPort == null || uiPort in 0..65535)) { "Atlas listener ports must be between 0 and 65535" }
    require(apiPort == 0 || apiPort != uiPort) { "Atlas API and UI must use different ports" }

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
    val createUi: ((Int) -> NettyServerEngine)? = if (uiConfig == null || uiPort == null) null else { boundApiPort ->
        val uiApplication = BoscaApplication(uiConfig, ownsServices = false)
        val prefix = config.propertyOrNull("atlas.ui.auth-cookie-prefix")
            ?.getString()?.takeIf(String::isNotBlank)
        try {
            uiApplication.installAtlasUiServer("http://127.0.0.1:$boundApiPort/graphql", prefix)
            uiApplication.freezeMiddleware()
            NettyServerEngine(uiApplication, uiPort)
        } catch (failure: Throwable) {
            runBlocking { uiApplication.shutdown() }
            throw failure
        }
    }
    AtlasServer(NettyServerEngine(application, apiPort), createUi).start()
}
