package bosca.cli.ci

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StepResolutionTest {

    private val parser = PipelineYamlParser()

    @Test
    fun `step definitions are keyed by name for lookup`() {
        val yaml = """
            name: CI
            jobs:
              build:
                steps:
                  - name: Checkout
                    uses: checkout
                  - name: Build
                    run: make build
                  - name: Test
                    run: make test
        """.trimIndent()

        val def = parser.parse(yaml)
        val byName = def.jobs["build"]!!.steps.associateBy { it.name }

        assertEquals("checkout", byName["Checkout"]?.uses)
        assertEquals("make build", byName["Build"]?.run)
        assertEquals("make test", byName["Test"]?.run)
        assertNull(byName["NonExistent"])
    }

    @Test
    fun `loadStepDefinitions finds definitions from pipeline YAML on disk`() {
        val dir = Files.createTempDirectory("step-res-test").toFile()
        try {
            val pipelinesDir = File(dir, ".bosca/pipelines")
            pipelinesDir.mkdirs()

            File(pipelinesDir, "build.yaml").writeText("""
                name: CI
                jobs:
                  compile:
                    runner: linux
                    timeout: 45
                    steps:
                      - name: Checkout
                        uses: checkout
                      - name: Build
                        run: ./gradlew build
            """.trimIndent())

            val yamlFiles = pipelinesDir.listFiles()?.filter {
                it.extension == "yaml" || it.extension == "yml"
            } ?: emptyList()

            var foundSteps: Map<String, StepDefinition>? = null
            var foundJob: JobDefinition? = null
            for (yamlFile in yamlFiles) {
                val definition = parser.parse(yamlFile.readText())
                val jobDef = definition.jobs["compile"] ?: continue
                foundSteps = jobDef.steps.associateBy { it.name }
                foundJob = jobDef
            }

            assertNotNull(foundSteps)
            assertNotNull(foundJob)
            assertEquals(2, foundSteps.size)
            assertEquals("checkout", foundSteps["Checkout"]?.uses)
            assertEquals("./gradlew build", foundSteps["Build"]?.run)
            assertEquals(45, foundJob.timeoutMinutes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `multiple pipeline YAML files search by job name`() {
        val dir = Files.createTempDirectory("multi-yaml-test").toFile()
        try {
            val pipelinesDir = File(dir, ".bosca/pipelines")
            pipelinesDir.mkdirs()

            File(pipelinesDir, "build.yaml").writeText("""
                name: Build
                jobs:
                  compile:
                    steps:
                      - name: Build
                        run: make build
            """.trimIndent())

            File(pipelinesDir, "test.yaml").writeText("""
                name: Test
                jobs:
                  test:
                    steps:
                      - name: Test
                        run: make test
            """.trimIndent())

            val yamlFiles = pipelinesDir.listFiles()!!.filter {
                it.extension == "yaml" || it.extension == "yml"
            }

            var found: Map<String, StepDefinition>? = null
            for (yamlFile in yamlFiles) {
                val definition = parser.parse(yamlFile.readText())
                val jobDef = definition.jobs["test"] ?: continue
                found = jobDef.steps.associateBy { it.name }
                break
            }

            assertNotNull(found)
            assertEquals("make test", found["Test"]?.run)
        } finally {
            dir.deleteRecursively()
        }
    }
}
