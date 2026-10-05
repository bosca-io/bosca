package bosca.cli.swarm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

@Serializable
data class SwarmConfig(
    val version: Int = 1,
    val manager: SwarmManager = SwarmManager(),
    val workers: List<SwarmWorker> = listOf(SwarmWorker()),
    val dataRoot: String = "/srv/bosca",
    val registryAuth: SwarmRegistryAuth = SwarmRegistryAuth(),
    val images: Map<String, String> = defaultImages(),
    val sites: List<SwarmSite> = listOf(
        SwarmSite("site1", "site1.example.invalid", "Site One", "noreply@site1.example.invalid", 1),
        SwarmSite("site2", "site2.example.invalid", "Site Two", "noreply@site2.example.invalid", 2),
    ),
    val secrets: SwarmSharedSecrets = SwarmSharedSecrets(),
    val backup: SwarmBackup = SwarmBackup(),
)

/**
 * Nightly off-VM backup with restic: PostgreSQL dumps of every site database plus the S3 objects, NATS
 * JetStream data, Caddy certificates and rendered configuration. A blank [repository] disables it.
 */
@Serializable data class SwarmBackup(
    /** restic repository, for example `s3:https://sfo3.digitaloceanspaces.com/my-bucket/bosca`. */
    val repository: String = "",
    /** S3 credentials for an `s3:` repository. */
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    /** UTC hour of the nightly database dump; the restic snapshot follows 30 minutes later. */
    val hour: Int = 3,
    val keepDaily: Int = 7,
    val keepWeekly: Int = 4,
    val keepMonthly: Int = 6,
)

@Serializable data class SwarmManager(val ssh: String = "operator@manager.example.invalid", val advertiseAddress: String = "10.0.0.10")
@Serializable data class SwarmWorker(
    val ssh: String = "operator@worker.example.invalid",
    /** Optional private address used for Swarm control and overlay traffic on hosts with multiple interfaces. */
    val advertiseAddress: String = "",
)
@Serializable data class SwarmRegistryAuth(
    val server: String = "ghcr.io",
    val username: String = "",
    val passwordEnv: String = "BOSCA_REGISTRY_PASSWORD",
    /** Optional inline credential; the owner-only config file is already a secret store. */
    val password: String = "",
)
@Serializable data class SwarmSharedSecrets(
    val postgresAdmin: String = "", val meilisearchKey: String = "", val s3Access: String = "", val s3Secret: String = "",
    /** Encrypts the restic backup repository; losing it makes the backups unreadable. */
    val backupPassword: String = "",
)
@Serializable data class SwarmSiteSecrets(
    val database: String = "", val warehouse: String = "", val nats: String = "", val jwt: String = "",
    val securityEncryption: String = "", val storageSigner: String = "", val initialAdmin: String = "",
    val initialSa: String = "", val pipeline: String = "",
    /** Bearer token for the gateway proxy's config endpoint; random because the Swarm runs no gateway proxy. */
    val gatewayProxy: String = "",
)
@Serializable data class SwarmSite(
    val id: String,
    /** Base domain; every public hostname of the site is derived from it (see [hosts]). */
    val domain: String,
    val mailFromName: String,
    val mailFromEmail: String,
    val redisDatabase: Int,
    val bmlArtifactsUrl: String = "SET_URL",
    val bmlArtifactsToken: String = "SET_TOKEN",
    val secrets: SwarmSiteSecrets = SwarmSiteSecrets(),
    /** Optional Git hostname override; blank uses `git.<domain>`. Kept for configurations created before derived hosts. */
    val gitDomain: String = "",
    val ml: SwarmSiteMl = SwarmSiteMl(),
    /** Optional Google sign-in client; blank values leave Google sign-in unconfigured. */
    val google: SwarmSiteGoogle = SwarmSiteGoogle(),
    /** Optional website image for the root and www hosts; it listens on [rootPort]. */
    val rootImage: String = "",
    val rootPort: Int = 3000,
    /** Optional login for a private root website image registry distinct from [SwarmConfig.registryAuth]. */
    val rootRegistryAuth: SwarmRegistryAuth? = null,
    /** Outgoing email provider; its credentials are configured per site in Studio. */
    val mailerType: String = "sendgrid",
    /** Self-service account site settings; blank values use this site's domain and sender name. */
    val profilesWeb: SwarmProfilesWeb = SwarmProfilesWeb(),
    /** Notification preference site settings. */
    val notificationsWeb: SwarmNotificationsWeb = SwarmNotificationsWeb(),
    /** Bosca server memory limit in Docker units, for example `768M` or `1536M`. */
    val serverMemory: String = "768M",
)

