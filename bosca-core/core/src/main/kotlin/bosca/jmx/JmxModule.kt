package bosca.jmx

import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Application module that registers a [BoscaServerMXBean] with the platform
 * MBean server, making server introspection data available to jconsole and
 * other JMX clients under the object name `bosca:type=Server`.
 *
 * This module should be installed after all other modules have been configured
 * so that the introspection data reflects the complete runtime state. If JMX
 * registration fails (e.g., in restrictive environments), a warning is logged
 * but the server continues to start normally.
 */
class JmxModule(
    private val port: Int,
    private val workerThreadCount: Int = Runtime.getRuntime().availableProcessors() * 2,
) : BoscaApplicationModule {

    private val log = LoggerFactory.getLogger(JmxModule::class.java)

    override suspend fun install(application: BoscaApplication) {
        val mbean = BoscaServer(application, port, workerThreadCount)
        try {
            val server = ManagementFactory.getPlatformMBeanServer()
            val objectName = ObjectName(OBJECT_NAME)
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName)
            }
            server.registerMBean(mbean, objectName)
            log.info("Registered JMX MBean: {}", objectName)
        } catch (e: Exception) {
            log.warn("Failed to register JMX MBean, server introspection via jconsole will not be available", e)
        }
    }

    companion object {
        const val OBJECT_NAME = "bosca:type=Server"
    }
}
