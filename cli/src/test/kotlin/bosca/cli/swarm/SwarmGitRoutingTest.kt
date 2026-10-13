package bosca.cli.swarm

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmGitRoutingTest {
    private val workspace = Path.of("..").toAbsolutePath().normalize()
    private val id = "382b5ae6-f5f4-47b7-8216-fa76e707e588"
    private val apiPaths = listOf(
        "/graphql", "/graphqlws", "/oauth2/google/login", "/oauth2/google/callback",
        "/api/v1/ready", "/api/webhooks/github/$id", "/api/webhooks/teamcity",
        "/ci/artifacts/$id/$id/reports/output.zip",
        "/alice/project", "/alice/project.git/info/refs?service=git-upload-pack",
        "/alice/project.git/git-upload-pack", "/alice/project.git/git-receive-pack",
        "/alice/project.git/info/lfs/objects/batch", "/alice/project.git/info/lfs/objects/verify",
        "/alice/project.git/info/lfs/objects/hash", "/alice/project/raw/main/images/logo.png",
        "/repositories/project.git/git-upload-pack", "/repositories/$id.git/info/refs",
        "/repositories/$id/raw/main/logo.png", "/auth/signup.git/git-receive-pack",
        "/_bml/action.git/info/refs", "/_git/fonts.git/info/refs",
    )
    private val uiPaths = listOf(
        "/", "/login", "/auth/signup", "/auth/link/confirm", "/auth/verify?token=fixture",
        "/repositories", "/repositories/$id", "/repositories/$id/settings", "/repositories/$id/github",
        "/pulls/$id?view=files", "/pipelines/$id", "/ci-agents/$id", "/settings/github",
        "/_bml/app.css", "/_bml/app.js", "/_bml/js/repository.page.js",
        "/_bml/action", "/_bml/deferred/repository", "/_bml/contract/markdown/preview",
        "/_git/fonts/geist.woff2", "/_git/subscriptions",
    )

    @Test
    fun `all Git pages are routed to UI and transport paths stay on API`() {
        val pattern = Regex(gitUiPathPattern())
        Files.list(workspace.resolve("git/git-ui/src/main/bml/pages")).use { pages ->
            pages.filter { it.toString().endsWith(".bml") }.forEach { page ->
                val path = requireNotNull(Regex("route=\"([^\"]+)\"").find(Files.readString(page)))
                    .groupValues[1].replace("{id}", id)
                assertTrue(pattern.matches(path), "$page: $path")
            }
        }
        uiPaths.forEach { assertTrue(pattern.matches(URI(it).path), it) }
        apiPaths.forEach { assertFalse(pattern.matches(URI(it).path), it) }
        listOf(
            "helm/bosca-git-server/templates/_routes.tpl",
            "deployments/bosca-compose/config/nginx.conf.template",
            "deployments/local/Caddyfile",
        ).forEach { assertTrue(Files.readString(workspace.resolve(it)).contains(gitUiPathPattern()), it) }
    }

    @Test
    fun `Caddy and nginx route both listeners on one Git host and preserve requests`() {
        assumeTrue("Docker is required for routing integration", DockerClientFactory.instance().isDockerAvailable)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        fun backend(listener: String): HttpServer = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                exchange.use {
                    val input = exchange.requestBody.readAllBytes()
                    val output = if (input.isEmpty()) exchange.requestURI.toString().toByteArray() else input
                    exchange.responseHeaders.add("X-Listener", listener)
                    exchange.responseHeaders.add("X-Method", exchange.requestMethod)
                    exchange.responseHeaders.add("X-Auth", exchange.requestHeaders.getFirst("Authorization"))
                    exchange.responseHeaders.add("Set-Cookie", "fixture=$listener; Path=/")
                    exchange.sendResponseHeaders(200, output.size.toLong())
                    exchange.responseBody.write(output)
                }
            }
            start()
        }
        val api = backend("api")
        val ui = backend("ui")
        val directory = Files.createTempDirectory("git-proxy-routing-")
        try {
            Testcontainers.exposeHostPorts(api.address.port, ui.address.port)
            val apiUpstream = "host.testcontainers.internal:${api.address.port}"
            val uiUpstream = "host.testcontainers.internal:${ui.address.port}"
            val config = SwarmConfig(sites = listOf(SwarmSite("site1", "localhost", "Test", "test@localhost", 1)))
            val gitBlock = caddyfile(config).substringAfter("git.localhost {\n")
                .substringBefore("\n\nartifacts.localhost {")
                .replace("site1-git:8091", apiUpstream).replace("site1-git:8093", uiUpstream)
            val swarm = "{\n    auto_https off\n}\nhttp://git.localhost {\n$gitBlock"
            val local = Files.readString(workspace.resolve("deployments/local/Caddyfile"))
                .replace("git-server:8091", apiUpstream).replace("git-server:8093", uiUpstream)
            for ((name, contents, port) in listOf(Triple("swarm", swarm, 80), Triple("compose", local, 8091))) {
                val file = directory.resolve("$name.Caddyfile")
                Files.writeString(file, contents)
                GenericContainer(config.images.getValue("caddy")).apply {
                    withEnv("BOSCA_PORT", "3000")
                    withExposedPorts(port)
                    withCopyFileToContainer(MountableFile.forHostPath(file), "/etc/caddy/Caddyfile")
                    waitingFor(Wait.forListeningPort())
                }.use { proxy ->
                    proxy.start()
                    assertRoutes(proxy.host, proxy.getMappedPort(port))
                }
            }
            val template = Files.readString(workspace.resolve("deployments/bosca-compose/config/nginx.conf.template"))
            val map = template.substringBefore("server {")
            val nginxGit = ("server {" + template.substringAfter("server_name @@GIT_DOMAIN@@;").let {
                "\n    listen 80;\n    server_name git.localhost;" + it
            }).replace("http://127.0.0.1:@@GIT_PORT@@", "http://$apiUpstream")
                .replace("http://127.0.0.1:@@GIT_UI_PORT@@", "http://$uiUpstream")
                .lineSequence().filterNot { it.trim().startsWith("ssl_") }.joinToString("\n")
            val nginx = directory.resolve("nginx.conf")
            Files.writeString(nginx, "events {}\nhttp {\n$map\n$nginxGit\n}\n")
            GenericContainer("nginx:stable-alpine").apply {
                withExposedPorts(80)
                withCopyFileToContainer(MountableFile.forHostPath(nginx), "/etc/nginx/nginx.conf")
                withCopyFileToContainer(MountableFile.forHostPath(workspace.resolve("deployments/bosca-compose/config/nginx-proxy.conf")),
                    "/etc/nginx/snippets/bosca-proxy.conf")
                waitingFor(Wait.forListeningPort())
            }.use { proxy ->
                proxy.start()
                assertRoutes(proxy.host, proxy.getMappedPort(80))
            }
        } finally {
            api.stop(0)
            ui.stop(0)
            executor.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun assertRoutes(host: String, port: Int) {
        val client = OkHttpClient()
        val bytes = byteArrayOf(0, 1, 127, -1, 10)
        try {
            for ((listener, paths) in listOf("api" to apiPaths, "ui" to uiPaths)) {
                for (path in paths) {
                    val method = when {
                        path.startsWith("/ci/artifacts/") -> "PUT"
                        path.contains("git-receive-pack") || path.contains("git-upload-pack") ||
                            path.endsWith("/batch") || path.endsWith("/verify") || path == "/graphql" ||
                            path.startsWith("/_bml/action") || path.startsWith("/_bml/deferred") -> "POST"
                        else -> "GET"
                    }
                    val body = if (method == "GET") null else bytes.toRequestBody()
                    val request = Request.Builder().url("http://$host:$port$path").header("Host", "git.localhost")
                        .header("Authorization", "Bearer fixture-token").method(method, body).build()
                    client.newCall(request).execute().use { response ->
                        assertEquals(200, response.code, path)
                        assertEquals(listener, response.header("X-Listener"), path)
                        assertEquals(method, response.header("X-Method"), path)
                        assertEquals("Bearer fixture-token", response.header("X-Auth"), path)
                        assertEquals("fixture=$listener; Path=/", response.header("Set-Cookie"), path)
                        val expected = body?.let { bytes } ?: path.toByteArray()
                        assertTrue(expected.contentEquals(requireNotNull(response.body).bytes()), path)
                    }
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
