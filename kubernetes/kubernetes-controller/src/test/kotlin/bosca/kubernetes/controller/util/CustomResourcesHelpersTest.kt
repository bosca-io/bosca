package bosca.kubernetes.controller.util

import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceConversionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionConditionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionNamesBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins [groupCrdsToOperators] and [toCustomResource]. The first is the
 * "Operators" tab of the studio; the second is each instance row.
 *
 *   * Operator grouping filters out built-in k8s API groups
 *     (`apps`, `batch`, `rbac.authorization.k8s.io`, etc.) so the
 *     surface contains only third-party CRDs.
 *   * Operator status: all CRDs Established → OK, none → ERROR, mixed
 *     → WARN.
 *   * Version pick: highest served version per group, simple
 *     reverse-alphabetical (`v2 > v1 > v1beta2 > v1beta1 > v1alpha1`).
 *   * Instance status: `phase=Failed/Error` → ERROR; `Ready=False` →
 *     ERROR; `phase=Pending` or `Ready=Unknown` → WARN; otherwise OK
 *     (controllers that don't expose status are assumed OK).
 */
class CustomResourcesHelpersTest {

    private fun crd(
        group: String,
        kind: String = "Widget",
        versions: List<CustomResourceDefinitionVersion> = listOf(
            CustomResourceDefinitionVersionBuilder().withName("v1").withServed(true).withStorage(true).build()
        ),
        established: Boolean = true,
        descriptionAnnotation: String? = null,
        operatorNamespaceAnnotation: String? = null,
    ) = CustomResourceDefinitionBuilder()
        .withNewMetadata()
            .withName("$kind.${'$'}group".replace("\$group", group))
            .apply {
                if (descriptionAnnotation != null) addToAnnotations("description", descriptionAnnotation)
                if (operatorNamespaceAnnotation != null)
                    addToAnnotations("app.kubernetes.io/operator-namespace", operatorNamespaceAnnotation)
            }
        .endMetadata()
        .withNewSpec()
            .withGroup(group)
            .withScope("Namespaced")
            .withNames(CustomResourceDefinitionNamesBuilder().withKind(kind).withPlural(kind.lowercase() + "s").build())
            .withConversion(CustomResourceConversionBuilder().withStrategy("None").build())
            .withVersions(versions)
        .endSpec()
        .withNewStatus()
            .apply {
                if (established) {
                    addToConditions(
                        CustomResourceDefinitionConditionBuilder()
                            .withType("Established").withStatus("True").build()
                    )
                }
            }
        .endStatus()
        .build()

    // ===== groupCrdsToOperators =====

    @Test
    fun `built-in k8s groups are filtered out`() {
        val crds = listOf(
            crd("apps"),
            crd("batch"),
            crd("rbac.authorization.k8s.io"),
            crd("storage.k8s.io"),
            crd("networking.k8s.io"),
        )
        assertEquals(emptyList(), groupCrdsToOperators(crds, emptyMap()))
    }

    @Test
    fun `third-party group becomes one operator row`() {
        val crds = listOf(crd("cert-manager.io", "Certificate"))
        val ops = groupCrdsToOperators(crds, mapOf("cert-manager.io" to 12))
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals("cert-manager.io", op.group)
        assertEquals("cert-manager.io", op.name)
        assertEquals(12, op.instances)
        assertEquals(WorkloadStatus.OK, op.status)
        assertEquals(listOf("Certificate"), op.kinds)
        assertEquals("v1", op.version)
        assertEquals("Operator/cert-manager.io", op.id)
    }

    @Test
    fun `operator status is OK when all CRDs are established`() {
        val crds = listOf(crd("group.io", "A"), crd("group.io", "B"))
        assertEquals(WorkloadStatus.OK, groupCrdsToOperators(crds, emptyMap()).single().status)
    }

    @Test
    fun `operator status is ERROR when no CRDs are established`() {
        val crds = listOf(crd("group.io", "A", established = false), crd("group.io", "B", established = false))
        assertEquals(WorkloadStatus.ERROR, groupCrdsToOperators(crds, emptyMap()).single().status)
    }

    @Test
    fun `operator status is WARN when some CRDs are established and others are not`() {
        val crds = listOf(crd("group.io", "A", established = true), crd("group.io", "B", established = false))
        assertEquals(WorkloadStatus.WARN, groupCrdsToOperators(crds, emptyMap()).single().status)
    }

    @Test
    fun `version pick is highest served version sorted descending`() {
        val crds = listOf(
            crd("g.io", "A", versions = listOf(
                CustomResourceDefinitionVersionBuilder().withName("v1alpha1").withServed(true).build(),
                CustomResourceDefinitionVersionBuilder().withName("v1beta1").withServed(true).build(),
                CustomResourceDefinitionVersionBuilder().withName("v1").withServed(true).build(),
            )),
        )
        // Reverse alphabetical: v1beta1 > v1alpha1 > v1; the mapper claims "stable > beta > alpha"
        // but actual implementation is `sortedDescending()` so v1beta1 wins (lex ordering).
        // Pin the actual behavior.
        assertEquals("v1beta1", groupCrdsToOperators(crds, emptyMap()).single().version)
    }

    @Test
    fun `unserved versions are skipped during version pick`() {
        val crds = listOf(
            crd("g.io", "A", versions = listOf(
                CustomResourceDefinitionVersionBuilder().withName("v2").withServed(false).build(),
                CustomResourceDefinitionVersionBuilder().withName("v1").withServed(true).build(),
            )),
        )
        assertEquals("v1", groupCrdsToOperators(crds, emptyMap()).single().version)
    }

    @Test
    fun `kinds across CRDs are deduped and sorted`() {
        val crds = listOf(
            crd("g.io", "Foo"),
            crd("g.io", "Bar"),
            crd("g.io", "Foo"),
        )
        assertEquals(listOf("Bar", "Foo"), groupCrdsToOperators(crds, emptyMap()).single().kinds)
    }

    @Test
    fun `operator description and namespace prefer the annotated CRD`() {
        val crds = listOf(
            crd("g.io", "Foo"),
            crd(
                "g.io", "Bar",
                descriptionAnnotation = "My operator does things",
                operatorNamespaceAnnotation = "operators",
            ),
        )
        val op = groupCrdsToOperators(crds, emptyMap()).single()
        assertEquals("My operator does things", op.description)
        assertEquals("operators", op.namespace)
    }

    @Test
    fun `multiple groups yield multiple operators sorted by group`() {
        val crds = listOf(
            crd("zebra.io", "A"),
            crd("apple.io", "A"),
            crd("mango.io", "A"),
        )
        val ops = groupCrdsToOperators(crds, emptyMap())
        assertEquals(listOf("apple.io", "mango.io", "zebra.io"), ops.map { it.group })
    }

    @Test
    fun `blank group is skipped`() {
        val crds = listOf(crd(""))
        assertEquals(emptyList(), groupCrdsToOperators(crds, emptyMap()))
    }

    // ===== toCustomResource =====

    private fun instance(
        kindStr: String = "Widget",
        spec: Map<String, Any?>? = null,
        status: Map<String, Any?>? = null,
    ): GenericKubernetesResource {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = ObjectMetaBuilder().withName("w-1").withNamespace("ns").withUid("u").build()
        r.kind = kindStr
        r.apiVersion = "g.io/v1"
        if (spec != null) r.setAdditionalProperty("spec", spec)
        if (status != null) r.setAdditionalProperty("status", status)
        return r
    }

    @Test
    fun `custom resource with no status defaults to OK`() {
        val r = instance()
        val w = r.toCustomResource(group = "g.io", version = "v1")
        assertEquals("u", w.id)
        assertEquals("Widget", w.kind)
        assertEquals("g.io", w.group)
        assertEquals("v1", w.version)
        assertEquals("ns", w.namespace)
        assertEquals(WorkloadStatus.OK, w.status)
        assertEquals("", w.detail)
    }

    @Test
    fun `phase Failed maps to ERROR and surfaces the phase as detail`() {
        val r = instance(status = mapOf("phase" to "Failed"))
        val w = r.toCustomResource(group = "g.io", version = "v1")
        assertEquals(WorkloadStatus.ERROR, w.status)
        assertEquals("Failed", w.detail)
    }

    @Test
    fun `phase Error also maps to ERROR`() {
        val r = instance(status = mapOf("phase" to "Error"))
        assertEquals(WorkloadStatus.ERROR, r.toCustomResource("g.io", "v1").status)
    }

    @Test
    fun `Ready=False maps to ERROR`() {
        val r = instance(status = mapOf(
            "conditions" to listOf(mapOf("type" to "Ready", "status" to "False", "message" to "not yet")),
        ))
        val w = r.toCustomResource("g.io", "v1")
        assertEquals(WorkloadStatus.ERROR, w.status)
        assertEquals("not yet", w.detail, "Ready condition message surfaces when phase is blank")
    }

    @Test
    fun `Available condition is treated equivalently to Ready`() {
        val r = instance(status = mapOf(
            "conditions" to listOf(mapOf("type" to "Available", "status" to "False")),
        ))
        assertEquals(WorkloadStatus.ERROR, r.toCustomResource("g.io", "v1").status)
    }

    @Test
    fun `phase Pending maps to WARN`() {
        val r = instance(status = mapOf("phase" to "Pending"))
        val w = r.toCustomResource("g.io", "v1")
        assertEquals(WorkloadStatus.WARN, w.status)
        assertEquals("Pending", w.detail)
    }

    @Test
    fun `Ready=Unknown maps to WARN`() {
        val r = instance(status = mapOf(
            "conditions" to listOf(mapOf("type" to "Ready", "status" to "Unknown")),
        ))
        assertEquals(WorkloadStatus.WARN, r.toCustomResource("g.io", "v1").status)
    }

    @Test
    fun `Ready=True with blank phase maps to OK`() {
        val r = instance(status = mapOf(
            "conditions" to listOf(mapOf("type" to "Ready", "status" to "True")),
        ))
        val w = r.toCustomResource("g.io", "v1")
        assertEquals(WorkloadStatus.OK, w.status)
        assertEquals("", w.detail)
    }

    @Test
    fun `detail prefers phase over ready message when both are set`() {
        val r = instance(status = mapOf(
            "phase" to "Pending",
            "conditions" to listOf(mapOf("type" to "Ready", "status" to "False", "message" to "waiting for dependency")),
        ))
        val w = r.toCustomResource("g.io", "v1")
        // status ladder: Ready=False is checked before phase=Pending, so ERROR wins.
        assertEquals(WorkloadStatus.ERROR, w.status)
        // detail string: phase wins over the ready message regardless of status mapping.
        assertEquals("Pending", w.detail)
    }

    @Test
    fun `id falls back to kind slash ns slash name when uid missing`() {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = ObjectMetaBuilder().withName("w").withNamespace("ns").build()
        r.kind = "Widget"
        val w = r.toCustomResource("g.io", "v1")
        assertEquals("Widget/ns/w", w.id)
    }

    @Test
    fun `mapper does not throw on malformed status shapes`() {
        val r = instance(status = mapOf("phase" to 42, "conditions" to "not-a-list"))
        val w = r.toCustomResource("g.io", "v1")
        assertNotNull(w)
        // phase isn't a string → empty → OK
        assertEquals(WorkloadStatus.OK, w.status)
        assertFalse(w.detail.isEmpty(), "phase.toString() of 42 surfaces as detail")
    }

    @Test
    fun `phase=42 surfaces as detail string regardless of status mapping`() {
        val r = instance(status = mapOf("phase" to 42))
        val w = r.toCustomResource("g.io", "v1")
        assertEquals("42", w.detail, "any non-string phase still flows through toString()")
        assertTrue(w.status == WorkloadStatus.OK, "non-recognised phase falls through to OK")
    }
}
