package bosca.profiles.web

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class PageLoadingTest {
    @Test
    fun `each account page loads its data in one GraphQL request`() = runBlocking {
        val responses = mapOf(
            "Dashboard" to """{"profiles":{"current":[]},"security":{"principals":{"current":{"verified":true,"lastLogin":null}}},"devices":{"devices":[]}}""",
            "ProfileDetails" to """{"profiles":{"attributeTypes":{"all":[]},"current":[{"id":"profile","name":"Profile","slug":"profile","visibility":"PUBLIC","searchable":true,"isPrimary":true,"created":"today","modified":"today","attributes":[]}]}}""",
            "Relationships" to """{"profiles":{"current":[{"id":"profile","name":"Profile","isPrimary":true,"relationships":[],"incomingRelationshipRequests":[],"outgoingRelationshipRequests":[]}]}}""",
            "CommunicationPreferences" to """{"communications":{"notificationTypes":[],"myNotificationPreferences":[],"myNotificationSettings":null}}""",
            "RegisteredDevices" to """{"devices":{"devices":[]},"security":{"apiTokens":{"my":[]}}}""",
            "AccountSecurity" to """{"security":{"thirdPartyProviders":[],"principals":{"current":{"id":"principal","verified":true,"created":"today","lastLogin":null,"credentials":[],"loginHistory":[]}},"passkeys":{"current":[]}}}""",
            "LoginProviders" to """{"security":{"thirdPartyProviders":[]}}""",
        )
        val pages: List<Pair<String, suspend () -> Any>> = listOf(
            "Dashboard" to { dashboard() },
            "ProfileDetails" to { profileEditor() },
            "Relationships" to { relationshipsModel() },
            "CommunicationPreferences" to { communicationsModel() },
            "RegisteredDevices" to { devicesModel() },
            "AccountSecurity" to { securityModel() },
            "LoginProviders" to { loginProviders() },
        )
        for ((expected, load) in pages) {
            val operations = mutableListOf<String>()
            val gql = object : GraphQLClient {
                override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                    val name = requireNotNull(operationName)
                    operations.add(name)
                    return Json.parseToJsonElement(responses.getValue(name))
                }
            }
            withRenderContext(RenderContext(gql = gql)) { load() }
            assertEquals(listOf(expected), operations, expected)
        }
    }
}
