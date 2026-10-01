package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimListBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassListBuilder
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [StorageClassesRoute] and [PvcsRoute] — admin gate, cluster id
 * parsing, and namespace narrowing for PVCs.
 */
@OptIn(ExperimentalUuidApi::class)
class StorageRoutesTest {

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

    @Test
    fun `storageclasses returns all classes`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.storage().v1().storageClasses().list() } returns StorageClassListBuilder()
            .addToItems(
                StorageClassBuilder()
                    .withNewMetadata().withName("standard").endMetadata()
                    .withProvisioner("kubernetes.io/aws-ebs")
                    .build()
            )
            .addToItems(
                StorageClassBuilder()
                    .withNewMetadata().withName("ssd").endMetadata()
                    .withProvisioner("ebs.csi.aws.com")
                    .build()
            )
            .build()

        val r = StorageClassesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(listOf("standard", "ssd"), r?.items?.map { it.name })
    }

    @Test
    fun `storageclasses propagates fabric8 errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.storage().v1().storageClasses().list() } throws RuntimeException("boom")
        assertFailsWith<RuntimeException> {
            StorageClassesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        }
    }

    @Test
    fun `pvcs fan out when no namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.persistentVolumeClaims().inAnyNamespace().list() } returns
            PersistentVolumeClaimListBuilder()
                .addToItems(
                    PersistentVolumeClaimBuilder()
                        .withNewMetadata().withName("data").withNamespace("a").endMetadata()
                        .withNewStatus().withPhase("Bound").endStatus()
                        .build()
                )
                .addToItems(
                    PersistentVolumeClaimBuilder()
                        .withNewMetadata().withName("data").withNamespace("b").endMetadata()
                        .withNewStatus().withPhase("Bound").endStatus()
                        .build()
                )
                .build()

        val r = PvcsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(setOf("a", "b"), r?.items?.map { it.namespace }?.toSet())
    }

    @Test
    fun `pvcs route through inNamespace when set`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.persistentVolumeClaims().inNamespace("prod").list() } returns
            PersistentVolumeClaimListBuilder()
                .addToItems(
                    PersistentVolumeClaimBuilder()
                        .withNewMetadata().withName("data").withNamespace("prod").endMetadata()
                        .withNewStatus().withPhase("Bound").endStatus()
                        .build()
                )
                .build()

        val r = PvcsRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals("prod", r?.items?.single()?.namespace)
        verify { client.persistentVolumeClaims().inNamespace("prod").list() }
    }

    @Test
    fun `storageclasses non-admin throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            StorageClassesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }

    @Test
    fun `pvcs non-admin throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            PvcsRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
        assertTrue(true)
    }
}
