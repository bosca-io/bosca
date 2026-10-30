package bosca.cli.swarm

import bosca.cli.compose.installedComposeNginx
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import kotlin.test.Test
import kotlin.test.assertEquals

class SwarmAtlasRoutingTest {
    @Test
    fun `Caddy and nginx preserve Atlas Git aliases registry paths authentication and uploads`() {
        assumeTrue("Docker is required for proxy integration", DockerClientFactory.instance().isDockerAvailable)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        fun backend(listener: String) = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange -> exchange.use {
                val input = exchange.requestBody.readAllBytes()
                val output = if (input.isEmpty()) exchange.requestURI.toString().toByteArray() else input
                exchange.responseHeaders.add("X-Listener", listener)
                exchange.responseHeaders.add("X-Uri", exchange.requestURI.toString())
                exchange.responseHeaders.add("X-Auth", exchange.requestHeaders.getFirst("Authorization"))
                exchange.responseHeaders.add("X-Host", exchange.requestHeaders.getFirst("Host"))
                exchange.responseHeaders.add("Set-Cookie", "fixture=$listener; Path=/")
                exchange.sendResponseHeaders(200, output.size.toLong())
                exchange.responseBody.write(output)
            } }
            start()
        }
        val api = backend("api")
        val ui = backend("ui")
        val git = backend("git")
        val registry = backend("registry")
        val directory = Files.createTempDirectory("atlas-ingress-")
        val workspace = Path.of("..").toAbsolutePath()
        try {
            Testcontainers.exposeHostPorts(api.address.port, ui.address.port, git.address.port, registry.address.port)
            val apiHost = "host.testcontainers.internal:${api.address.port}"
            val uiHost = "host.testcontainers.internal:${ui.address.port}"
            val gitHost = "host.testcontainers.internal:${git.address.port}"
            val registryHost = "host.testcontainers.internal:${registry.address.port}"
            val config = SwarmConfig(sites = listOf(SwarmSite("site1", "example.test", "Test", "test@example.test", 1)))
            val hosts = listOf("git", "atlas", "artifacts")
            fun caddy(config: SwarmConfig): String {
                val rendered = caddyfile(config)
                return "{\n auto_https off\n}\n" + hosts.joinToString("\n") { host ->
                    "http://$host.example.test {\n" + rendered.substringAfter("$host.example.test {\n").substringBefore("\n}\n") + "\n}\n"
                }.replace("site1-atlas:8094", apiHost).replace("site1-atlas:8095", uiHost)
                    .replace("site1-atlas-ui:8095", uiHost).replace("site1-git:8091", gitHost).replace("site1-artifacts:8084", registryHost)
            }
            Files.writeString(directory.resolve("Caddyfile"), caddy(config))
            Files.writeString(directory.resolve("CaddySplit"), caddy(config.copy(sites = config.sites.map {
                it.copy(atlas = SwarmAtlas(separateUi = true, separateGit = true, separateArtifacts = true))
            })))
            for (split in listOf(false, true)) {
                val template = installedComposeNginx(directory.resolve(if (split) "nginx-split" else "nginx-combined"), split)
                val nginx = ("events {}\nhttp {\n" + template.substringBefore("server {") + hosts.joinToString("\n") { host ->
                    val block = template.substringAfter("    server_name $host.example.test;").substringBefore("\nserver {")
                        .lineSequence().filterNot { it.trim().startsWith("ssl_") || it.trim() == "http2 on;" }.joinToString("\n")
                    "server {\n listen 80;\n server_name $host.example.test;" + block
                } + "\n}\n").replace("127.0.0.1:8094", apiHost).replace("127.0.0.1:8095", uiHost)
                    .replace("127.0.0.1:8091", gitHost).replace("127.0.0.1:8084", registryHost)
                Files.writeString(directory.resolve(if (split) "nginx-split.conf" else "nginx.conf"), nginx)
            }
            for ((image, source, target) in listOf(
                Triple(config.images.getValue("caddy"), "Caddyfile", "/etc/caddy/Caddyfile"),
                Triple(config.images.getValue("caddy"), "CaddySplit", "/etc/caddy/Caddyfile"),
                Triple("nginx:stable-alpine", "nginx.conf", "/etc/nginx/nginx.conf"),
                Triple("nginx:stable-alpine", "nginx-split.conf", "/etc/nginx/nginx.conf"),
            )) {
                GenericContainer(image).apply {
                    withExposedPorts(80)
                    withCopyFileToContainer(MountableFile.forHostPath(directory.resolve(source)), target)
                    withCopyFileToContainer(MountableFile.forHostPath(workspace.resolve("deployments/bosca-compose/config/nginx-proxy.conf")),
                        "/etc/nginx/snippets/bosca-proxy.conf")
                    waitingFor(Wait.forListeningPort())
                }.use { proxy ->
                    proxy.start()
                    val split = source == "CaddySplit" || source == "nginx-split.conf"
                    val client = OkHttpClient.Builder().followRedirects(false).build()
                    fun request(host: String, path: String, listener: String, upstream: String = path, payload: String? = null) {
                        val request = Request.Builder().url("http://${proxy.host}:${proxy.getMappedPort(80)}$path")
                            .header("Host", "$host.example.test").header("Authorization", "Bearer fixture")
                            .apply { if (payload != null) post(payload.toRequestBody()) }.build()
                        client.newCall(request).execute().use { response ->
                            assertEquals(200, response.code, "$source $host$path")
                            assertEquals(listener, response.header("X-Listener"), "$source $host$path")
                            assertEquals(upstream, response.header("X-Uri"), "$source $host$path")
                            assertEquals("Bearer fixture", response.header("X-Auth"))
                            assertEquals("$host.example.test", response.header("X-Host"))
                            assertEquals("fixture=$listener; Path=/", response.header("Set-Cookie"))
                            assertEquals(payload ?: upstream, response.body.string())
                        }
                    }
                    val id = "382b5ae6-f5f4-47b7-8216-fa76e707e588"
                    listOf("/", "/login", "/repositories/$id", "/artifacts/repositories/upload", "/artifacts/settings/repositories/$id",
                        "/artifacts/runs/$id", "/_bml/app.css", "/favicon.svg",
                        "/_bml/libraries/${"a".repeat(64)}/styles/design.css",
                        "/_bml/libraries/${"a".repeat(64)}/chunks/chunk-ABCDEFGH.js",
                        "/_bml/libraries/${"a".repeat(64)}/fonts/geist-latin.woff2").forEach { request("atlas", it, "ui") }
                    val payload = "atlas-file-payload".repeat(4096)
                    request("atlas", "/_bml/action?target=save", "ui", payload = payload)
                    request("atlas", "/_artifacts/raw/ns/pkg/build/file%20name.zip?checksum=true", "ui", payload = payload)
                    for (host in listOf("atlas", "git")) {
                        listOf("/graphql", "/graphqlws", "/oauth2/google/callback?code=fixture", "/api/webhooks/github/$id",
                            "/owner/project.git/info/refs?service=git-upload-pack", "/artifacts/project.git/info/refs",
                            "/owner/project.git/info/lfs/objects/batch", "/owner/project/raw/main/logo.png")
                            .forEach { path ->
                                val gitRequest = host == "git" || Regex(gitBackendPathPattern()).matches(java.net.URI(path).path)
                                request(host, path, if (split && gitRequest) "git" else "api")
                            }
                        request(host, "/owner/project.git/git-receive-pack", if (split) "git" else "api", payload = payload)
                    }
                    listOf("raw", "maven", "npm", "helm", "ml").forEach { protocol ->
                        val path = "/$protocol/ns/pkg/build/file%20name?token=fixture"
                        request("artifacts", path, if (split) "registry" else "api", if (split) path else "/_artifacts$path", payload)
                        request("atlas", path, if (split) "registry" else "api", payload = payload)
                    }
                    request("artifacts", "/npm/ns/@scope%2Fpkg?revision=one%2Ftwo", if (split) "registry" else "api", (if (split) "" else "/_artifacts") + "/npm/ns/@scope%2Fpkg?revision=one%2Ftwo")
                    request("artifacts", "/v2/ns/pkg/manifests/latest", if (split) "registry" else "api")
                    client.newCall(Request.Builder().url("http://${proxy.host}:${proxy.getMappedPort(80)}/repositories?view=recent")
                        .header("Host", "git.example.test").build()).execute().use { response ->
                        assertEquals(302, response.code)
                        assertEquals("https://atlas.example.test/repositories?view=recent", response.header("Location"))
                    }
                }
            }
        } finally {
            git.stop(0)
            registry.stop(0)
            ui.stop(0)
            api.stop(0)
            executor.close()
            directory.toFile().deleteRecursively()
        }
    }
}
