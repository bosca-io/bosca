package bosca.kubernetes.controller.util

import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.ConditionBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClassBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClassStatusBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayStatusAddressBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayStatusBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPBackendRefBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPPathMatchBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteMatchBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteRuleBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteStatusBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ParentReferenceBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.RouteParentStatusBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Gateway API mappers. The most interesting decisions are the
 * status ladders, because Gateway API status is unusual — it spreads
 * acceptance across multiple conditions per resource:
 *
 *   * Gateway: `Programmed=True` → OK, `Accepted=True` (but not yet
 *     Programmed) → WARN, anything False → ERROR, missing → WARN.
 *   * HTTPRoute: needs both `Accepted=True` AND `ResolvedRefs=True` for
 *     OK; one of two True → WARN; either False → ERROR.
 *
 * Backend rendering flattens `(serviceName, port, kind)` triples to
 * `service:port` strings; `kind` is prefixed when it's a non-Service
 * backend.
 */
class GatewayApiMapperTest {

    // ===== GatewayClass =====

    @Test
    fun `gatewayclass with Accepted=True maps to accepted=true`() {
        val gc = GatewayClassBuilder()
            .withNewMetadata().withName("nginx").endMetadata()
            .withNewSpec().withControllerName("k8s.io/nginx").endSpec()
            .withStatus(
                GatewayClassStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                    .build()
            )
            .build()
        val w = gc.toWire()
        assertEquals("nginx", w.name)
        assertEquals("k8s.io/nginx", w.controller)
        assertTrue(w.accepted)
    }

    @Test
    fun `gatewayclass without Accepted=True maps to accepted=false`() {
        val gc = GatewayClassBuilder()
            .withNewMetadata().withName("traefik").endMetadata()
            .withNewSpec().withControllerName("traefik.io/gateway-controller").endSpec()
            .build()
        assertFalse(gc.toWire().accepted)
    }

