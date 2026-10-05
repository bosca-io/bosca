package bosca.cli.ci

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StepExecutorIntegrationTest {

    private fun createTestLogBuffer(): TestLogBuffer {
        return TestLogBuffer()
    }

    @Test
    fun `executes simple shell command and captures output`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val executor = StepExecutor(
                workDir = dir,
                serverUrl = "",
                agentToken = "",
                commitSha = "abc123",
                ref = "refs/heads/main",
                repositoryId = "repo-1",
                env = emptyMap(),
                secrets = emptyMap(),
                logBuffer = logs,
                sharedEnvFile = envFile,
                sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Test", run = "echo 'hello world'")
            val context = ExpressionContext(ref = "refs/heads/main", branch = "main", event = "push")
            val result = executor.execute(step, context)

            logs.flush()
            assertTrue(result.success)
            assertEquals(0, result.exitCode)
            assertTrue(logs.lines.any { it.contains("hello world") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `captures non-zero exit code on failure`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val executor = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Fail", run = "exit 42")
            val context = ExpressionContext()
            val result = executor.execute(step, context)

            assertTrue(!result.success)
            assertEquals(42, result.exitCode)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shared env persists variables across executor calls`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val context = ExpressionContext()

            val executor1 = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )
            val step1 = StepDefinition(name = "Set Var", run = "echo 'MY_VAR=hello_from_step1' >> \"\$BOSCA_ENV\"")
            val r1 = executor1.execute(step1, context)
            assertTrue(r1.success)

            val executor2 = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )
            val step2 = StepDefinition(name = "Read Var", run = "echo \"value=\$MY_VAR\"")
            val r2 = executor2.execute(step2, context)
            logs.flush()

            assertTrue(r2.success)
            assertTrue(logs.lines.any { it.contains("value=hello_from_step1") },
                "Expected MY_VAR to propagate. Logs: ${logs.lines}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shared PATH persists across executor calls`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val binDir = File(dir, "custom-bin")
            binDir.mkdirs()
            File(binDir, "my-tool").apply {
                writeText("#!/bin/bash\necho 'my-tool-output'")
                setExecutable(true)
            }

            val context = ExpressionContext()

            val executor1 = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )
            val step1 = StepDefinition(name = "Add Path", run = "echo '${binDir.absolutePath}' >> \"\$BOSCA_PATH\"")
            assertTrue(executor1.execute(step1, context).success)

            val executor2 = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )
            val step2 = StepDefinition(name = "Use Tool", run = "my-tool")
            val r2 = executor2.execute(step2, context)
            logs.flush()

            assertTrue(r2.success, "Expected my-tool to be found on PATH. Logs: ${logs.lines}")
            assertTrue(logs.lines.any { it.contains("my-tool-output") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `expression interpolation works in run commands`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val context = ExpressionContext(branch = "feature/xyz", event = "push")
            val executor = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/feature/xyz", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Interp", run = "echo 'branch=\${{ branch }} event=\${{ event }}'")
            val result = executor.execute(step, context)
            logs.flush()

            assertTrue(result.success)
            assertTrue(logs.lines.any { it.contains("branch=feature/xyz event=push") },
                "Logs: ${logs.lines}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `secrets are masked in log output`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = TestLogBuffer(maskSecrets = setOf("super-secret-value"))

        try {
            val executor = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(),
                secrets = mapOf("SECRET_TOKEN" to "super-secret-value"),
                logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Leak", run = "echo \"token=\$SECRET_TOKEN\"")
            val result = executor.execute(step, context = ExpressionContext())
            logs.flush()

            assertTrue(result.success)
            assertTrue(logs.lines.none { it.contains("super-secret-value") },
                "Secret leaked in logs: ${logs.lines}")
            assertTrue(logs.lines.any { it.contains("***") },
                "Expected masked output. Logs: ${logs.lines}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `working-directory changes step execution directory`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val subDir = File(dir, "subproject")
        subDir.mkdirs()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val executor = StepExecutor(
                workDir = subDir,
                serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Pwd", run = "pwd")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success)
            assertTrue(logs.lines.any { it.contains("subproject") },
                "Expected pwd to show subproject dir. Logs: ${logs.lines}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `checkout with submodules recursive initializes submodules`() = runTest {
        val root = Files.createTempDirectory("checkout-sub-test").toFile()
        try {
            val parentRemote = setupBareRepoWithSubmodule(root)
            val (workDir, envFile, pathFile) = setupAgentLayout(root)
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = workDir,
                serverUrl = "",
                agentToken = "",
                commitSha = parentRemote.headSha,
                ref = "refs/heads/main",
                repositoryId = "r",
                cloneUrl = parentRemote.url,
                env = gitTestEnv(root),
                secrets = emptyMap(),
                logBuffer = logs,
                sharedEnvFile = envFile,
                sharedPathFile = pathFile,
            )

            val step = StepDefinition(
                name = "Checkout",
                uses = "checkout",
                with = mapOf("submodules" to "recursive"),
            )
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "checkout step failed. Logs: ${logs.lines}")
            assertTrue(File(workDir, "parent.txt").exists())
            assertTrue(File(workDir, "sub/child.txt").exists(),
                "submodule was not initialized. Logs: ${logs.lines}")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `checkout without submodules skips submodule init`() = runTest {
        val root = Files.createTempDirectory("checkout-no-sub-test").toFile()
        try {
            val parentRemote = setupBareRepoWithSubmodule(root)
            val (workDir, envFile, pathFile) = setupAgentLayout(root)
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = workDir, serverUrl = "", agentToken = "",
                commitSha = parentRemote.headSha, ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = parentRemote.url,
                env = gitTestEnv(root),
                secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Checkout", uses = "checkout")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success)
            assertTrue(File(workDir, "parent.txt").exists())
            assertTrue(!File(workDir, "sub/child.txt").exists(),
                "submodule files should not have been initialized")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `checkout warns on unknown submodules value`() = runTest {
        val root = Files.createTempDirectory("checkout-warn-test").toFile()
        try {
            val parentRemote = setupBareRepoWithSubmodule(root)
            val (workDir, envFile, pathFile) = setupAgentLayout(root)
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = workDir, serverUrl = "", agentToken = "",
                commitSha = parentRemote.headSha, ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = parentRemote.url,
                env = gitTestEnv(root),
                secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(
                name = "Checkout",
                uses = "checkout",
                with = mapOf("submodules" to "rercursive"),
            )
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success)
            assertTrue(logs.lines.any { it.contains("unknown submodules value") },
                "Expected warning for unknown value. Logs: ${logs.lines}")
            assertTrue(!File(workDir, "sub/child.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-push pushes current branch to remote`() = runTest {
        val root = Files.createTempDirectory("git-push-test").toFile()
        try {
            val bare = File(root, "remote.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", bare.absolutePath)

            val local = File(root, "local").apply { mkdirs() }
            runGit(local, "init", "-q", "-b", "main")
            runGit(local, "config", "user.email", "t@t.t")
            runGit(local, "config", "user.name", "t")
            runGit(local, "config", "commit.gpgsign", "false")
            runGit(local, "remote", "add", "origin", bare.absolutePath)
            File(local, "init.txt").writeText("init")
            runGit(local, "add", "init.txt")
            runGit(local, "commit", "-q", "-m", "initial")
            runGit(local, "push", "-u", "origin", "main")

            File(local, "new.txt").writeText("new")
            runGit(local, "add", "new.txt")
            runGit(local, "commit", "-q", "-m", "new commit")

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = local, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = bare.absolutePath,
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Push", uses = "git-push")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "git-push failed. Logs: ${logs.lines}")

            val verify = File(root, "verify").apply { mkdirs() }
            runGit(verify, "clone", "-q", bare.absolutePath, verify.absolutePath)
            assertTrue(File(verify, "new.txt").exists(), "pushed commit not found in remote")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-push-submodules pushes each submodule to its remote`() = runTest {
        val root = Files.createTempDirectory("push-sub-test").toFile()
        try {
            val subBare = File(root, "sub.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", subBare.absolutePath)

            val subLocal = File(root, "sub-local").apply { mkdirs() }
            runGit(subLocal, "init", "-q", "-b", "main")
            runGit(subLocal, "config", "user.email", "t@t.t")
            runGit(subLocal, "config", "user.name", "t")
            runGit(subLocal, "config", "commit.gpgsign", "false")
            File(subLocal, "sub.txt").writeText("sub")
            runGit(subLocal, "add", "sub.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub init")
            runGit(subLocal, "remote", "add", "origin", subBare.absolutePath)
            runGit(subLocal, "push", "-u", "origin", "main")

            val parent = File(root, "parent").apply { mkdirs() }
            runGit(parent, "init", "-q", "-b", "main")
            runGit(parent, "config", "user.email", "t@t.t")
            runGit(parent, "config", "user.name", "t")
            runGit(parent, "config", "commit.gpgsign", "false")
            runGit(parent, "config", "protocol.file.allow", "always")
            File(parent, "parent.txt").writeText("p")
            runGit(parent, "add", "parent.txt")
            runGit(parent, "commit", "-q", "-m", "parent init")
            runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", subBare.absolutePath, "sub")
            runGit(parent, "commit", "-q", "-m", "add submodule")

            val subWorkDir = File(parent, "sub")
            File(subWorkDir, "new.txt").writeText("new")
            runGit(subWorkDir, "add", "new.txt")
            runGit(subWorkDir, "commit", "-q", "-m", "new sub commit")

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = parent, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = subBare.absolutePath,
                env = gitTestEnv(root), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Push Submodules", uses = "git-push-submodules")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "git-push-submodules failed. Logs: ${logs.lines}")

            val verify = File(root, "verify").apply { mkdirs() }
            runGit(verify, "clone", "-q", subBare.absolutePath, verify.absolutePath)
            assertTrue(File(verify, "new.txt").exists(), "submodule push didn't reach remote")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-pull-submodules fast-forwards each submodule to the latest remote commit`() = runTest {
        val root = Files.createTempDirectory("pull-sub-test").toFile()
        try {
            val subBare = File(root, "sub.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", subBare.absolutePath)

            val subLocal = File(root, "sub-local").apply { mkdirs() }
            runGit(subLocal, "init", "-q", "-b", "main")
            runGit(subLocal, "config", "user.email", "t@t.t")
            runGit(subLocal, "config", "user.name", "t")
            runGit(subLocal, "config", "commit.gpgsign", "false")
            File(subLocal, "sub.txt").writeText("sub")
            runGit(subLocal, "add", "sub.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub init")
            runGit(subLocal, "remote", "add", "origin", subBare.absolutePath)
            runGit(subLocal, "push", "-u", "origin", "main")

            val parent = File(root, "parent").apply { mkdirs() }
            runGit(parent, "init", "-q", "-b", "main")
            runGit(parent, "config", "user.email", "t@t.t")
            runGit(parent, "config", "user.name", "t")
            runGit(parent, "config", "commit.gpgsign", "false")
            runGit(parent, "config", "protocol.file.allow", "always")
            File(parent, "parent.txt").writeText("p")
            runGit(parent, "add", "parent.txt")
            runGit(parent, "commit", "-q", "-m", "parent init")
            runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", subBare.absolutePath, "sub")
            runGit(parent, "commit", "-q", "-m", "add submodule")

            // A commit lands on the submodule's remote from outside this checkout —
            // the workspace anchor must follow it to the newest commit, not the old one.
            File(subLocal, "second.txt").writeText("second")
            runGit(subLocal, "add", "second.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub second commit")
            runGit(subLocal, "push", "origin", "main")

            val subWorkDir = File(parent, "sub")
            assertTrue(
                !File(subWorkDir, "second.txt").exists(),
                "precondition: submodule should start behind its remote",
            )

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = parent, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = subBare.absolutePath,
                env = gitTestEnv(root), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Pull Submodule Refs", uses = "git-pull-submodules")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "git-pull-submodules failed. Logs: ${logs.lines}")
            assertTrue(
                File(subWorkDir, "second.txt").exists(),
                "submodule was not fast-forwarded to the latest remote commit",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-pull-submodules fails instead of merging when a submodule has diverged`() = runTest {
        val root = Files.createTempDirectory("pull-diverge-test").toFile()
        try {
            val subBare = File(root, "sub.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", subBare.absolutePath)

            val subLocal = File(root, "sub-local").apply { mkdirs() }
            runGit(subLocal, "init", "-q", "-b", "main")
            runGit(subLocal, "config", "user.email", "t@t.t")
            runGit(subLocal, "config", "user.name", "t")
            runGit(subLocal, "config", "commit.gpgsign", "false")
            File(subLocal, "sub.txt").writeText("sub")
            runGit(subLocal, "add", "sub.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub init")
            runGit(subLocal, "remote", "add", "origin", subBare.absolutePath)
            runGit(subLocal, "push", "-u", "origin", "main")

            val parent = File(root, "parent").apply { mkdirs() }
            runGit(parent, "init", "-q", "-b", "main")
            runGit(parent, "config", "user.email", "t@t.t")
            runGit(parent, "config", "user.name", "t")
            runGit(parent, "config", "commit.gpgsign", "false")
            runGit(parent, "config", "protocol.file.allow", "always")
            File(parent, "parent.txt").writeText("p")
            runGit(parent, "add", "parent.txt")
            runGit(parent, "commit", "-q", "-m", "parent init")
            runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", subBare.absolutePath, "sub")
            runGit(parent, "commit", "-q", "-m", "add submodule")

            // Diverge: an unpushed local commit on the workspace's submodule main ...
            val subWorkDir = File(parent, "sub")
            runGit(subWorkDir, "config", "user.email", "t@t.t")
            runGit(subWorkDir, "config", "user.name", "t")
            runGit(subWorkDir, "config", "commit.gpgsign", "false")
            runGit(subWorkDir, "checkout", "main")
            File(subWorkDir, "local-only.txt").writeText("local")
            runGit(subWorkDir, "add", "local-only.txt")
            runGit(subWorkDir, "commit", "-q", "-m", "local only (unpushed)")
            // ... versus a different commit on the remote main. A clean (non-conflicting) merge is
            // possible here, but --ff-only must refuse it rather than manufacture an unpushed merge.
            File(subLocal, "remote-only.txt").writeText("remote")
            runGit(subLocal, "add", "remote-only.txt")
            runGit(subLocal, "commit", "-q", "-m", "remote only")
            runGit(subLocal, "push", "origin", "main")

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = parent, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = subBare.absolutePath,
                env = gitTestEnv(root), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Pull Submodule Refs", uses = "git-pull-submodules")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(
                !result.success,
                "git-pull-submodules must fail (not merge) when a submodule has diverged. Logs: ${logs.lines}",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-commit-submodules commits changes in dirty submodules`() = runTest {
        val root = Files.createTempDirectory("commit-sub-test").toFile()
        try {
            val subBare = File(root, "sub.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", subBare.absolutePath)

            val subLocal = File(root, "sub-local").apply { mkdirs() }
            runGit(subLocal, "init", "-q", "-b", "main")
            runGit(subLocal, "config", "user.email", "t@t.t")
            runGit(subLocal, "config", "user.name", "t")
            runGit(subLocal, "config", "commit.gpgsign", "false")
            File(subLocal, "sub.txt").writeText("sub")
            runGit(subLocal, "add", "sub.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub init")
            runGit(subLocal, "remote", "add", "origin", subBare.absolutePath)
            runGit(subLocal, "push", "-u", "origin", "main")

            val parent = File(root, "parent").apply { mkdirs() }
            runGit(parent, "init", "-q", "-b", "main")
            runGit(parent, "config", "user.email", "t@t.t")
            runGit(parent, "config", "user.name", "t")
            runGit(parent, "config", "commit.gpgsign", "false")
            runGit(parent, "config", "protocol.file.allow", "always")
            File(parent, "parent.txt").writeText("p")
            runGit(parent, "add", "parent.txt")
            runGit(parent, "commit", "-q", "-m", "parent init")
            runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", subBare.absolutePath, "sub")
            runGit(parent, "commit", "-q", "-m", "add submodule")

            val subWorkDir = File(parent, "sub")
            runGit(subWorkDir, "config", "user.email", "t@t.t")
            runGit(subWorkDir, "config", "user.name", "t")
            runGit(subWorkDir, "config", "commit.gpgsign", "false")
            File(subWorkDir, "dirty.txt").writeText("dirty")

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = parent, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = subBare.absolutePath,
                env = gitTestEnv(root), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(
                name = "Commit Submodules",
                uses = "git-commit-submodules",
                with = mapOf("message" to "test commit"),
            )
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "git-commit-submodules failed. Logs: ${logs.lines}")

            val logOutput = runGitCapturing(subWorkDir, "log", "--oneline", "-1")
            assertTrue(logOutput.contains("test commit"), "commit message not found. Log: $logOutput")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git-commit-submodules skips clean submodules`() = runTest {
        val root = Files.createTempDirectory("commit-sub-clean-test").toFile()
        try {
            val subBare = File(root, "sub.git")
            runGit(root, "init", "-q", "--bare", "-b", "main", subBare.absolutePath)

            val subLocal = File(root, "sub-local").apply { mkdirs() }
            runGit(subLocal, "init", "-q", "-b", "main")
            runGit(subLocal, "config", "user.email", "t@t.t")
            runGit(subLocal, "config", "user.name", "t")
            runGit(subLocal, "config", "commit.gpgsign", "false")
            File(subLocal, "sub.txt").writeText("sub")
            runGit(subLocal, "add", "sub.txt")
            runGit(subLocal, "commit", "-q", "-m", "sub init")
            runGit(subLocal, "remote", "add", "origin", subBare.absolutePath)
            runGit(subLocal, "push", "-u", "origin", "main")

            val parent = File(root, "parent").apply { mkdirs() }
            runGit(parent, "init", "-q", "-b", "main")
            runGit(parent, "config", "user.email", "t@t.t")
            runGit(parent, "config", "user.name", "t")
            runGit(parent, "config", "commit.gpgsign", "false")
            runGit(parent, "config", "protocol.file.allow", "always")
            File(parent, "parent.txt").writeText("p")
            runGit(parent, "add", "parent.txt")
            runGit(parent, "commit", "-q", "-m", "parent init")
            runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", subBare.absolutePath, "sub")
            runGit(parent, "commit", "-q", "-m", "add submodule")

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = createTestLogBuffer()

            val executor = StepExecutor(
                workDir = parent, serverUrl = "", agentToken = "",
                commitSha = "", ref = "refs/heads/main", repositoryId = "r",
                cloneUrl = subBare.absolutePath,
                env = gitTestEnv(root), secrets = emptyMap(), logBuffer = logs,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(
                name = "Commit Submodules",
                uses = "git-commit-submodules",
                with = mapOf("message" to "should not appear"),
            )
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(result.success, "git-commit-submodules failed on clean submodule. Logs: ${logs.lines}")
            assertTrue(logs.lines.any { it.contains("clean, skipping") },
                "Expected 'clean, skipping' message. Logs: ${logs.lines}")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Modern git (>=2.38) refuses `file://` for submodule clones unless
     * `protocol.file.allow` is set. Write a HOME/.gitconfig that allows it
     * and point HOME there for the spawned git process.
     */
    private fun gitTestEnv(root: File): Map<String, String> {
        val fakeHome = File(root, "home").apply { mkdirs() }
        File(fakeHome, ".gitconfig").writeText("[protocol \"file\"]\n\tallow = always\n")
        return mapOf(
            "HOME" to fakeHome.absolutePath,
            "GIT_CONFIG_SYSTEM" to "/dev/null",
        )
    }






    @Test
    fun `registry-upload fails honestly without a configured registry`() = runTest {
        val dir = Files.createTempDirectory("registry-upload-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()
        try {
            val executor = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "t", commitSha = "abc",
                ref = "refs/tags/v1.4.0", repositoryId = "r", env = emptyMap(), secrets = emptyMap(),
                logBuffer = logs, sharedEnvFile = envFile, sharedPathFile = pathFile,
            )
            val step = StepDefinition(
                name = "Publish values", uses = "registry-upload",
                with = mapOf("namespace" to "bosca-values", "name" to "my-api", "version" to "1.4.0", "files" to "values.yaml"),
            )
            val result = executor.execute(step, ExpressionContext(ref = "refs/tags/v1.4.0"))
            logs.flush()
            assertTrue(!result.success)
            assertTrue(logs.lines.any { "registry" in it.lowercase() }, logs.lines.joinToString("\n"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun setupAgentLayout(root: File): Triple<File, File, File> {
        val jobDir = File(root, "job").apply { mkdirs() }
        val workDir = File(jobDir, "src").apply { mkdirs() }
        val envFile = File(jobDir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(jobDir, ".bosca_path").also { it.createNewFile() }
        return Triple(workDir, envFile, pathFile)
    }

    private data class TestRemote(val url: String, val headSha: String)

    private fun setupBareRepoWithSubmodule(root: File): TestRemote {
        val child = File(root, "child").apply { mkdirs() }
        runGit(child, "init", "-q", "-b", "main")
        runGit(child, "-c", "user.email=t@t.t", "-c", "user.name=t", "config", "user.email", "t@t.t")
        runGit(child, "config", "user.name", "t")
        runGit(child, "config", "commit.gpgsign", "false")
        File(child, "child.txt").writeText("c")
        runGit(child, "add", "child.txt")
        runGit(child, "commit", "-q", "-m", "child")

        val childBare = File(root, "child.git")
        runGit(root, "clone", "-q", "--bare", child.absolutePath, childBare.absolutePath)

        val parent = File(root, "parent").apply { mkdirs() }
        runGit(parent, "init", "-q", "-b", "main")
        runGit(parent, "config", "user.email", "t@t.t")
        runGit(parent, "config", "user.name", "t")
        runGit(parent, "config", "commit.gpgsign", "false")
        runGit(parent, "config", "protocol.file.allow", "always")
        File(parent, "parent.txt").writeText("p")
        runGit(parent, "add", "parent.txt")
        runGit(parent, "commit", "-q", "-m", "parent")
        runGit(parent, "-c", "protocol.file.allow=always", "submodule", "add", childBare.absolutePath, "sub")
        runGit(parent, "commit", "-q", "-m", "add submodule")

        val parentBare = File(root, "parent.git")
        runGit(root, "clone", "-q", "--bare", parent.absolutePath, parentBare.absolutePath)
        val head = runGitCapturing(parent, "rev-parse", "HEAD").trim()
        return TestRemote(url = parentBare.absolutePath, headSha = head)
    }

    private fun runGit(dir: File, vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = pb.inputStream.bufferedReader().readText()
        check(pb.waitFor() == 0) { "git ${args.joinToString(" ")} failed in ${dir.absolutePath}:\n$out" }
    }

    private fun runGitCapturing(dir: File, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(false).start()
        val out = pb.inputStream.bufferedReader().readText()
        check(pb.waitFor() == 0) { "git ${args.joinToString(" ")} failed" }
        return out
    }

    @Test
    fun `timeout kills long-running process`() = runTest {
        val dir = Files.createTempDirectory("step-exec-test").toFile()
        val envFile = File(dir, ".bosca_env").also { it.createNewFile() }
        val pathFile = File(dir, ".bosca_path").also { it.createNewFile() }
        val logs = createTestLogBuffer()

        try {
            val executor = StepExecutor(
                workDir = dir, serverUrl = "", agentToken = "",
                commitSha = "abc", ref = "refs/heads/main", repositoryId = "r",
                env = emptyMap(), secrets = emptyMap(), logBuffer = logs,
                timeoutMinutes = 0,
                sharedEnvFile = envFile, sharedPathFile = pathFile,
            )

            val step = StepDefinition(name = "Slow", run = "sleep 300")
            val result = executor.execute(step, ExpressionContext())
            logs.flush()

            assertTrue(!result.success)
            assertEquals(124, result.exitCode)
            assertTrue(logs.lines.any { it.contains("timed out") })
        } finally {
            dir.deleteRecursively()
        }
    }
}

class TestLogBuffer(private val maskSecrets: Set<String> = emptySet()) : LogBuffer(
    api = NoOpCiApi,
    repositoryId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
    runId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
    jobId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
    stepId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
    secretValues = maskSecrets,
) {
    val lines = mutableListOf<String>()

    override suspend fun add(content: String, stream: String) {
        var masked = content
        for (s in maskSecrets) {
            if (s.isNotEmpty()) masked = masked.replace(s, "***")
        }
        synchronized(lines) {
            lines.add(masked)
        }
    }

    override suspend fun flush() {}
}

private object NoOpCiApi : CiApi(bosca.cli.api.NetworkClient("http://localhost:0"))
