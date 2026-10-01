package bosca.cli.bml

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmlDevCommandTest {

    @Test
    fun `default command delegates to its Gradle wrapper bmlDev task`() {
        val project = Files.createTempDirectory("bml-dev-gradle")
        val wrapper = project.resolve("gradlew")
        wrapper.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > gradle-arguments.txt
            exit 0
            """.trimIndent() + "\n",
        )
        assertTrue(wrapper.toFile().setExecutable(true))

        val result = BmlDevCommand().test(project.toString())

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf("bmlDev", "--console=plain"), project.resolve("gradle-arguments.txt").readLines())
        assertTrue(result.stdout.contains("delegating"), result.stdout)
    }

    @Test
    fun `delegated Gradle failure becomes the CLI exit status`() {
        val project = Files.createTempDirectory("bml-dev-gradle-failure")
        val wrapper = project.resolve("gradlew")
        wrapper.writeText("#!/bin/sh\nexit 7\n")
        assertTrue(wrapper.toFile().setExecutable(true))

        val result = BmlDevCommand().test(project.toString())

        assertEquals(7, result.statusCode)
    }

    @Test
    fun `Gradle command prefers the platform wrapper and permits a custom task`() {
        val unixProject = Files.createTempDirectory("bml-dev-unix")
        unixProject.resolve("gradlew").writeText("")
        assertEquals(
            listOf(unixProject.resolve("gradlew").toAbsolutePath().toString(), ":site:bmlDev", "--console=plain"),
            gradleBmlDevCommand(unixProject, ":site:bmlDev", "Linux"),
        )

        val windowsProject = Files.createTempDirectory("bml-dev-windows")
        windowsProject.resolve("gradlew.bat").writeText("")
        assertEquals(
            listOf(windowsProject.resolve("gradlew.bat").toAbsolutePath().toString(), "bmlDev", "--console=plain"),
            gradleBmlDevCommand(windowsProject, "bmlDev", "Windows 11"),
        )

        val fallback = Files.createTempDirectory("bml-dev-system-gradle")
        assertEquals(listOf("gradle", "bmlDev", "--console=plain"), gradleBmlDevCommand(fallback, "bmlDev", "Linux"))
    }

}
