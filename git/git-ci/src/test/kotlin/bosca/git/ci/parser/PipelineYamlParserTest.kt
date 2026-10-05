package bosca.git.ci.parser

import bosca.git.model.ArtifactRequirement
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class PipelineYamlParserTest {

    private val parser = PipelineYamlParser()

    @Test
    fun `parse minimal pipeline`() {
        val yaml = """
            name: Build
            on:
              push:
                branches: [main]
            jobs:
              build:
                steps:
                  - name: Checkout
                    uses: checkout
        """.trimIndent()

        val definition = parser.parse(yaml, "build.yaml")
        assertEquals("Build", definition.name)
        assertEquals(1, definition.triggers.size)
        assertEquals(PipelineTriggerType.PUSH, definition.triggers[0].type)
        assertEquals(listOf("main"), definition.triggers[0].branches)
        assertEquals(1, definition.jobs.size)
        assertNotNull(definition.jobs["build"])
        assertEquals(1, definition.jobs["build"]!!.steps.size)
        assertEquals("checkout", definition.jobs["build"]!!.steps[0].uses)
    }

    @Test
    fun `parse full pipeline with all features`() {
        val yaml = """
            name: Build and Test
            on:
              push:
                branches: [main, "release/*"]
                paths: ["src/**"]
                paths-ignore: ["docs/**"]
              pull_request:
                branches: [main]
              tag:
                patterns: ["v*"]
              manual: true
              schedule:
                cron: "0 3 * * 1"
            concurrency:
              group: ci-test
              cancel-in-progress: true
            env:
              GRADLE_OPTS: "-Xmx2g"
            secrets:
              - DEPLOY_TOKEN
            jobs:
              build:
                runner: linux
                timeout: 30m
                steps:
                  - name: Checkout
                    uses: checkout
                    with:
                      depth: "1"
                  - name: Build
                    run: ./gradlew build
              test:
                runner: linux
                needs: [build]
                matrix:
                  java: ["21", "25"]
                steps:
                  - name: Test
                    run: ./gradlew test
                    env:
                      CI: "true"
              deploy:
                runner: linux
                needs: [test]
                if: ref == 'refs/heads/main'
                steps:
                  - name: Deploy
                    run: ./deploy.sh
                  - name: Docker
                    image: gradle:8.5
                    run: gradle build
        """.trimIndent()

        val definition = parser.parse(yaml, "build.yaml")
        assertEquals("Build and Test", definition.name)
        assertEquals(5, definition.triggers.size)

        val pushTrigger = definition.triggers.first { it.type == PipelineTriggerType.PUSH }
        assertEquals(listOf("main", "release/*"), pushTrigger.branches)
        assertEquals(listOf("src/**"), pushTrigger.paths)
        assertEquals(listOf("docs/**"), pushTrigger.pathsIgnore)

        val tagTrigger = definition.triggers.first { it.type == PipelineTriggerType.TAG }
        assertEquals(listOf("v*"), tagTrigger.tags)

        val scheduleTrigger = definition.triggers.first { it.type == PipelineTriggerType.SCHEDULE }
        assertEquals("0 3 * * 1", scheduleTrigger.cron)

        assertNotNull(definition.concurrency)
        assertEquals("ci-test", definition.concurrency!!.group)
        assertTrue(definition.concurrency!!.cancelInProgress)

        assertEquals(mapOf("GRADLE_OPTS" to "-Xmx2g"), definition.env)
        assertEquals(listOf("DEPLOY_TOKEN"), definition.secrets)

        assertEquals(3, definition.jobs.size)

        val buildJob = definition.jobs["build"]!!
        assertEquals("linux", buildJob.runner)
        assertEquals(30.minutes, buildJob.timeout)
        assertEquals(2, buildJob.steps.size)
        assertEquals("1", buildJob.steps[0].with["depth"])

        val testJob = definition.jobs["test"]!!
        assertEquals(listOf("build"), testJob.needs)
        assertNotNull(testJob.matrix)
        assertEquals(listOf("21", "25"), testJob.matrix!!["java"])

        val deployJob = definition.jobs["deploy"]!!
        assertEquals("ref == 'refs/heads/main'", deployJob.condition)
        assertEquals("gradle:8.5", deployJob.steps[1].image)
    }

    @Test
    fun `validate catches missing steps`() {
        val yaml = """
            name: Bad
            on:
              push:
                branches: [main]
            jobs:
              empty:
                steps: []
        """.trimIndent()

        val definition = parser.parse(yaml, "bad.yaml")
        val errors = parser.validate(definition)
        assertTrue(errors.any { it.contains("at least one step") })
    }

    @Test
    fun `validate requires syntactically valid cron for schedule triggers`() {
        val missing = parser.parse(
            """
                name: Scheduled
                on:
                  schedule: {}
                jobs:
                  build:
                    steps:
                      - run: echo build
            """.trimIndent(),
            "scheduled.yaml",
        ).copy(triggers = listOf(PipelineTrigger(PipelineTriggerType.SCHEDULE)))
        assertTrue(parser.validate(missing).any { it.contains("cron", ignoreCase = true) })

        val invalid = parser.parse(
            """
                name: Scheduled
                on:
                  schedule:
                    cron: definitely-not-cron
                jobs:
                  build:
                    steps:
                      - run: echo build
            """.trimIndent(),
            "scheduled.yaml",
        )
        assertTrue(parser.validate(invalid).any { it.contains("cron", ignoreCase = true) })

        val valid = invalid.copy(
            triggers = invalid.triggers.map { it.copy(cron = "0 3 * * *") },
        )
        assertTrue(parser.validate(valid).none { it.contains("cron", ignoreCase = true) })
    }

    @Test
    fun `validate catches missing run and uses`() {
        val yaml = """
            name: Bad
            on:
              push:
                branches: [main]
            jobs:
              build:
                steps:
                  - name: Nothing
        """.trimIndent()

        val definition = parser.parse(yaml, "bad.yaml")
        val errors = parser.validate(definition)
        assertTrue(errors.any { it.contains("'run' or 'uses'") })
    }

    @Test
    fun `validate catches unknown dependency`() {
        val yaml = """
            name: Bad
            on:
              push:
                branches: [main]
            jobs:
              deploy:
                needs: [nonexistent]
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        val definition = parser.parse(yaml, "bad.yaml")
        val errors = parser.validate(definition)
        assertTrue(errors.any { it.contains("nonexistent") })
    }

    @Test
    fun `validate catches both run and uses on same step`() {
        val yaml = """
            name: Bad
            on:
              push:
                branches: [main]
            jobs:
              build:
                steps:
                  - name: Both
                    run: echo hi
                    uses: checkout
        """.trimIndent()

        val definition = parser.parse(yaml, "bad.yaml")
        val errors = parser.validate(definition)
        assertTrue(errors.any { it.contains("cannot have both") })
    }

    @Test
    fun `parse trigger as list shorthand`() {
        val yaml = """
            name: Simple
            on: [push, pull_request]
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo build
        """.trimIndent()

        val definition = parser.parse(yaml, "simple.yaml")
        assertEquals(2, definition.triggers.size)
        assertTrue(definition.triggers.any { it.type == PipelineTriggerType.PUSH })
        assertTrue(definition.triggers.any { it.type == PipelineTriggerType.PULL_REQUEST })
    }

    @Test
    fun `parse job artifact declarations`() {
        val yaml = """
            name: Release Build
            on:
              tag:
                patterns: ["v*"]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                artifacts:
                  - type: docker
                    namespace: bosca-docker
                    coordinate: "my-api:${'$'}{{ env.VERSION }}"
                  - type: helm
                    namespace: bosca-helm
                    coordinate: "my-api:${'$'}{{ env.VERSION }}"
        """.trimIndent()

        val definition = parser.parse(yaml, "build.yaml")
        val artifacts = definition.jobs.getValue("build").artifacts
        assertEquals(2, artifacts.size)
        assertEquals("docker", artifacts[0].type)
        assertEquals("bosca-docker", artifacts[0].namespace)
        assertEquals("my-api:${'$'}{{ env.VERSION }}", artifacts[0].coordinate)
    }

    @Test
    fun `parse artifact environment filters`() {
        val yaml = """
            name: Release Build
            on:
              tag:
                patterns: ["*"]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                artifacts:
                  - type: helm-values
                    namespace: bosca-helm
                    coordinate: "bosca-values:1.0.0"
                    environments: ["development", "staging"]
                  - type: docker
                    namespace: bosca-docker
                    coordinate: "bosca:1.0.0"
        """.trimIndent()

        val artifacts = parser.parse(yaml, "build.yaml").jobs.getValue("build").artifacts
        assertEquals(listOf("development", "staging"), artifacts[0].environments)
        // Undeclared = not environment-scoped.
        assertEquals(emptyList(), artifacts[1].environments)
    }

    @Test
    fun `parse job artifact requirements with default and explicit timeouts`() {
        val yaml = """
            name: Consumer Build
            on:
              tag:
                patterns: ["v*"]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                requires:
                  - type: maven
                    namespace: bosca-maven
                    coordinate: "io.bosca:core-content:${'$'}{{ version }}"
                    timeout: 45m
                  - type: docker
                    namespace: bosca-docker
                    coordinate: "core-api:6.0.*"
        """.trimIndent()

        val requires = parser.parse(yaml, "build.yaml").jobs.getValue("build").requires
        assertEquals(2, requires.size)
        assertEquals("maven", requires[0].type)
        assertEquals("bosca-maven", requires[0].namespace)
        assertEquals("io.bosca:core-content:${'$'}{{ version }}", requires[0].coordinate)
        assertEquals(45, requires[0].timeout.inWholeMinutes)
        // No timeout declared → the default applies.
        assertEquals(ArtifactRequirement.DEFAULT_TIMEOUT, requires[1].timeout)
    }

    @Test
    fun `a job without requires parses with an empty requirement list`() {
        val yaml = """
            name: Plain Build
            on: [push]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
        """.trimIndent()

        assertEquals(emptyList(), parser.parse(yaml, "build.yaml").jobs.getValue("build").requires)
    }

    @Test
    fun `a requirement missing its coordinate fails the parse`() {
        val yaml = """
            name: Consumer Build
            on: [push]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                requires:
                  - type: maven
                    namespace: bosca-maven
        """.trimIndent()

        assertFailsWith<PipelineParseException> { parser.parse(yaml, "build.yaml") }
    }

    @Test
    fun `a non-list requires declaration fails the parse`() {
        val yaml = """
            name: Consumer Build
            on: [push]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                requires: "io.bosca:core-content:1.0"
        """.trimIndent()

        assertFailsWith<PipelineParseException> { parser.parse(yaml, "build.yaml") }
    }

    @Test
    fun `a non-list artifact environments declaration fails the parse`() {
        val yaml = """
            name: Release Build
            on:
              tag:
                patterns: ["*"]
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                artifacts:
                  - type: helm-values
                    namespace: bosca-helm
                    coordinate: "bosca-values:1.0.0"
                    environments: development
        """.trimIndent()

        assertFailsWith<PipelineParseException> { parser.parse(yaml, "build.yaml") }
    }

    @Test
    fun `a malformed artifact declaration fails the parse rather than being dropped`() {
        val yaml = """
            name: Bad
            on:
              tag:
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                artifacts:
                  - type: docker
                    namespace: bosca-docker
        """.trimIndent()

        val e = kotlin.test.assertFailsWith<PipelineParseException> { parser.parse(yaml, "bad.yaml") }
        assertTrue("coordinate" in (e.message ?: ""), e.message)
    }

    // ── Pipeline requirements ────────────────────────────────────────────────────────

    @Test
    fun `parse pipeline requirements alongside artifact requirements in one requires list`() {
        val yaml = """
            name: Release Consumer
            on:
              tag:
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                requires:
                  - type: maven
                    namespace: bosca-maven
                    coordinate: "io.bosca:core:${'$'}{{ version }}"
                  - pipeline: "Bosca Release"
                    repository: bosca-workspace
                    timeout: 60m
                  - pipeline: "Other Release"
                    repository: acme/other
                    ref: "refs/tags/v${'$'}{{ version }}"
        """.trimIndent()

        val job = parser.parse(yaml, "build.yaml").jobs.getValue("build")
        assertEquals(1, job.requires.size)
        assertEquals("io.bosca:core:${'$'}{{ version }}", job.requires[0].coordinate)
        assertEquals(2, job.pipelineRequires.size)
        assertEquals("Bosca Release", job.pipelineRequires[0].pipeline)
        assertEquals("bosca-workspace", job.pipelineRequires[0].repository)
        assertNull(job.pipelineRequires[0].ref)
        assertEquals(60, job.pipelineRequires[0].timeout.inWholeMinutes)
        assertEquals("refs/tags/v${'$'}{{ version }}", job.pipelineRequires[1].ref)
        assertEquals(ArtifactRequirement.DEFAULT_TIMEOUT, job.pipelineRequires[1].timeout)
    }

    @Test
    fun `top-level requires applies to root jobs and only root jobs`() {
        val yaml = """
            name: Release Consumer
            on:
              tag:
            requires:
              - pipeline: "Bosca Release"
                repository: bosca-workspace
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
              notify:
                needs: [build]
                steps:
                  - name: Notify
                    run: echo done
        """.trimIndent()

        val jobs = parser.parse(yaml, "build.yaml").jobs
        assertEquals(1, jobs.getValue("build").pipelineRequires.size)
        assertEquals("Bosca Release", jobs.getValue("build").pipelineRequires[0].pipeline)
        assertEquals(emptyList(), jobs.getValue("notify").pipelineRequires)
    }

    @Test
    fun `top-level requires prepends to a root job's own requirements`() {
        val yaml = """
            name: Release Consumer
            on:
              tag:
            requires:
              - pipeline: "Bosca Release"
                repository: bosca-workspace
            jobs:
              build:
                steps:
                  - name: Build
                    run: ./gradlew build
                requires:
                  - type: maven
                    namespace: bosca-maven
                    coordinate: "io.bosca:core:1.0"
                  - pipeline: "Web Release"
                    repository: web
        """.trimIndent()

        val job = parser.parse(yaml, "build.yaml").jobs.getValue("build")
        assertEquals(1, job.requires.size)
        assertEquals(listOf("Bosca Release", "Web Release"), job.pipelineRequires.map { it.pipeline })
    }

    @Test
    fun `a requirement mixing artifact and pipeline fields fails the parse`() {
        val yaml = """
            name: Bad
            on:
              tag:
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                requires:
                  - pipeline: "Bosca Release"
                    repository: bosca-workspace
                    type: maven
        """.trimIndent()

        val e = kotlin.test.assertFailsWith<PipelineParseException> { parser.parse(yaml, "bad.yaml") }
        assertTrue("not both" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a pipeline requirement missing its repository fails the parse`() {
        val yaml = """
            name: Bad
            on:
              tag:
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                requires:
                  - pipeline: "Bosca Release"
        """.trimIndent()

        val e = kotlin.test.assertFailsWith<PipelineParseException> { parser.parse(yaml, "bad.yaml") }
        assertTrue("repository" in (e.message ?: ""), e.message)
    }

    @Test
    fun `validate rejects a refless pipeline requirement in a pipeline without a tag trigger`() {
        val yaml = """
            name: Push Only
            on:
              push:
                branches: [main]
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                requires:
                  - pipeline: "Bosca Release"
                    repository: bosca-workspace
        """.trimIndent()

        val errors = parser.validate(parser.parse(yaml, "build.yaml"))
        assertTrue(errors.any { "tag trigger" in it && "Bosca Release" in it }, errors.joinToString())
    }

    @Test
    fun `validate accepts a refless pipeline requirement when a tag trigger exists`() {
        val yaml = """
            name: Tagged
            on:
              tag:
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                requires:
                  - pipeline: "Bosca Release"
                    repository: bosca-workspace
        """.trimIndent()

        assertEquals(emptyList(), parser.validate(parser.parse(yaml, "build.yaml")))
    }

    // ── Release/promotion triggers + inputs ─────────────────────────────────────────

    @Test
    fun `manual trigger accepts input declarations and retains boolean enablement`() {
        val yaml = """
            name: Manual Build
            on:
              manual:
                inputs:
                  image:
                    type: choice
                    options: [bosca-server, bosca-runner]
                  version:
                    description: Version to publish
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo build
        """.trimIndent()
        val trigger = parser.parse(yaml, "build.yaml").triggers.single()
        assertEquals(PipelineTriggerType.MANUAL, trigger.type)
        assertEquals(listOf("bosca-server", "bosca-runner"), trigger.inputs.getValue("image").options)
        assertEquals("string", trigger.inputs.getValue("version").type)
        assertEquals("Version to publish", trigger.inputs.getValue("version").description)
        assertEquals(emptyMap(), parser.parse(yaml.replace(Regex("(?s)manual:.*?jobs:"), "manual: true\njobs:"), "build.yaml").triggers.single().inputs)
        assertTrue(parser.parse(yaml.replace(Regex("(?s)manual:.*?jobs:"), "manual: false\njobs:"), "build.yaml").triggers.isEmpty())
    }

    @Test
    fun `parse release and promotion triggers with environments and inputs`() {
        val yaml = """
            name: Platform Release
            on:
              release: true
              promotion:
                environments: [production]
                inputs:
                  phasedRelease:
                    type: boolean
                    default: "true"
                    description: "Roll out gradually"
                  track:
                    type: choice
                    options: [internal, beta, production]
                    default: production
            jobs:
              deploy:
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        val triggers = parser.parse(yaml, "release.yaml").triggers
        assertEquals(2, triggers.size)
        val release = triggers.first { it.type == PipelineTriggerType.RELEASE }
        assertEquals(emptyMap(), release.inputs)
        val promotion = triggers.first { it.type == PipelineTriggerType.PROMOTION }
        assertEquals(listOf("production"), promotion.environments)
        assertEquals("boolean", promotion.inputs.getValue("phasedRelease").type)
        assertEquals("true", promotion.inputs.getValue("phasedRelease").default)
        assertEquals("Roll out gradually", promotion.inputs.getValue("phasedRelease").description)
        assertEquals(listOf("internal", "beta", "production"), promotion.inputs.getValue("track").options)
    }

    @Test
    fun `an input with an unknown type fails the parse`() {
        val yaml = """
            name: Bad
            on:
              release:
                inputs:
                  thing:
                    type: uuid
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
        """.trimIndent()

        val e = kotlin.test.assertFailsWith<PipelineParseException> { parser.parse(yaml, "bad.yaml") }
        assertTrue("unknown type" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a choice input without options fails the parse`() {
        val yaml = """
            name: Bad
            on:
              release:
                inputs:
                  track:
                    type: choice
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
        """.trimIndent()

        val e = kotlin.test.assertFailsWith<PipelineParseException> { parser.parse(yaml, "bad.yaml") }
        assertTrue("options" in (e.message ?: ""), e.message)
    }

    // ── Environments, approvals, and gate jobs ──────────────────────────────────────

    @Test
    fun `parse an environments block with job environment and approval bindings`() {
        val yaml = """
            name: Platform Release
            on:
              release: true
            environments:
              staging:
                deploy-on: release
              production:
                promotes-from: staging
                approval: required
            jobs:
              deploy-staging:
                environment: staging
                steps:
                  - name: Deploy
                    run: echo staging
              approve-production:
                approval: true
              deploy-production:
                environment: production
                needs: [approve-production]
                steps:
                  - name: Deploy
                    run: echo production
        """.trimIndent()

        val definition = parser.parse(yaml, "release.yaml")
        val staging = definition.environments.getValue("staging")
        assertTrue(staging.deployOnRelease)
        assertEquals(null, staging.promotesFrom)
        assertTrue(!staging.approval)
        val production = definition.environments.getValue("production")
        assertTrue(!production.deployOnRelease)
        assertEquals("staging", production.promotesFrom)
        assertTrue(production.approval)
        assertEquals("staging", definition.jobs.getValue("deploy-staging").environment)
        assertTrue(definition.jobs.getValue("approve-production").approval)
        assertTrue(definition.jobs.getValue("approve-production").steps.isEmpty())
        assertEquals(emptyList(), parser.validate(definition))
    }

    @Test
    fun `a steps-less job with no requirements and no approval fails validation`() {
        val yaml = """
            name: Bad
            on:
              push:
                branches: [main]
            jobs:
              empty:
                runs-on: linux
        """.trimIndent()

        val errors = parser.validate(parser.parse(yaml, "bad.yaml"))
        assertTrue(errors.any { "empty" in it && "gate" in it }, errors.joinToString())
    }

    @Test
    fun `a steps-less job gated by a pipeline requirement passes validation`() {
        val yaml = """
            name: Gated
            on:
              tag:
            jobs:
              await-builds:
                requires:
                  - pipeline: "Server Build"
                    repository: server
              deploy:
                needs: [await-builds]
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        assertEquals(emptyList(), parser.validate(parser.parse(yaml, "gated.yaml")))
    }

    @Test
    fun `a job targeting an undeclared environment fails validation`() {
        val yaml = """
            name: Bad
            on:
              release: true
            environments:
              staging:
                deploy-on: release
            jobs:
              deploy:
                environment: production
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        val errors = parser.validate(parser.parse(yaml, "bad.yaml"))
        assertTrue(errors.any { "production" in it && "environments" in it }, errors.joinToString())
    }

    @Test
    fun `parse pipeline-level and job-level secrets declarations`() {
        val yaml = """
            name: Release
            on:
              release: true
            secrets: [REGISTRY_TOKEN]
            jobs:
              deploy:
                secrets: [PLAY_SERVICE_ACCOUNT]
                steps:
                  - name: Deploy
                    run: echo deploy
        """.trimIndent()

        val definition = parser.parse(yaml, "release.yaml")
        assertEquals(listOf("REGISTRY_TOKEN"), definition.secrets)
        assertEquals(listOf("PLAY_SERVICE_ACCOUNT"), definition.jobs.getValue("deploy").secrets)
    }

    @Test
    fun `validate accepts an explicit-ref pipeline requirement without a tag trigger`() {
        val yaml = """
            name: Push Only
            on:
              push:
                branches: [main]
            jobs:
              build:
                steps:
                  - name: Build
                    run: echo hi
                requires:
                  - pipeline: "Bosca Release"
                    repository: bosca-workspace
                    ref: "refs/heads/main"
        """.trimIndent()

        assertEquals(emptyList(), parser.validate(parser.parse(yaml, "build.yaml")))
    }
}
