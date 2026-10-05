package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder
import io.fabric8.kubernetes.api.model.LabelSelectorRequirementBuilder
import io.fabric8.kubernetes.api.model.LoadBalancerIngressBuilder
import io.fabric8.kubernetes.api.model.LoadBalancerStatusBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.ServiceStatusBuilder
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPortBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Service / Ingress / NetworkPolicy mappers. These render
 * directly into list tables so the output strings are part of the
 * UI contract — review the rendered values, not just the structure.
 */
class NetworkingMapperTest {

    // ===== Service =====

    @Test
    fun `clusterip service maps with no externalIP and rendered ports`() {
        val s = ServiceBuilder()
            .withNewMetadata().withUid("u").withName("api").withNamespace("prod").endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .withClusterIP("10.0.0.42")
                .addToPorts(
                    ServicePortBuilder().withPort(80).withTargetPort(IntOrString(8080)).withProtocol("TCP").build()
                )
                .withSelector<String, String>(mapOf("app" to "api"))
            .endSpec()
            .build()
        val w = s.toWire()
        assertEquals("u", w.id)
        assertEquals("api", w.name)
        assertEquals("prod", w.namespace)
        assertEquals("ClusterIP", w.type)
        assertEquals("10.0.0.42", w.clusterIP)
        assertEquals("-", w.externalIP)
        assertEquals(listOf("80→8080/TCP"), w.ports)
        assertEquals("app=api", w.selector)
        assertEquals(0, w.endpoints)
    }

    @Test
    fun `loadbalancer service picks ingress IP first then hostname then externalIPs`() {
        val withIp = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec().withType("LoadBalancer").endSpec()
            .withStatus(
                ServiceStatusBuilder().withLoadBalancer(
                    LoadBalancerStatusBuilder()
                        .addToIngress(LoadBalancerIngressBuilder().withIp("203.0.113.5").build())
                        .build()
                ).build()
            ).build()
        assertEquals("203.0.113.5", withIp.toWire().externalIP)

        val withHostname = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec().withType("LoadBalancer").endSpec()
            .withStatus(
                ServiceStatusBuilder().withLoadBalancer(
                    LoadBalancerStatusBuilder()
                        .addToIngress(LoadBalancerIngressBuilder().withHostname("api.example.com").build())
                        .build()
                ).build()
            ).build()
        assertEquals("api.example.com", withHostname.toWire().externalIP)

        val withExternalIp = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec().withType("ClusterIP").withExternalIPs("198.51.100.1").endSpec()
            .build()
        assertEquals("198.51.100.1", withExternalIp.toWire().externalIP)
    }

    @Test
    fun `port string omits targetPort when blank`() {
        val s = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .addToPorts(ServicePortBuilder().withPort(443).withProtocol("TCP").build())
            .endSpec()
            .build()
        assertEquals(listOf("443/TCP"), s.toWire().ports)
    }

    @Test
    fun `port string renders named targetPort as the port name`() {
        val s = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .addToPorts(
                    ServicePortBuilder()
                        .withPort(80)
                        .withTargetPort(IntOrString("http"))
                        .withProtocol("TCP")
                        .build()
                )
            .endSpec()
            .build()
        assertEquals(listOf("80→http/TCP"), s.toWire().ports)
    }

    @Test
    fun `port defaults protocol to TCP when null`() {
        val s = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .addToPorts(ServicePortBuilder().withPort(53).build())
            .endSpec()
            .build()
        assertEquals(listOf("53/TCP"), s.toWire().ports)
    }

    @Test
    fun `service defaults type to ClusterIP and clusterIP to dash when null`() {
        val s = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .build()
        val w = s.toWire()
        assertEquals("ClusterIP", w.type)
        assertEquals("-", w.clusterIP)
        assertEquals(emptyList(), w.ports)
        assertEquals("", w.selector)
    }

    @Test
    fun `service id falls back to Service slash ns slash name when uid missing`() {
        val s = ServiceBuilder().withNewMetadata().withName("a").withNamespace("n").endMetadata().build()
        assertEquals("Service/n/a", s.toWire().id)
    }

    @Test
    fun `service selector renders multiple labels comma-joined`() {
        val s = ServiceBuilder()
            .withNewMetadata().withName("a").withNamespace("n").endMetadata()
            .withNewSpec()
                .withType("ClusterIP")
                .withSelector<String, String>(linkedMapOf("app" to "api", "tier" to "web"))
            .endSpec().build()
        assertEquals("app=api, tier=web", s.toWire().selector)
    }

    // ===== Ingress =====

    private fun ingressPath(path: String, svc: String, portNumber: Int? = null, portName: String? = null) =
        HTTPIngressPathBuilder()
            .withPath(path)
            .withPathType("Prefix")
            .withBackend(
                IngressBackendBuilder()
                    .withService(
                        IngressServiceBackendBuilder()
                            .withName(svc)
                            .withPort(
                                ServiceBackendPortBuilder().apply {
                                    if (portNumber != null) withNumber(portNumber)
                                    if (portName != null) withName(portName)
                                }.build()
                            )
                            .build()
                    ).build()
            ).build()

