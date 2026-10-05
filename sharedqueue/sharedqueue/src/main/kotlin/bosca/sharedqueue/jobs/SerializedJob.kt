package bosca.sharedqueue.jobs

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.KClass

@Serializable
class SerializedJob(
    @Contextual
    val parentId: UUID?,
    @Contextual
    val id: UUID,
    val type: String,
    val status: JobStatus,
    val failures: Int,
    val maxFailures: Int,
    /** Run this job on the parent's terminal failure too (see [Job.runOnFailure]); defaulted for backward-compat. */
    val runOnFailure: Boolean = false,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val modified: OffsetDateTime,
    @Contextual
    val definition: JsonElement,
    val executor: String,
    val executorName: String?,
    /**
     * Human-readable label defaulted to null for backward-compatible
     * deserialization of queue entries written by older producers that didn't
     * emit this field.
     */
    val displayName: String? = null,
    val children: List<SerializedJob>,
    val callbacks: List<SerializedCallback>,
    @Contextual
    val context: JsonElement,
    /** Physical queue this job was enqueued on; defaulted for states written before the field existed. */
    val queueName: String? = null,
    /** Physical queue the parent job lives on (see [Job.parentQueue]); defaulted for older states. */
    val parentQueue: String? = null,
)

@Serializable
class SerializedCallback(
    @Contextual
    internal val context: JsonElement = JsonNull,
    internal val listener: String,
    internal val listenerName: String? = null
)

@Suppress("UNCHECKED_CAST")
fun SerializedJob.deserialize(): Job = Job(
    id = id,
    parentId = parentId,
    definition = definition,
    failures = failures,
    maxFailures = maxFailures,
    runOnFailure = runOnFailure,
    executor = Class.forName(executor).kotlin as KClass<JobExecutor>,
    executorName = executorName,
    displayName = displayName,
    status = status,
    created = created,
    modified = modified,
    children = children.map { it.deserialize() },
    callbacks = callbacks.mapTo(mutableListOf()) { it.deserialize() },
    context = context,
    queueName = queueName,
    parentQueue = parentQueue,
)

@Suppress("UNCHECKED_CAST")
fun SerializedCallback.deserialize(): JobCallback = JobCallback(
    listener = Class.forName(listener).kotlin as KClass<JobListener>,
    listenerName = listenerName
)

fun Job.serialize(): SerializedJob {
    if (id == UUID.NIL) {
        setId(UUID.random())
    }

    return SerializedJob(
        id = id,
        parentId = parentId,
        type = this::class.qualifiedName ?: error("Job type not found"),
        definition = definition,
        failures = failures,
        maxFailures = maxFailures,
        runOnFailure = runOnFailure,
        executor = executor.qualifiedName ?: error("Job executor not found"),
        executorName = executorName,
        displayName = displayName,
        status = status,
        created = created,
        modified = modified,
        children = children.map { it.serialize() },
        callbacks = callbacks.map { it.serialize() },
        context = context,
        queueName = queueName,
        parentQueue = parentQueue,
    )
}

fun JobCallback.serialize(): SerializedCallback = SerializedCallback(
    listener = listener.qualifiedName ?: error("Job listener not found"),
    listenerName = listenerName
)
