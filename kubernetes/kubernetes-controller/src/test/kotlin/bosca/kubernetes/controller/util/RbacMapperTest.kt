package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the RBAC mappers. The most operationally-meaningful decisions:
 *
 *   * `builtin` flag identifies cluster-shipped roles — anything
 *     starting with `system:` (`system:masters`, `system:node-proxier`,
 *     …) and the four user-facing baselines (`cluster-admin`, `view`,
 *     `edit`, `admin`).
 *   * `bindings` is always 0 from the mapper; the route layer fills it
 *     in by counting RoleBindings that reference each Role.
 *   * `description` falls back through annotations:
 *     `rbac.authorization.kubernetes.io/autoupdate` (rendered as
 *     `autoupdate=<value>`) → `description` annotation → empty string.
 *   * ServiceAccount `iamRole` resolves through EKS / GKE / Azure
 *     workload-identity annotations in that order.
 */
class RbacMapperTest {

    // ===== Role =====

    @Test
    fun `role wire shape includes namespace and rule count`() {
        val role = RoleBuilder()
            .withNewMetadata()
                .withUid("u")
                .withName("editor").withNamespace("apps")
                .addToAnnotations("description", "Read-write access to apps")
            .endMetadata()
            .withRules(
                PolicyRuleBuilder().withVerbs("get", "list").build(),
                PolicyRuleBuilder().withVerbs("create", "update").build(),
            )
            .build()
        val w = role.toWire()
        assertEquals("u", w.id)
        assertEquals("Role", w.kind)
        assertEquals("editor", w.name)
        assertEquals("apps", w.namespace)
        assertEquals(2, w.rules)
        assertEquals(0, w.bindings)
        assertFalse(w.builtin)
        assertEquals("Read-write access to apps", w.description)
    }

    @Test
    fun `role autoupdate annotation renders as autoupdate=value description`() {
        val role = RoleBuilder()
            .withNewMetadata()
                .withName("system:auth-delegator").withNamespace("kube-system")
                .addToAnnotations("rbac.authorization.kubernetes.io/autoupdate", "true")
            .endMetadata()
            .build()
        val w = role.toWire()
        assertEquals("autoupdate=true", w.description)
        assertTrue(w.builtin, "system: prefixed names are detected as builtin")
    }

    @Test
    fun `role with no annotations has empty description`() {
        val role = RoleBuilder()
            .withNewMetadata().withName("r").withNamespace("ns").endMetadata()
            .build()
        assertEquals("", role.toWire().description)
    }

    @Test
    fun `role id falls back to Role slash ns slash name when uid missing`() {
        val role = RoleBuilder()
            .withNewMetadata().withName("r").withNamespace("ns").endMetadata()
            .build()
        assertEquals("Role/ns/r", role.toWire().id)
    }

    @Test
    fun `role rules count is 0 when rules is null`() {
        val role = RoleBuilder()
            .withNewMetadata().withName("r").withNamespace("ns").endMetadata()
            .build()
        assertEquals(0, role.toWire().rules)
    }

    // ===== ClusterRole =====

    @Test
    fun `cluster-admin is detected as builtin`() {
        val cr = ClusterRoleBuilder()
            .withNewMetadata().withName("cluster-admin").endMetadata()
            .build()
        val w = cr.toWire()
        assertEquals("ClusterRole", w.kind)
        assertNull(w.namespace, "ClusterRole has no namespace")
        assertTrue(w.builtin)
        assertEquals("ClusterRole/cluster-admin", w.id)
    }

    @Test
    fun `view edit admin are detected as builtin`() {
        for (name in listOf("view", "edit", "admin")) {
            val cr = ClusterRoleBuilder().withNewMetadata().withName(name).endMetadata().build()
            assertTrue(cr.toWire().builtin, "$name must be builtin")
        }
    }

    @Test
    fun `custom cluster role is not builtin`() {
        val cr = ClusterRoleBuilder()
            .withNewMetadata().withName("my-custom-role").endMetadata()
            .build()
        assertFalse(cr.toWire().builtin)
    }

    // ===== RoleBinding =====