/** Per-site host, auth cookie domain, and optional branding for profiles-web. */
@Serializable data class SwarmProfilesWeb(
    val host: String = "",
    val cookieDomain: String = "",
    val brandName: String = "",
    val logoUrl: String = "",
    val primaryColor: String = "",
    val accentColor: String = "",
)

/** Per-site host and optional branding for notifications-web. */
@Serializable data class SwarmNotificationsWeb(
    val host: String = "",
    val brandName: String = "",
    val logoUrl: String = "",
    val primaryColor: String = "",
    val accentColor: String = "",
)

/**
 * Tokens the recommendation trainer and model loader use to call the site's Bosca server and artifacts
 * server, like the Kubernetes `recommendation-ml` Secret. They are created in the site after the first
 * deployment; until then the trainer and loader run but cannot train or load models.
 */
@Serializable data class SwarmSiteMl(
    val boscaToken: String = "",
    val artifactsPushToken: String = "",
    val artifactsPullToken: String = "",
)

/**
 * Google OAuth web client for the site's sign-in. Its authorized redirect URI must be
 * `https://<domain>/oauth2/google/callback`, the callback the server is given.
 */
@Serializable data class SwarmSiteGoogle(
    val clientId: String = "",
    val clientSecret: String = "",
)

/**
 * Public hostnames of one site, laid out like the Kubernetes Gateway routes: the base domain and its
 * `api.`, `upload.` and `ws.` hosts serve bosca-server, `studio.` serves Studio with same-origin API paths,
 * `a.` the analytics collector, `git.` the Git server, `artifacts.` the artifacts registry,
 * `notifications.` the preference site, and `messages.` the message server's public assets.
 */
internal data class SiteHosts(
    val root: String,
    val www: String,
    val api: String,
    val upload: String,
    val ws: String,
    val studio: String,
    val profiles: String,
    val analytics: String,
    val git: String,
    val notifications: String,
    val messages: String,
    val artifacts: String,
) {
    val server: List<String> get() = listOf(root, api, upload, ws)
    val all: List<String> get() = server + listOf(www, studio, profiles, analytics, git, notifications, messages, artifacts)
}

internal val SwarmSite.hosts: SiteHosts
    get() = SiteHosts(
        root = domain,
        www = "www.$domain",
        api = "api.$domain",
        upload = "upload.$domain",
        ws = "ws.$domain",
        studio = "studio.$domain",
        profiles = profilesWeb.host.ifBlank { "profiles.$domain" },
        analytics = "a.$domain",
        git = gitDomain.ifBlank { "git.$domain" },
        notifications = notificationsWeb.host.ifBlank { "notifications.$domain" },
        messages = "messages.$domain",
        artifacts = "artifacts.$domain",
    )

/** Default images, pinned to exact versions; configurations keep the images they already name. */
internal fun defaultImages() = mapOf(
    "postgres" to "pgvector/pgvector:0.8.6-pg18", "pgbouncer" to "pgbouncer/pgbouncer:1.25.2", "nats" to "nats:2.12.15-alpine",
    "redis" to "redis:7.4.11-alpine", "meilisearch" to "getmeili/meilisearch:v1.39.0",
    "s3proxy" to "andrewgaul/s3proxy:3.0.0", "trino" to "trinodb/trino:479",
    "imageprocessor" to "ghcr.io/bosca-io/bosca/imageprocessor:4.8.22",
    "text-embeddings-inference" to "ghcr.io/huggingface/text-embeddings-inference:cpu-1.8.1",
    "tf-serving" to "tensorflow/serving:2.16.1",
    "backup" to "restic/restic:0.19.1",
    "caddy" to "caddy:2.11.4", "server" to "ghcr.io/bosca-io/bosca/bosca-server:SET_NEW_TAG",
    "runner" to "ghcr.io/bosca-io/bosca/bosca-runner:SET_NEW_TAG",
    "collector" to "ghcr.io/bosca-io/bosca/analytics-collector:SET_NEW_TAG",
    "git" to "ghcr.io/bosca-io/bosca/git-server:SET_NEW_TAG",
    "studio" to "ghcr.io/bosca-io/bosca/bosca-studio:SET_NEW_TAG",
    "profiles-web" to "ghcr.io/bosca-io/bosca/profiles-web:6.17.3",
    "notifications-web" to "ghcr.io/bosca-io/bosca/notifications-web:6.17.3",
    "bml" to "ghcr.io/bosca-io/bosca/bml-message-server:6.23.0",
    "artifacts" to "ghcr.io/bosca-io/bosca/artifacts-server:SET_NEW_TAG",
    "recommendation-trainer" to "ghcr.io/bosca-io/bosca/recommendation-trainer:SET_NEW_TAG",
    "recommendation-model-loader" to "ghcr.io/bosca-io/bosca/recommendation-model-loader:6.31.1",
)

