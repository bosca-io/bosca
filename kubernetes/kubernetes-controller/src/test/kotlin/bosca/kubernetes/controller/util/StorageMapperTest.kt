package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.VolumeResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the StorageClass / PVC mappers. The decisions worth fixing in
 * tests:
 *
 *   * `isDefault` honours the canonical
 *     `storageclass.kubernetes.io/is-default-class=true` annotation
 *     *and* the legacy beta form `storageclass.beta.kubernetes.io/...`.
 *   * `reclaim` and `binding` default to k8s defaults when unspecified
 *     (`Delete`, `Immediate`) — same rules `kubectl describe sc` uses.
 *   * PVC `capacity` shows `requested → actual` only when the two
 *     differ (in-flight resize); otherwise just the actual size.
 *   * PVC `accessMode` abbreviations match `kubectl` (`RWO`, `RWX`,
 *     `ROX`, `RWOP`), comma-joined when multiple are present.
 *   * PVC `workload` is the first owner-reference name — `null` when
 *     no owner is set.
 */
class StorageMapperTest {

    // ===== StorageClass =====

    @Test
    fun `storageclass with the canonical default annotation reports isDefault=true`() {
        val sc = StorageClassBuilder()
            .withNewMetadata()
                .withName("gp3")
                .addToAnnotations("storageclass.kubernetes.io/is-default-class", "true")
            .endMetadata()
            .withProvisioner("ebs.csi.aws.com")
            .withReclaimPolicy("Retain")
            .withVolumeBindingMode("WaitForFirstConsumer")
            .withParameters<String, String>(linkedMapOf("type" to "gp3", "encrypted" to "true"))
            .build()
        val w = sc.toWire()
        assertEquals("gp3", w.name)
        assertEquals("ebs.csi.aws.com", w.provisioner)
        assertEquals("Retain", w.reclaim)
        assertEquals("WaitForFirstConsumer", w.binding)
        assertTrue(w.isDefault)
        assertEquals("type=gp3, encrypted=true", w.parameters)
    }

    @Test
    fun `storageclass with the legacy beta annotation also reports isDefault=true`() {
        val sc = StorageClassBuilder()
            .withNewMetadata()
                .withName("legacy")
                .addToAnnotations("storageclass.beta.kubernetes.io/is-default-class", "true")
            .endMetadata()
            .withProvisioner("kubernetes.io/aws-ebs")
            .build()
        assertTrue(sc.toWire().isDefault)
    }

    @Test
    fun `storageclass without the default annotation has isDefault=false`() {
        val sc = StorageClassBuilder()
            .withNewMetadata().withName("other").endMetadata()
            .withProvisioner("x")
            .build()
        assertFalse(sc.toWire().isDefault)
    }

    @Test
    fun `storageclass reclaim defaults to Delete and binding to Immediate`() {
        val sc = StorageClassBuilder()
            .withNewMetadata().withName("a").endMetadata()
            .withProvisioner("x")
            .build()
        val w = sc.toWire()
        assertEquals("Delete", w.reclaim)
        assertEquals("Immediate", w.binding)
    }

    @Test
    fun `storageclass parameters render as an empty string when null`() {
        val sc = StorageClassBuilder()
            .withNewMetadata().withName("a").endMetadata()
            .withProvisioner("x")
            .build()
        assertEquals("", sc.toWire().parameters)
    }

    // ===== PVC =====

    @Test
    fun `pvc capacity renders requested arrow actual when the two differ`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withUid("u").withName("data").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withVolumeName("pv-1")
                .withStorageClassName("gp3")
                .withAccessModes("ReadWriteOnce")
                .withResources(
                    VolumeResourceRequirementsBuilder()
                        .withRequests<String, Quantity>(mapOf("storage" to Quantity("100Gi")))
                        .build()
                )
            .endSpec()
            .withNewStatus()
                .withPhase("Bound")
                .withCapacity<String, Quantity>(mapOf("storage" to Quantity("50Gi")))
            .endStatus()
            .build()
        val w = pvc.toWire()
        assertEquals("u", w.id)
        assertEquals("Bound", w.status)
        assertEquals("pv-1", w.volume)
        assertEquals("100Gi → 50Gi", w.capacity, "in-flight resize renders both sides")
        assertEquals("RWO", w.accessMode)
        assertEquals("gp3", w.storageClass)
    }

    @Test
    fun `pvc capacity is just the actual size when requested matches`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withAccessModes("ReadWriteMany")
                .withResources(
                    VolumeResourceRequirementsBuilder().withRequests<String, Quantity>(mapOf("storage" to Quantity("100Gi"))).build()
                )
            .endSpec()
            .withNewStatus()
                .withPhase("Bound")
                .withCapacity<String, Quantity>(mapOf("storage" to Quantity("100Gi")))
            .endStatus()
            .build()
        assertEquals("100Gi", pvc.toWire().capacity)
        assertEquals("RWX", pvc.toWire().accessMode)
    }

    @Test
    fun `pvc capacity falls back to requested when status capacity is missing`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withAccessModes("ReadOnlyMany")
                .withResources(
                    VolumeResourceRequirementsBuilder().withRequests<String, Quantity>(mapOf("storage" to Quantity("10Gi"))).build()
                )
            .endSpec()
            .build()
        val w = pvc.toWire()
        assertEquals("10Gi", w.capacity)
        assertEquals("ROX", w.accessMode)
        assertEquals("Pending", w.status, "missing status phase defaults to Pending")
    }

    @Test
    fun `pvc capacity is dash when neither requested nor actual is present`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec().withAccessModes("ReadWriteOncePod").endSpec()
            .build()
        val w = pvc.toWire()
        assertEquals("-", w.capacity)
        assertEquals("RWOP", w.accessMode)
        assertEquals("-", w.storageClass)
        assertNull(w.usedPercent)
    }

    @Test
    fun `pvc with multiple access modes joins them comma-separated`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec().withAccessModes("ReadWriteOnce", "ReadOnlyMany").endSpec()
            .build()
        assertEquals("RWO,ROX", pvc.toWire().accessMode)
    }

    @Test
    fun `pvc with an unknown access mode passes the value through verbatim`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec().withAccessModes("ExclusiveTalk").endSpec()
            .build()
        assertEquals("ExclusiveTalk", pvc.toWire().accessMode)
    }

    @Test
    fun `pvc accessMode renders dash when no modes are set`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .withNewSpec().endSpec()
            .build()
        assertEquals("-", pvc.toWire().accessMode)
    }

    @Test
    fun `pvc workload is the first owner-reference name`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName("data-db-0").withNamespace("ns")
                .withOwnerReferences(
                    OwnerReferenceBuilder().withKind("StatefulSet").withName("db").withUid("u").build()
                )
            .endMetadata()
            .build()
        assertEquals("db", pvc.toWire().workload)
    }

    @Test
    fun `pvc workload is null when no owner-reference is set`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("orphan").withNamespace("ns").endMetadata()
            .build()
        assertNull(pvc.toWire().workload)
    }

    @Test
    fun `pvc id falls back to PersistentVolumeClaim slash ns slash name when uid missing`() {
        val pvc = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").endMetadata()
            .build()
        assertEquals("PersistentVolumeClaim/ns/data", pvc.toWire().id)
    }
}
