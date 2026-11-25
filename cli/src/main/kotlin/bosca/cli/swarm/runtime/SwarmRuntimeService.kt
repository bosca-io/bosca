package bosca.cli.swarm.runtime

import bosca.ai.kit.harness.KitSwarmExecution
import bosca.ai.kit.harness.KitSwarmRequest
import bosca.cli.harness.egress.parseEgressProxyOrigin
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Owns only generation-labelled Services, Secrets and short-lived cancellation records. */
internal class SwarmRuntimeService(private val engine: SwarmEngine, private val encryption: EncryptionService,
    private val configuration: SwarmRuntimeConfiguration, private val json: Json,
    private val registryAuth: Map<String, SwarmRuntimeRegistryAuth> = emptyMap()) {
    private val environmentSerializer = MapSerializer(String.serializer(), String.serializer())
    private val terminal = setOf("complete", "shutdown", "failed", "rejected", "remove")
    private val ownerKeys = setOf("BOSCA_CHAT_SESSION_ID", "BOSCA_RUNTIME_GENERATION", "BOSCA_ENDPOINT", "BOSCA_ARCHIVE_MAX_BYTES", "BOSCA_JOURNAL_MAX_BYTES", "BOSCA_JOURNAL_MAX_TURNS", "BOSCA_MCP_SECRET_NAMES")
    private val optionalOwnerKeys = setOf("BOSCA_EGRESS_PROXY")
    private val secretKeys = setOf("BOSCA_TOKEN", "BOSCA_RUNTIME_TOKEN", "BOSCA_RUNTIME_EXPIRES")
    private val optionalSecretKeys = setOf("BOSCA_EGRESS_TOKEN")

    suspend fun ensure(id: UUID, request: KitSwarmRequest): KitSwarmExecution {
        require(request.environment.keys.containsAll(ownerKeys) && (request.environment.keys - ownerKeys - optionalOwnerKeys).isEmpty() &&
            request.environment.values.none { value -> value.any { it in "\r\n\u0000" } }) { "Invalid Swarm generation environment" }
        request.environment["BOSCA_EGRESS_PROXY"]?.let(::parseEgressProxyOrigin)
        require(request.environment.getValue("BOSCA_CHAT_SESSION_ID") == request.secrets.keyId.toString() && request.key == "kit:${request.secrets.keyId}:${request.environment.getValue("BOSCA_RUNTIME_GENERATION")}") { "Invalid Swarm generation identity" }
        val values = secrets(request)
        val expires = values.getValue("BOSCA_RUNTIME_EXPIRES").toLong()
        require(expires > Instant.now().epochSecond) { "Swarm generation has expired" }
        check(cancelled(id) == null) { "Swarm generation was cancelled" }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical(json.encodeToJsonElement(KitSwarmRequest.serializer(), request)).toString().toByteArray()).toHexString()
        val existing = inspect("services", name(id))
        if (existing != null) {
            owned(existing, id, "service")
            require(existing.objectAt("Spec").objectAt("Labels").textAt("bosca.kit.request") == hash) { "Swarm dispatch differs from its immutable request" }
            return observe(id, existing)
        }
        val profile = requireNotNull(configuration.profiles[request.profile]) { "Swarm profile is unavailable" }
        validateSwarmRuntime(profile, request.image)
        val headers = profile.registryAuthSecret?.let { name ->
            val auth = requireNotNull(registryAuth[name]) { "Swarm image registry credential is unavailable" }
            require(auth.serveraddress == request.image.substringBefore('/') && auth.username.isNotBlank() && auth.password.isNotBlank()) { "Swarm image registry credential does not match the image" }
            mapOf("X-Registry-Auth" to Base64.getUrlEncoder().encodeToString(json.encodeToString(SwarmRuntimeRegistryAuth.serializer(), auth).toByteArray()))
        }.orEmpty()
        val info = success(engine.request("GET", "/info")).jsonObject
        require(info.objectAt("Swarm").boolAt("ControlAvailable") == true) { "Swarm runtime controller must run on a manager" }
        for (network in profile.service.objectAt("TaskTemplate").arrayAt("Networks")) {
            val target = requireNotNull(network.jsonObject.textAt("Target"))
            val info = requireNotNull(inspect("networks", target)) { "Swarm profile network is unavailable" }
            require(info.textAt("Driver") == "overlay" && info.boolAt("Ingress") != true) { "Swarm runtime requires a private overlay network" }
        }
        val labels = labels(id, "secret", expires).updated("bosca.kit.request", JsonPrimitive(hash))
        val secretName = name(id) + "-runtime"
        val createdSecret = engine.request("POST", "/secrets/create", buildJsonObject {
            put("Name", secretName); put("Labels", labels)
            put("Data", Base64.getEncoder().encodeToString(json.encodeToString(environmentSerializer, values).toByteArray()))
        })
        check(createdSecret.status in setOf(201, 409)) { "Docker Engine secret creation failed: HTTP ${createdSecret.status}" }
        val secret = requireNotNull(inspect("secrets", secretName))
        owned(secret, id, "secret")
        require(secret.objectAt("Spec").objectAt("Labels").textAt("bosca.kit.request") == hash) { "Swarm secret differs from its immutable request" }
        val mounted = mutableListOf(secretReference(secret, "bosca-runtime.json"))
        val files = mutableMapOf<String, String>()
        for ((key, source) in profile.secretEnvironment) {
            val provider = requireNotNull(inspect("secrets", source)) { "Swarm provider secret is unavailable" }
            val target = "provider-$key"
            mounted += secretReference(provider, target); files[key] = target
        }
        val original = profile.service.objectAt("TaskTemplate").objectAt("ContainerSpec")
        val staticEnvironment = original.arrayAt("Env").map { it.jsonPrimitive.content }
        require(staticEnvironment.none { it.substringBefore('=') in ownerKeys + optionalOwnerKeys + secretKeys + optionalSecretKeys + "BOSCA_SECRET_FILES" }) { "Swarm profile overrides generation-owned environment" }
        val container = original.updated("Env", JsonArray((staticEnvironment + request.environment.map { "${it.key}=${it.value}" } +
            "BOSCA_SECRET_FILES=${json.encodeToString(environmentSerializer, files)}").map(::JsonPrimitive)))
            .updated("Secrets", JsonArray(mounted)).updated("Labels", original.objectAt("Labels").let { JsonObject(it + labels(id, "task", expires)) })
        val spec = profile.service.updated("Name", JsonPrimitive(name(id)))
            .updated("Labels", JsonObject(profile.service.objectAt("Labels") + labels(id, "service", expires) + ("bosca.kit.request" to JsonPrimitive(hash))))
            .updated("TaskTemplate", profile.service.objectAt("TaskTemplate").updated("ContainerSpec", container))
        val created = engine.request("POST", "/services/create", spec, headers)
        check(created.status in setOf(201, 409)) { "Docker Engine service creation failed: HTTP ${created.status}" }
        if (cancelled(id) != null) { cleanup(id); error("Swarm generation was cancelled") }
        val service = requireNotNull(inspect("services", name(id)))
        owned(service, id, "service")
        require(service.objectAt("Spec").objectAt("Labels").textAt("bosca.kit.request") == hash) { "Swarm dispatch differs from its immutable request" }
        return observe(id, service)
    }

    suspend fun status(id: UUID): KitSwarmExecution? = inspect("services", name(id))?.let { owned(it, id, "service"); observe(id, it) }

    suspend fun stop(id: UUID, request: KitSwarmRequest): Boolean {
        val expires = secrets(request).getValue("BOSCA_RUNTIME_EXPIRES").toLong()
        val response = engine.request("POST", "/configs/create", buildJsonObject {
            put("Name", name(id) + "-stop"); put("Labels", labels(id, "cancel", expires))
            put("Data", Base64.getEncoder().encodeToString("cancelled".toByteArray()))
        })
        check(response.status in setOf(201, 409)) { "Docker Engine cancellation failed: HTTP ${response.status}" }
        owned(requireNotNull(cancelled(id)), id, "cancel")
        return cleanup(id)
    }

    /** Reconciles cancellations and expired resources after controller/request interruption. */
    suspend fun sweep() {
        val now = Instant.now().epochSecond
        for (kind in listOf("configs", "services", "secrets")) {
            for (entry in list(kind, "bosca.kit.runtime")) {
                val labels = entry.objectAt("Spec").objectAt("Labels")
                val id = labels.textAt("bosca.kit.runtime")?.let { UUID.parse(it) } ?: continue
                val expectedKind = when (kind) { "configs" -> "cancel"; "services" -> "service"; else -> "secret" }
                if (labels.textAt("bosca.kit.kind") != expectedKind) continue
                val expectedName = name(id) + when (kind) { "configs" -> "-stop"; "secrets" -> "-runtime"; else -> "" }
                if (entry.objectAt("Spec").textAt("Name") != expectedName) continue
                val expires = labels.textAt("bosca.kit.expires")?.toLongOrNull() ?: continue
                if (kind == "configs" || expires <= now) {
                    val removed = cleanup(id)
                    if (kind == "configs" && removed && expires + 60 < now) remove("configs", requireNotNull(entry.textAt("ID")))
                }
            }
        }
    }

    private suspend fun observe(id: UUID, service: JsonObject): KitSwarmExecution {
        val tasks = tasks(id)
        val running = tasks.filter { it.textAt("DesiredState") == "running" && it.objectAt("Status").textAt("State") == "running" }.singleOrNull()
        val active = tasks.any { it.objectAt("Status").textAt("State") !in terminal }
        val image = running?.objectAt("Spec")?.objectAt("ContainerSpec")?.textAt("Image")
        return KitSwarmExecution(requireNotNull(service.textAt("CreatedAt")), tasks.isNotEmpty() && !active,
            running?.let { configuration.endpointTemplate.replace("{service}", name(id)) }, image,
            image?.substringAfter('@')?.takeIf { it.matches(Regex("sha256:[a-f0-9]{64}")) }, running?.textAt("ID"))
    }

    private suspend fun cleanup(id: UUID): Boolean {
        val service = inspect("services", name(id))
        if (service != null) {
            owned(service, id, "service")
            if (service.objectAt("Spec").objectAt("Mode").objectAt("Replicated").longAt("Replicas") != 0L) {
                val spec = service.objectAt("Spec").updated("Mode", buildJsonObject { putJsonObject("Replicated") { put("Replicas", 0) } })
                val response = engine.request("POST", "/services/${service.textAt("ID")}/update?version=${service.objectAt("Version").longAt("Index")}", spec)
                if (response.status == 409) return false
                success(response)
            }
        }
        // Keep the service until workers acknowledge shutdown; deleting it first loses task evidence.
        if (tasks(id).any { it.objectAt("Status").textAt("State") !in terminal }) return false
        service?.let { remove("services", requireNotNull(it.textAt("ID"))) }
        val secretName = name(id) + "-runtime"
        inspect("secrets", secretName)?.let { owned(it, id, "secret"); remove("secrets", requireNotNull(it.textAt("ID"))) }
        return inspect("services", name(id)) == null && inspect("secrets", secretName) == null
    }

    private suspend fun tasks(id: UUID) = (success(engine.request("GET", "/tasks" + filters("name", name(id)))) as JsonArray).map { it.jsonObject }
        .filter { it.objectAt("Spec").objectAt("ContainerSpec").objectAt("Labels").textAt("bosca.kit.runtime") == id.toString() }
    private suspend fun list(kind: String, label: String) = (success(engine.request("GET", "/$kind" + filters("label", label))) as JsonArray).map { it.jsonObject }
    private suspend fun inspect(kind: String, name: String): JsonObject? {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Invalid Swarm resource name" }
        val response = engine.request("GET", "/$kind/$name")
        return if (response.status == 404) null else success(response).jsonObject
    }
    private suspend fun remove(kind: String, id: String) { success(engine.request("DELETE", "/$kind/$id"), 200, 204, 404) }
    private suspend fun cancelled(id: UUID) = inspect("configs", name(id) + "-stop")
    private fun success(response: SwarmEngineResponse, vararg codes: Int = intArrayOf(200)): JsonElement {
        check(response.status in codes) { "Docker Engine request failed: HTTP ${response.status}" }; return response.body
    }
    private fun labels(id: UUID, kind: String, expires: Long) = buildJsonObject { put("bosca.kit.runtime", id.toString()); put("bosca.kit.kind", kind); put("bosca.kit.expires", expires.toString()) }
    private fun owned(resource: JsonObject, id: UUID, kind: String) {
        val labels = resource.objectAt("Spec").objectAt("Labels")
        require(labels.textAt("bosca.kit.runtime") == id.toString() && labels.textAt("bosca.kit.kind") == kind) { "Swarm resource is not owned by this runtime" }
    }
    private suspend fun secrets(request: KitSwarmRequest): Map<String, String> {
        val payload = request.secrets
        val values = json.decodeFromString(environmentSerializer, encryption.decrypt(EncryptionService.Encrypted(Base64.getDecoder().decode(payload.nonce), Base64.getDecoder().decode(payload.ciphertext)), payload.keyId).decodeToString())
        require(values.keys.containsAll(secretKeys) && (values.keys - secretKeys - optionalSecretKeys).isEmpty() && values.keys == payload.names.toSet()) { "Invalid encrypted generation fields" }
        return values
    }
    private fun name(id: UUID) = "bosca-kit-$id"
    private fun secretReference(secret: JsonObject, target: String) = buildJsonObject {
        put("SecretID", requireNotNull(secret.textAt("ID"))); put("SecretName", requireNotNull(secret.objectAt("Spec").textAt("Name")))
        putJsonObject("File") { put("Name", target); put("UID", "1000"); put("GID", "1000"); put("Mode", 256) }
    }
}