internal val swarmJson = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }
private val random = SecureRandom()
private fun secret(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
private fun filled(value: String) = value.ifEmpty(::secret)

/** Adds images introduced after a configuration was created, keeping every image the operator set. */
internal fun SwarmConfig.withDefaultImages(): SwarmConfig = copy(images = defaultImages() + images)

internal fun SwarmConfig.withSecrets(): SwarmConfig = copy(
    secrets = secrets.copy(
        postgresAdmin = filled(secrets.postgresAdmin), meilisearchKey = filled(secrets.meilisearchKey),
        s3Access = secrets.s3Access.ifEmpty { "bosca${secret().take(24)}" }, s3Secret = filled(secrets.s3Secret),
        backupPassword = filled(secrets.backupPassword),
    ),
    sites = sites.map { site ->
        val s = site.secrets
        site.copy(secrets = s.copy(
            database = filled(s.database), warehouse = filled(s.warehouse), nats = filled(s.nats),
            jwt = filled(s.jwt), securityEncryption = filled(s.securityEncryption),
            storageSigner = filled(s.storageSigner), initialAdmin = filled(s.initialAdmin),
            initialSa = filled(s.initialSa), gatewayProxy = filled(s.gatewayProxy), pipeline = s.pipeline.ifEmpty {
                java.util.Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
            },
        ))
    },
)

/** Keeps one partially encrypted configuration unlocked for a command, including incremental token saves. */
internal class SwarmConfigFile(
    val path: Path,
    private val readPassphrase: (Boolean) -> CharArray = ::swarmConfigPassphrase,
) : AutoCloseable {
    private var passphrase: CharArray? = null
    private var encryptedSecrets: Boolean? = null

    val hasEncryptedSecrets: Boolean get() = encryptedSecrets == true

    private fun passphrase(confirm: Boolean): CharArray = passphrase ?: readPassphrase(confirm).also { passphrase = it }

    fun load(deploy: Boolean = false): SwarmConfig {
        require(Files.isRegularFile(path)) { "Configuration not found: $path" }
        val document = swarmJson.parseToJsonElement(Files.readString(path)).jsonObject
        encryptedSecrets = SwarmSecretEncryption.hasEncryptedSecrets(document)
        val clear = if (hasEncryptedSecrets) SwarmSecretEncryption.decrypt(document, passphrase(false)) else document
        val original = swarmJson.decodeFromJsonElement(SwarmConfig.serializer(), clear)
        val config = original.withDefaultImages().withSecrets()
        config.validate(deploy)
        if (config != original) save(config)
        return config
    }

    fun save(config: SwarmConfig) {
        val existing = if (encryptedSecrets == null && Files.isRegularFile(path)) {
            swarmJson.parseToJsonElement(Files.readString(path)).jsonObject
        } else null
        val useEncryption = encryptedSecrets ?: existing?.let(SwarmSecretEncryption::hasEncryptedSecrets) ?: false
        if (existing != null && useEncryption) SwarmSecretEncryption.decrypt(existing, passphrase(false))
        val document = swarmJson.encodeToJsonElement(SwarmConfig.serializer(), config).jsonObject
        val stored = if (useEncryption) SwarmSecretEncryption.encrypt(document, passphrase(false)) else document
        privateWrite(path, swarmJson.encodeToString(JsonObject.serializer(), stored) + "\n")
        encryptedSecrets = useEncryption
    }

    override fun close() {
        passphrase?.fill('\u0000')
        passphrase = null
    }
}

internal fun saveConfig(path: Path, config: SwarmConfig) = SwarmConfigFile(path).use { it.save(config) }
internal fun loadConfig(path: Path, deploy: Boolean = false): SwarmConfig =
    SwarmConfigFile(path).use { it.load(deploy) }

internal fun SwarmConfig.validate(deploy: Boolean = false) {
    require(version == 1) { "Unsupported configuration version" }
    val target = Regex("[A-Za-z0-9_.-]+@[A-Za-z0-9_.:-]+")
    require(target.matches(manager.ssh)) { "manager.ssh must be user@host" }
    require(manager.advertiseAddress.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid manager.advertiseAddress" }
    require(workers.isNotEmpty() && workers.all { target.matches(it.ssh) }) { "Configure worker SSH targets as user@host" }
    require(workers.all { it.advertiseAddress.isEmpty() || it.advertiseAddress.matches(Regex("[A-Za-z0-9_.:-]+")) }) {
        "Invalid worker advertiseAddress"
    }
    require((workers.map { it.ssh } + manager.ssh).distinct().size == workers.size + 1) { "SSH targets must be distinct" }
    require(Path.of(dataRoot).isAbsolute && dataRoot != "/" && !dataRoot.contains(Regex("[\\n\\r:]"))) { "dataRoot must be a safe absolute directory" }
    require(sites.isNotEmpty() && sites.size <= 15) { "Configure between one and 15 sites" }
    val idPattern = Regex("[a-z][a-z0-9]{0,19}")
    val domainPattern = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
    require(sites.map { it.id }.distinct().size == sites.size) { "Site ids must be unique" }
    val hostnames = sites.flatMap { it.hosts.all }
    require(hostnames.distinct().size == hostnames.size) { "Site hostnames must be unique: ${hostnames.groupBy { it }.filterValues { it.size > 1 }.keys}" }
    require(sites.map { it.redisDatabase }.distinct().size == sites.size) { "Redis databases must be unique" }
    for (site in sites) {
        require(idPattern.matches(site.id) && site.id !in setOf("infra", "edge")) { "Invalid site id: ${site.id}" }
        require(site.hosts.all.all(domainPattern::matches)) { "Invalid domain for ${site.id}" }
        require(site.redisDatabase in 1..15) { "Redis database must be between 1 and 15 for ${site.id}" }
        require(site.rootPort in 1..65535) { "Invalid rootPort for ${site.id}" }
        require(site.serverMemory.matches(Regex("[1-9][0-9]*[bkmg]?", RegexOption.IGNORE_CASE))) {
            "serverMemory for ${site.id} must be a positive byte count with an optional B, K, M, or G suffix"
        }
        require(site.mailerType in setOf("sendgrid", "mailgun")) {
            "mailerType for ${site.id} must be sendgrid or mailgun"
        }
        val cookieDomain = site.profilesWeb.cookieDomain.ifBlank { site.domain }
        require(domainPattern.matches(cookieDomain) &&
            (site.domain == cookieDomain || site.domain.endsWith(".$cookieDomain")) &&
            (site.hosts.profiles == cookieDomain || site.hosts.profiles.endsWith(".$cookieDomain"))) {
            "profilesWeb.cookieDomain for ${site.id} must cover the site and Profiles hosts"
        }
        val brandColors = listOf(site.profilesWeb.primaryColor, site.profilesWeb.accentColor,
            site.notificationsWeb.primaryColor, site.notificationsWeb.accentColor)
        require(brandColors.all { it.isBlank() || HEX_COLOR.matches(it) }) {
            "Web brand colors for ${site.id} must be CSS hex colors"
        }
        val brandText = listOf(site.profilesWeb.brandName, site.profilesWeb.logoUrl,
            site.notificationsWeb.brandName, site.notificationsWeb.logoUrl)
        require(brandText.all(::singleLine)) { "Web brand values for ${site.id} must be single-line" }
        require(site.rootImage.isEmpty() || (site.rootImage.isNotBlank() && site.rootImage == site.rootImage.trim() && singleLine(site.rootImage))) {
            "Invalid rootImage for ${site.id}"
        }
        site.rootRegistryAuth?.let { auth ->
            require(site.rootImage.isNotBlank() && auth.server.isNotBlank() &&
                site.rootImage.startsWith("${auth.server}/") && auth.username.isNotBlank() &&
                singleLine(auth.server) && singleLine(auth.username) && singleLine(auth.password) &&
                singleLine(auth.passwordEnv)) {
                "rootRegistryAuth for ${site.id} must name the root image registry and a username"
            }
        }
        require(listOf(site.mailFromName, site.mailFromEmail, site.bmlArtifactsUrl, site.bmlArtifactsToken).all {
            it.isNotBlank() && !it.contains('\n') && !it.contains('\r')
        }) { "Set mail and Artifacts values for ${site.id}" }
        require(listOf(site.ml.boscaToken, site.ml.artifactsPushToken, site.ml.artifactsPullToken).all(::singleLine)) {
            "Recommendation tokens for ${site.id} must be single-line values"
        }
        require(listOf(site.google.clientId, site.google.clientSecret).all(::singleLine) &&
            site.google.clientId.isBlank() == site.google.clientSecret.isBlank()) {
            "Set both or neither of google.clientId and google.clientSecret for ${site.id}"
        }
        if (deploy) {
            require(site.hosts.all.none { it.endsWith(".invalid") }) { "Replace example domains for ${site.id}" }
            require(!site.bmlArtifactsUrl.startsWith("SET_")) { "Set bmlArtifactsUrl for ${site.id}" }
            require(!site.bmlArtifactsToken.startsWith("SET_")) { "Set bmlArtifactsToken for ${site.id}" }
            require(!site.rootImage.contains("SET_")) { "Set rootImage for ${site.id}" }
        }
    }
    val registryServers = registryAuths(this).map(SwarmRegistryAuth::server)
    require(registryServers.distinct().size == registryServers.size) {
        "Configure only one registry login per server"
    }
    require(defaultImages().keys.all { images[it]?.isNotBlank() == true }) { "Configure every image" }
    require(singleLine(registryAuth.password)) { "registryAuth.password must be a single-line value" }
    with(backup) {
        require(listOf(repository, accessKeyId, secretAccessKey).all(::singleLine)) { "Backup settings must be single-line values" }
        require(hour in 0..23) { "backup.hour must be between 0 and 23" }
        require(keepDaily >= 1 && keepWeekly >= 0 && keepMonthly >= 0) { "Backup retention must keep at least one daily snapshot" }
        // restic runs in a container with no volume for a local path, so only remote backends keep backups.
        require(repository.isBlank() || REMOTE_BACKENDS.any(repository::startsWith)) {
            "backup.repository must be a remote restic repository (${REMOTE_BACKENDS.joinToString()})"
        }
        if (repository.startsWith("s3:")) {
            require(accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()) { "Set backup.accessKeyId and backup.secretAccessKey for an s3: repository" }
        }
    }
    if (deploy) {
        require(!manager.ssh.endsWith(".invalid") && workers.none { it.ssh.endsWith(".invalid") }) { "Replace example SSH hosts" }
        require(images.values.none { it.contains("SET_") }) { "Replace example image tags" }
    }
}

private val REMOTE_BACKENDS = listOf("s3:", "b2:", "azure:", "gs:", "sftp:", "rest:", "swift:", "rclone:")
private val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

private fun singleLine(value: String) = !value.contains('\n') && !value.contains('\r')

/**
 * Writes [value] to [path] with owner-only permissions. The content goes to a temporary file created
 * owner-only and then atomically replaces [path], so an interrupted write never truncates the only copy
 * of the generated credentials and the secrets are never readable by other accounts, even briefly.
 */
internal fun privateWrite(path: Path, value: String, replaceExisting: Boolean = true) =
    privateWrite(path, value.toByteArray(Charsets.UTF_8), replaceExisting)

internal fun privateWrite(path: Path, value: ByteArray, replaceExisting: Boolean = true) {
    val directory = path.toAbsolutePath().parent
    require(replaceExisting || !Files.exists(path)) { "Output already exists: $path" }
    Files.createDirectories(directory)
    val attributes = if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        arrayOf<FileAttribute<*>>(
            PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
        )
    } else {
        emptyArray()
    }
    val temporary = Files.createTempFile(directory, ".${path.fileName}.", ".tmp", *attributes)
    try {
        Files.write(temporary, value)
        if (replaceExisting) Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
