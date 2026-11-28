package bosca.kubernetes.controller.jobs

import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.KubernetesJobSecrets
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueueFactory
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.Base64
import kotlin.test.*

class KubernetesRuntimeResourcesTest {
    @Test
    fun `real kubernetes HTTP client creates encrypted workload secret and owner bound endpoint`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val job = JobBuilder().withNewMetadata().withName("runtime-1").withNamespace("kit")
                .withUid("immutable-owner").endMetadata().build()
            val missing = """{"apiVersion":"v1","kind":"Status","code":404,"reason":"NotFound"}"""
            server.enqueue(MockResponse.Builder().code(404).body(missing).build())
            server.enqueue(MockResponse.Builder().body("""{"apiVersion":"v1","kind":"Secret","metadata":{"name":"runtime-1"}}""").build())
            server.enqueue(MockResponse.Builder().code(404).body(missing).build())
            server.enqueue(MockResponse.Builder().body("""{"apiVersion":"v1","kind":"Service","metadata":{"name":"runtime-1"}}""").build())
            val encryption = mockk<EncryptionService>()
            val key = UUID.random()
            coEvery { encryption.decrypt(any(), key) } returns """{"BOSCA_TOKEN":"scoped-secret"}""".encodeToByteArray()
            val factory = mockk<JobQueueFactory> { every { create(any()) } returns mockk(relaxed = true) }
            val controller = KubernetesJobController(encryption, mockk(), mockk(), mockk(), factory, mockk(), Json)
            val request = KubernetesJobRequest("kit", "chat:1", secrets = KubernetesJobSecrets(key,
                Base64.getEncoder().encodeToString(byteArrayOf(1)), Base64.getEncoder().encodeToString(byteArrayOf(2)), listOf("BOSCA_TOKEN")), servicePort = 8080)
            val config = ConfigBuilder().withMasterUrl(server.url("/").toString()).withNamespace("kit").build()
            KubernetesClientBuilder().withConfig(config).build().use { client ->
                controller.ensureWorkloadResources(client, job, request, key)
            }
            assertEquals("/api/v1/namespaces/kit/secrets/runtime-1", server.takeRequest().url.encodedPath)
            val secretRequest = server.takeRequest()
            assertEquals("POST", secretRequest.method)
            val secret = Json.parseToJsonElement(requireNotNull(secretRequest.body).utf8()).jsonObject
            assertEquals("scoped-secret", secret.getValue("stringData").jsonObject.getValue("BOSCA_TOKEN").jsonPrimitive.content)
            assertEquals("immutable-owner", secret.getValue("metadata").jsonObject.getValue("ownerReferences").jsonArray.single().jsonObject.getValue("uid").jsonPrimitive.content)
            assertTrue(secret.getValue("immutable").jsonPrimitive.boolean)
            assertEquals("/api/v1/namespaces/kit/services/runtime-1", server.takeRequest().url.encodedPath)
            val service = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
            assertEquals(key.toString(), service.getValue("spec").jsonObject.getValue("selector").jsonObject.getValue(DISPATCH_ID_LABEL).jsonPrimitive.content)
            assertEquals(8080, service.getValue("spec").jsonObject.getValue("ports").jsonArray.single().jsonObject.getValue("port").jsonPrimitive.int)
            assertFalse(Json { serializersModule = kotlinx.serialization.modules.SerializersModule { contextual(UUID::class, bosca.serialization.UUIDSerializer()) } }.encodeToString(KubernetesJobRequest.serializer(), request).contains("scoped-secret"))
            controller.close()
        }
    }
}
