package bosca.cli.ci

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepExecutorSetupNodeTest {

    @Test
    fun `setup-node reuses NVM and Node preinstalled by the Docker image`() = runTest {
        val root = Files.createTempDirectory("setup-node-preinstalled-test").toFile()
        try {
            val home = File(root, "home/bosca").apply { mkdirs() }
            val xdgConfigHome = File(home, ".config").apply { mkdirs() }
            val nvmDir = File(xdgConfigHome, "nvm").apply { mkdirs() }
            installFakeNvm(nvmDir)
            val fakeBin = File(root, "bin").apply { mkdirs() }
            installFailingCurl(fakeBin)

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = TestLogBuffer()
            val executor = StepExecutor(
                workDir = root,
                serverUrl = "",
                agentToken = "",
                commitSha = "abc",
                ref = "refs/heads/main",
                repositoryId = "r",
                env = mapOf(
                    "HOME" to home.absolutePath,
                    "XDG_CONFIG_HOME" to xdgConfigHome.absolutePath,
                    "NVM_DIR" to nvmDir.absolutePath,
                    "PATH" to "${fakeBin.absolutePath}:${System.getenv("PATH")}",
                ),
                secrets = emptyMap(),
                logBuffer = logs,
                sharedEnvFile = envFile,
                sharedPathFile = pathFile,
            )

            val result = executor.execute(
                StepDefinition(
                    name = "Setup Node",
                    uses = "setup-node",
                    with = mapOf("version" to "22"),
                ),
                ExpressionContext(),
            )
            logs.flush()

            assertTrue(result.success, "setup-node failed. Logs: ${logs.lines}")
            assertEquals(
                File(nvmDir, "versions/node/v22/bin").absolutePath,
                pathFile.readLines().single(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `setup-node uses the XDG nvm directory in the Docker agent environment`() = runTest {
        val root = Files.createTempDirectory("setup-node-xdg-test").toFile()
        try {
            val home = File(root, "home/bosca").apply { mkdirs() }
            val xdgConfigHome = File(home, ".config").apply { mkdirs() }
            val fakeBin = File(root, "bin").apply { mkdirs() }
            installFakeNvmInstaller(fakeBin)

            val envFile = File(root, ".bosca_env").also { it.createNewFile() }
            val pathFile = File(root, ".bosca_path").also { it.createNewFile() }
            val logs = TestLogBuffer()
            val executor = StepExecutor(
                workDir = root,
                serverUrl = "",
                agentToken = "",
                commitSha = "abc",
                ref = "refs/heads/main",
                repositoryId = "r",
                env = mapOf(
                    "HOME" to home.absolutePath,
                    "XDG_CONFIG_HOME" to xdgConfigHome.absolutePath,
                    "NVM_DIR" to "",
                    "PATH" to "${fakeBin.absolutePath}:${System.getenv("PATH")}",
                ),
                secrets = emptyMap(),
                logBuffer = logs,
                sharedEnvFile = envFile,
                sharedPathFile = pathFile,
            )

            val result = executor.execute(
                StepDefinition(
                    name = "Setup Node",
                    uses = "setup-node",
                    with = mapOf("version" to "22"),
                ),
                ExpressionContext(),
            )
            logs.flush()

            val nvmDir = File(xdgConfigHome, "nvm")
            assertTrue(result.success, "setup-node failed. Logs: ${logs.lines}")
            assertTrue(File(nvmDir, "nvm.sh").isFile)
            assertFalse(File(home, ".nvm").exists())
            assertEquals(
                File(nvmDir, "versions/node/v22/bin").absolutePath,
                pathFile.readLines().single(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installFakeNvmInstaller(binDir: File) {
        File(binDir, "curl").apply {
            writeText(
                """
                #!/bin/bash
                cat <<'INSTALLER'
                if [ "${'$'}NVM_DIR" != "${'$'}XDG_CONFIG_HOME/nvm" ]; then
                    echo "unexpected NVM_DIR: ${'$'}NVM_DIR" >&2
                    exit 91
                fi
                mkdir -p "${'$'}NVM_DIR"
                cat > "${'$'}NVM_DIR/nvm.sh" <<'NVM'
                nvm() {
                    case "${'$'}1" in
                        install|use)
                            return 0
                            ;;
                        which)
                            printf '%s\n' "${'$'}NVM_DIR/versions/node/v${'$'}2/bin/node"
                            ;;
                        *)
                            return 1
                            ;;
                    esac
                }
                NVM
                INSTALLER
                """.trimIndent(),
            )
            assertTrue(setExecutable(true), "failed to make fake curl executable")
        }
    }

    private fun installFakeNvm(nvmDir: File) {
        File(nvmDir, "nvm.sh").writeText(
            """
            nvm() {
                case "${'$'}1" in
                    version)
                        printf '%s\n' "v22.0.0"
                        ;;
                    install)
                        echo "setup-node unexpectedly tried to install Node" >&2
                        return 93
                        ;;
                    use)
                        return 0
                        ;;
                    which)
                        printf '%s\n' "${'$'}NVM_DIR/versions/node/v${'$'}2/bin/node"
                        ;;
                    *)
                        return 1
                        ;;
                esac
            }
            """.trimIndent(),
        )
    }

    private fun installFailingCurl(binDir: File) {
        File(binDir, "curl").apply {
            writeText(
                """
                #!/bin/bash
                echo "setup-node unexpectedly tried to download NVM" >&2
                exit 92
                """.trimIndent(),
            )
            assertTrue(setExecutable(true), "failed to make fake curl executable")
        }
    }
}
