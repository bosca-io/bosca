package bosca.cli.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineYamlParserTest {

    private val parser = PipelineYamlParser()

    @Test
    fun `parses a job's produced-artifact declarations`() {
        val yaml = """
            name: build
            jobs:
              publish:
                steps:
                  - name: Publish
                    run: ./gradlew publish
                artifacts:
                  - type: maven
                    namespace: bosca-maven
                    coordinate: "io.bosca:core-content:${'$'}{{ env.VERSION }}"
                  - type: docker
                    namespace: bosca-docker
                    coordinate: "kubernetes-controller:${'$'}{{ ref }}"
        """.trimIndent()

        val artifacts = parser.parse(yaml).jobs["publish"]!!.artifacts
        assertEquals(2, artifacts.size)
        assertEquals(ArtifactDefinition("maven", "bosca-maven", "io.bosca:core-content:\${{ env.VERSION }}"), artifacts[0])
        assertEquals("docker", artifacts[1].type)
        assertEquals("kubernetes-controller:\${{ ref }}", artifacts[1].coordinate)
    }

    @Test
    fun `a job with no artifacts block declares none`() {
        val def = parser.parse("name: build\njobs:\n  compile:\n    steps:\n      - name: c\n        run: echo")
        assertTrue(def.jobs["compile"]!!.artifacts.isEmpty())
    }

    @Test
    fun `parses minimal pipeline with one job and one step`() {
        val yaml = """
            name: build
            jobs:
              compile:
                steps:
                  - name: Checkout
                    uses: checkout
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("build", def.name)
        assertEquals(1, def.jobs.size)
        val job = def.jobs["compile"]!!
        assertEquals(1, job.steps.size)
        assertEquals("Checkout", job.steps[0].name)
        assertEquals("checkout", job.steps[0].uses)
        assertNull(job.steps[0].run)
    }

    @Test
    fun `parses run step with shell command`() {
        val yaml = """
            name: test
            jobs:
              test:
                steps:
                  - name: Run tests
                    run: ./gradlew test
        """.trimIndent()

        val def = parser.parse(yaml)
        val step = def.jobs["test"]!!.steps[0]
        assertEquals("Run tests", step.name)
        assertEquals("./gradlew test", step.run)
        assertNull(step.uses)
    }

    @Test
    fun `parses step with condition`() {
        val yaml = """
            name: deploy
            jobs:
              deploy:
                steps:
                  - name: Notify Failure
                    uses: notify
                    if: failure()
                    with:
                      channel: slack
                      message: Build failed
        """.trimIndent()

        val def = parser.parse(yaml)
        val step = def.jobs["deploy"]!!.steps[0]
        assertEquals("failure()", step.condition)
        assertEquals("slack", step.with["channel"])
        assertEquals("Build failed", step.with["message"])
    }

    @Test
    fun `parses job runner label`() {
        val yaml = """
            name: build
            jobs:
              heavy:
                runner: linux-8cpu
                steps:
                  - name: Build
                    run: make build
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("linux-8cpu", def.jobs["heavy"]!!.runner)
    }

    @Test
    fun `defaults runner to 'default' when not specified`() {
        val yaml = """
            name: build
            jobs:
              simple:
                steps:
                  - name: Build
                    run: echo hello
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("default", def.jobs["simple"]!!.runner)
    }

    @Test
    fun `parses job dependencies`() {
        val yaml = """
            name: pipeline
            jobs:
              build:
                steps:
                  - name: Build
                    run: make
              test:
                needs:
                  - build
                steps:
                  - name: Test
                    run: make test
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(listOf("build"), def.jobs["test"]!!.needs)
    }

    @Test
    fun `retains server release orchestration fields for local-runner diagnostics`() {
        val yaml = """
            name: release
            on:
              release: true
              promotion:
                environments: [production]
            environments:
              production:
                deploy-on: release
                promotes-from: staging
                approval: required
            requires:
              - pipeline: Bosca Release
                repository: workspace
                ref: "refs/tags/${'$'}{{ version }}"
                timeout: 90m
            jobs:
              gate:
                environment: production
                approval: true
                requires:
                  - pipeline: Build
                    repository: server
                    timeout: 1h
        """.trimIndent()

        val definition = parser.parse(yaml)

        assertEquals(setOf("release", "promotion"), definition.triggerTypes)
        assertEquals(EnvironmentDefinition(true, "staging", true), definition.environments["production"])
        assertEquals(90, definition.pipelineRequires.single().timeoutMinutes)
        assertEquals("production", definition.jobs["gate"]!!.environment)
        assertTrue(definition.jobs["gate"]!!.approval)
        assertEquals(60, definition.jobs["gate"]!!.pipelineRequires.single().timeoutMinutes)
        assertTrue(definition.hasServerOrchestration())
    }

    @Test
    fun `parses matrix configuration`() {
        val yaml = """
            name: matrix-build
            jobs:
              test:
                matrix:
                  java:
                    - "17"
                    - "21"
                  os:
                    - ubuntu
                    - macos
                steps:
                  - name: Test
                    run: echo testing
        """.trimIndent()

        val def = parser.parse(yaml)
        val matrix = def.jobs["test"]!!.matrix!!
        assertEquals(listOf("17", "21"), matrix["java"])
        assertEquals(listOf("ubuntu", "macos"), matrix["os"])
    }

    @Test
    fun `parses global env vars`() {
        val yaml = """
            name: build
            env:
              CI: "true"
              NODE_ENV: test
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo build
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("true", def.env["CI"])
        assertEquals("test", def.env["NODE_ENV"])
    }

    @Test
    fun `parses secrets list`() {
        val yaml = """
            name: deploy
            secrets:
              - DEPLOY_TOKEN
              - SSH_KEY
            jobs:
              deploy:
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(listOf("DEPLOY_TOKEN", "SSH_KEY"), def.secrets)
    }

    @Test
    fun `parses step environment variables`() {
        val yaml = """
            name: build
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./build.sh
                    env:
                      JAVA_HOME: /usr/lib/jvm/java-17
                      GRADLE_OPTS: -Xmx4g
        """.trimIndent()

        val def = parser.parse(yaml)
        val step = def.jobs["build"]!!.steps[0]
        assertEquals("/usr/lib/jvm/java-17", step.env["JAVA_HOME"])
        assertEquals("-Xmx4g", step.env["GRADLE_OPTS"])
    }

    @Test
    fun `parses step with docker image`() {
        val yaml = """
            name: build
            jobs:
              build:
                steps:
                  - name: Test in container
                    image: node:22
                    run: npm test
        """.trimIndent()

        val def = parser.parse(yaml)
        val step = def.jobs["build"]!!.steps[0]
        assertEquals("node:22", step.image)
        assertEquals("npm test", step.run)
    }

    @Test
    fun `parses timeout in minutes`() {
        val yaml = """
            name: build
            jobs:
              build:
                timeout: 30m
                steps:
                  - name: Build
                    run: make
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(30, def.jobs["build"]!!.timeoutMinutes)
    }

    @Test
    fun `parses timeout in hours`() {
        val yaml = """
            name: build
            jobs:
              build:
                timeout: 2h
                steps:
                  - name: Build
                    run: make
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(120, def.jobs["build"]!!.timeoutMinutes)
    }

    @Test
    fun `parses timeout as plain number defaults to minutes`() {
        val yaml = """
            name: build
            jobs:
              build:
                timeout: 45
                steps:
                  - name: Build
                    run: make
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(45, def.jobs["build"]!!.timeoutMinutes)
    }

    @Test
    fun `throws on missing name field`() {
        val yaml = """
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hello
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(yaml)
        }
    }

    @Test
    fun `parses multiple jobs`() {
        val yaml = """
            name: ci
            jobs:
              lint:
                runner: linux
                steps:
                  - name: Lint
                    run: make lint
              build:
                runner: linux-8cpu
                needs:
                  - lint
                steps:
                  - name: Build
                    run: make build
              test:
                runner: linux
                needs:
                  - build
                steps:
                  - name: Test
                    run: make test
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals(3, def.jobs.size)
        assertTrue(def.jobs.containsKey("lint"))
        assertTrue(def.jobs.containsKey("build"))
        assertTrue(def.jobs.containsKey("test"))
        assertEquals("linux-8cpu", def.jobs["build"]!!.runner)
        assertEquals(listOf("build"), def.jobs["test"]!!.needs)
    }

    @Test
    fun `parses complete realistic pipeline`() {
        val yaml = """
            name: CI Pipeline
            env:
              CI: "true"
            secrets:
              - NPM_TOKEN
            jobs:
              build:
                runner: linux
                timeout: 30m
                steps:
                  - name: Checkout
                    uses: checkout
                    with:
                      depth: "1"
                  - name: Cache Gradle
                    uses: cache
                    with:
                      key: "gradle-hash"
                      paths: "~/.gradle/caches,~/.gradle/wrapper"
                  - name: Setup Java
                    uses: setup-java
                    with:
                      version: "21"
                      distribution: zulu
                  - name: Build
                    run: ./gradlew build
                  - name: Upload JAR
                    uses: upload-artifact
                    with:
                      name: build-output
                      paths: build/libs/*.jar
                  - name: Notify on failure
                    uses: notify
                    if: failure()
                    with:
                      channel: slack
                      message: Build failed
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("CI Pipeline", def.name)
        assertEquals("true", def.env["CI"])
        assertEquals(listOf("NPM_TOKEN"), def.secrets)

        val job = def.jobs["build"]!!
        assertEquals("linux", job.runner)
        assertEquals(30, job.timeoutMinutes)
        assertEquals(6, job.steps.size)

        assertEquals("checkout", job.steps[0].uses)
        assertEquals("1", job.steps[0].with["depth"])
        assertEquals("cache", job.steps[1].uses)
        assertEquals("setup-java", job.steps[2].uses)
        assertEquals("./gradlew build", job.steps[3].run)
        assertEquals("upload-artifact", job.steps[4].uses)
        assertEquals("failure()", job.steps[5].condition)
    }

    @Test
    fun `parses job-level condition`() {
        val yaml = """
            name: deploy
            jobs:
              deploy:
                if: branch == 'main'
                steps:
                  - name: Deploy
                    run: deploy.sh
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("branch == 'main'", def.jobs["deploy"]!!.condition)
    }

    @Test
    fun `handles missing jobs section as empty map`() {
        val yaml = """
            name: empty
        """.trimIndent()

        val def = parser.parse(yaml)
        assertTrue(def.jobs.isEmpty())
    }

    @Test
    fun `unnamed step defaults to 'Unnamed step'`() {
        val yaml = """
            name: build
            jobs:
              build:
                steps:
                  - run: echo hello
        """.trimIndent()

        val def = parser.parse(yaml)
        assertEquals("Unnamed step", def.jobs["build"]!!.steps[0].name)
    }
}
