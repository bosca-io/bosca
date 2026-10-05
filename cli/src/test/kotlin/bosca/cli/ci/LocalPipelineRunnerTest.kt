package bosca.cli.ci

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPipelineRunnerTest {

    private fun createTempProject(): File {
        val dir = Files.createTempDirectory("local-pipeline-test").toFile()
        File(dir, ".git").mkdirs()
        return dir
    }

    private fun writePipeline(projectDir: File, yaml: String): File {
        val pipelinesDir = File(projectDir, ".bosca/pipelines")
        pipelinesDir.mkdirs()
        val file = File(pipelinesDir, "test.yaml")
        file.writeText(yaml)
        return file
    }

    @Test
    fun `runs simple pipeline with single echo step`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Simple
                jobs:
                  build:
                    steps:
                      - name: Echo
                        run: echo 'hello from local'
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, null) { output.add(it) }

            assertTrue(success)
            assertTrue(output.any { it.contains("Simple") })
            assertTrue(output.any { it.contains("completed successfully") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `warns that server release orchestration is not simulated locally`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Release-shaped local check
                on:
                  release: true
                  promotion:
                    environments: [production]
                environments:
                  production:
                    approval: required
                requires:
                  - pipeline: Bosca Release
                    repository: workspace
                jobs:
                  check:
                    environment: production
                    steps:
                      - name: Check
                        run: echo checked
            """.trimIndent())

            val output = mutableListOf<String>()
            val success = LocalPipelineRunner(projectDir = project).run(pipeline, null) { output.add(it) }

            assertTrue(success)
            assertTrue(output.any { it.contains("not simulated by the local runner") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `fails when step returns non-zero exit code`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Failing
                jobs:
                  build:
                    steps:
                      - name: Fail
                        run: exit 1
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, null) { output.add(it) }

            assertFalse(success)
            assertTrue(output.any { it.contains("failed") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `skips subsequent steps after failure`() {
        val project = createTempProject()
        try {
            val marker = File(project, "should-not-exist.txt")

            val pipeline = writePipeline(project, """
                name: Skip After Fail
                jobs:
                  build:
                    steps:
                      - name: Fail Early
                        run: exit 1
                      - name: Should Skip
                        run: touch should-not-exist.txt
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            runner.run(pipeline, null) { output.add(it) }

            assertFalse(marker.exists())
            assertTrue(output.any { it.contains("skipped") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `runs jobs in dependency order`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Ordered
                jobs:
                  deploy:
                    needs:
                      - build
                    steps:
                      - name: Deploy
                        run: echo 'deploying'
                  build:
                    steps:
                      - name: Build
                        run: echo 'building'
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, null) { output.add(it) }

            assertTrue(success)
            val buildIdx = output.indexOfFirst { it.contains("Job: build") }
            val deployIdx = output.indexOfFirst { it.contains("Job: deploy") }
            assertTrue(buildIdx < deployIdx, "build should run before deploy")
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `filters to specific job and its dependencies`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Filtered
                jobs:
                  lint:
                    steps:
                      - name: Lint
                        run: echo 'linting'
                  build:
                    needs: [lint]
                    steps:
                      - name: Build
                        run: echo 'building'
                  test:
                    needs: [build]
                    steps:
                      - name: Test
                        run: echo 'testing'
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, "build") { output.add(it) }

            assertTrue(success)
            assertTrue(output.any { it.contains("Job: lint") })
            assertTrue(output.any { it.contains("Job: build") })
            assertFalse(output.any { it.contains("Job: test") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `returns error when filtered job does not exist`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Missing Job
                jobs:
                  build:
                    steps:
                      - name: Build
                        run: echo hello
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, "nonexistent") { output.add(it) }

            assertFalse(success)
            assertTrue(output.any { it.contains("not found") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `skips checkout action in local mode`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: With Checkout
                jobs:
                  build:
                    steps:
                      - name: Checkout
                        uses: checkout
                      - name: Build
                        run: echo 'built'
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, null) { output.add(it) }

            assertTrue(success)
            assertTrue(output.any { it.contains("local") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `passes environment variables to steps`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Env Test
                env:
                  GREETING: hello
                jobs:
                  build:
                    steps:
                      - name: Print Env
                        run: echo "val=${'$'}GREETING"
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(
                projectDir = project,
                envOverrides = mapOf("EXTRA" to "world"),
            )
            val success = runner.run(pipeline, null) { output.add(it) }

            assertTrue(success)
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `skips dependent job when dependency fails`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Dep Failure
                jobs:
                  build:
                    steps:
                      - name: Fail
                        run: exit 1
                  deploy:
                    needs: [build]
                    steps:
                      - name: Deploy
                        run: echo deploying
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            val success = runner.run(pipeline, null) { output.add(it) }

            assertFalse(success)
            assertTrue(output.any { it.contains("Skipping job: deploy") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `respects step conditions`() {
        val project = createTempProject()
        try {
            val pipeline = writePipeline(project, """
                name: Conditional
                jobs:
                  build:
                    steps:
                      - name: Always
                        run: exit 1
                      - name: On Failure
                        if: failure()
                        run: echo 'handling failure'
            """.trimIndent())

            val output = mutableListOf<String>()
            val runner = LocalPipelineRunner(projectDir = project)
            runner.run(pipeline, null) { output.add(it) }

            assertTrue(output.any { it.contains("On Failure") && !it.contains("skipped") })
        } finally {
            project.deleteRecursively()
        }
    }
}
