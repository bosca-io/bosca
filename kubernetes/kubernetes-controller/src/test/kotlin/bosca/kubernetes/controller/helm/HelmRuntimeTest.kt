package bosca.kubernetes.controller.helm

import bosca.kubernetes.model.HelmInstallRequest
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.service.ClusterCredentialService
import bosca.serialization.UUID
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Tests for the helm subprocess driver. Use a fake [HelmCommandRunner]
 * so the kubeconfig + values temp-file lifecycle, argv composition,
 * non-zero exit handling, and timeout signalling can all be pinned
 * without launching a real `helm` binary.
 *
 * The temp-file invariants pinned here are security-sensitive:
 *
 *  * Kubeconfig and values YAML written to private temp files with
 *    `0600` perms — process listings would leak credentials if these
 *    were passed via argv.
 *  * Files deleted in `finally`, regardless of subprocess exit code.
 *  * `--kubeconfig <file>` form used (not env var or stdin) so the
 *    runner sees the path in argv.
 */
@OptIn(ExperimentalUuidApi::class)
class HelmRuntimeTest {

    private val credentials = mockk<ClusterCredentialService>()
    private val clusterId = UUID.random()

    /** Captures every invocation so tests can assert argv shape + file state. */
    private class CapturingRunner(
        private val response: HelmCommandResult = HelmCommandResult(0, "ok\n", false),
        private val observer: (List<String>) -> Unit = {},
    ) : HelmCommandRunner {
        val invocations = mutableListOf<List<String>>()
        val seenKubeconfigContents = mutableListOf<String>()
        val seenValuesContents = mutableListOf<String?>()
        override suspend fun run(argv: List<String>, timeoutSeconds: Long): HelmCommandResult {
            invocations += argv
            // Capture the kubeconfig + values file contents at the moment
            // of invocation — proves the files existed at run time, and
            // the cleanup tests below prove they're gone afterwards.
            val kubeconfigIdx = argv.indexOf("--kubeconfig")
            if (kubeconfigIdx >= 0) {
                seenKubeconfigContents += Files.readString(Path.of(argv[kubeconfigIdx + 1]))
            }
            val valuesIdx = argv.indexOf("--values")
            seenValuesContents += if (valuesIdx >= 0) Files.readString(Path.of(argv[valuesIdx + 1])) else null
            observer(argv)
            return response
        }
    }

    @BeforeTest
    fun setup() {
        coEvery { credentials.load(clusterId) } returns "apiVersion: v1\nkind: Config\nclusters: []\n"
    }

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `getValues passes kubeconfig path and all flag in argv`() = runTest {
        val runner = CapturingRunner(HelmCommandResult(0, "key: value\n", false))
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        val out = helm.getValues(clusterId, "default", "podinfo")

        assertEquals("key: value\n", out)
        val argv = runner.invocations.single()
        assertEquals("helm", argv.first())
        assertContains(argv, "get")
        assertContains(argv, "values")
        assertContains(argv, "podinfo")
        assertContains(argv, "--namespace")
        assertContains(argv, "default")
        assertContains(argv, "--all")
        assertContains(argv, "--kubeconfig")
        assertContains(argv, "--output")
        assertContains(argv, "yaml")
    }

    @Test
    fun `getValues pins to a specific revision when supplied`() = runTest {
        val runner = CapturingRunner()
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        helm.getValues(clusterId, "default", "podinfo", revision = 7)

        val argv = runner.invocations.single()
        assertContains(argv, "--revision")
        assertEquals("7", argv[argv.indexOf("--revision") + 1])
    }

    @Test
    fun `getManifest emits the helm get manifest argv shape`() = runTest {
        val runner = CapturingRunner(HelmCommandResult(0, "kind: Deployment\n", false))
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        val out = helm.getManifest(clusterId, "default", "podinfo")

        assertEquals("kind: Deployment\n", out)
        val argv = runner.invocations.single()
        assertContains(argv, "get")
        assertContains(argv, "manifest")
        assertContains(argv, "podinfo")
        // get manifest doesn't take --all (different from get values).
        assertFalse(argv.contains("--all"), "--all should not appear in get manifest argv")
    }

    @Test
    fun `install passes private repository credentials without enabling cross-domain forwarding`() = runTest {
        val runner = CapturingRunner()
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)
        val request = HelmInstallRequest(
            name = "api",
            namespace = "prod",
            repo = "private",
            chart = "nginx",
            version = "1.0.0",
            dryRun = true,
        )

