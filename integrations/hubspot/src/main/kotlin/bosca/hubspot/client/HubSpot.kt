package bosca.hubspot.client

import bosca.configuration.service.getValueAs
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.transformer.HubspotData
import bosca.serialization.OffsetDateTime
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CommunicationPreference(
    val id: String,
    val name: String,
    val description: String
)

@Serializable
data class CommunicationSubscription(
    val subscriptionId: Int,
    val subscribed: Boolean,
    @Contextual
    val timestamp: OffsetDateTime? = null,
    val setStatusSuccessReason: String? = null
) {

    val isModifiedRecently: Boolean
        get() {
            val ts = timestamp ?: return false
            return ts.isAfter(OffsetDateTime.now().minusDays(2))
        }
}

data class HttpResponse(
    val status: Int,
    val text: String,
    val ok: Boolean = status in 200..299
)

data class HubspotAddResults(val id: String, val added: Boolean)

class HubSpot(
    private val token: String,
    private val baseUrl: String = "https://api.hubapi.com",
    private val client: OkHttpClient = OkHttpClient(),
    private val configurationService: bosca.configuration.service.ConfigurationService? = null,
    private val json: Json? = null,
) {

    private var preferences: List<CommunicationPreference> = emptyList()
    private var subscriptionIds: List<Int> = emptyList()
    private var initialized = false

    private val mutex = Mutex()

    private suspend fun initialize() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            initializePreferences()
            initialized = true
        }
    }

    private suspend fun initializePreferences() {
        val response = httpGet(
            url = "https://api.hubapi.com/communication-preferences/v4/definitions",
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
        val json = Json.parseToJsonElement(response.text).jsonObject
        val results = json["results"]?.jsonArray ?: throw Exception("No results found")
        preferences = results
            .filter { element ->
                val obj = element.jsonObject
                obj["isActive"]?.jsonPrimitive?.boolean == true
            }
            .map { element ->
                val obj = element.jsonObject
                CommunicationPreference(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: ""
                )
            }
        subscriptionIds = preferences.map { it.id.toInt() }
    }

    suspend fun getCommunicationPreferences(): List<CommunicationPreference> {
        initialize()
        return preferences
    }

    suspend fun addContact(contact: JsonObject, associations: JsonArray? = null): HubspotAddResults {
        return add("contacts", contact, associations, "Existing ID: ")
    }

    suspend fun updateContact(hubspotId: String, contact: JsonObject) {
        update("contacts", hubspotId, contact)
    }

    suspend fun addCompany(contact: JsonObject, associations: JsonArray? = null): HubspotAddResults {
        return add("companies", contact, associations, "Existing ID: ")
    }

    suspend fun updateCompany(hubspotId: String, contact: JsonObject) {
        update("companies", hubspotId, contact)
    }

    suspend fun createAssociations(profile: HubspotData) {
        val memberships = profile.memberships ?: return
        val hubspotId = profile.hubspotId ?: error("hubspot id isn't sent on profile")
        val configurationService = configurationService ?: error("Configuration service not found")
        val json = json ?: error("Json not found")
        val configuration = configurationService.getValueAs<HubSpotConfiguration>("hubspot", json) ?: error("Hubspot configuration not found")
        for (membership in memberships) {
            if (membership.hubspotId == null) {
                throw Exception("hubspot id isn't sent on membership")
            }
            createAssociation(
                fromObjectType = "contact",
                fromObjectId = hubspotId,
                toObjectType = "company",
                toObjectId = membership.hubspotId,
                associationTypeId = configuration.contactToCompanyAssociationTypeId,
                associationCategory = configuration.contactToCompanyAssociationCategory
            )
        }
    }

    suspend fun createAssociation(
        fromObjectType: String,
        fromObjectId: String,
        toObjectType: String,
        toObjectId: String,
        associationTypeId: Int,
        associationCategory: String = "HUBSPOT_DEFINED"
    ) {
        val requestBody = buildJsonArray {
            addJsonObject {
                put("associationCategory", associationCategory)
                put("associationTypeId", associationTypeId)
            }
        }
        val response = httpPut(
            url = "https://api.hubapi.com/crm/v4/objects/$fromObjectType/$fromObjectId/associations/$toObjectType/$toObjectId",
            body = requestBody.toString()
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
    }

    suspend fun removeAssociation(
        fromObjectType: String,
        fromObjectId: String,
        toObjectType: String,
        toObjectId: String
    ) {
        val response = httpDelete(
            url = "https://api.hubapi.com/crm/v4/objects/$fromObjectType/$fromObjectId/associations/$toObjectType/$toObjectId",
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
    }

    internal suspend fun add(
        objectType: String,
        properties: JsonObject,
        associations: JsonArray?,
        alreadyExistsPrefix: String
    ): HubspotAddResults {
        val requestBody = buildJsonObject {
            put("properties", properties)
            if (associations != null) {
                put("associations", associations)
            }
        }
        val url = "$baseUrl/crm/v3/objects/$objectType"
        val response = httpPost(url = url, body = requestBody.toString())
        if (!response.ok) {
            val text = response.text
            // HubSpot signals "object already exists" with HTTP 409 Conflict, carrying the existing
            // record's id in the error body. Recognize the status code (not just the message text) and
            // recover that id, returning it as a non-created result — so an already-existing object is
            // handled, not raised as a (retryable) failure that can never resolve.
            if (response.status == 409) {
                existingIdFromConflict(text, alreadyExistsPrefix)?.let { return HubspotAddResults(it, false) }
            }
            throw Exception(text)
        }

        val responseJson = Json.parseToJsonElement(response.text).jsonObject
        val id = responseJson["id"]?.jsonPrimitive?.content ?: throw Exception("No ID in response")
        return HubspotAddResults(id, true)
    }

    internal suspend fun update(
        objectType: String,
        hubspotId: String,
        properties: JsonObject
    ) {
        val requestBody = buildJsonObject {
            put("properties", properties)
        }
        val url = "$baseUrl/crm/v3/objects/$objectType/$hubspotId"
        val response = httpPatch(url = url, body = requestBody.toString())
        if (!response.ok) {
            val text = response.text
            throw Exception(text)
        }
    }

    /**
     * The existing record's id parsed from a 409 Conflict body, or null when the body doesn't carry
     * one. HubSpot phrases the conflict as e.g. `"Contact already exists. Existing ID: 12345"`, so the
     * id follows [alreadyExistsPrefix]; a body without it (or unparseable) yields null and the caller
     * surfaces the original error.
     */
    private fun existingIdFromConflict(text: String, alreadyExistsPrefix: String): String? = try {
        val message = Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        if (message?.contains(alreadyExistsPrefix) == true) message.split(alreadyExistsPrefix).last().trim() else null
    } catch (_: Exception) {
        null
    }

    suspend fun getContactId(email: String): String? {
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val response = httpGet(
            url = "https://api.hubapi.com/crm/v3/objects/contacts/$encodedEmail?idProperty=email",
        )
        return when (response.status) {
            404 -> null
            in 200..299 -> {
                val json = Json.parseToJsonElement(response.text).jsonObject
                json["id"]?.jsonPrimitive?.content
            }

            else -> throw Exception(response.text)
        }
    }

    suspend fun addToList(listId: String, contactId: String) {
        val response = httpPut(
            url = "https://api.hubapi.com/crm/v3/lists/$listId/memberships/add",
            body = Json.encodeToString(listOf(contactId))
        )

        if (!response.ok) {
            throw Exception(response.text)
        }
    }

    suspend fun getSubscriptions(email: String): List<CommunicationSubscription> {
        if (email.isBlank()) return emptyList()
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val response = httpGet(
            url = "https://api.hubapi.com/communication-preferences/v4/statuses/$encodedEmail?channel=EMAIL",
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
        val json = Json.parseToJsonElement(response.text).jsonObject
        val results = json["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element.jsonObject
            val subscriptionId = obj["subscriptionId"]?.jsonPrimitive?.intOrNull ?: error("No subscriptionId")
            if (obj["status"]?.jsonPrimitive?.content == "NOT_SPECIFIED") {
                return@mapNotNull null
            }
            val subscribed = obj["status"]?.jsonPrimitive?.content == "SUBSCRIBED"
            CommunicationSubscription(
                subscriptionId = subscriptionId,
                subscribed = subscribed,
                timestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.let { OffsetDateTime.parse(it) },
                setStatusSuccessReason = obj["setStatusSuccessReason"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    suspend fun setSubscription(email: String, subscriptionId: Int, subscribed: Boolean = true): String {
        val status = if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED"
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val requestBody = buildJsonObject {
            put("subscriptionId", subscriptionId)
            put("channel", "EMAIL")
            put("statusState", status)
            put("optState", "OPT_IN")
            put("legalBasis", "CONSENT_WITH_NOTICE")
            put("legalBasisExplanation", "User consented via signup form.")
        }
        val response = httpPost(
            url = "https://api.hubapi.com/communication-preferences/v4/statuses/$encodedEmail",
            body = requestBody.toString()
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
        val json = Json.parseToJsonElement(response.text).jsonObject
        val numErrors = json["numErrors"]?.jsonPrimitive?.int ?: 0
        return if (numErrors > 0) {
            "error"
        } else {
            val results = json["results"]?.jsonArray
            val firstResult = results?.firstOrNull()?.jsonObject
            val resultStatus = firstResult?.get("status")?.jsonPrimitive?.content
            if (resultStatus == status) "ok" else "missing"
        }
    }

    /**
     * Sends a custom behavioral event to HubSpot's Custom Behavioral Events API
     * (`POST /events/v3/send`).
     *
     * The event is attributed to a single contact, identified by at least one of
     * [objectId] (the HubSpot contact id), [email], or [utk] (the HubSpot user
     * token / `hubspotutk` cookie). When more than one is supplied HubSpot
     * resolves them in that order of precedence.
     *
     * The endpoint returns `204 No Content` on success, so no response body is read.
     *
     * @param eventName the fully-qualified internal event name, e.g. `pe<portalId>_<name>`
     * @param objectId the HubSpot contact id, or null
     * @param email the contact's email address, or null
     * @param utk the HubSpot user token, or null
     * @param properties event property values keyed by their internal property name
     * @param occurredAt when the event occurred; HubSpot defaults to ingestion time when null
     * @throws IllegalArgumentException if no contact identifier is supplied
     * @throws Exception if HubSpot responds with a non-2xx status
     */
    suspend fun sendEvent(
        eventName: String,
        objectId: String? = null,
        email: String? = null,
        utk: String? = null,
        properties: JsonObject = JsonObject(emptyMap()),
        occurredAt: OffsetDateTime? = null,
    ) {
        require(objectId != null || email != null || utk != null) {
            "sendEvent requires one of objectId, email, or utk to identify the contact"
        }
        val requestBody = buildJsonObject {
            put("eventName", eventName)
            objectId?.let { put("objectId", it) }
            email?.let { put("email", it) }
            utk?.let { put("utk", it) }
            occurredAt?.let { put("occurredAt", it.toString()) }
            put("properties", properties)
        }
        val response = httpPost(
            url = "$baseUrl/events/v3/send",
            body = requestBody.toString()
        )
        if (!response.ok) {
            throw Exception(response.text)
        }
    }

    private fun getHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token"
    )

    private suspend fun httpGet(url: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .apply {
                getHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        return executeRequest(request)
    }

    private suspend fun httpPost(url: String, body: String): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply {
                getHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        return executeRequest(request)
    }

    private suspend fun httpPatch(url: String, body: String): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .patch(requestBody)
            .apply {
                getHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        return executeRequest(request)
    }

    private suspend fun httpPut(url: String, body: String): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .apply {
                getHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        return executeRequest(request)
    }

    private suspend fun httpDelete(url: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .delete()
            .apply {
                getHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        return executeRequest(request)
    }

    private suspend fun executeRequest(request: Request): HttpResponse = suspendCancellableCoroutine { continuation ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body.string()
                    val httpResponse = HttpResponse(
                        status = response.code,
                        text = responseBody
                    )
                    continuation.resume(httpResponse)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    response.close()
                }
            }
        })
    }
}
