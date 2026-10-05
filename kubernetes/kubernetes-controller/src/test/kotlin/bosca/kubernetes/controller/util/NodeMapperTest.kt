package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder
import io.fabric8.kubernetes.api.model.TaintBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Pins the wire mapping for [io.fabric8.kubernetes.api.model.Node]. The
 * decisions worth pinning here are:
 *
 *   * Role: explicit `control-plane` label wins; legacy `master` is
 *     normalised *to* `control-plane`; otherwise any other
 *     `node-role.kubernetes.io/<x>` label is surfaced as-is; otherwise
 *     `"worker"`.
 *   * Status: Ready=False → `"NotReady"`; pressure conditions take the
 *     status text when Ready=True; nominal Ready=True → `"Ready"`;
 *     missing Ready condition → `"Unknown"`.
 *   * Instance and zone fall back from the standard `topology.*` /
 *     `node.kubernetes.io/...` labels to the legacy beta equivalents.
 *   * Taints render in `key=value:effect` form, omitting `=value` when
 *     value is blank — matches `kubectl describe node` parlance.
 */
class NodeMapperTest {

    private fun readyCondition(status: String) =
        NodeConditionBuilder().withType("Ready").withStatus(status).build()

    @Test
    fun `control-plane label promotes role to control-plane`() {
        val node = NodeBuilder()
            .withNewMetadata()
                .withName("n").addToLabels("node-role.kubernetes.io/control-plane", "")
            .endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("control-plane", node.toK8sNode().role)
    }

    @Test
    fun `legacy master label is normalised to control-plane`() {
        val node = NodeBuilder()
            .withNewMetadata()
                .withName("n").addToLabels("node-role.kubernetes.io/master", "")
            .endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("control-plane", node.toK8sNode().role)
    }

    @Test
    fun `explicit role label is surfaced verbatim`() {
        val node = NodeBuilder()
            .withNewMetadata()
                .withName("n").addToLabels("node-role.kubernetes.io/ingress", "")
            .endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("ingress", node.toK8sNode().role)
    }

    @Test
    fun `node with no role label defaults to worker`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("worker", node.toK8sNode().role)
    }

    @Test
    fun `instance and zone read from standard labels`() {
        val node = NodeBuilder()
            .withNewMetadata()
                .withName("n")
                .addToLabels("node.kubernetes.io/instance-type", "m5.large")
                .addToLabels("topology.kubernetes.io/zone", "us-east-1a")
            .endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        val wire = node.toK8sNode()
        assertEquals("m5.large", wire.instance)
        assertEquals("us-east-1a", wire.zone)
    }

    @Test
    fun `instance and zone fall back to legacy beta labels`() {
        val node = NodeBuilder()
            .withNewMetadata()
                .withName("n")
                .addToLabels("beta.kubernetes.io/instance-type", "t2.medium")
                .addToLabels("failure-domain.beta.kubernetes.io/zone", "us-west-2b")
            .endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        val wire = node.toK8sNode()
        assertEquals("t2.medium", wire.instance)
        assertEquals("us-west-2b", wire.zone)
    }

    @Test
    fun `status is Ready when Ready=True and no pressure condition is set`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("Ready", node.toK8sNode().status)
    }

    @Test
    fun `status is NotReady when Ready condition is not True`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("False")).endStatus()
            .build()
        assertEquals("NotReady", node.toK8sNode().status)
    }

    @Test
    fun `status is Unknown when there is no Ready condition`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().endStatus()
            .build()
        assertEquals("Unknown", node.toK8sNode().status)
    }

    @Test
    fun `pressure conditions promote the status when Ready=True`() {
        for (pressure in listOf("MemoryPressure", "DiskPressure", "PIDPressure", "NetworkUnavailable")) {
            val node = NodeBuilder()
                .withNewMetadata().withName("n").endMetadata()
                .withNewStatus()
                    .addToConditions(readyCondition("True"))
                    .addToConditions(NodeConditionBuilder().withType(pressure).withStatus("True").build())
                .endStatus()
                .build()
            assertEquals(pressure, node.toK8sNode().status, "expected $pressure to win over Ready=True")
        }
    }

    @Test
    fun `pressure condition with status=False is ignored`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus()
                .addToConditions(readyCondition("True"))
                .addToConditions(NodeConditionBuilder().withType("MemoryPressure").withStatus("False").build())
            .endStatus()
            .build()
        assertEquals("Ready", node.toK8sNode().status)
    }

    @Test
    fun `taints render as key=value colon effect`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewSpec()
                .addToTaints(TaintBuilder().withKey("dedicated").withValue("gpu").withEffect("NoSchedule").build())
                .addToTaints(TaintBuilder().withKey("node.kubernetes.io/unreachable").withEffect("NoExecute").build())
            .endSpec()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        val taints = node.toK8sNode().taints
        assertEquals(listOf("dedicated=gpu:NoSchedule", "node.kubernetes.io/unreachable:NoExecute"), taints)
    }

    @Test
    fun `kubelet version comes from nodeInfo`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus()
                .addToConditions(readyCondition("True"))
                .withNodeInfo(NodeSystemInfoBuilder().withKubeletVersion("v1.30.2").build())
            .endStatus()
            .build()
        assertEquals("v1.30.2", node.toK8sNode().version)
    }

    @Test
    fun `labels are emitted as a JsonObject when present and null when empty`() {
        val withLabels = NodeBuilder()
            .withNewMetadata().withName("n").addToLabels("a", "1").addToLabels("b", "2").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        val labels = withLabels.toK8sNode().labels
        assertIs<JsonObject>(labels)
        assertEquals("1", labels["a"]?.jsonPrimitive?.content)
        assertEquals("2", labels["b"]?.jsonPrimitive?.content)

        val noLabels = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertNull(noLabels.toK8sNode().labels)
    }

    @Test
    fun `nodeStatusText helper matches toK8sNode status across the condition cases`() {
        // The node list-metrics stream calls nodeStatusText() directly
        // (rather than going through toK8sNode), so pin that the helper
        // agrees with the full mapper for each branch.
        val ready = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        assertEquals("Ready", ready.nodeStatusText())

        val notReady = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("False")).endStatus()
            .build()
        assertEquals("NotReady", notReady.nodeStatusText())

        val unknown = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().endStatus()
            .build()
        assertEquals("Unknown", unknown.nodeStatusText())

        val pressured = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus()
                .addToConditions(readyCondition("True"))
                .addToConditions(NodeConditionBuilder().withType("DiskPressure").withStatus("True").build())
            .endStatus()
            .build()
        assertEquals("DiskPressure", pressured.nodeStatusText())
    }

    @Test
    fun `nodeRole helper resolves control-plane and worker`() {
        val controlPlane = NodeBuilder()
            .withNewMetadata().withName("n").addToLabels("node-role.kubernetes.io/control-plane", "").endMetadata()
            .build()
        assertEquals("control-plane", controlPlane.nodeRole())

        val worker = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .build()
        assertEquals("worker", worker.nodeRole())
    }

    @Test
    fun `usage placeholders cpu memory pods are zero until metrics-server is wired`() {
        val node = NodeBuilder()
            .withNewMetadata().withName("n").endMetadata()
            .withNewStatus().addToConditions(readyCondition("True")).endStatus()
            .build()
        val wire = node.toK8sNode()
        assertEquals(0, wire.cpu)
        assertEquals(0, wire.memory)
        assertEquals(0, wire.pods)
        assertTrue(wire.age == "-" || wire.age.isNotBlank())
    }
}
