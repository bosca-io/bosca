package bosca.workops.service

import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.Document
import bosca.ai.agents.model.AgentResource
import kotlinx.serialization.json.JsonElement
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.DocumentService
import bosca.ai.agents.service.AgentResourceService
import bosca.serialization.UUID
import bosca.workops.model.guidance.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class WorkOpsGuidanceServiceTest {
    private val configurations = mockk<ConfigurationService>()
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val resources = mockk<AgentResourceService>()
    private val service = WorkOpsGuidanceServiceImpl(configurations, metadata, documents, resources, Json, mockk(relaxed = true))

    private val settings = mutableMapOf<String, Pair<Configuration, JsonElement>>()
    private val bodies = mutableMapOf<UUID, Document>()
    private val agentResources = mutableMapOf<String, AgentResource>()

    @BeforeTest fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { configurations.getByKey(any()) } answers { settings[firstArg()]?.first }
        coEvery { configurations.getValue(any()) } answers { settings.values.first { it.first.id == firstArg<UUID>() }.second }
        coEvery { configurations.setConfiguration(any()) } answers {
            val input = firstArg<ConfigurationInput>()
            val record = Configuration(UUID.random(), input.key, input.description, input.public)
            settings[input.key] = record to input.value
            record
        }
        coEvery { configurations.deleteConfiguration(any()) } answers { settings.remove(firstArg())?.first?.id ?: error("Missing configuration") }
        coEvery { metadata.add(null, null, any()) } answers {
            val input = thirdArg<bosca.content.metadata.model.MetadataInput>()
            val id = UUID.random()
            bodies[id] = requireNotNull(input.document).toDocument(id, 1)
            mockk<Metadata> { every { this@mockk.id } returns id; every { version } returns 1 }
        }
        coEvery { documents.getDocument(any(), any()) } answers { bodies[firstArg()] }
        coEvery { resources.getByKey(any()) } answers { agentResources[firstArg()] }
        coEvery { resources.add(any()) } answers {
            val input = firstArg<bosca.ai.agents.model.AgentResourceInput>()
            mockk<AgentResource>(relaxed = true) { every { id } returns UUID.random(); every { gitRepositoryId } returns null; every { gitPath } returns null }.also { agentResources[input.key] = it }
        }
        coEvery { resources.delete(any()) } answers { val key = agentResources.entries.firstOrNull { it.value.id == firstArg<UUID>() }?.key; key != null && agentResources.remove(key) != null }
        coEvery { resources.edit(any(), any()) } answers { agentResources.values.first { it.id == firstArg<UUID>() } }
    }

    @AfterTest fun cleanup() = unmockkStatic("bosca.db.ConnectionManagerKt")

    @Test fun `project overrides inherit defaults and reset restores current platform policy and documents`() = runTest {
        val project = UUID.random()
        val base = service.effective(null)
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "custom-tests", "When implementing: user's guidance", "# Contract\n\nTest rollback and permission failures.")
        val platform = service.saveCurrent(null, WorkOpsGuidanceInput(WorkOpsGuidancePolicy(minimumLineCoverage = 100.0), listOf(guide)))
        assertNotEquals(base.revision, platform.revision)
        val inherited = service.effective(project)
        assertEquals(platform.revision, inherited.revision)
        assertTrue(inherited.policyInherited && inherited.guides.all { it.inherited })
        val own = guide.copy(name = "project-tests", markdown = "# Project contract\n\nExercise integration wiring.")
        val saved = service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(own)))
        assertEquals(100.0, saved.policy.minimumLineCoverage)
        assertTrue(saved.policyInherited)
        assertFalse(saved.guides.single { it.phase == own.phase }.inherited)
        assertTrue(saved.guides.filter { it.phase != own.phase }.all { it.inherited })
        assertTrue(saved.guides.single { it.phase == own.phase }.markdown.contains("integration wiring"))
        coVerify { metadata.add(null, null, match { it.document?.content != null && it.searchable == false && it.recommendable == false }) }
        assertFalse(settings.values.any { it.second.toString().contains("integration wiring") }, "Configuration stores references, not document bodies")
        val previous = saved.guides.single { it.phase == own.phase }
        val revision = service.saveCurrent(project, WorkOpsGuidanceInput(WorkOpsGuidancePolicy(requireTdd = false), listOf(own.copy(markdown = "New tests."))))
        assertFalse(revision.policyInherited)
        assertFalse(revision.policy.requireTdd)
        assertNotEquals(previous.metadataId, revision.guides.single { it.phase == own.phase }.metadataId)
        assertEquals(previous.resourceId, revision.guides.single { it.phase == own.phase }.resourceId)
        assertTrue(requireNotNull(bodies[previous.metadataId]?.content).toString().isNotBlank())
        coVerify(exactly = 1) { resources.edit(any(), any()) }
        val skill = service.exportSkill(project, own.phase)
        assertEquals("New tests.", service.importSkill(own.phase, skill).markdown)
        assertEquals(platform.revision, service.resetCurrent(project).revision)
        assertEquals(base.revision, service.resetCurrent(null).revision)
        assertEquals(base.revision, service.resetCurrent(project).revision)
    }


    @Test fun `identical guide saves preserve revision documents and resources`() = runTest {
        val project = UUID.random()
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "tests", "Use tests", "# Tests")
        val input = WorkOpsGuidanceInput(guides = listOf(guide))
        val first = service.saveCurrent(project, input)
        val beforeBodies = bodies.keys.toSet()
        val beforeResources = agentResources.mapValues { it.value.id }
        val again = service.save(project, input, first.revision)
        assertEquals(first.revision, again.revision)
        assertEquals(first.guides, again.guides)
        assertEquals(beforeBodies, bodies.keys)
        assertEquals(beforeResources, agentResources.mapValues { it.value.id })
    }

    @Test fun `fully overridden guidance ignores hidden platform changes`() = runTest {
        val project = UUID.random()
        val base = service.effective(project)
        val input = WorkOpsGuidanceInput(base.policy, base.guides.map { WorkOpsGuideInput(it.phase, it.name, it.description, it.markdown) })
        val own = service.saveCurrent(project, input)
        service.saveCurrent(null, WorkOpsGuidanceInput(WorkOpsGuidancePolicy(minimumLineCoverage = 100.0), listOf(
            WorkOpsGuideInput(WorkOpsGuidancePhase.INVESTIGATION, "investigate", "Investigate", "# New platform guide"))))
        val current = service.effective(project)
        assertEquals(own.policy, current.policy)
        assertEquals(own.guides, current.guides)
        assertEquals(own.revision, current.revision)
    }

    @Test fun `renames descriptions and independent policy changes reuse unchanged guide snapshots`() = runTest {
        val project = UUID.random()
        val original = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "tests", "Use tests", "# Tests")
        val saved = service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(original)))
        val renamed = service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(original.copy(name = "renamed-tests"))))
        assertEquals("renamed-tests", renamed.guides.single { it.phase == original.phase }.name)
        assertNotEquals(saved.revision, renamed.revision)
        val described = service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(original.copy(name = "renamed-tests", description = "Use for regressions"))))
        assertEquals("Use for regressions", described.guides.single { it.phase == original.phase }.description)
        val previous = described.guides.single { it.phase == original.phase }
        val policy = service.saveCurrent(project, WorkOpsGuidanceInput(WorkOpsGuidancePolicy(minimumLineCoverage = 100.0), listOf(original.copy(name = "renamed-tests", description = "Use for regressions"))))
        assertEquals(previous.metadataId, policy.guides.single { it.phase == original.phase }.metadataId)
        assertNotEquals(described.revision, policy.revision)
        assertEquals(100.0, policy.policy.minimumLineCoverage)
    }

    @Test fun `empty saves are no-ops and hidden overridden guides do not affect partially inherited projects`() = runTest {
        val project = UUID.random()
        val bundled = service.effective(project)
        assertEquals(bundled.revision, service.save(project, WorkOpsGuidanceInput(), bundled.revision).revision)
        assertTrue(settings.isEmpty())
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "tests", "Use tests", "# Platform")
        service.saveCurrent(null, WorkOpsGuidanceInput(guides = listOf(guide)))
        val own = service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = "# Project"))))
        service.saveCurrent(null, WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = "# New platform"))))
        assertEquals(own.revision, service.effective(project).revision)
    }

    @Test fun `stale editor saves and resets preserve the newer guides without writing documents`() = runTest {
        val project = UUID.random()
        val initial = service.effective(project)
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "tests", "Use tests", "# New guide")
        val saved = service.save(project, WorkOpsGuidanceInput(guides = listOf(guide)), initial.revision)
        val count = bodies.size
        assertFailsWith<bosca.workops.model.WorkOpsValidationException> {
            service.save(project, WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = "# Stale guide"))), initial.revision)
        }
        assertFailsWith<bosca.workops.model.WorkOpsValidationException> { service.reset(project, initial.revision) }
        assertEquals(saved.revision, service.effective(project).revision)
        assertEquals(count, bodies.size)
    }

    @Test fun `removed overrides remove their resources and leave retained and platform resources intact`() = runTest {
        val project = UUID.random()
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.IMPLEMENTATION, "tests", "Use tests", "# Tests")
        val other = guide.copy(phase = WorkOpsGuidancePhase.VERIFICATION, name = "verify")
        service.saveCurrent(null, WorkOpsGuidanceInput(guides = listOf(guide)))
        service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(guide, other)))
        service.saveCurrent(project, WorkOpsGuidanceInput(guides = listOf(other)))
        assertNull(agentResources["workops.guidance.$project.implementation"])
        assertNotNull(agentResources["workops.guidance.$project.verification"])
        assertNotNull(agentResources["workops.guidance.default.implementation"])
        service.resetCurrent(project)
        assertNull(agentResources["workops.guidance.$project.verification"])
        assertNotNull(agentResources["workops.guidance.default.implementation"])
    }

    @Test fun `missing values corrupt configuration and missing document bodies fail loudly`() = runTest {
        val saved = service.saveCurrent(null, WorkOpsGuidanceInput(guides = listOf(WorkOpsGuideInput(WorkOpsGuidancePhase.SPECIFICATION, "spec-guide", "Use when planning", "# Plan"))))
        val id = requireNotNull(saved.guides.single { it.phase == WorkOpsGuidancePhase.SPECIFICATION }.metadataId)
        val body = requireNotNull(bodies[id])
        bodies[id] = Document(metadataId = id, version = body.version, title = body.title, content = null)
        assertFailsWith<IllegalStateException> { service.effective(null) }
        bodies.remove(id)
        assertFailsWith<IllegalStateException> { service.effective(null) }
        coEvery { configurations.getValue(any()) } returns null
        assertFailsWith<IllegalStateException> { service.effective(null) }
        coEvery { configurations.getValue(any()) } returns kotlinx.serialization.json.JsonPrimitive("invalid")
        assertFailsWith<kotlinx.serialization.SerializationException> { service.effective(null) }
    }


    @Test fun `bundled defaults contain five portable guides and high coverage policy`() = runTest {
        val guidance = service.effective(null)
        assertEquals(WorkOpsGuidancePhase.entries, guidance.guides.map { it.phase })
        assertEquals(99.0, guidance.policy.minimumLineCoverage)
        assertEquals(97.0, guidance.policy.minimumBranchCoverage)
        assertTrue(guidance.policy.requireInterfaceReview && guidance.policy.requireTdd && guidance.policy.requireTestQuality)
        assertTrue(guidance.guides.all { it.markdown.isNotBlank() && it.inherited })
        assertEquals(64, guidance.revision.length)
        assertEquals(guidance, service.effective(null))
        for (guide in guidance.guides) {
            val imported = service.importSkill(guide.phase, service.exportSkill(null, guide.phase))
            assertEquals(guide.name, imported.name)
            assertEquals(guide.description, imported.description)
            assertEquals(guide.markdown.trim(), imported.markdown.trim())
        }
    }

    @Test fun `skill frontmatter and body are validated and typed policy cannot be imported as prose`() {
        val phase = WorkOpsGuidancePhase.IMPLEMENTATION
        val valid = "---\nname: my-workflow\ndescription: \"Use for implementation: TDD\"\nmetadata:\n  policy: disable-all\n---\n# Implement\n\nUse tests.\n"
        assertEquals(WorkOpsGuideInput(phase, "my-workflow", "Use for implementation: TDD", "# Implement\n\nUse tests."), service.importSkill(phase, valid))
        assertEquals(service.importSkill(phase, valid), service.importSkill(phase, valid.replace("\n", "\r\n")))
        for (bad in listOf("---\n[x]\n---\nbody", "---\nname: [x]\ndescription: y\n---\nbody", "---\nname: x\ndescription: \"\"\n---\nbody", "---\nname: x\ndescription: y", "---\nname: "+"x".repeat(65)+"\ndescription: y\n---\nbody", "---\nname: x\ndescription: [\n---\nbody", "body", "---\nname: X\ndescription: y\n---\nbody", "---\nname: x\n---\nbody", "---\nname: x\ndescription: y\n---\n ", "---\nname: x\ndescription: [a]\n---\nbody", "---\nname: x\nname: y\ndescription: z\n---\nbody")) {
            assertFailsWith<IllegalArgumentException> { service.importSkill(phase, bad) }
        }
    }

    @Test fun `a missing packaged default fails loudly instead of returning empty guidance`() = runTest {
        val original = WorkOpsGuidanceServiceImpl::class.java
        val loader = object : ClassLoader(original.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith(original.name)) return super.loadClass(name, resolve)
                val path = name.replace('.', '/') + ".class"
                val bytes = requireNotNull(parent.getResourceAsStream(path)).use { it.readBytes() }
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
            }
            override fun getResourceAsStream(name: String): java.io.InputStream? =
                if (name == "workops/guidance/investigation/SKILL.md") null else super.getResourceAsStream(name)
        }
        val isolated = loader.loadClass(original.name).constructors.single().newInstance(configurations, metadata, documents, resources, Json, mockk<bosca.workops.repository.WorkOpsGuidanceRepository>(relaxed = true)) as WorkOpsGuidanceService
        assertTrue(assertFailsWith<IllegalStateException> { isolated.effective(null) }.message.orEmpty().contains("Bundled WorkOps guide is missing"))
    }

    @Test fun `explicit overrides conflict with stale inheritance even when current effective content is identical`() = runTest {
        val inherited = service.effective(null)
        val explicit = service.save(null, WorkOpsGuidanceInput(policy = inherited.policy), inherited.revision)
        assertNotEquals(inherited.revision, explicit.revision)
        assertFailsWith<bosca.workops.model.WorkOpsValidationException> { service.reset(null, inherited.revision) }
        assertEquals(explicit, service.effective(null))
    }

    @Test fun `stored guidance rejects missing policy guides and reference fields rather than inventing defaults`() = runTest {
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.VERIFICATION, "verify", "Use when verifying", "# Verify")
        service.saveCurrent(null, WorkOpsGuidanceInput(guides = listOf(guide)))
        val (configuration, value) = settings.getValue("workops.guidance.default")
        val stored = value as kotlinx.serialization.json.JsonObject
        val reference = stored.getValue("guides") as kotlinx.serialization.json.JsonArray
        for (field in listOf("policy", "guides")) {
            settings[configuration.key] = configuration to kotlinx.serialization.json.JsonObject(stored - field)
            assertFailsWith<kotlinx.serialization.SerializationException> { service.effective(null) }
        }
        for (field in listOf("phase", "name", "description", "metadataId", "documentVersion", "resourceId")) {
            settings[configuration.key] = configuration to kotlinx.serialization.json.JsonObject(stored + ("guides" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject((reference.single() as kotlinx.serialization.json.JsonObject) - field)))))
            assertFailsWith<kotlinx.serialization.SerializationException> { service.effective(null) }
        }
    }

    @Test fun `invalid policies and duplicate guides fail before any writes`() = runTest {
        val guide = WorkOpsGuideInput(WorkOpsGuidancePhase.INVESTIGATION, "investigate", "Use when investigating", "# Read source")
        for (bad in listOf(WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumLineCoverage = -1.0)),
            WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumBranchCoverage = 101.0)),
            WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumLineCoverage = Double.NaN)),
            WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumLineCoverage = 101.0)),
            WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumBranchCoverage = -1.0)),
            WorkOpsGuidanceInput(policy = WorkOpsGuidancePolicy(minimumBranchCoverage = Double.POSITIVE_INFINITY)),
            WorkOpsGuidanceInput(guides = listOf(guide, guide)),
            WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = ""))),
            WorkOpsGuidanceInput(guides = listOf(guide.copy(name = "INVALID"))))) {
            assertFailsWith<IllegalArgumentException> { service.saveCurrent(null, bad) }
        }
        coVerify(exactly = 0) { configurations.setConfiguration(any()) }
        coVerify(exactly = 0) { metadata.add(any(), any(), any()) }
    }
}

private suspend fun WorkOpsGuidanceService.saveCurrent(projectId: UUID?, input: bosca.workops.model.guidance.WorkOpsGuidanceInput) = save(projectId, input, effective(projectId).revision)
private suspend fun WorkOpsGuidanceService.resetCurrent(projectId: UUID?) = reset(projectId, effective(projectId).revision)