    @Test
    fun `gatewayclass Accepted with status=False is not accepted`() {
        val gc = GatewayClassBuilder()
            .withNewMetadata().withName("custom").endMetadata()
            .withStatus(
                GatewayClassStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("False").build())
                    .build()
            )
            .build()
        assertFalse(gc.toWire().accepted)
    }

    // ===== Gateway =====

    @Test
    fun `gateway Programmed=True maps to OK and surfaces addresses and listener count`() {
        val g = GatewayBuilder()
            .withNewMetadata().withUid("u").withName("gw").withNamespace("net").endMetadata()
            .withNewSpec()
                .withGatewayClassName("nginx")
                .addToListeners(ListenerBuilder().withName("http").withPort(80).withProtocol("HTTP").build())
                .addToListeners(ListenerBuilder().withName("https").withPort(443).withProtocol("HTTPS").build())
            .endSpec()
            .withStatus(
                GatewayStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                    .addToConditions(ConditionBuilder().withType("Programmed").withStatus("True").build())
                    .addToAddresses(GatewayStatusAddressBuilder().withType("IPAddress").withValue("203.0.113.5").build())
                    .build()
            )
            .build()
        val w = g.toWire(routes = 7)
        assertEquals("u", w.id)
        assertEquals("nginx", w.gatewayClass)
        assertEquals(2, w.listeners)
        assertEquals(7, w.routes)
        assertEquals(listOf("203.0.113.5"), w.addresses)
        assertEquals(WorkloadStatus.OK, w.status)
    }

    @Test
    fun `gateway Accepted=True but Programmed missing maps to WARN`() {
        val g = GatewayBuilder()
            .withNewMetadata().withName("gw").withNamespace("net").endMetadata()
            .withStatus(
                GatewayStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                    .build()
            )
            .build()
        assertEquals(WorkloadStatus.WARN, g.toWire().status)
    }

    @Test
    fun `gateway with any Accepted or Programmed False maps to ERROR`() {
        val acceptedFalse = GatewayBuilder()
            .withNewMetadata().withName("gw").withNamespace("net").endMetadata()
            .withStatus(
                GatewayStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("False").build())
                    .build()
            )
            .build()
        assertEquals(WorkloadStatus.ERROR, acceptedFalse.toWire().status)

        val programmedFalse = GatewayBuilder()
            .withNewMetadata().withName("gw").withNamespace("net").endMetadata()
            .withStatus(
                GatewayStatusBuilder()
                    .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                    .addToConditions(ConditionBuilder().withType("Programmed").withStatus("False").build())
                    .build()
            )
            .build()
        assertEquals(WorkloadStatus.ERROR, programmedFalse.toWire().status)
    }

    @Test
    fun `gateway with no conditions falls through to WARN`() {
        val g = GatewayBuilder()
            .withNewMetadata().withName("gw").withNamespace("net").endMetadata()
            .build()
        assertEquals(WorkloadStatus.WARN, g.toWire().status)
    }

    @Test
    fun `gateway id falls back when uid missing`() {
        val g = GatewayBuilder()
            .withNewMetadata().withName("gw").withNamespace("net").endMetadata()
            .build()
        assertEquals("Gateway/net/gw", g.toWire().id)
    }

    // ===== HTTPRoute =====

    @Test
    fun `httproute Accepted=True and ResolvedRefs=True maps to OK`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withUid("u").withName("api").withNamespace("net").endMetadata()
            .withNewSpec()
                .addToParentRefs(ParentReferenceBuilder().withName("gw").build())
                .addToHostnames("api.example.com")
                .addToRules(
                    HTTPRouteRuleBuilder()
                        .addToMatches(
                            HTTPRouteMatchBuilder()
                                .withPath(HTTPPathMatchBuilder().withType("PathPrefix").withValue("/").build())
                                .build()
                        )
                        .addToBackendRefs(
                            HTTPBackendRefBuilder().withName("api").withPort(80).build()
                        )
                        .build()
                )
            .endSpec()
            .withStatus(
                HTTPRouteStatusBuilder().addToParents(
                    RouteParentStatusBuilder()
                        .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                        .addToConditions(ConditionBuilder().withType("ResolvedRefs").withStatus("True").build())
                        .build()
                ).build()
            )
            .build()
        val w = r.toWire()
        assertEquals("u", w.id)
        assertEquals(listOf("gw"), w.parents)
        assertEquals(listOf("api.example.com"), w.hosts)
        assertEquals(listOf("/"), w.paths)
        assertEquals(listOf("api:80"), w.backends)
        assertEquals(1, w.rules)
        assertEquals(WorkloadStatus.OK, w.status)
    }

    @Test
    fun `httproute Accepted=True ResolvedRefs missing maps to WARN`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withStatus(
                HTTPRouteStatusBuilder().addToParents(
                    RouteParentStatusBuilder()
                        .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                        .build()
                ).build()
            )
            .build()
        assertEquals(WorkloadStatus.WARN, r.toWire().status)
    }

    @Test
    fun `httproute either condition False maps to ERROR`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withStatus(
                HTTPRouteStatusBuilder().addToParents(
                    RouteParentStatusBuilder()
                        .addToConditions(ConditionBuilder().withType("Accepted").withStatus("True").build())
                        .addToConditions(ConditionBuilder().withType("ResolvedRefs").withStatus("False").build())
                        .build()
                ).build()
            )
            .build()
        assertEquals(WorkloadStatus.ERROR, r.toWire().status)
    }

    @Test
    fun `httproute backend with non-Service kind embeds the kind`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withNewSpec()
                .addToRules(
                    HTTPRouteRuleBuilder()
                        .addToBackendRefs(
                            HTTPBackendRefBuilder()
                                .withKind("BackendTLSPolicy")
                                .withName("policy")
                                .withPort(443)
                                .build()
                        )
                        .build()
                )
            .endSpec()
            .build()
        assertEquals(listOf("BackendTLSPolicy/policy:443"), r.toWire().backends)
    }

    @Test
    fun `httproute backend without port renders just the service name`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withNewSpec()
                .addToRules(
                    HTTPRouteRuleBuilder()
                        .addToBackendRefs(
                            HTTPBackendRefBuilder().withName("svc").build()
                        )
                        .build()
                )
            .endSpec()
            .build()
        assertEquals(listOf("svc"), r.toWire().backends)
    }

    @Test
    fun `httproute id falls back when uid missing`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .build()
        assertEquals("HTTPRoute/net/api", r.toWire().id)
    }

    @Test
    fun `httproute paths are distinct across rules`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withNewSpec()
                .addToRules(
                    HTTPRouteRuleBuilder()
                        .addToMatches(HTTPRouteMatchBuilder().withPath(HTTPPathMatchBuilder().withValue("/v1").build()).build())
                        .addToMatches(HTTPRouteMatchBuilder().withPath(HTTPPathMatchBuilder().withValue("/v2").build()).build())
                        .build()
                )
                .addToRules(
                    HTTPRouteRuleBuilder()
                        .addToMatches(HTTPRouteMatchBuilder().withPath(HTTPPathMatchBuilder().withValue("/v1").build()).build())
                        .build()
                )
            .endSpec()
            .build()
        assertEquals(listOf("/v1", "/v2"), r.toWire().paths)
    }

    @Test
    fun `httproute parents are distinct names from parentRefs`() {
        val r = HTTPRouteBuilder()
            .withNewMetadata().withName("api").withNamespace("net").endMetadata()
            .withNewSpec()
                .addToParentRefs(ParentReferenceBuilder().withName("gw-a").build())
                .addToParentRefs(ParentReferenceBuilder().withName("gw-a").build())
                .addToParentRefs(ParentReferenceBuilder().withName("gw-b").build())
            .endSpec()
            .build()
        assertEquals(listOf("gw-a", "gw-b"), r.toWire().parents)
    }
}
