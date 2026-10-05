package bosca.graphql.gradle

import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoscaGraphqlPluginTest {

    @Test
    fun `browser TypeScript generation is opt-in and fully configurable`() {
        val projectDir = kotlin.io.path.createTempDirectory("bosca-graphql-gradle").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply(BoscaGraphqlPlugin::class.java)
            val extension = project.extensions.getByType(BoscaGraphqlExtension::class.java)

            assertFalse(extension.generateTypeScript.get())
            assertEquals("@bosca/bml", extension.typeScriptRuntimeModule.get())
            assertTrue(
                extension.typeScriptOutputDir.get().asFile.invariantSeparatorsPath
                    .endsWith("build/generated/bosca-graphql/typescript"),
            )

            extension.generateTypeScript.set(true)
            extension.typeScriptRuntimeModule.set("./runtime")
            extension.typeScriptScalarMappings.put("Long", "number")
            val task = project.tasks.named("generateBoscaGraphqlTypeScriptClient", JavaExec::class.java).get()
            val arguments = task.argumentProviders.flatMap { it.asArguments() }

            assertTrue(arguments.windowed(2).any { it == listOf("--target", "typescript") })
            assertTrue(arguments.windowed(2).any { it == listOf("--runtime-module", "./runtime") })
            assertTrue(arguments.windowed(2).any { it == listOf("--scalar", "Long:number") })
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
