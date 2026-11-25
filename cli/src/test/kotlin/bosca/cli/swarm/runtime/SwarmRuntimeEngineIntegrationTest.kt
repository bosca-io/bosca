package bosca.cli.swarm.runtime

import bosca.ai.kit.harness.KitSwarmRequest
import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.RuntimeVersions
import bosca.cli.swarm.SwarmConfig
import bosca.cli.swarm.SwarmKit
import bosca.cli.swarm.SwarmSiteSecrets
import bosca.cli.swarm.SwarmRegistryAuth
import bosca.cli.swarm.kitControllerConfiguration
import bosca.ai.kit.harness.runtime.KitRuntimeSecrets
import bosca.security.encryption.EncryptionServiceImpl
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.Base64
import kotlin.test.*

/** Uses two private Docker daemons; never changes the developer daemon's Swarm membership. */
class SwarmRuntimeEngineIntegrationTest {
    @Test fun `manager controls real worker isolation replacement idempotency and fenced removal`(): Unit = runBlocking {
        val image = ImageFromDockerfile("bosca-kit-swarm-engine-${UUID.random()}", true).withFileFromString("Dockerfile", """
            FROM docker:28.1.1-dind@sha256:eceba5b0fc2fcf83a74c298391c2ed9e1adbdaf04ee173611bd6282ec973e7ba
            RUN apk add --no-cache curl
        """.trimIndent())
        Network.newNetwork().use { network ->
          SwarmTestRegistry().withNetwork(network).withNetworkAliases("kit-registry").withExposedPorts(5000)
            .withEnv("REGISTRY_AUTH", "htpasswd").withEnv("REGISTRY_AUTH_HTPASSWD_REALM", "Kit fixture")
            .withEnv("REGISTRY_AUTH_HTPASSWD_PATH", "/auth/htpasswd")
            .withCopyFileToContainer(MountableFile.forClasspathResource("swarm/registry.htpasswd"), "/auth/htpasswd")
            .waitingFor(Wait.forHttp("/v2/").forStatusCode(401)).use { registry ->
            registry.start()
            fun daemon() = SwarmTestDaemon(image).withNetwork(network).withEnv("DOCKER_TLS_CERTDIR", "")
                .withCommand("--insecure-registry=kit-registry:5000")
                .withCreateContainerCmdModifier { it.hostConfig?.withPrivileged(true) }
                .waitingFor(Wait.forLogMessage(".*API listen on /var/run/docker.sock.*", 1).withStartupTimeout(Duration.ofSeconds(90)))
            daemon().use { manager -> daemon().use { worker ->
                manager.start(); worker.start()
                val managerApi = CurlSwarmEngine("/var/run/docker.sock", listOf("docker", "exec", "-i", manager.containerId, "curl"))
                val workerApi = CurlSwarmEngine("/var/run/docker.sock", listOf("docker", "exec", "-i", worker.containerId, "curl"))
                val managerIp = manager.containerInfo.networkSettings.networks.values.single().ipAddress
                val workerIp = worker.containerInfo.networkSettings.networks.values.single().ipAddress
                assertEquals(200, managerApi.request("POST", "/swarm/init", buildJsonObject { put("AdvertiseAddr", managerIp); put("ListenAddr", "0.0.0.0:2377") }).status)
                val swarm = managerApi.request("GET", "/swarm").body.jsonObject
                assertEquals(200, workerApi.request("POST", "/swarm/join", buildJsonObject {
                    put("AdvertiseAddr", workerIp); put("ListenAddr", "0.0.0.0:2377")
                    putJsonArray("RemoteAddrs") { add("$managerIp:2377") }; put("JoinToken", swarm.objectAt("JoinTokens").getValue("Worker"))
                }).status)
                val workerNode = workerApi.request("GET", "/info").body.jsonObject.objectAt("Swarm").textAt("NodeID")
                assertNotNull(workerNode)
                assertEquals(201, managerApi.request("POST", "/networks/create", buildJsonObject { put("Name", "bosca"); put("Driver", "overlay"); put("Attachable", true) }).status)
                val pull = manager.execInContainer("docker", "pull", "busybox:1.37.0")
                assertEquals(0, pull.exitCode, pull.stderr)
                val privateTag = "kit-registry:5000/kit-runtime:fixture"
                assertEquals(0, manager.execInContainer("docker", "tag", "busybox:1.37.0", privateTag).exitCode)
                val login = manager.execInContainer("sh", "-c", "printf '%s' testpassword | docker login kit-registry:5000 --username testuser --password-stdin")
                assertEquals(0, login.exitCode, login.stderr)
                val push = manager.execInContainer("docker", "push", privateTag)
                assertEquals(0, push.exitCode, push.stderr)
                val imageInfo = Json.parseToJsonElement(manager.execInContainer("docker", "image", "inspect", privateTag).stdout).jsonArray.single().jsonObject
                val pinned = imageInfo.arrayAt("RepoDigests").map { it.jsonPrimitive.content }.single { it.startsWith("kit-registry:5000/") }
                val providerSecret = "private-provider-fixture-key"
                assertEquals(201, managerApi.request("POST", "/secrets/create", buildJsonObject { put("Name", "kit-runtime-provider"); put("Data", Base64.getEncoder().encodeToString(providerSecret.toByteArray())) }).status)
                val site = SwarmConfig().sites.first().copy(
                    kit = SwarmKit(enabled = true, memoryBytes = 1024L * 1024 * 1024, platform = "${imageInfo.textAt("Os")}/${imageInfo.textAt("Architecture")}", resolved = KitHarnessConfiguration("fixture", "http://{service}:8080", "http://bosca.invalid/graphql",
                        scopes = listOf("content:view"), image = pinned, imageId = pinned.substringAfterLast('@'), versions = RuntimeVersions("fixture", "fixture", "fixture", "codex-home-v1"))),
                    secrets = SwarmSiteSecrets(kitProvider = providerSecret))
                val generated = Json.decodeFromString(SwarmRuntimeConfiguration.serializer(), kitControllerConfiguration(
                    SwarmConfig(sites = listOf(site), registryAuth = SwarmRegistryAuth("kit-registry:5000", "testuser", password = "testpassword")), site)).profiles.getValue("fixture")
                // The fixture handles TERM like the native image's init process; retain the generated deployment controls.
                val template = generated.service.objectAt("TaskTemplate")
                val container = template.objectAt("ContainerSpec").updated("Command", buildJsonArray {
                    add("sh"); add("-c"); add("trap 'exit 0' TERM INT; mkdir -m 700 /state/session; sleep 3600 & wait"); add("--")
                })
                val base = generated.copy(service = generated.service.updated("TaskTemplate", template.updated("ContainerSpec", container)),
                    secretEnvironment = mapOf("OPENAI_API_KEY" to "kit-runtime-provider"))
                val onWorker = base.copy(service = base.service.updated("TaskTemplate", base.service.objectAt("TaskTemplate").updated("Placement",
                    base.service.objectAt("TaskTemplate").objectAt("Placement").updated("Constraints", buildJsonArray { add("node.id==$workerNode") }))))
                val application = BoscaApplication(ApplicationConfig.load("security: {encryption: {key: isolated-test-key}}".byteInputStream()), ownsServices = false)
                val encryption = EncryptionServiceImpl(application)
                val otherPlatform = if (imageInfo.textAt("Architecture") == "amd64") "arm64" else "amd64"
                val incompatible = base.copy(service = base.service.updated("TaskTemplate", base.service.objectAt("TaskTemplate").updated("Placement",
                    base.service.objectAt("TaskTemplate").objectAt("Placement").updated("Platforms", buildJsonArray { add(buildJsonObject { put("OS", "linux"); put("Architecture", otherPlatform) }) }))))
                val configuration = SwarmRuntimeConfiguration(mapOf("worker" to onWorker, "movable" to base, "incompatible" to incompatible, "unauthenticated" to onWorker.copy(registryAuthSecret = null)))
                val registryAuth = mapOf("kit-registry" to SwarmRuntimeRegistryAuth("testuser", "testpassword", "kit-registry:5000"))
                var service = SwarmRuntimeService(managerApi, encryption, configuration, application.json, registryAuth)
                suspend fun request(profile: String, lifetimeSeconds: Long = 600): KitSwarmRequest {
                    val session = UUID.random()
                    val values = mapOf("BOSCA_TOKEN" to "bsk_private_fixture", "BOSCA_RUNTIME_TOKEN" to "private-control-fixture", "BOSCA_RUNTIME_EXPIRES" to (System.currentTimeMillis() / 1000 + lifetimeSeconds).toString())
                    val encrypted = encryption.encrypt(application.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), values).toByteArray(), session)
                    return KitSwarmRequest(profile, "kit:$session:1", pinned, mapOf("BOSCA_CHAT_SESSION_ID" to session.toString(), "BOSCA_RUNTIME_GENERATION" to "1",
                        "BOSCA_ENDPOINT" to "http://bosca.invalid/graphql", "BOSCA_ARCHIVE_MAX_BYTES" to "10000000", "BOSCA_JOURNAL_MAX_BYTES" to "1000000", "BOSCA_JOURNAL_MAX_TURNS" to "256", "BOSCA_MCP_SECRET_NAMES" to "[]"),
                        KitRuntimeSecrets(session, Base64.getEncoder().encodeToString(encrypted.nonce), Base64.getEncoder().encodeToString(encrypted.data), values.keys.toList()))
                }
                suspend fun ready(id: UUID) = withTimeoutOrNull(90_000) {
                    var value = service.status(id)
                    while (value?.taskId == null && value?.terminal != true) { delay(100); value = service.status(id) }
                    if (value?.terminal == true) return@withTimeoutOrNull null
                    value
                } ?: fail("Runtime $id did not start: " + managerApi.request("GET", "/tasks").body.jsonArray.map {
                    val task = it.jsonObject
                    buildJsonObject { put("ID", task.getValue("ID")); put("Status", task.getValue("Status")); put("DesiredState", task.getValue("DesiredState")); put("Labels", task["Labels"] ?: JsonNull) }
                })
                val denied = UUID.random(); val deniedRequest = request("unauthenticated")
                service.ensure(denied, deniedRequest)
                withTimeout(30_000) { while (service.status(denied)?.terminal != true) delay(100) }
                assertNull(service.status(denied)?.endpoint, "A cold worker cannot pull the private image without the registry credential")
                withTimeout(30_000) { while (!service.stop(denied, deniedRequest)) delay(100) }

                val mismatched = UUID.random(); val mismatchedRequest = request("incompatible")
                service.ensure(mismatched, mismatchedRequest)
                withTimeout(10_000) {
                    while (managerApi.request("GET", "/tasks").body.jsonArray.none { it.jsonObject.objectAt("Status").textAt("Err")?.contains("unsupported platform") == true }) delay(100)
                }
                assertNull(service.status(mismatched)?.taskId, "An incompatible image platform must not run on an application worker")
                withTimeout(30_000) { while (!service.stop(mismatched, mismatchedRequest)) delay(100) }

                val first = UUID.random(); val firstRequest = request("worker")
                service.ensure(first, firstRequest)
                val running = ready(first)
                assertEquals(pinned, running.image)
                assertEquals(pinned.substringAfter('@'), running.imageId)
                val task = managerApi.request("GET", "/tasks/${running.taskId}").body.jsonObject
                assertEquals(workerNode, task.textAt("NodeID"))
                val containerId = assertNotNull(task.objectAt("Status").objectAt("ContainerStatus").textAt("ContainerID"))
                val inspected = Json.parseToJsonElement(worker.execInContainer("docker", "inspect", containerId).stdout).jsonArray.single().jsonObject
                assertTrue(inspected.objectAt("HostConfig").boolAt("ReadonlyRootfs") == true)
                assertEquals(1024L * 1024 * 1024, inspected.objectAt("HostConfig").longAt("Memory"))
                assertEquals(1_000_000_000L, inspected.objectAt("HostConfig").longAt("NanoCpus"))
                assertEquals(512L, inspected.objectAt("HostConfig").longAt("PidsLimit"))
                assertFalse(inspected.toString().contains("bsk_private_fixture"), "Generation credentials must not enter container/service environment")
                assertFalse(inspected.toString().contains(providerSecret), "Provider credentials stay in Docker Secrets")
                assertEquals(providerSecret, worker.execInContainer("docker", "exec", containerId, "cat", "/run/secrets/provider-OPENAI_API_KEY").stdout.trim())
                val isolated = worker.execInContainer("docker", "exec", containerId, "sh", "-c", "test \"\$(id -u)\" = 1000 && grep -Eq '^NoNewPrivs:[[:space:]]+1' /proc/1/status && grep -Eq '^CapEff:[[:space:]]+0+$' /proc/1/status && test -r /run/secrets/bosca-runtime.json && touch /state/session/owned && test \"\$(stat -c %u /state/session)\" = 1000 && test \"\$(stat -c %a /state/session)\" = 700")
                assertEquals(0, isolated.exitCode, isolated.stderr)
                val workerVolumes = workerApi.request("GET", "/volumes").body.jsonObject.arrayAt("Volumes")
                assertTrue(workerVolumes.isEmpty(), "Private tmpfs state must not create volume records")
                service = SwarmRuntimeService(managerApi, encryption, configuration, application.json, registryAuth)
                assertEquals(running.taskId, service.ensure(first, firstRequest).taskId, "Controller replacement reuses the same task")
                assertFailsWith<IllegalArgumentException> { service.ensure(first, firstRequest.copy(environment = firstRequest.environment + ("BOSCA_ENDPOINT" to "http://changed.invalid"))) }

                val second = UUID.random(); val secondRequest = request("movable")
                service.ensure(second, secondRequest)
                val beforeReplacement = ready(second)
                val oldTask = managerApi.request("GET", "/tasks/${beforeReplacement.taskId}").body.jsonObject
                val oldNode = assertNotNull(oldTask.textAt("NodeID"))
                val node = managerApi.request("GET", "/nodes/$oldNode").body.jsonObject
                assertEquals(200, managerApi.request("POST", "/nodes/$oldNode/update?version=${node.objectAt("Version").longAt("Index")}", node.objectAt("Spec").updated("Availability", JsonPrimitive("drain"))).status)
                val replacing = managerApi.request("GET", "/services/bosca-kit-$second").body.jsonObject
                val replacedSpec = replacing.objectAt("Spec").updated("TaskTemplate", replacing.objectAt("Spec").objectAt("TaskTemplate").updated("ForceUpdate", JsonPrimitive(1)))
                assertEquals(200, managerApi.request("POST", "/services/${replacing.textAt("ID")}/update?version=${replacing.objectAt("Version").longAt("Index")}", replacedSpec).status)
                val afterReplacement = withTimeout(90_000) {
                    var value = service.status(second)
                    while (value?.taskId == null || value.taskId == beforeReplacement.taskId) { delay(100); value = service.status(second) }
                    value
                }
                assertNotEquals(beforeReplacement.taskId, afterReplacement.taskId)
                withTimeout(30_000) { while (!service.stop(first, firstRequest)) delay(100) }
                val removedContainer = workerApi.request("GET", "/containers/$containerId/json")
                assertTrue(removedContainer.status == 404 || removedContainer.body.jsonObject.objectAt("State").boolAt("Running") == false, "Removal must wait for the worker process to stop")
                assertFailsWith<IllegalStateException> { service.ensure(first, firstRequest) }
                assertNotNull(service.status(second), "Stale removal cannot delete another generation")
                withTimeout(30_000) { while (!service.stop(second, secondRequest)) delay(100) }
                assertNull(service.status(first)); assertNull(service.status(second))
                assertEquals(404, managerApi.request("GET", "/secrets/bosca-kit-$first-runtime").status)
                assertEquals(404, managerApi.request("GET", "/secrets/bosca-kit-$second-runtime").status)
                assertTrue(workerApi.request("GET", "/volumes").body.jsonObject.arrayAt("Volumes").isEmpty())
                assertTrue(managerApi.request("GET", "/volumes").body.jsonObject.arrayAt("Volumes").isEmpty())
                val expired = UUID.random()
                service.ensure(expired, request("movable", 5))
                delay(6000)
                service = SwarmRuntimeService(managerApi, encryption, configuration, application.json, registryAuth)
                withTimeout(30_000) {
                    do { service.sweep(); delay(100) } while (service.status(expired) != null || managerApi.request("GET", "/secrets/bosca-kit-$expired-runtime").status != 404)
                }
                assertEquals(200, managerApi.request("GET", "/secrets/kit-runtime-provider").status, "Generation cleanup preserves administrator-owned provider secrets")
            } }
          }
        }
    }
}