        helm.install(
            client = mockk<KubernetesClient>(),
            clusterId = clusterId,
            req = request,
            repoUrl = "https://charts.example.com",
            repoCredentials = HelmRepoCredentials("api_token", "bsk_secret"),
        )

        val argv = runner.invocations.single()
        assertEquals("api_token", argv[argv.indexOf("--username") + 1])
        assertEquals("bsk_secret", argv[argv.indexOf("--password") + 1])
        assertFalse(argv.contains("--pass-credentials"), "credentials must stay scoped to the repository origin")
    }

    @Test
    fun `kubeconfig is written to a 0600 temp file at run time`() = runTest {
        var observedPath: Path? = null
        var observedPerms: Set<PosixFilePermission>? = null
        val runner = CapturingRunner { argv ->
            val path = Path.of(argv[argv.indexOf("--kubeconfig") + 1])
            observedPath = path
            observedPerms = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                ?.readAttributes()?.permissions()
        }
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        helm.getValues(clusterId, "default", "podinfo")

        val perms = assertNotNull(observedPerms, "kubeconfig perms not read")
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
            "kubeconfig temp file must be 0600 — relaxed perms would leak credentials to other host users")
        val path = assertNotNull(observedPath)
        // Sanity: the file was written with the decrypted contents the credentials service returned.
        assertEquals("apiVersion: v1\nkind: Config\nclusters: []\n", runner.seenKubeconfigContents.single())
        // Cleanup invariant: temp file is deleted after the subprocess returns.
        assertFalse(Files.exists(path), "kubeconfig temp file must be deleted in finally")
    }

    @Test
    fun `kubeconfig is deleted even when the subprocess exits non-zero`() = runTest {
        var observedPath: Path? = null
        val runner = object : HelmCommandRunner {
            override suspend fun run(argv: List<String>, timeoutSeconds: Long): HelmCommandResult {
                observedPath = Path.of(argv[argv.indexOf("--kubeconfig") + 1])
                return HelmCommandResult(exitCode = 1, output = "Error: chart not found\n", timedOut = false)
            }
        }
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        assertFailsWith<IllegalStateException> {
            helm.getValues(clusterId, "default", "missing")
        }
        val path = assertNotNull(observedPath)
        assertFalse(Files.exists(path), "kubeconfig temp file must be deleted even on non-zero exit")
    }

    @Test
    fun `non-zero exit code surfaces the captured helm output`() = runTest {
        val runner = object : HelmCommandRunner {
            override suspend fun run(argv: List<String>, timeoutSeconds: Long) =
                HelmCommandResult(exitCode = 1, output = "Error: INSTALLATION FAILED: repo X not found\n", timedOut = false)
        }
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        val ex = assertFailsWith<IllegalStateException> {
            helm.getValues(clusterId, "default", "podinfo")
        }
        assertContains(ex.message ?: "", "exit 1")
        assertContains(ex.message ?: "", "repo X not found")
    }

    @Test
    fun `timeout flag surfaces as a distinct error message`() = runTest {
        val runner = object : HelmCommandRunner {
            override suspend fun run(argv: List<String>, timeoutSeconds: Long) =
                HelmCommandResult(exitCode = -1, output = "partial...", timedOut = true)
        }
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        val ex = assertFailsWith<IllegalStateException> {
            helm.getValues(clusterId, "default", "podinfo")
        }
        assertContains(ex.message ?: "", "timed out")
        // The timeout message should name the subcommand so an operator
        // can see *which* helm call hung, not just "something hung".
        assertContains(ex.message ?: "", "get values")
    }

    @Test
    fun `missing kubeconfig fails fast before invoking helm`() = runTest {
        coEvery { credentials.load(clusterId) } returns null
        val runner = CapturingRunner()
        val helm = HelmRuntime(credentials = credentials, commandRunner = runner)

        val ex = assertFailsWith<IllegalStateException> {
            helm.getValues(clusterId, "default", "podinfo")
        }
        assertContains(ex.message ?: "", "no kubeconfig stored")
        assertTrue(runner.invocations.isEmpty(), "helm must not be invoked when kubeconfig is missing")
    }

    @Test
    fun `helmBinary override is honored`() = runTest {
        val runner = CapturingRunner()
        val helm = HelmRuntime(
            credentials = credentials,
            helmBinary = "/usr/local/bin/helm3",
            commandRunner = runner,
        )

        helm.getValues(clusterId, "default", "podinfo")

        assertEquals("/usr/local/bin/helm3", runner.invocations.single().first())
    }
}
