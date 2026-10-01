package bosca.hubspot

import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.configuration.HubSpotExpressions
import bosca.hubspot.transformer.HubspotData
import bosca.profile.model.ProfileType
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class HubSpotEntityTransformerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testJsonataGenericProfileExpression() {
        val expression = """
            {
                "email": profile.name,
                "firstname": "John",
                "lastname": "Doe"
            }
        """.trimIndent()
        val data = mapOf(
            "profile" to mapOf(
                "name" to "john@example.com",
                "type" to "GENERIC"
            )
        )
        val jsonata = Jsonata.jsonata(expression)
        val result = jsonata.evaluate(data)
        assertNotNull(result)
        val resultElement = result.toJsonElement().jsonObject
        assertEquals("john@example.com", resultElement["email"]?.let { (it as JsonPrimitive).content })
        assertEquals("John", resultElement["firstname"]?.let { (it as JsonPrimitive).content })
        assertEquals("Doe", resultElement["lastname"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun testJsonataOrganizationProfileExpression() {
        val expression = """
            {
                "company": organization.name,
                "domain": organization.domain
            }
        """.trimIndent()
        val data = mapOf(
            "organization" to mapOf(
                "name" to "Acme Corp",
                "domain" to "acme.com"
            ),
            "profile" to mapOf(
                "name" to "Acme Corp",
                "type" to "ORGANIZATION"
            )
        )
        val jsonata = Jsonata.jsonata(expression)
        val result = jsonata.evaluate(data)
        assertNotNull(result)
        val resultElement = result.toJsonElement().jsonObject
        assertEquals("Acme Corp", resultElement["company"]?.let { (it as JsonPrimitive).content })
        assertEquals("acme.com", resultElement["domain"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun testJsonataExpressionWithAttributes() {
        val expression = """
            {
                "email": attributes.`bosca.profiles.email`.attributes.email,
                "firstname": attributes.`bosca.profiles.name`.attributes.first,
                "lastname": attributes.`bosca.profiles.name`.attributes.last
            }
        """.trimIndent()
        val data = mapOf(
            "profile" to mapOf(
                "name" to "Test User",
                "type" to "GENERIC"
            ),
            "attributes" to mapOf(
                "bosca.profiles.email" to mapOf(
                    "attributes" to mapOf("email" to "test@example.com")
                ),
                "bosca.profiles.name" to mapOf(
                    "attributes" to mapOf("first" to "Test", "last" to "User")
                )
            )
        )
        val jsonata = Jsonata.jsonata(expression)
        val result = jsonata.evaluate(data)
        assertNotNull(result)
        val resultElement = result.toJsonElement().jsonObject
        assertEquals("test@example.com", resultElement["email"]?.let { (it as JsonPrimitive).content })
        assertEquals("Test", resultElement["firstname"]?.let { (it as JsonPrimitive).content })
        assertEquals("User", resultElement["lastname"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun testJsonataExpressionWithMemberships() {
        val expression = """
            {
                "email": profile.name,
                "company": memberships[0].organization.name
            }
        """.trimIndent()
        val data = mapOf(
            "profile" to mapOf(
                "name" to "user@example.com",
                "type" to "GENERIC"
            ),
            "memberships" to listOf(
                mapOf(
                    "organization" to mapOf("name" to "First Org"),
                    "profile" to mapOf("name" to "First Org")
                ),
                mapOf(
                    "organization" to mapOf("name" to "Second Org"),
                    "profile" to mapOf("name" to "Second Org")
                )
            )
        )
        val jsonata = Jsonata.jsonata(expression)
        val result = jsonata.evaluate(data)
        assertNotNull(result)
        val resultElement = result.toJsonElement().jsonObject
        assertEquals("user@example.com", resultElement["email"]?.let { (it as JsonPrimitive).content })
        assertEquals("First Org", resultElement["company"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun testHubspotDataConstruction() {
        val id = Uuid.random()
        val context = buildJsonObject {
            put("profile", buildJsonObject {
                put("name", "test")
                put("type", "GENERIC")
            })
        }
        @Suppress("UNCHECKED_CAST")
        val data = context.toAny() as Map<String, Any>
        val hubspotData = HubspotData(
            id = id,
            type = ProfileType.GENERIC,
            hubspotId = null,
            data = data,
            context = context
        )
        assertEquals(id, hubspotData.id)
        assertEquals(ProfileType.GENERIC, hubspotData.type)
        assertNull(hubspotData.hubspotId)
        assertNotNull(hubspotData.data)
        assertNotNull(hubspotData.context)
        assertNull(hubspotData.memberships)
    }

    @Test
    fun testHubspotDataWithMemberships() {
        val contactId = Uuid.random()
        val orgId = Uuid.random()
        val orgContext = buildJsonObject {
            put("profile", buildJsonObject {
                put("name", "Acme Corp")
                put("type", "ORGANIZATION")
            })
            put("organization", buildJsonObject {
                put("name", "Acme Corp")
                put("domain", "acme.com")
            })
        }
        @Suppress("UNCHECKED_CAST")
        val orgData = orgContext.toAny() as Map<String, Any>
        val orgHubspotData = HubspotData(
            id = orgId,
            type = ProfileType.ORGANIZATION,
            hubspotId = "company-123",
            data = orgData,
            context = orgContext
        )
        val contactContext = buildJsonObject {
            put("profile", buildJsonObject {
                put("name", "user@example.com")
                put("type", "GENERIC")
            })
        }
        @Suppress("UNCHECKED_CAST")
        val contactData = contactContext.toAny() as Map<String, Any>
        val contactHubspotData = HubspotData(
            id = contactId,
            type = ProfileType.GENERIC,
            hubspotId = "contact-456",
            data = contactData,
            context = contactContext,
            memberships = listOf(orgHubspotData)
        )
        assertNotNull(contactHubspotData.memberships)
        assertEquals(1, contactHubspotData.memberships!!.size)
        assertEquals(orgId, contactHubspotData.memberships!![0].id)
        assertEquals(ProfileType.ORGANIZATION, contactHubspotData.memberships!![0].type)
        assertEquals("company-123", contactHubspotData.memberships!![0].hubspotId)
    }

    @Test
    fun testHubspotDataWithHubspotId() {
        val id = Uuid.random()
        val context = buildJsonObject {
            put("profile", buildJsonObject {
                put("name", "test")
            })
            put("attributes", buildJsonObject {
                put("bosca.profiles.hubspot.id", buildJsonObject {
                    put("attributes", buildJsonObject {
                        put("id", "12345")
                    })
                })
            })
        }
        @Suppress("UNCHECKED_CAST")
        val data = context.toAny() as Map<String, Any>
        val hubspotData = HubspotData(
            id = id,
            type = ProfileType.GENERIC,
            hubspotId = "12345",
            data = data,
            context = context
        )
        assertEquals("12345", hubspotData.hubspotId)
    }

    @Test
    fun testJsonataMembershipOrganizationExpression() {
        val expression = """
            {
                "name": organization.name,
                "domain": organization.domain
            }
        """.trimIndent()
        val data = mapOf(
            "profile" to mapOf(
                "name" to "Acme Corp",
                "type" to "ORGANIZATION"
            ),
            "organization" to mapOf(
                "name" to "Acme Corp",
                "domain" to "acme.com"
            )
        )
        val jsonata = Jsonata.jsonata(expression)
        val result = jsonata.evaluate(data)
        assertNotNull(result)
        val resultElement = result.toJsonElement().jsonObject
        assertEquals("Acme Corp", resultElement["name"]?.let { (it as JsonPrimitive).content })
        assertEquals("acme.com", resultElement["domain"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun testJsonataSelectsCorrectExpressionByProfileType() {
        val configuration = HubSpotConfiguration(
            token = "test-token",
            expressions = HubSpotExpressions(
                generic = """{ "type": "contact" }""",
                organization = """{ "type": "company" }"""
            )
        )
        val genericExpression = if (ProfileType.GENERIC == ProfileType.GENERIC) {
            configuration.expressions.generic
        } else {
            configuration.expressions.organization
        }
        val orgExpression = if (ProfileType.ORGANIZATION == ProfileType.GENERIC) {
            configuration.expressions.generic
        } else {
            configuration.expressions.organization
        }
        val genericResult = Jsonata.jsonata(genericExpression).evaluate(emptyMap<String, Any>())
        val orgResult = Jsonata.jsonata(orgExpression).evaluate(emptyMap<String, Any>())
        val genericElement = genericResult.toJsonElement().jsonObject
        val orgElement = orgResult.toJsonElement().jsonObject
        assertEquals("contact", (genericElement["type"] as JsonPrimitive).content)
        assertEquals("company", (orgElement["type"] as JsonPrimitive).content)
    }
}
