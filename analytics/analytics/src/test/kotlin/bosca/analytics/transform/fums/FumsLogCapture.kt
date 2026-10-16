package bosca.analytics.transform.fums

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Captures the operator-visible FUMS messages without changing the root logger. */
internal class FumsLogCapture(vararg types: Class<*>) : AutoCloseable {
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val loggers = types.map { LoggerFactory.getLogger(it) as Logger }
    private val levels = loggers.map { it.level }

    init {
        loggers.forEach {
            it.level = Level.INFO
            it.addAppender(appender)
        }
    }

    val events: List<ILoggingEvent> get() = appender.list.toList()
    val messages: List<String> get() = events.map { it.formattedMessage }

    override fun close() {
        loggers.zip(levels).forEach { (logger, level) ->
            logger.detachAppender(appender)
            logger.level = level
        }
        appender.stop()
    }
}
