package bosca.cli.swarm

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwarmDeploymentTest {
    @Test
    fun `site URLs use valid Swarm aliases and reject malformed hosts`() {
        val config = SwarmConfig().withSecrets()
        val site = config.sites.first()
        @Suppress("UNCHECKED_CAST")
        val services = siteStack(config, site)["services"] as Map<String, Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val environment = services.getValue("server")["environment"] as Map<String, String>
        assertEquals("http://s3proxy:8000", environment["STORAGE_ENDPOINT"])
        assertEquals("http://s3proxy:8000", environment["ICEBERG_S3_ENDPOINT"])
        assertEquals("jdbc:trino://trino:8089", environment["TRINO_DATABASE_URL"])
        assertEquals("jdbc:postgresql://pgbouncer:6432/site1-bosca", environment["DATABASE_URL"])

        val aliases = setOf("s3proxy")
        listOf("http://infra_s3proxy:8000", "http://missing-service:8000").forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException> {
                validateServiceUrls(mapOf("server" to mapOf("environment" to mapOf("STORAGE_ENDPOINT" to url))), aliases)
            }
            assertTrue(failure.message.orEmpty().contains("server.STORAGE_ENDPOINT"), failure.message)
        }
    }

    @Test
    fun `render creates separate resources for each configured site and preserves credentials`() {
        val directory = Files.createTempDirectory("bosca-swarm-test-")
        try {
            val path = directory.resolve("swarm.local.json")
            val original = SwarmConfig().withSecrets()
            saveConfig(path, original)
            val loaded = loadConfig(path)
            assertEquals(original, loaded)
            assertEquals(loaded.sites[0].secrets.database, loadConfig(path).sites[0].secrets.database)

            val destination = directory.resolve("output")
            renderSwarm(loaded, destination)
            val first = Files.readString(destination.resolve("stacks/site1.yml"))
            val second = Files.readString(destination.resolve("stacks/site2.yml"))
            assertTrue(first.contains("site1-bosca"))
            assertFalse(first.contains("site2-bosca"))
            assertTrue(second.contains("site2-bosca"))
            assertFalse(second.contains("site1-bosca"))
            assertTrue(Files.readString(destination.resolve("config/nats.conf")).contains("site2"))
            assertTrue(Files.readString(destination.resolve("config/db-bootstrap.sh")).contains("site2-warehouse"))
            assertTrue(Files.readString(destination.resolve("config/trino/rules.json")).contains("site1_readonly"))

            renderSwarm(loaded, destination)
            assertEquals(first, Files.readString(destination.resolve("stacks/site1.yml")))

            val upgraded = loaded.copy(images = loaded.images + ("server" to "registry.example.invalid/bosca-server:v2"))
            renderSwarm(upgraded, destination)
            val updated = Files.readString(destination.resolve("stacks/site1.yml"))
            assertTrue(updated.contains("registry.example.invalid/bosca-server:v2"))
            assertEquals(loaded.sites[0].secrets.database, upgraded.sites[0].secrets.database)
            assertEquals(loaded.sites[0].secrets.nats, upgraded.sites[0].secrets.nats)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `configuration changes roll only the services that read them`() {
        val config = SwarmConfig().withSecrets()
        fun label(stack: Map<String, Any>, service: String): Any? {
            @Suppress("UNCHECKED_CAST")
            val services = stack["services"] as Map<String, Map<String, Any>>
            @Suppress("UNCHECKED_CAST")
            val definition = services.getValue(service)
            @Suppress("UNCHECKED_CAST")
            val taskLabels = definition["labels"] as Map<String, String>?
            @Suppress("UNCHECKED_CAST")
            val deploy = definition["deploy"] as Map<String, Any>
            assertEquals((deploy["labels"] as Map<String, String>?)?.get("io.bosca.config-sha256"),
                taskLabels?.get("io.bosca.config-sha256"))
            return taskLabels?.get("io.bosca.config-sha256")
        }
        val infra = infraStack(config)
        val edge = edgeStack(config)
        assertEquals(infra, infraStack(config))
        listOf("nats", "trino").forEach { assertTrue(label(infra, it).toString().matches(Regex("[0-9a-f]{64}")), it) }
        assertTrue(label(edge, "caddy").toString().matches(Regex("[0-9a-f]{64}")))

        val third = SwarmSite("site3", "site3.example.invalid", "Site Three", "noreply@site3.example.invalid", 3)
        val expanded = config.copy(sites = config.sites + third).withSecrets()
        listOf("nats", "trino", "pgbouncer").forEach { assertNotEquals(label(infra, it), label(infraStack(expanded), it), it) }
        assertNotEquals(label(edge, "caddy"), label(edgeStack(expanded), "caddy"))
        assertEquals(label(infra, "postgres"), label(infraStack(expanded), "postgres"))

        val upgraded = config.copy(images = config.images + ("server" to "registry.example.invalid/bosca-server:v2"))
        listOf("nats", "trino").forEach { assertEquals(label(infra, it), label(infraStack(upgraded), it), it) }
    }

    @Test
    fun `private files are owner-only and replaced without leaving temporary files`() {
        val directory = Files.createTempDirectory("bosca-swarm-test-")
        try {
            val path = directory.resolve("swarm.local.json")
            privateWrite(path, "first")
            privateWrite(path, "second")
            assertEquals("second", Files.readString(path))
            assertEquals(listOf("swarm.local.json"), Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() })
            if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path),
                )
            }

            val destination = directory.resolve("output")
            renderSwarm(SwarmConfig().withSecrets(), destination)
            Files.walk(destination).use { files ->
                files.filter(Files::isRegularFile).forEach { file ->
                    assertFalse(file.fileName.toString().endsWith(".tmp"), file.toString())
                    if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
                        assertEquals(
                            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(file),
                            file.toString(),
                        )
                    }
                }
            }
            assertTrue(Files.isRegularFile(destination.resolve("config/trino/catalog/site1_bosca.properties")))
            assertTrue(Files.isRegularFile(destination.resolve("config/trino/rules.json")))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun bash(vararg command: String, stdin: String? = null): Pair<Int, String> {
        val process = ProcessBuilder("bash", *command).redirectErrorStream(true).start()
        process.outputStream.use { stream -> stdin?.let { stream.write(it.toByteArray()) } }
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun `deployment scripts keep secrets private and create buckets in the S3Proxy store`() {
        val config = SwarmConfig().withSecrets()
        val extract = extractBundleCommand(config)
        val install = installBundleScript(config)

        // Both scripts must parse; the extract command wraps its script in `bash -c`.
        assertEquals(0, bash("-c", extract.replaceFirst("bash -c ", "bash -n -c ")).first, extract)
        assertEquals(0, bash("-n", stdin = install).let { (code, output) -> assertEquals("", output); code })

        assertTrue(extract.startsWith("bash -c "))
        listOf("install -d -m 0700", "chmod 0700", "tar -xz --no-same-owner", "--exclude=", "._*", "/srv/bosca/.upload")
            .forEach { assertTrue(extract.contains(it), it) }

        assertTrue(install.contains("sync_tree '/srv/bosca/.upload/config' '/srv/bosca/config'"))
        assertTrue(install.contains("sync_tree '/srv/bosca/.upload/stacks' '/srv/bosca/stacks'"))
        assertFalse(install.contains("rm -rf '/srv/bosca/config'"))
        assertTrue(
            install.contains(
                "install -d -m 0700 '/srv/bosca/s3/site1-storage' '/srv/bosca/s3/site1-warehouse' " +
                    "'/srv/bosca/s3/site2-storage' '/srv/bosca/s3/site2-warehouse'",
            ),
        )
        assertTrue(install.contains("install -d -m 0755 '/srv/bosca/postgres'"))
        assertTrue(install.contains("chmod 0600 '/srv/bosca/config/nats.conf'"))
        assertTrue(install.contains("--entrypoint id '${config.images.getValue("pgbouncer")}' -u"))
        assertTrue(install.contains("chmod 0600 '/srv/bosca/config/pgbouncer/userlist.txt'"))
        assertTrue(install.contains("-type f -exec chmod 0600"))
        assertTrue(install.contains("--entrypoint id '${config.images.getValue("trino")}' -u"))
        assertFalse(Regex("""minio|\bmc\b""").containsMatchIn(extract + install))
    }

    @Test
    fun `sync_tree overwrites live files in place and removes stale entries`() {
        val directory = Files.createTempDirectory("bosca-swarm-sync-")
        try {
            val staged = directory.resolve("staged")
            val live = directory.resolve("live")
            Files.createDirectories(staged.resolve("catalog"))
            Files.writeString(staged.resolve("nats.conf"), "new")
            Files.writeString(staged.resolve("catalog/site1_bosca.properties"), "site1")
            Files.createDirectories(live.resolve("catalog"))
            Files.createDirectories(live.resolve("removed"))
            Files.writeString(live.resolve("nats.conf"), "old")
            Files.writeString(live.resolve("catalog/site9_bosca.properties"), "stale")
            Files.writeString(live.resolve("removed/file"), "stale")
            val identity = Files.readAttributes(live.resolve("nats.conf"), BasicFileAttributes::class.java).fileKey()

            val (code, output) = bash("-c", syncTreeFunction(sudo = "") + "\nset -euo pipefail\nsync_tree '$staged' '$live'\n")

            assertEquals(0, code, output)
            assertEquals("new", Files.readString(live.resolve("nats.conf")))
            if (identity != null) {
                assertEquals(identity, Files.readAttributes(live.resolve("nats.conf"), BasicFileAttributes::class.java).fileKey())
            }
            assertEquals("site1", Files.readString(live.resolve("catalog/site1_bosca.properties")))
            assertFalse(Files.exists(live.resolve("catalog/site9_bosca.properties")))
            assertFalse(Files.exists(live.resolve("removed")))
            assertEquals("new", Files.readString(staged.resolve("nats.conf")))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `captured command output excludes warnings written to standard error`() {
        val output = runProcess(
            listOf("bash", "-c", "echo 'Warning: Permanently added host' >&2; echo SWMTKN-1-token; echo 'sudo: unable to resolve host' >&2"),
            capture = true,
        )
        assertEquals("SWMTKN-1-token", output)

        val failure = assertFailsWith<IllegalArgumentException> {
            runProcess(listOf("bash", "-c", "echo partial; echo 'permission denied' >&2; exit 3"), capture = true)
        }
        assertTrue(failure.message.orEmpty().startsWith("Command failed (3): bash -c"), failure.message)
        assertTrue(failure.message.orEmpty().contains("partial"), failure.message)
        assertTrue(failure.message.orEmpty().contains("permission denied"), failure.message)

        assertEquals("", runProcess(listOf("bash", "-c", "cat >/dev/null"), input = "data".toByteArray()))
    }

    @Test
    fun `readiness probe reports the last reason a site was not ready`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ready") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.createContext("/starting") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertNull(readinessFailure("$base/ready"))
            assertEquals("HTTP 503", readinessFailure("$base/starting"))
        } finally {
            server.stop(0)
        }
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val refused = readinessFailure("http://127.0.0.1:$closedPort/api/v1/ready", timeoutMillis = 2_000)
        assertTrue(refused.orEmpty().startsWith("ConnectException"), refused)
    }

    @Test
    fun `every site service receives the site's own initial passwords and gateway token`() {
        val config = SwarmConfig().withSecrets()
        val site = config.sites.first()
        @Suppress("UNCHECKED_CAST")
        val services = siteStack(config, site)["services"] as Map<String, Map<String, Any>>
        // Both the server and the runner install `security-initial`; whichever wins creates the accounts.
        listOf("server", "runner", "collector", "git").forEach { name ->
            @Suppress("UNCHECKED_CAST")
            val environment = services.getValue(name)["environment"] as Map<String, String>
            assertEquals(site.secrets.initialAdmin, environment["INIT_ADMIN_PASSWORD"], name)
            assertEquals(site.secrets.initialSa, environment["INIT_SA_PASSWORD"], name)
            assertEquals(site.secrets.gatewayProxy, environment["GATEWAY_PROXY_SHARED_TOKEN"], name)
            assertEquals("https://studio.${site.domain}", environment["OAUTH2_ALLOWED_REDIRECT"], name)
            assertEquals("https://${site.domain}/oauth2/google/callback", environment["OAUTH2_GOOGLE_REDIRECT"], name)
            assertEquals("https://${site.domain}/oauth2/facebook/callback", environment["OAUTH2_FACEBOOK_ADMIN_REDIRECT"], name)
        }
        assertEquals(64, site.secrets.gatewayProxy.length)
        assertNotEquals(site.secrets.gatewayProxy, config.sites[1].secrets.gatewayProxy)

        val rendered = config.sites.joinToString("\n") { document(siteStack(config, it)) }
        listOf("dev-gateway-proxy-shared-token-not-for-prod", "\"password\"", "\"sa-password\"", "localhost")
            .forEach { assertFalse(rendered.contains(it), it) }
    }

    @Test
    fun `an existing configuration gains a gateway token without changing its other secrets`() {
        val directory = Files.createTempDirectory("bosca-swarm-test-")
        try {
            val path = directory.resolve("swarm.local.json")
            val original = SwarmConfig().withSecrets()
            val legacy = swarmJson.encodeToString(SwarmConfig.serializer(), original)
                .replace(Regex(""",\s*"gatewayProxy":\s*"[0-9a-f]+""""), "")
            assertFalse(legacy.contains("gatewayProxy"))
            Files.writeString(path, legacy)

            val upgraded = loadConfig(path)

            assertTrue(upgraded.sites.all { it.secrets.gatewayProxy.length == 64 })
            assertEquals(original.sites.map { it.secrets.copy(gatewayProxy = "") }, upgraded.sites.map { it.secrets.copy(gatewayProxy = "") })
            assertEquals(upgraded, loadConfig(path))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `site hostnames follow the Kubernetes layout and keep an existing Git override`() {
        val site = SwarmSite("sitea", "example.org", "Site A", "noreply@example.org", 1)
        assertEquals(listOf("example.org", "api.example.org", "upload.example.org", "ws.example.org"), site.hosts.server)
        assertEquals("studio.example.org", site.hosts.studio)
        assertEquals("profiles.example.org", site.hosts.profiles)
        assertEquals("a.example.org", site.hosts.analytics)
        assertEquals("git.example.org", site.hosts.git)
        assertEquals("notifications.example.org", site.hosts.notifications)
        assertEquals("messages.example.org", site.hosts.messages)
        assertEquals("code.example.org", site.copy(gitDomain = "code.example.org").hosts.git)

        // A configuration written before hosts were derived still names its Git host explicitly.
        val legacy = swarmJson.decodeFromString(
            SwarmSite.serializer(),
            """{"id":"sitea","domain":"example.org","gitDomain":"git.example.org","mailFromName":"A",""" +
                """"mailFromEmail":"noreply@example.org","redisDatabase":1}""",
        )
        assertEquals("git.example.org", legacy.hosts.git)
    }

    @Test
    fun `per-site web settings control public hosts cookies and branding`() {
        val site = SwarmSite("sitea", "example.org", "Sender Name", "noreply@example.org", 1,
            profilesWeb = SwarmProfilesWeb(
                host = "account.example.org", cookieDomain = "example.org", brandName = "Account Name",
                logoUrl = "https://example.org/account.svg", primaryColor = "#123456", accentColor = "#abc",
            ),
            notificationsWeb = SwarmNotificationsWeb(
                host = "mail.example.org", brandName = "Mail Name",
                logoUrl = "https://example.org/mail.svg", primaryColor = "#abcdef", accentColor = "#1234",
            ),
        )
        val config = SwarmConfig(sites = listOf(site)).withSecrets()
        config.validate()
        @Suppress("UNCHECKED_CAST")
        val services = siteStack(config, site)["services"] as Map<String, Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        fun environment(name: String) = services.getValue(name)["environment"] as Map<String, String>

        assertEquals("https://account.example.org", environment("profiles-web")["PROFILES_WEB_PUBLIC_URL"])
        assertEquals("example.org", environment("profiles-web")["PROFILES_WEB_COOKIE_DOMAIN"])
        assertEquals("Account Name", environment("profiles-web")["BRAND_NAME"])
        assertEquals("https://example.org/account.svg", environment("profiles-web")["BRAND_LOGO_URL"])
        assertEquals("#123456", environment("profiles-web")["BRAND_PRIMARY_COLOR"])
        assertEquals("Mail Name", environment("notifications-web")["BRAND_NAME"])
        assertEquals("https://example.org/mail.svg", environment("notifications-web")["BRAND_LOGO_URL"])
        assertEquals("#1234", environment("notifications-web")["BRAND_ACCENT_COLOR"])
        assertEquals("https://mail.example.org/unsubscribe", environment("server")["MAILER_UNSUBSCRIBE_URL"])
        assertEquals("https://messages.example.org", environment("bml-message-server")["BML_MESSAGE_PUBLIC_URL"])
        assertTrue(caddyfile(config).contains("account.example.org {\n    reverse_proxy sitea-profiles-web:9095"))
        assertTrue(caddyfile(config).contains("mail.example.org {\n    reverse_proxy sitea-notifications-web:9094"))
        assertTrue(caddyfile(config).contains("messages.example.org {\n    @public path"))
        assertEquals("https://account.example.org/login", publicReadinessUrls(config)[2])
        assertEquals("https://mail.example.org/", publicReadinessUrls(config)[3])
    }

    @Test
    fun `per-site web settings reject invalid cookie domains and colors`() {
        val site = SwarmSite("sitea", "example.org", "A", "noreply@example.org", 1)
        assertFailsWith<IllegalArgumentException> {
            SwarmConfig(sites = listOf(site.copy(profilesWeb = SwarmProfilesWeb(cookieDomain = "other.org")))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            SwarmConfig(sites = listOf(site.copy(notificationsWeb = SwarmNotificationsWeb(primaryColor = "red")))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            SwarmConfig(sites = listOf(site.copy(notificationsWeb = SwarmNotificationsWeb(host = "profiles.example.org")))).validate()
        }
    }

    @Test
    fun `validation rejects hostnames shared between sites`() {
        val config = SwarmConfig().withSecrets()
        config.validate()
        // site2's base domain is site1's analytics host.
        val overlapping = config.copy(sites = listOf(config.sites[0], config.sites[1].copy(domain = "a.site1.example.invalid")))
        assertFailsWith<IllegalArgumentException> { overlapping.validate() }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = config.sites.map { it.copy(gitDomain = "git.shared.example.invalid") }).validate()
        }
    }

    @Test
    fun `studio profiles and notifications use the Kubernetes public origins`() {
        val config = SwarmConfig().withSecrets()
        val site = config.sites.first()
        @Suppress("UNCHECKED_CAST")
        val services = siteStack(config, site)["services"] as Map<String, Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        fun environment(service: String) = services.getValue(service)["environment"] as Map<String, String>
        val studio = environment("studio")
        // Studio appends /graphqlws itself, so the WebSocket URL is a bare origin.
        assertEquals("wss://studio.${site.domain}", studio["NUXT_PUBLIC_WS_URL"])
        assertEquals("https://studio.${site.domain}", studio["NUXT_PUBLIC_API_URL"])
        assertEquals("https://studio.${site.domain}/graphql", studio["NUXT_PUBLIC_APOLLO_CLIENTS_DEFAULT_HTTP_ENDPOINT"])
        assertEquals("https://git.${site.domain}", studio["NUXT_PUBLIC_GIT_SERVER_URL"])
        assertEquals(site.domain, studio["NUXT_PUBLIC_AUTH_DOMAIN"])
        assertEquals("https://messages.${site.domain}", environment("bml-message-server")["BML_MESSAGE_PUBLIC_URL"])
        assertEquals("http://${site.id}-server:8080/graphql", environment("profiles-web")["BML_GRAPHQL_ENDPOINT"])
        assertEquals("https://profiles.${site.domain}", environment("profiles-web")["PROFILES_WEB_PUBLIC_URL"])
        assertEquals(site.domain, environment("profiles-web")["PROFILES_WEB_COOKIE_DOMAIN"])
        assertEquals("http://${site.id}-server:8080/graphql", environment("notifications-web")["BML_GRAPHQL_ENDPOINT"])
        assertEquals(site.mailFromName, environment("notifications-web")["BRAND_NAME"])

        val server = environment("server")
        assertEquals("https://studio.${site.domain}", server["APP_URL"])
        assertEquals("https://studio.${site.domain}", server["APP_ALLOWED_ORIGIN"])
        assertEquals("https://${site.domain}", server["STORAGE_URL_PREFIX"])
        assertEquals("https://upload.${site.domain}", server["STORAGE_URL_UPLOAD_PREFIX"])
        assertEquals("https://${site.domain}/oauth2/google/callback", server["OAUTH2_GOOGLE_REDIRECT"])
        assertEquals("https://notifications.${site.domain}/unsubscribe", server["MAILER_UNSUBSCRIBE_URL"])
        assertEquals("https://notifications.${site.domain}/preferences", server["MAILER_PREFERENCES_URL"])
        assertEquals("https://profiles.${site.domain}/security?tab=logins", server["SECURITY_ALERT_URL"])
        assertEquals("https://git.${site.domain}", server["GIT_URL"])
        assertEquals("https://studio.${site.domain}", server["WEBAUTHN_EXTRA_ORIGIN"])
    }

    @Test
    fun `server receives the site's Google sign-in client`() {
        val config = SwarmConfig().withSecrets()
        val site = config.sites[0].copy(google = SwarmSiteGoogle("id.apps.googleusercontent.com", "google-secret"))
        val environment = siteEnvironment(config, site)
        assertEquals("id.apps.googleusercontent.com", environment["GOOGLE_CLIENT_ID"])
        assertEquals("google-secret", environment["GOOGLE_CLIENT_SECRET"])
        assertEquals("", siteEnvironment(config, config.sites[1])["GOOGLE_CLIENT_ID"])
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = listOf(config.sites[0].copy(google = SwarmSiteGoogle(clientId = "id-only"))) + config.sites.drop(1)).validate()
        }
    }

    @Test
    fun `caddy routes each hostname like the Kubernetes HTTPRoutes`() {
        val config = SwarmConfig().withSecrets()
        val caddy = caddyfile(config)
        val d = "site1.example.invalid"
        assertTrue(caddy.contains("$d, api.$d, upload.$d, ws.$d {\n    reverse_proxy site1-server:8080\n}"), caddy)
        assertTrue(caddy.contains("studio.$d {"))
        assertTrue(caddy.contains("@collector path /api/v1/events /api/v1/events/* /api/v1/installation /api/v1/installation/*"))
        assertTrue(
            caddy.contains(
                "@server path /graphql /graphql/* /oauth2 /oauth2/* /api/v1 /api/v1/* /files /files/* " +
                    "/content /content/* /graphqlws /graphqlws/*",
            ),
        )
        // Collector paths are matched before the broader /api/v1 prefix, and Studio takes everything else,
        // including its own /collaboration WebSocket.
        val studioBlock = caddy.substringAfter("studio.$d {").substringBefore("\n}\n")
        assertTrue(studioBlock.indexOf("reverse_proxy @collector") < studioBlock.indexOf("reverse_proxy @server"))
        assertTrue(studioBlock.indexOf("reverse_proxy @server") < studioBlock.indexOf("reverse_proxy site1-studio:3000"))
        assertFalse(caddy.contains("collaboration"))
        assertTrue(caddy.contains("a.$d {\n    reverse_proxy site1-collector:8081\n}"))
        assertTrue(caddy.contains("profiles.$d {\n    reverse_proxy site1-profiles-web:9095\n}"))
        assertTrue(caddy.contains("notifications.$d {\n    reverse_proxy site1-notifications-web:9094\n}"))
        assertTrue(caddy.contains("git.$d {\n    reverse_proxy site1-git:8091\n}"))
        val messages = caddy.substringAfter("messages.$d {").substringBefore("\n}\n")
        assertTrue(messages.contains("@public path /assets /assets/* /c /c/* /o /o/*"))
        assertTrue(messages.contains("reverse_proxy @public site1-bml-message-server:9093"))
        assertTrue(messages.contains("reverse_proxy site1-notifications-web:9094"))
        assertFalse(caddy.contains("bml-messages"))
        assertTrue(caddy.contains("studio.site2.example.invalid {"))
    }

    @Test
    fun `a site website image serves its root and www hosts`() {
        val site = SwarmSite("site1", "example.org", "Site One", "mail@example.org", 1,
            rootImage = "registry.example.org/site-web:v1", rootPort = 4321)
        val config = SwarmConfig(sites = listOf(site)).withSecrets()
        @Suppress("UNCHECKED_CAST")
        val services = siteStack(config, site)["services"] as Map<String, Map<String, Any>>

        assertEquals("registry.example.org/site-web:v1", services.getValue("root-web")["image"])
        @Suppress("UNCHECKED_CAST")
        val rootEnvironment = services.getValue("root-web")["environment"] as Map<String, String>
        assertEquals("http://site1-server:8080/graphql", rootEnvironment["BML_GRAPHQL_ENDPOINT"])
        assertTrue(caddyfile(config).contains("example.org, www.example.org {"))
        assertTrue(caddyfile(config).contains("reverse_proxy site1-root-web:4321"))
        assertTrue(caddyfile(config).contains("reverse_proxy @server site1-server:8080"))
        assertTrue(caddyfile(config).contains("@server path /graphql /graphql/* /oauth2 /oauth2/*"))
        assertTrue(caddyfile(config).contains("api.example.org, upload.example.org, ws.example.org {\n    reverse_proxy site1-server:8080\n}"))
        assertFalse(caddyfile(config).contains("example.org, api.example.org"))
        assertTrue("site1_root-web" in expectedServices(config))
        assertEquals(
            listOf("https://api.example.org/api/v1/ready", "https://studio.example.org/health",
                "https://profiles.example.org/login", "https://notifications.example.org/",
                "https://example.org/", "https://www.example.org/"),
            publicReadinessUrls(config),
        )
        assertEquals(
            listOf("https://example.org/api/v1/ready", "https://studio.example.org/health",
                "https://profiles.example.org/login", "https://notifications.example.org/"),
            publicReadinessUrls(config.copy(sites = listOf(site.copy(rootImage = "")))),
        )
    }

    @Test
    fun `applications reach PostgreSQL through PgBouncer while Trino connects directly`() {
        val config = SwarmConfig().withSecrets()
        @Suppress("UNCHECKED_CAST")
        val infra = infraStack(config)["services"] as Map<String, Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val pgbouncer = infra.getValue("pgbouncer")["environment"] as Map<String, String>
        assertEquals("pgbouncer/pgbouncer:1.25.2", infra.getValue("pgbouncer")["image"])
        assertEquals("transaction", pgbouncer["PGBOUNCER_POOL_MODE"])
        assertEquals("1000", pgbouncer["PGBOUNCER_MAX_CLIENT_CONN"])
        assertEquals("25", pgbouncer["PGBOUNCER_DEFAULT_POOL_SIZE"])
        assertEquals("scram-sha-256", pgbouncer["PGBOUNCER_AUTH_TYPE"])
        assertEquals("6432", pgbouncer["PGBOUNCER_LISTEN_PORT"])
        assertEquals(
            "site1-bosca = host=postgres port=5432 dbname=site1-bosca," +
                "site1-warehouse = host=postgres port=5432 dbname=site1-warehouse," +
                "site2-bosca = host=postgres port=5432 dbname=site2-bosca," +
                "site2-warehouse = host=postgres port=5432 dbname=site2-warehouse",
            pgbouncer["DATABASES"],
        )
        // Credentials live only in the owner-only auth file, never in the service definition.
        config.sites.forEach { site -> assertFalse(document(infraStack(config)).contains(site.secrets.database)) }
        val site = config.sites.first()
        assertEquals(
            "\"site1\" \"${site.secrets.database}\"\n\"site1_warehouse\" \"${site.secrets.warehouse}\"\n",
            pgbouncerUserlist(config.copy(sites = listOf(site))),
        )
        assertEquals(listOf("postgres", "-c", "max_connections=200"), infra.getValue("postgres")["command"])
        val many = config.copy(sites = (1..15).map { SwarmSite("site$it", "site$it.example.invalid", "Site", "noreply@site$it.example.invalid", it) })
        assertEquals(15 * 2 * 25 + 50, postgresMaxConnections(many))

        val environment = siteEnvironment(config, site)
        assertEquals("jdbc:postgresql://pgbouncer:6432/site1-bosca", environment["DATABASE_URL"])
        assertEquals("jdbc:postgresql://pgbouncer:6432/site1-warehouse", environment["ICEBERG_DATABASE_URI"])
        val destination = Files.createTempDirectory("bosca-swarm-test-")
        try {
            renderSwarm(config, destination.resolve("output"))
            val catalogs = destination.resolve("output/config/trino/catalog")
            assertTrue(Files.readString(catalogs.resolve("site1_bosca.properties")).contains("jdbc:postgresql://postgres:5432/site1-bosca"))
            assertTrue(Files.readString(catalogs.resolve("site1_warehouse.properties")).contains("jdbc:postgresql://postgres:5432/site1-warehouse"))
            assertEquals(pgbouncerUserlist(config), Files.readString(destination.resolve("output/config/pgbouncer/userlist.txt")))
        } finally {
            Files.walk(destination).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `an existing configuration gains the PgBouncer image and keeps the operator's images`() {
        val directory = Files.createTempDirectory("bosca-swarm-test-")
        try {
            val path = directory.resolve("swarm.local.json")
            val original = SwarmConfig().withSecrets().let {
                it.copy(images = it.images - "pgbouncer" + ("server" to "registry.example.invalid/bosca-server:v9"))
            }
            saveConfig(path, original)
            assertFalse(Files.readString(path).contains("pgbouncer"))

            val upgraded = loadConfig(path)

            assertEquals("pgbouncer/pgbouncer:1.25.2", upgraded.images["pgbouncer"])
            assertEquals("registry.example.invalid/bosca-server:v9", upgraded.images["server"])
            assertTrue(Files.readString(path).contains("pgbouncer/pgbouncer:1.25.2"))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `render refuses to replace an unmanaged directory`() {
        val directory = Files.createTempDirectory("bosca-swarm-test-")
        try {
            Files.writeString(directory.resolve("important.txt"), "keep")
            assertFailsWith<IllegalArgumentException> { renderSwarm(SwarmConfig().withSecrets(), directory) }
            assertEquals("keep", Files.readString(directory.resolve("important.txt")))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `template pulls public Bosca images anonymously and requires the Artifacts URL`() {
        val template = SwarmConfig().withSecrets()
        assertTrue(template.images.getValue("server").startsWith("ghcr.io/bosca-io/bosca/bosca-server:"))
        assertTrue(registryAuths(template).isEmpty(), "public images need no registry login")

        val ready = template.copy(
            manager = template.manager.copy(ssh = "operator@manager.example.com"),
            workers = template.workers.map { it.copy(ssh = "operator@worker.example.com") },
            images = template.images.mapValues { (_, image) -> image.replace("SET_NEW_TAG", "1.0.0") },
            sites = template.sites.map {
                it.copy(
                    domain = it.domain.removeSuffix(".invalid") + ".com",
                    bmlArtifactsUrl = "https://artifacts.example.com",
                    bmlArtifactsToken = "artifacts-token",
                )
            },
        )
        ready.validate(deploy = true)

        val unsetArtifactsUrl = assertFailsWith<IllegalArgumentException> {
            ready.copy(sites = listOf(ready.sites.first().copy(bmlArtifactsUrl = "SET_URL")) + ready.sites.drop(1))
                .validate(deploy = true)
        }
        assertEquals("Set bmlArtifactsUrl for site1", unsetArtifactsUrl.message)
    }

    @Test
    fun `deployment rejects placeholders and duplicate isolation identifiers`() {
        val config = SwarmConfig().withSecrets()
        config.validate()
        assertFailsWith<IllegalArgumentException> { config.validate(deploy = true) }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = config.sites.map { it.copy(redisDatabase = 1) }).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = config.sites.map { it.copy(id = "site1") }).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(workers = listOf(SwarmWorker("ops@worker", "10.0.0.11;shutdown"))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = listOf(config.sites[0].copy(rootPort = 0))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sites = listOf(config.sites[0].copy(rootImage = "image:v1\nmalformed"))).validate()
        }
    }
}
