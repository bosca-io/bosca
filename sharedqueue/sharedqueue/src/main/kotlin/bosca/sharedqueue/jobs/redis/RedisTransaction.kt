@file:OptIn(ExperimentalSerializationApi::class)

package bosca.sharedqueue.jobs.redis

import bosca.redis.RedisConnection
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.SerializedJob
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.time.Instant

class RedisTransaction(private val json: Json) {

    private val ops: MutableList<RedisTransactionOp> = mutableListOf()

    val isEmpty: Boolean
        get() = ops.isEmpty()

    fun addOp(op: RedisTransactionOp) {
        ops.add(op)
    }

    internal class BuiltScript(
        val script: String,
        val keys: Array<String>,
        val args: Array<String>,
    )

    internal fun build(): BuiltScript {
        val script = StringBuilder()
        var keyIx = 0
        var argIx = 0

        for (op in ops) {
            when (op) {
                is RedisTransactionOp.Queue -> {
                    script.append("redis.call('HSET', KEYS[${keyIx + 1}], KEYS[${keyIx + 2}], tostring(ARGV[${argIx + 1}]))\n")
                    script.append("redis.call('RPUSH', KEYS[${keyIx + 3}], tostring(KEYS[${keyIx + 4}]))\n")
                    keyIx += 4
                    argIx += 1
                }

                is RedisTransactionOp.SetState -> {
                    script.append("redis.call('HSET', KEYS[${keyIx + 1}], KEYS[${keyIx + 2}], tostring(ARGV[${argIx + 1}]))\n")
                    keyIx += 2
                    argIx += 1
                }

                is RedisTransactionOp.QueueLater -> {
                    script.append("redis.call('HSET', KEYS[${keyIx + 1}], KEYS[${keyIx + 2}], tostring(ARGV[${argIx + 1}]))\n")
                    script.append("redis.call('ZADD', KEYS[${keyIx + 3}], tonumber(ARGV[${argIx + 2}]) + tonumber(ARGV[${argIx + 3}]), tostring(KEYS[${keyIx + 4}]))\n")
                    keyIx += 4
                    argIx += 3
                }

                is RedisTransactionOp.RemoveState -> {
                    script.append("redis.call('HDEL', KEYS[${keyIx + 1}], tostring(KEYS[${keyIx + 2}]))\n")
                    keyIx += 2
                }

                is RedisTransactionOp.RemoveRunning -> {
                    script.append("redis.call('ZREM', KEYS[${keyIx + 1}], tostring(KEYS[${keyIx + 2}]))\n")
                    keyIx += 2
                }

                is RedisTransactionOp.RemovePending -> {
                    script.append("redis.call('LREM', KEYS[${keyIx + 1}], 0, tostring(KEYS[${keyIx + 2}]))\n")
                    keyIx += 2
                }

                is RedisTransactionOp.Checkin -> {
                    script.append("redis.call('ZADD', KEYS[${keyIx + 1}], tonumber(ARGV[${argIx + 1}]) + tonumber(ARGV[${argIx + 2}]), tostring(KEYS[${keyIx + 2}]))\n")
                    script.append("redis.call('LREM', KEYS[${keyIx + 3}], 0, tostring(KEYS[${keyIx + 4}]))\n")
                    script.append("redis.call('INCR', 'sharedqueue::job::checkin::count')\n")
                    keyIx += 4
                    argIx += 2
                }
            }
        }
        script.append("return 1\n")

        val keys = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (op in ops) {
            when (op) {
                is RedisTransactionOp.Queue -> {
                    val job = json.encodeToJsonElement(op.job)

                    keys.add(RedisValues.state(op.queue))
                    keys.add(op.job.id.toString())
                    args.add(job.toString())

                    keys.add(RedisValues.pending(op.queue))
                    keys.add(op.job.id.toString())
                }

                is RedisTransactionOp.SetState -> {
                    val job = json.encodeToJsonElement(op.job)

                    keys.add(RedisValues.state(op.queue))
                    keys.add(op.id.toString())
                    args.add(job.toString())
                }

                is RedisTransactionOp.QueueLater -> {
                    val job = json.encodeToJsonElement(op.job)

                    keys.add(RedisValues.state(op.queue))
                    keys.add(op.job.id.toString())
                    args.add(job.toString())

                    keys.add(RedisValues.running(op.queue))
                    args.add(Instant.now().epochSecond.toString())
                    args.add(op.timeout.toString())
                    keys.add(op.job.id.toString())
                }

                is RedisTransactionOp.RemoveState -> {
                    keys.add(RedisValues.state(op.queue))
                    keys.add(op.id.toString())
                }

                is RedisTransactionOp.RemovePending -> {
                    keys.add(RedisValues.pending(op.queue))
                    keys.add(op.id.toString())
                }

                is RedisTransactionOp.RemoveRunning -> {
                    keys.add(RedisValues.running(op.queue))
                    keys.add(op.id.toString())
                }

                is RedisTransactionOp.Checkin -> {
                    keys.add(RedisValues.running(op.queue))
                    args.add(Instant.now().epochSecond.toString())
                    args.add("1800")
                    keys.add(op.id.toString())
                    keys.add(RedisValues.pending(op.queue))
                    keys.add(op.id.toString())
                }
            }
        }
        return BuiltScript(script.toString(), keys.toTypedArray(), args.toTypedArray())
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun execute(redis: RedisConnection) {
        val built = build()
        val result = try {
            redis.coroutines().eval<Long>(
                built.script,
                ScriptOutputType.INTEGER,
                built.keys,
                *built.args
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Redis transaction failed: ${e.message}")
        }
        if (result != 1L) {
            throw Exception("Redis script failed with code $result")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisTransaction::class.java)
    }
}

object RedisValues {

    fun pending(queue: String): String {
        return "sharedqueue::pending::job::$queue"
    }

    fun state(queue: String): String {
        return "sharedqueue::state::job::$queue"
    }

    fun running(queue: String): String {
        return "sharedqueue::running::job::$queue"
    }
}

sealed class RedisTransactionOp {
    data class Checkin(val queue: String, val id: UUID) : RedisTransactionOp()
    data class SetState(val queue: String, val id: UUID, val job: SerializedJob) : RedisTransactionOp()
    data class Queue(val queue: String, val job: SerializedJob) : RedisTransactionOp()
    data class QueueLater(val queue: String, val job: SerializedJob, val timeout: Int) : RedisTransactionOp()
    data class RemoveState(val queue: String, val id: UUID) : RedisTransactionOp()
    data class RemovePending(val queue: String, val id: UUID) : RedisTransactionOp()
    data class RemoveRunning(val queue: String, val id: UUID) : RedisTransactionOp()
}