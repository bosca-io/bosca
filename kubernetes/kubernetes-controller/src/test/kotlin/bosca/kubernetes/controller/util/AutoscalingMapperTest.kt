package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder
import io.fabric8.kubernetes.api.model.LabelSelectorRequirementBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerConditionBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpecBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricStatusBuilder
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the HPA / PDB mappers. The decisions worth fixing in tests:
 *
 *   * HPA `minReplicas` defaults to 1 (the API server default) when
 *     the spec omits it.
 *   * Metric current values pair with their spec rule by source type
 *     + metric identity, NOT by array index — the status array is not
 *     order-guaranteed.
 *   * `Utilization` targets render as `NN%`; quantity targets render
 *     the raw quantity string (`100m`, `2Gi`).
 *   * Condition flags default to the neutral reading (`ableToScale`,
 *     `scalingActive` true; `scalingLimited` false) when the HPA has
 *     no conditions yet.
 *   * PDB `minAvailable` / `maxUnavailable` preserve the int-or-percent
 *     form; the selector renders in `kubectl` syntax, `-` when empty.
 */
class AutoscalingMapperTest {

    // ===== HorizontalPodAutoscaler =====

    @Test
    fun `hpa maps target ref, bounds, and replica counts`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata()
                .withName("web")
                .withNamespace("prod")
                .withUid("uid-1")
            .endMetadata()
            .withNewSpec()
                .withNewScaleTargetRef().withKind("Deployment").withName("web").endScaleTargetRef()
                .withMinReplicas(2)
                .withMaxReplicas(10)
            .endSpec()
            .withNewStatus()
                .withCurrentReplicas(3)
                .withDesiredReplicas(4)
            .endStatus()
            .build()
        val w = hpa.toWire()
        assertEquals("uid-1", w.id)
        assertEquals("web", w.name)
        assertEquals("prod", w.namespace)
        assertEquals("Deployment", w.targetKind)
        assertEquals("web", w.targetName)
        assertEquals(2, w.minReplicas)
        assertEquals(10, w.maxReplicas)
        assertEquals(3, w.currentReplicas)
        assertEquals(4, w.desiredReplicas)
    }

    @Test
    fun `hpa minReplicas defaults to 1 when the spec omits it`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec().withMaxReplicas(5).endSpec()
            .build()
        assertEquals(1, hpa.toWire().minReplicas)
    }

    @Test
    fun `resource utilization metric renders percent target and pairs current by identity`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withMaxReplicas(5)
                .addToMetrics(
                    MetricSpecBuilder()
                        .withType("Resource")
                        .withNewResource()
                            .withName("cpu")
                            .withNewTarget().withType("Utilization").withAverageUtilization(80).endTarget()
                        .endResource()
                        .build(),
                    MetricSpecBuilder()
                        .withType("Resource")
                        .withNewResource()
                            .withName("memory")
                            .withNewTarget().withType("AverageValue").withAverageValue(Quantity("1Gi")).endTarget()
                        .endResource()
                        .build(),
                )
            .endSpec()
            .withNewStatus()
                // Deliberately reversed relative to the spec order —
                // pairing must go through metric identity.
                .addToCurrentMetrics(
                    MetricStatusBuilder()
                        .withType("Resource")
                        .withNewResource()
                            .withName("memory")
                            .withNewCurrent().withAverageValue(Quantity("512Mi")).endCurrent()
                        .endResource()
                        .build(),
                    MetricStatusBuilder()
                        .withType("Resource")
                        .withNewResource()
                            .withName("cpu")
                            .withNewCurrent().withAverageUtilization(63).endCurrent()
                        .endResource()
                        .build(),
                )
            .endStatus()
            .build()
        val metrics = hpa.toWire().metrics
        assertEquals(2, metrics.size)
        assertEquals("cpu", metrics[0].label)
        assertEquals("80%", metrics[0].target)
        assertEquals("63%", metrics[0].current)
        assertEquals("memory", metrics[1].label)
        assertEquals("1Gi", metrics[1].target)
        assertEquals("512Mi", metrics[1].current)
    }

    @Test
    fun `metric without a reported current value maps current=null`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withMaxReplicas(5)
                .addToMetrics(
                    MetricSpecBuilder()
                        .withType("Pods")
                        .withNewPods()
                            .withNewMetric().withName("requests-per-second").endMetric()
                            .withNewTarget().withType("AverageValue").withAverageValue(Quantity("100")).endTarget()
                        .endPods()
                        .build(),
                )
            .endSpec()
            .build()
        val metric = hpa.toWire().metrics.single()
        assertEquals("requests-per-second", metric.label)
        assertEquals("100", metric.target)
        assertNull(metric.current)
    }

    @Test
    fun `hpa without conditions reads as neutral, not failing`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec().withMaxReplicas(3).endSpec()
            .build()
        val w = hpa.toWire()
        assertTrue(w.ableToScale)
        assertTrue(w.scalingActive)
        assertFalse(w.scalingLimited)
        assertNull(w.lastScaleTime)
    }

    @Test
    fun `hpa conditions map through to the flags`() {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec().withMaxReplicas(3).endSpec()
            .withNewStatus()
                .withConditions(
                    HorizontalPodAutoscalerConditionBuilder()
                        .withType("AbleToScale").withStatus("True").build(),
                    HorizontalPodAutoscalerConditionBuilder()
                        .withType("ScalingActive").withStatus("False").build(),
                    HorizontalPodAutoscalerConditionBuilder()
                        .withType("ScalingLimited").withStatus("True").build(),
                )
            .endStatus()
            .build()
        val w = hpa.toWire()
        assertTrue(w.ableToScale)
        assertFalse(w.scalingActive)
        assertTrue(w.scalingLimited)
    }

    // ===== PodDisruptionBudget =====

    @Test
    fun `pdb preserves integer and percent threshold forms`() {
        val intPdb = PodDisruptionBudgetBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").withUid("uid-2").endMetadata()
            .withNewSpec().withMinAvailable(IntOrString(2)).endSpec()
            .withNewStatus()
                .withCurrentHealthy(3)
                .withDesiredHealthy(2)
                .withDisruptionsAllowed(1)
                .withExpectedPods(3)
            .endStatus()
            .build()
        val w = intPdb.toWire()
        assertEquals("uid-2", w.id)
        assertEquals("2", w.minAvailable)
        assertNull(w.maxUnavailable)
        assertEquals(3, w.currentHealthy)
        assertEquals(2, w.desiredHealthy)
        assertEquals(1, w.disruptionsAllowed)
        assertEquals(3, w.expectedPods)

        val pctPdb = PodDisruptionBudgetBuilder()
            .withNewMetadata().withName("b").withNamespace("ns").endMetadata()
            .withNewSpec().withMaxUnavailable(IntOrString("50%")).endSpec()
            .build()
        assertEquals("50%", pctPdb.toWire().maxUnavailable)
        assertNull(pctPdb.toWire().minAvailable)
    }

    @Test
    fun `pdb selector renders labels and expressions in kubectl syntax`() {
        val pdb = PodDisruptionBudgetBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withSelector(
                    LabelSelectorBuilder()
                        .withMatchLabels<String, String>(linkedMapOf("app" to "web", "tier" to "frontend"))
                        .withMatchExpressions(
                            LabelSelectorRequirementBuilder()
                                .withKey("env").withOperator("In").withValues("prod", "staging").build(),
                        )
                        .build(),
                )
                .endSpec()
            .build()
        assertEquals("app=web,tier=frontend,env in (prod,staging)", pdb.toWire().selector)
    }

    @Test
    fun `pdb without selector or status maps to placeholders and zeros`() {
        val pdb = PodDisruptionBudgetBuilder()
            .withNewMetadata().withName("bare").withNamespace("ns").endMetadata()
            .withNewSpec().endSpec()
            .build()
        val w = pdb.toWire()
        assertEquals("-", w.selector)
        assertEquals(0, w.currentHealthy)
        assertEquals(0, w.disruptionsAllowed)
    }
}
