package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedJobTest {

    @OptIn(Internal::class)
    private fun job() = InternalJobConstructor(Json.parseToJsonElement("{}"), TestJobExecutor::class)

    @OptIn(Internal::class)
    @Test
    fun serialize_with_child_succeeds() {
        val child = job()
        val parent = job()
        parent.addChild(child)

        parent.serialize()
    }

    @OptIn(Internal::class)
    @Test
    fun runOnFailure_round_trips_when_set() {
        val job = job().apply { setRunOnFailure(true) }
        assertTrue(job.serialize().deserialize().getRunOnFailure())
    }

    @OptIn(Internal::class)
    @Test
    fun runOnFailure_round_trips_false_by_default() {
        assertFalse(job().serialize().deserialize().getRunOnFailure())
    }

    @OptIn(Internal::class)
    @Test
    fun runOnFailure_round_trips_on_a_child() {
        val parent = job()
        val child = job().apply { setRunOnFailure(true) }
        parent.addChild(child)
        val restored = parent.serialize().deserialize()
        assertTrue(restored.getChildren().single().getRunOnFailure(), "a child's runOnFailure must survive the parent's round-trip")
    }
}

private class TestJobExecutor : JobExecutor {
    override suspend fun execute() {}
}
