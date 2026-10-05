package bosca.kubernetes.controller.helm

import bosca.kubernetes.controller.util.HelmReleaseDecoder
import bosca.kubernetes.model.HelmInstallRequest
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.model.HelmRollbackRequest
import bosca.kubernetes.model.HelmUpgradeRequest
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.service.ClusterCredentialService
import bosca.serialization.UUID
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Shells out to the `helm` binary to run install / upgrade / rollback
 * / uninstall against a registered cluster.
 *
 * Why subprocess instead of an in-process SDK:
 *  * `helm` is the reference implementation — every release format,
 *    template engine, hook handling, and Chart dependency rule has
 *    a single canonical source of truth, and that's the binary. An
 *    in-process Java port lags upstream and accumulates subtle
 *    incompatibilities.
 *  * GraalVM native-image cleanly runs `Process` and friends; the
 *    helm binary is shipped in the kubernetes-controller image (see
 *    the Docker build), so the cost is one extra subprocess launch
 *    per call.
 *
 * Authentication:
 *  * Per call, we write the cluster's decrypted kubeconfig to a
 *    temp file with 0600 perms, pass `--kubeconfig <file>` to helm,
 *    and delete the file in `finally`.
 *  * Values YAML is written to a sibling temp file and removed the
 *    same way. We never pipe values through environment variables
 *    or argv — kubernetes process listings would leak secrets.
 *
 * Result resolution:
 *  * After install/upgrade/rollback, we read the release Secret back
 *    via fabric8 and decode it through [HelmReleaseDecoder]. This
 *    gives a consistent wire shape across the read and write paths
 *    — no manual parsing of helm's JSON output, no version drift.
 */
