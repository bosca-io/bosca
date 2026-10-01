package bosca.hubspot

import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.configuration.HubSpotExpressions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HubSpotConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testSerializeHubSpotConfiguration() {
        val config = HubSpotConfiguration(
            token = "test-token-123",
            expressions = HubSpotExpressions(
                generic = "{ \"email\": profile.name }",
                organization = "{ \"company\": organization.name }"
            )
        )
        val encoded = json.encodeToString(HubSpotConfiguration.serializer(), config)
        val decoded = json.decodeFromString(HubSpotConfiguration.serializer(), encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun testDeserializeHubSpotConfigurationFromJson() {
        val jsonString = """
            {
                "token": "my-secret-token",
                "expressions": {
                    "generic": "{ \"firstname\": profile.name }",
                    "organization": "{ \"company\": organization.name }"
                }
            }
        """.trimIndent()
        val config = json.decodeFromString(HubSpotConfiguration.serializer(), jsonString)
        assertEquals("my-secret-token", config.token)
        assertEquals("{ \"firstname\": profile.name }", config.expressions.generic)
        assertEquals("{ \"company\": organization.name }", config.expressions.organization)
    }

    @Test
    fun testHubSpotExpressionsEquality() {
        val expressions1 = HubSpotExpressions(generic = "expr1", organization = "expr2")
        val expressions2 = HubSpotExpressions(generic = "expr1", organization = "expr2")
        assertEquals(expressions1, expressions2)
    }

    @Test
    fun testHubSpotConfigurationDefaults() {
        val config = HubSpotConfiguration(
            token = "test-token",
            expressions = HubSpotExpressions(generic = "{}", organization = "{}")
        )
        assertEquals(279, config.contactToCompanyAssociationTypeId)
        assertEquals("HUBSPOT_DEFINED", config.contactToCompanyAssociationCategory)
        assertEquals(emptyList(), config.listIds)
    }

    @Test
    fun testHubSpotConfigurationWithListIds() {
        val config = HubSpotConfiguration(
            token = "test-token",
            expressions = HubSpotExpressions(generic = "{}", organization = "{}"),
            listIds = listOf("list-1", "list-2")
        )
        assertEquals(listOf("list-1", "list-2"), config.listIds)
        val encoded = json.encodeToString(HubSpotConfiguration.serializer(), config)
        val decoded = json.decodeFromString(HubSpotConfiguration.serializer(), encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun testHubSpotConfigurationDeserializationWithoutListIds() {
        val jsonString = """
            {
                "token": "test-token",
                "expressions": {
                    "generic": "{}",
                    "organization": "{}"
                }
            }
        """.trimIndent()
        val config = json.decodeFromString(HubSpotConfiguration.serializer(), jsonString)
        assertEquals(emptyList(), config.listIds)
    }

    @Test
    fun testHubSpotConfigurationCustomAssociationType() {
        val config = HubSpotConfiguration(
            token = "test-token",
            expressions = HubSpotExpressions(generic = "{}", organization = "{}"),
            contactToCompanyAssociationTypeId = 123,
            contactToCompanyAssociationCategory = "USER_DEFINED"
        )
        assertEquals(123, config.contactToCompanyAssociationTypeId)
        assertEquals("USER_DEFINED", config.contactToCompanyAssociationCategory)
    }
}
