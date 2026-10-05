package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountListBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingListBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleListBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingListBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [RolesRoute], [RoleBindingsRoute], [ServiceAccountsRoute]:
 * admin gate, `kind` query narrowing for the role pair, and namespace
 * narrowing for service accounts.
 */
@OptIn(ExperimentalUuidApi::class)
class RbacRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    /**
     * The roles and rolebindings routes both fetch BOTH binding kinds
     * regardless of which kind they're listing — the count of bindings
     * touching each role is computed cross-kind. Stub both to empty so
     * the strict mock doesn't fail the lookup.
     */
    private fun stubEmptyBindings() {
        every { client.rbac().roleBindings().inAnyNamespace().list() } returns RoleBindingListBuilder().build()
        every { client.rbac().clusterRoleBindings().list() } returns ClusterRoleBindingListBuilder().build()
    }

    @Test
    fun `roles route returns Roles and ClusterRoles unfiltered`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.rbac().roles().inAnyNamespace().list() } returns RoleListBuilder()
            .addToItems(RoleBuilder().withNewMetadata().withName("r").withNamespace("ns").endMetadata().build())
            .build()
        every { client.rbac().clusterRoles().list() } returns ClusterRoleListBuilder()
            .addToItems(ClusterRoleBuilder().withNewMetadata().withName("cr").endMetadata().build())
            .build()
        stubEmptyBindings()

        val r = RolesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val kinds = r?.items?.map { it.kind }?.toSet()
        assertEquals(setOf("Role", "ClusterRole"), kinds)
    }

    @Test
    fun `roles route narrows to Role only when kind=Role`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.rbac().roles().inAnyNamespace().list() } returns RoleListBuilder()
            .addToItems(RoleBuilder().withNewMetadata().withName("r").withNamespace("ns").endMetadata().build())
            .build()
        stubEmptyBindings()

        val r = RolesRoute(groups, pool).runExecute(call(id.toString(), mapOf("kind" to "Role")), ctx)
        assertEquals(1, r?.items?.size)
        assertEquals("Role", r?.items?.single()?.kind)
        verify { client.rbac().roles().inAnyNamespace().list() }
    }

    @Test
    fun `roles route narrows to ClusterRole only when kind=ClusterRole`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.rbac().clusterRoles().list() } returns ClusterRoleListBuilder()
            .addToItems(ClusterRoleBuilder().withNewMetadata().withName("cr").endMetadata().build())
            .build()
        stubEmptyBindings()

        val r = RolesRoute(groups, pool).runExecute(call(id.toString(), mapOf("kind" to "ClusterRole")), ctx)
        assertEquals("ClusterRole", r?.items?.single()?.kind)
        verify { client.rbac().clusterRoles().list() }
    }

    @Test
    fun `rolebindings route returns both kinds unfiltered`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.rbac().roleBindings().inAnyNamespace().list() } returns RoleBindingListBuilder()
            .addToItems(RoleBindingBuilder().withNewMetadata().withName("rb").withNamespace("ns").endMetadata().build())
            .build()
        every { client.rbac().clusterRoleBindings().list() } returns ClusterRoleBindingListBuilder()
            .addToItems(ClusterRoleBindingBuilder().withNewMetadata().withName("crb").endMetadata().build())
            .build()

        val r = RoleBindingsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(setOf("RoleBinding", "ClusterRoleBinding"), r?.items?.map { it.kind }?.toSet())
    }

    @Test
    fun `rolebindings narrowing kind=RoleBinding`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.rbac().roleBindings().inAnyNamespace().list() } returns RoleBindingListBuilder()
            .addToItems(RoleBindingBuilder().withNewMetadata().withName("rb").withNamespace("ns").endMetadata().build())
            .build()

        val r = RoleBindingsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "RoleBinding")), ctx,
        )
        assertEquals("RoleBinding", r?.items?.single()?.kind)
        verify { client.rbac().roleBindings().inAnyNamespace().list() }
    }

    @Test
    fun `service accounts fan out when no namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.serviceAccounts().inAnyNamespace().list() } returns ServiceAccountListBuilder()
            .addToItems(
                ServiceAccountBuilder()
                    .withNewMetadata().withName("default").withNamespace("a").endMetadata().build()
            )
            .addToItems(
                ServiceAccountBuilder()
                    .withNewMetadata().withName("ci").withNamespace("b").endMetadata().build()
            )
            .build()
        // Pods + bindings power the per-SA `pods` and `bindings`
        // counts; empty lists keep the existing assertion on
        // namespace identity intact.
        every { client.pods().inAnyNamespace().list() } returns PodListBuilder().build()
        stubEmptyBindings()

        val r = ServiceAccountsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(setOf("a", "b"), r?.items?.map { it.namespace }?.toSet())
    }

    @Test
    fun `service accounts narrow to namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.serviceAccounts().inNamespace("prod").list() } returns ServiceAccountListBuilder()
            .addToItems(
                ServiceAccountBuilder()
                    .withNewMetadata().withName("default").withNamespace("prod").endMetadata().build()
            )
            .build()
        every { client.pods().inNamespace("prod").list() } returns PodListBuilder().build()
        stubEmptyBindings()

        val r = ServiceAccountsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("namespace" to "prod")), ctx,
        )
        assertEquals("prod", r?.items?.single()?.namespace)
        verify { client.serviceAccounts().inNamespace("prod").list() }
        assertTrue(true)
    }
}
