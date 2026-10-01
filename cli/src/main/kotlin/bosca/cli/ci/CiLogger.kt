package bosca.cli.ci

import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.uuid.Uuid

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

class CiLogger(
    private val agentId: Uuid,
    private val component: String,
    private val minLevel: LogLevel = LogLevel.INFO,
) {
    private var jobId: Uuid? = null
    private var stepId: Uuid? = null

    // Volatile because the heartbeat coroutine reads this concurrently with
    // the main job coroutine that writes it via setActiveBuffer.
    @Volatile private var activeBuffer: LogBuffer? = null

    fun withJob(id: Uuid): CiLogger {
        jobId = id
        return this
    }

    fun withStep(id: Uuid): CiLogger {
        stepId = id
        return this
    }

    fun clearStep() { stepId = null }
    fun clearJob() { jobId = null; stepId = null }

    // Tees subsequent INFO+ messages into the given buffer so the user sees
    // agent diagnostics inline with the step's own subprocess output. Pass
    // null to detach before closing the buffer.
    fun setActiveBuffer(buffer: LogBuffer?) {
        activeBuffer = buffer
    }

    fun debug(message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.DEBUG, message, *fields)
    fun info(message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.INFO, message, *fields)
    fun warn(message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, message, *fields)
    fun error(message: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, message, *fields)
    fun error(message: String, ex: Throwable, vararg fields: Pair<String, Any?>) =
        log(LogLevel.ERROR, message, "error" to ex.javaClass.simpleName, "errorMessage" to ex.message, *fields)

    private fun log(level: LogLevel, message: String, vararg fields: Pair<String, Any?>) {
        if (level.ordinal < minLevel.ordinal) return

        val json = buildJsonObject {
            put("ts", Instant.now().toString())
            put("level", level.name)
            put("component", component)
            put("agentId", agentId.toString())
            jobId?.let { put("jobId", it.toString()) }
            stepId?.let { put("stepId", it.toString()) }
            put("msg", message)
            for ((k, v) in fields) {
                when (v) {
                    null -> put(k, JsonNull)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

        val output = if (level == LogLevel.ERROR) System.err else System.out
        output.println(json)

        teeToBuffer(level, message, fields)
    }

    private fun teeToBuffer(level: LogLevel, message: String, fields: Array<out Pair<String, Any?>>) {
        val buffer = activeBuffer ?: return
        // DEBUG stays local-only; if minLevel admitted it for stdout, the user
        // probably configured verbose agent logs but the pipeline view should
        // stay focused on the operator narrative (INFO+).
        if (level.ordinal < LogLevel.INFO.ordinal) return

        val stream = if (level.ordinal >= LogLevel.WARN.ordinal) "stderr" else "stdout"
        val rendered = buildString {
            append("[bosca-agent ").append(level.name).append("] ").append(message)
            if (fields.isNotEmpty()) {
                append(" (")
                fields.joinTo(this, ", ") { (k, v) -> "$k=${v ?: "null"}" }
                append(")")
            }
        }
        buffer.addLine(rendered, stream)
    }
}