    @Test
    fun `role binding wire shape includes role and subjects`() {
        val rb = RoleBindingBuilder()
            .withNewMetadata().withUid("u").withName("rb").withNamespace("ns").endMetadata()
            .withRoleRef(RoleRefBuilder().withName("editor").withKind("Role").withApiGroup("rbac.authorization.k8s.io").build())
            .withSubjects(
                SubjectBuilder().withKind("User").withName("alice").build(),
                SubjectBuilder().withKind("ServiceAccount").withName("ci").withNamespace("ci").build(),
            )
            .build()
        val w = rb.toWire()
        assertEquals("u", w.id)
        assertEquals("RoleBinding", w.kind)
        assertEquals("editor", w.role)
        assertEquals("ns", w.namespace)
        assertEquals(2, w.subjects.size)
        assertEquals("User", w.subjects[0].kind)
        assertEquals("alice", w.subjects[0].name)
        assertNull(w.subjects[0].namespace)
        assertEquals("ci", w.subjects[1].namespace)
    }

    @Test
    fun `role binding id falls back when uid missing`() {
        val rb = RoleBindingBuilder()
            .withNewMetadata().withName("rb").withNamespace("ns").endMetadata()
            .withRoleRef(RoleRefBuilder().withName("r").build())
            .build()
        assertEquals("RoleBinding/ns/rb", rb.toWire().id)
    }

    // ===== ClusterRoleBinding =====

    @Test
    fun `cluster role binding has null namespace and ClusterRoleBinding kind`() {
        val crb = ClusterRoleBindingBuilder()
            .withNewMetadata().withName("crb").endMetadata()
            .withRoleRef(RoleRefBuilder().withName("cluster-admin").build())
            .withSubjects(SubjectBuilder().withKind("User").withName("root").build())
            .build()
        val w = crb.toWire()
        assertEquals("ClusterRoleBinding", w.kind)
        assertEquals("cluster-admin", w.role)
        assertEquals("ClusterRoleBinding/crb", w.id)
        assertNull(w.namespace)
    }

    @Test
    fun `cluster role binding with no subjects emits empty subjects list`() {
        val crb = ClusterRoleBindingBuilder()
            .withNewMetadata().withName("crb").endMetadata()
            .withRoleRef(RoleRefBuilder().withName("r").build())
            .build()
        assertEquals(emptyList(), crb.toWire().subjects)
    }

    // ===== ServiceAccount =====

    @Test
    fun `service account counts mounted secrets and reports zero pods slash bindings`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata().withUid("u").withName("ci").withNamespace("apps").endMetadata()
            .addToSecrets(ObjectReferenceBuilder().withName("ci-token-1").build())
            .addToSecrets(ObjectReferenceBuilder().withName("ci-token-2").build())
            .build()
        val w = sa.toWire()
        assertEquals("u", w.id)
        assertEquals(2, w.secrets)
        assertEquals(0, w.pods)
        assertEquals(0, w.bindings)
        assertNull(w.iamRole)
    }

    @Test
    fun `service account iamRole reads EKS annotation`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata()
                .withName("sa").withNamespace("ns")
                .addToAnnotations("eks.amazonaws.com/role-arn", "arn:aws:iam::123:role/r")
            .endMetadata()
            .build()
        assertEquals("arn:aws:iam::123:role/r", sa.toWire().iamRole)
    }

    @Test
    fun `service account iamRole falls back to GKE workload-identity annotation`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata()
                .withName("sa").withNamespace("ns")
                .addToAnnotations("iam.gke.io/gcp-service-account", "sa@proj.iam.gserviceaccount.com")
            .endMetadata()
            .build()
        assertEquals("sa@proj.iam.gserviceaccount.com", sa.toWire().iamRole)
    }

    @Test
    fun `service account iamRole falls back to Azure workload-identity annotation`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata()
                .withName("sa").withNamespace("ns")
                .addToAnnotations("azure.workload.identity/client-id", "abc-123")
            .endMetadata()
            .build()
        assertEquals("abc-123", sa.toWire().iamRole)
    }

    @Test
    fun `service account iamRole is null without an identity annotation`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata().withName("sa").withNamespace("ns").endMetadata()
            .build()
        assertNull(sa.toWire().iamRole)
    }

    @Test
    fun `service account id falls back when uid missing`() {
        val sa = ServiceAccountBuilder()
            .withNewMetadata().withName("sa").withNamespace("ns").endMetadata()
            .build()
        assertEquals("ServiceAccount/ns/sa", sa.toWire().id)
    }
}