    @Test
    fun `ingress with TLS and multiple rules renders hosts paths and backends`() {
        val i = IngressBuilder()
            .withNewMetadata().withUid("u").withName("web").withNamespace("prod").endMetadata()
            .withNewSpec()
                .withIngressClassName("nginx")
                .addToRules(
                    IngressRuleBuilder()
                        .withHost("api.example.com")
                        .withHttp(
                            HTTPIngressRuleValueBuilder()
                                .addToPaths(ingressPath("/", "api", portNumber = 80))
                                .build()
                        ).build()
                )
                .addToRules(
                    IngressRuleBuilder()
                        .withHost("api.example.com")  // duplicate host
                        .withHttp(
                            HTTPIngressRuleValueBuilder()
                                .addToPaths(ingressPath("/v2", "api", portName = "https"))
                                .build()
                        ).build()
                )
                .addToTls(IngressTLSBuilder().withHosts("api.example.com").build())
            .endSpec()
            .build()
        val w = i.toWire()
        assertEquals("u", w.id)
        assertEquals("nginx", w.ingressClass)
        assertEquals(listOf("api.example.com"), w.hosts, "distinct hosts only")
        assertEquals(listOf("/", "/v2"), w.paths)
        assertEquals(listOf("api:80", "api:https"), w.backends)
        assertTrue(w.tls)
    }

    @Test
    fun `ingress class falls back to legacy annotation`() {
        val i = IngressBuilder()
            .withNewMetadata()
                .withName("web").withNamespace("n")
                .addToAnnotations("kubernetes.io/ingress.class", "traefik")
            .endMetadata()
            .withNewSpec().endSpec()
            .build()
        assertEquals("traefik", i.toWire().ingressClass)
    }

    @Test
    fun `ingress class falls back to dash when neither field nor annotation is present`() {
        val i = IngressBuilder()
            .withNewMetadata().withName("web").withNamespace("n").endMetadata()
            .withNewSpec().endSpec()
            .build()
        assertEquals("-", i.toWire().ingressClass)
    }

    @Test
    fun `ingress backend renders just the service name when port has neither number nor name`() {
        val i = IngressBuilder()
            .withNewMetadata().withName("web").withNamespace("n").endMetadata()
            .withNewSpec()
                .addToRules(
                    IngressRuleBuilder().withHttp(
                        HTTPIngressRuleValueBuilder()
                            .addToPaths(
                                HTTPIngressPathBuilder().withPath("/")
                                    .withPathType("Prefix")
                                    .withBackend(
                                        IngressBackendBuilder().withService(
                                            IngressServiceBackendBuilder().withName("svc").build()
                                        ).build()
                                    ).build()
                            ).build()
                    ).build()
                )
            .endSpec().build()
        assertEquals(listOf("svc"), i.toWire().backends)
    }

    @Test
    fun `ingress tls=false when there are no TLS entries`() {
        val i = IngressBuilder()
            .withNewMetadata().withName("web").withNamespace("n").endMetadata()
            .withNewSpec().endSpec()
            .build()
        assertFalse(i.toWire().tls)
    }

    @Test
    fun `ingress id falls back to Ingress slash ns slash name`() {
        val i = IngressBuilder()
            .withNewMetadata().withName("web").withNamespace("n").endMetadata()
            .withNewSpec().endSpec()
            .build()
        assertEquals("Ingress/n/web", i.toWire().id)
    }

    // ===== NetworkPolicy =====

    @Test
    fun `network policy with empty ingress rules but Ingress type renders deny-all`() {
        val np = NetworkPolicyBuilder()
            .withNewMetadata().withName("default-deny").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(LabelSelectorBuilder().build())  // empty selector — all pods
                .withPolicyTypes("Ingress")
            .endSpec()
            .build()
        val w = np.toWire()
        assertEquals("all pods", w.podSelector)
        assertEquals("deny-all", w.ingress)
        assertEquals("-", w.egress, "egress column is dash when policy doesn't apply to egress")
    }

    @Test
    fun `network policy with one ingress rule renders singular rule label`() {
        val np = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(LabelSelectorBuilder().withMatchLabels<String, String>(mapOf("app" to "api")).build())
                .withPolicyTypes("Ingress")
                .addToIngress(NetworkPolicyIngressRuleBuilder().build())
            .endSpec()
            .build()
        assertEquals("1 rule", np.toWire().ingress)
    }

    @Test
    fun `network policy with multiple ingress rules renders pluralized label`() {
        val np = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(LabelSelectorBuilder().build())
                .withPolicyTypes("Ingress")
                .addToIngress(NetworkPolicyIngressRuleBuilder().build())
                .addToIngress(NetworkPolicyIngressRuleBuilder().build())
            .endSpec()
            .build()
        assertEquals("2 rules", np.toWire().ingress)
    }

    @Test
    fun `network policy egress mirrors ingress logic`() {
        val denyAll = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(LabelSelectorBuilder().build())
                .withPolicyTypes("Egress")
            .endSpec()
            .build()
        assertEquals("deny-all", denyAll.toWire().egress)

        val twoRules = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(LabelSelectorBuilder().build())
                .withPolicyTypes("Egress")
                .addToEgress(NetworkPolicyEgressRuleBuilder().build())
                .addToEgress(NetworkPolicyEgressRuleBuilder().build())
            .endSpec()
            .build()
        assertEquals("2 rules", twoRules.toWire().egress)
    }

    @Test
    fun `network policy selector renders matchLabels and matchExpressions`() {
        val np = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .withPodSelector(
                    LabelSelectorBuilder()
                        .withMatchLabels<String, String>(mapOf("app" to "api"))
                        .addToMatchExpressions(
                            LabelSelectorRequirementBuilder()
                                .withKey("tier").withOperator("In").withValues("web", "api").build()
                        ).build()
                )
                .withPolicyTypes("Ingress")
            .endSpec()
            .build()
        val w = np.toWire()
        assertEquals("app=api, tier In (web,api)", w.podSelector)
    }

    @Test
    fun `network policy id falls back when uid missing`() {
        val np = NetworkPolicyBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec().withPodSelector(LabelSelectorBuilder().build()).endSpec()
            .build()
        assertEquals("NetworkPolicy/ns/p", np.toWire().id)
    }
}