class HelmRuntime(
    private val credentials: ClusterCredentialService,
    private val helmBinary: String = System.getenv("HELM_BINARY") ?: "helm",
    private val commandRunner: HelmCommandRunner = ProcessHelmCommandRunner(),
) {

    private val log = LoggerFactory.getLogger(HelmRuntime::class.java)

    /**
     * Installs a chart. `repoUrl` lets the caller resolve the chart
     * via `helm install --repo <url> <chart>` rather than the
     * pre-registered `<repo>/<chart>` form — Bosca's `helmRepoAdd`
     * stores repos in its own Postgres table without registering them
     * with the helm CLI, so the CLI form would fail with `repo X not
     * found`. The URL is looked up by [HelmInstallRoute] from
     * [bosca.kubernetes.service.HelmRepoService].
     */
    suspend fun install(
        client: KubernetesClient,
        clusterId: UUID,
        req: HelmInstallRequest,
        repoUrl: String,
        repoCredentials: HelmRepoCredentials? = null,
    ): K8sHelmRelease {
        return runHelm(
            clusterId = clusterId,
            valuesYaml = req.values,
            buildArgs = { kubeconfig, valuesPath ->
                buildList {
                    add("install")
                    add(req.name)
                    add(req.chart)
                    add("--repo"); add(repoUrl)
                    addRepositoryCredentials(repoCredentials)
                    add("--namespace"); add(req.namespace)
                    add("--version"); add(req.version)
                    if (req.createNamespace) add("--create-namespace")
                    if (req.dryRun) add("--dry-run")
                    if (valuesPath != null) {
                        add("--values"); add(valuesPath.toString())
                    }
                    add("--kubeconfig"); add(kubeconfig.toString())
                    add("--output"); add("json")
                }
            },
        ).let {
            if (req.dryRun) {
                // dry-run never writes a Secret; return a synthetic
                // shape so the caller can still surface a `succeeded`
                // signal to the user.
                K8sHelmRelease(
                    id = "dry-run/${req.namespace}/${req.name}",
                    name = req.name,
                    namespace = req.namespace,
                    chart = req.chart,
                    chartVersion = req.version,
                    appVersion = "",
                    revision = 0,
                    status = bosca.kubernetes.model.HelmStatus.PENDING,
                    updated = "just now",
                    installed = "just now",
                    repo = req.repo,
                    repoUrl = "",
                    description = "dry-run",
                )
            } else {
                readRelease(client, req.namespace, req.name)
                    ?: error("install succeeded but no release secret found for ${req.namespace}/${req.name}")
            }
        }
    }

    suspend fun upgrade(
        client: KubernetesClient,
        clusterId: UUID,
        req: HelmUpgradeRequest,
        repoUrl: String,
        chart: String,
        repoCredentials: HelmRepoCredentials? = null,
    ): K8sHelmRelease {
        runHelm(
            clusterId = clusterId,
            valuesYaml = req.values,
            buildArgs = { kubeconfig, valuesPath ->
                buildList {
                    add("upgrade")
                    add(req.name)
                    add(chart)
                    add("--repo"); add(repoUrl)
                    addRepositoryCredentials(repoCredentials)
                    add("--namespace"); add(req.namespace)
                    add("--version"); add(req.version)
                    if (req.dryRun) add("--dry-run")
                    if (req.resetValues) add("--reset-values")
                    if (valuesPath != null) {
                        add("--values"); add(valuesPath.toString())
                    }
                    add("--kubeconfig"); add(kubeconfig.toString())
                    add("--output"); add("json")
                }
            },
        )
        return readRelease(client, req.namespace, req.name)
            ?: error("upgrade succeeded but no release secret found for ${req.namespace}/${req.name}")
    }

    suspend fun rollback(client: KubernetesClient, clusterId: UUID, req: HelmRollbackRequest): K8sHelmRelease {
        runHelm(
            clusterId = clusterId,
            valuesYaml = null,
            buildArgs = { kubeconfig, _ ->
                buildList {
                    add("rollback")
                    add(req.name)
                    add(req.toRevision.toString())
                    add("--namespace"); add(req.namespace)
                    add("--kubeconfig"); add(kubeconfig.toString())
                }
            },
        )
        return readRelease(client, req.namespace, req.name)
            ?: error("rollback succeeded but no release secret found for ${req.namespace}/${req.name}")
    }

    suspend fun uninstall(clusterId: UUID, namespace: String, name: String, keepHistory: Boolean): Boolean {
        runHelm(
            clusterId = clusterId,
            valuesYaml = null,
            buildArgs = { kubeconfig, _ ->
                buildList {
                    add("uninstall")
                    add(name)
                    add("--namespace"); add(namespace)
                    if (keepHistory) add("--keep-history")
                    add("--kubeconfig"); add(kubeconfig.toString())
                }
            },
        )
        return true
    }

    /**
     * Returns the merged values for the named release as a YAML string.
     *
     * Mirrors `helm get values <release> --namespace <ns> --all`. The
     * `--all` flag merges the chart defaults with the caller-supplied
     * overrides — that's the canonical view operators want when
     * answering "what is this release actually running with?".
     *
     * Passing [revision] pins to a historical revision; omitting it
     * returns values for the current revision.
     */
    suspend fun getValues(clusterId: UUID, namespace: String, name: String, revision: Int? = null): String =
        runHelm(
            clusterId = clusterId,
            valuesYaml = null,
            buildArgs = { kubeconfig, _ ->
                buildList {
                    add("get"); add("values")
                    add(name)
                    add("--namespace"); add(namespace)
                    add("--all")
                    revision?.let { add("--revision"); add(it.toString()) }
                    add("--output"); add("yaml")
                    add("--kubeconfig"); add(kubeconfig.toString())
                }
            },
        )

    /**
     * Returns the rendered manifest for the named release as a YAML
     * stream (`---`-separated documents). Used by the studio's release
     * detail Manifest tab.
     */
    suspend fun getManifest(clusterId: UUID, namespace: String, name: String, revision: Int? = null): String =
        runHelm(
            clusterId = clusterId,
            valuesYaml = null,
            buildArgs = { kubeconfig, _ ->
                buildList {
                    add("get"); add("manifest")
                    add(name)
                    add("--namespace"); add(namespace)
                    revision?.let { add("--revision"); add(it.toString()) }
                    add("--kubeconfig"); add(kubeconfig.toString())
                }
            },
        )

    /**
     * Writes the decrypted kubeconfig (and values YAML, when supplied)
     * to temp files, runs `helm` with the argv computed by
     * [buildArgs], captures combined stdout/stderr, and cleans up the
     * temp files. Throws on non-zero exit with the captured output
     * inlined so the caller surfaces a useful error.
     */
    private suspend fun runHelm(
        clusterId: UUID,
        valuesYaml: String?,
        buildArgs: (kubeconfig: Path, valuesPath: Path?) -> List<String>,
    ): String = withContext(Dispatchers.IO) {
        val kubeconfig = credentials.load(clusterId)
            ?: error("no kubeconfig stored for cluster $clusterId")
        val kubeconfigPath = writeSecret(kubeconfig, prefix = "kubeconfig", suffix = ".yaml")
        val valuesPath = valuesYaml?.let { writeSecret(it, prefix = "values", suffix = ".yaml") }
        try {
            val argv = listOf(helmBinary) + buildArgs(kubeconfigPath, valuesPath)
            val result = commandRunner.run(argv, DEFAULT_TIMEOUT_SECONDS)
            if (result.timedOut) {
                error("helm command timed out after ${DEFAULT_TIMEOUT_SECONDS}s: ${argv.drop(1).take(2).joinToString(" ")}")
            }
            if (result.exitCode != 0) {
                log.warn("helm exited {} for cluster {}: {}", result.exitCode, clusterId, result.output.take(400))
                error("helm command failed (exit ${result.exitCode}): ${result.output.trim().takeLast(400)}")
            }
            result.output
        } finally {
            runCatching { Files.deleteIfExists(kubeconfigPath) }
            valuesPath?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Reads the highest-revision helm release Secret in [namespace]
     * matching [name] and decodes it. Used to return the wire shape
     * after a successful install/upgrade/rollback.
     *
     * helm writes its release secret as the final step of a successful
     * install, but with a fresh fabric8 client we sometimes see a
     * brief window where the secret hasn't appeared in the list yet
     * (label-selector path goes through the API server's index
     * caches). Retry with a short backoff so the post-install
     * read-back doesn't fail on a freshly-installed release.
     */
    private suspend fun readRelease(client: KubernetesClient, namespace: String, name: String): K8sHelmRelease? {
        repeat(READ_RELEASE_RETRIES) { attempt ->
            val secrets = withContext(Dispatchers.IO) {
                client.secrets()
                    .inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .withLabel("name", name)
                    .list()
                    .items
            }
            log.info("readRelease attempt={} ns={} name={} found {} secrets", attempt + 1, namespace, name, secrets.size)
            val release = secrets
                .filter { HelmReleaseDecoder.isHelmReleaseSecret(it) }
                .mapNotNull { HelmReleaseDecoder.decodeRelease(it) }
                .maxByOrNull { it.revision }
            if (release != null) return release
            if (attempt < READ_RELEASE_RETRIES - 1) {
                kotlinx.coroutines.delay(READ_RELEASE_BACKOFF_MS)
            }
        }
        return null
    }

    /**
     * Writes [content] to a private temp file (`0600`) and returns
     * the path. The caller is responsible for deleting it — see
     * [runHelm] for the standard `finally` pattern.
     */
    private fun writeSecret(content: String, prefix: String, suffix: String): Path {
        val path = Files.createTempFile(prefix, suffix)
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        Files.writeString(path, content)
        return path
    }

    private fun MutableList<String>.addRepositoryCredentials(credentials: HelmRepoCredentials?) {
        if (credentials == null) return
        add("--username"); add(credentials.username)
        add("--password"); add(credentials.password)
    }

    companion object {
        /**
         * helm can hang under odd cluster conditions — `--wait` plus a
         * slow rollout can sit on this subprocess for minutes. Five
         * minutes matches the streaming-response cap elsewhere in the
         * controller and is enough for typical install flows.
         */
        private const val DEFAULT_TIMEOUT_SECONDS = 300L

        private const val READ_RELEASE_RETRIES = 5
        private const val READ_RELEASE_BACKOFF_MS = 400L
    }
}
