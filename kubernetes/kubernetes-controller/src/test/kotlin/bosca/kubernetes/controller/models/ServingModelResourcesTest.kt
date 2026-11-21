package bosca.kubernetes.controller.models

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServingModelResourcesTest {
    @Test
    fun `instance captures profile resources and pairs exact exports without a deployment or job`() {
        val profile = profile()
        val export = export(42)
        val model = buildServingModel(profile, export).apply { metadata.uid = "model-42" }
        val resources = ServingModelResources(model)
        val pod = resources.pod()
        assertEquals("Pod", pod.kind)
        assertEquals("Always", pod.spec.restartPolicy)
        assertEquals("serving-42", pod.metadata.name)
        assertEquals("gpu", pod.spec.nodeSelector["workload"])
        assertEquals("6Gi", pod.spec.containers.single().resources.limits["memory"].toString())
        assertEquals("model-42", pod.metadata.ownerReferences.single().uid)
        assertEquals("ServingModel", pod.metadata.ownerReferences.single().kind)
        val init = pod.spec.initContainers.single()
        assertEquals("{\"content\":42,\"personalized\":42}", init.env.single { it.name == "MODEL_VERSIONS_JSON" }.value)
        assertTrue(resources.configMap().data.getValue("models.config").contains("specific { versions: 42 }"))
        assertEquals(resources.service().spec.selector, pod.metadata.labels.filterKeys { it == SERVING_MODEL_LABEL })
        assertEquals("http://serving-42.test.svc:8501", resources.endpoint)
        assertTrue(resources.owns(pod))
        pod.metadata.ownerReferences.single().uid = "someone-else"
        assertFalse(resources.owns(pod))
    }

    @Test
    fun `profile changes affect new generations while existing instances retain their captured template`() {
        val profile = profile()
        val model = buildServingModel(profile, export(3)).apply { metadata.uid = "first" }
        profile.podTemplate.spec.containers.single().image = "changed"
        assertEquals("serving:test", ServingModelResources(model).pod().spec.containers.single().image)
        assertEquals("changed", ServingModelResources(buildServingModel(profile, export(4)).apply { metadata.uid = "second" })
            .pod().spec.containers.single().image)
    }

    @Test
    fun `content-only generations omit the personalized export`() {
        val model = buildServingModel(profile(), export(3).copy(personalized = false)).apply { metadata.uid = "only" }
        assertFalse(ServingModelResources(model).configMap().data.getValue("models.config").contains("personalized"))
    }

    @Test
    fun `invalid names versions prefixes and shared claims are rejected`() {
        assertFailsWith<IllegalArgumentException> { buildServingModel(profile(), export(0)) }
        assertFailsWith<IllegalArgumentException> { buildServingModel(profile(), export(3).copy(contentModelName = "../escape")) }
        assertFailsWith<IllegalArgumentException> {
            profile(mapOf("serviceNamePrefix" to "../escape"))
        }
        assertFailsWith<IllegalArgumentException> {
            val resource = profile().resource
            @Suppress("UNCHECKED_CAST")
            val spec = resource.additionalProperties["spec"] as MutableMap<String, Any?>
            spec["podTemplate"] = mapOf("spec" to mapOf("containers" to listOf(mapOf("name" to "server", "image" to "x")),
                "volumes" to listOf(mapOf("name" to "models", "persistentVolumeClaim" to mapOf("claimName" to "shared")))))
            ServingModelProfile(resource)
        }
    }

    companion object {
        fun export(version: Long) = ServingModelExport("context", version, 1, "RUNNING", "content", "personalized", true)

        fun profile(overrides: Map<String, Any?> = emptyMap()): ServingModelProfile {
            val template = Serialization.unmarshal("""
                {"spec":{"nodeSelector":{"workload":"gpu"},"containers":[{"name":"server","image":"serving:test",
                  "resources":{"limits":{"memory":"6Gi"}}}],
                  "initContainers":[{"name":"download-models","image":"loader:test"}],
                  "volumes":[{"name":"models","emptyDir":{}}]}}
            """.trimIndent(), Map::class.java)
            return ServingModelProfile(GenericKubernetesResource().apply {
                apiVersion = SERVING_API
                kind = "ServingModelProfile"
                metadata = ObjectMetaBuilder().withName("serving").withNamespace("test").withUid("profile-uid").build()
                additionalProperties["spec"] = mutableMapOf<String, Any?>(
                    "boscaUrl" to "http://bosca", "tokenSecretName" to "token", "controlTokenSecretName" to "token", "serviceNamePrefix" to "serving-",
                    "podTemplate" to template,
                ).apply { putAll(overrides) }
            })
        }
    }
}
