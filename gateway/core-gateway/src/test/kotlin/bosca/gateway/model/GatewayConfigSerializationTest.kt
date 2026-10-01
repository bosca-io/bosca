package bosca.gateway.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for the wire format the Rust proxy reads.
 *
 * If [GatewayRoute.injectHeaders] is ever changed back to a `String`
 * type, this test fails — the proxy's `HashMap<String, String>`
 * deserialization cannot pull headers from a quoted JSON string. The
 * route field MUST be a structured [JsonElement] so the GraphQL/JSON
 * round-trip emits a real object.
 */
class GatewayConfigSerializationTest {

    @Test
    fun `injectHeaders is a JsonElement in the public contract`() {
        val type = GatewayRoute::class.java.getDeclaredField("injectHeaders").type
        assertTrue(
            JsonElement::class.java.isAssignableFrom(type),
            "GatewayRoute.injectHeaders must be a JsonElement (got ${type.name})",
        )

        val inputType = GatewayRouteInput::class.java.getDeclaredField("injectHeaders").type
        assertTrue(
            JsonElement::class.java.isAssignableFrom(inputType),
            "GatewayRouteInput.injectHeaders must be a JsonElement (got ${inputType.name})",
        )
    }

    @Test
    fun `default injectHeaders is an empty JSON object`() {
        val route = GatewayRoute(
            gatewayId = bosca.serialization.UUID.random(),
            pathPattern = "/x",
            authMethod = GatewayAuthMethod.NONE,
        )
        assertTrue(
            route.injectHeaders is JsonObject,
            "default injectHeaders should be a JsonObject, was ${route.injectHeaders::class.simpleName}",
        )
    }
}
