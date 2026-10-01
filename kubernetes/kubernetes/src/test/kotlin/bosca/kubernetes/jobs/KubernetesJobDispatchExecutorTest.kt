package bosca.kubernetes.jobs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class KubernetesJobDispatchExecutorTest {

    @Test
    fun `ordinary job runners cannot execute controller queue records`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            KubernetesJobDispatchExecutor().execute()
        }

        assertTrue(failure.message.orEmpty().contains(KubernetesJobQueueNames.queue))
    }
}
