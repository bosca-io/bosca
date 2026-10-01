package bosca.workops.service

import bosca.git.model.CommitInfo
import bosca.git.model.ComparisonResult
import bosca.git.model.DiffChangeType
import bosca.git.model.DiffFile
import bosca.git.model.DiffHunk
import bosca.git.model.DiffLine
import bosca.git.model.DiffLineType
import bosca.git.model.Repository
import bosca.git.model.TagInfo
import bosca.git.service.DiffService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationProjectLanguage
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.project.Project
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.ReleaseNotesGenerationInput
import bosca.workops.model.release.ReleaseNotesSection
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.model.task.Task
import bosca.workops.model.version.Version
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ProjectRepositoryRepository
import bosca.workops.repository.ReleaseNotesRepository
import bosca.workops.repository.ReleaseNotesTaskRepository
import bosca.workops.repository.ReleaseRepository
import bosca.workops.repository.VersionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseNotesServiceTest {

    private val repository = mockk<ReleaseNotesRepository>()
    private val releaseRepository = mockk<ReleaseRepository>()
    private val taskRepository = mockk<ReleaseNotesTaskRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val projectRepositories = mockk<ProjectRepositoryRepository>()
    private val versionRepository = mockk<VersionRepository>()
    private val gitRepositories = mockk<RepositoryService>()
    private val browseService = mockk<RepositoryBrowseService>()
    private val diffService = mockk<DiffService>()
    private val localizationService = mockk<LocalizationService>()
    private val releaseNotesAIService = mockk<ReleaseNotesAIService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val service = ReleaseNotesServiceImpl(
        repository,
        releaseRepository,
        taskRepository,
        projectRepository,
        projectRepositories,
        versionRepository,
        gitRepositories,
        browseService,
        diffService,
        localizationService,
        releaseNotesAIService,
        json,
    )

    @Test
    fun `get generate and manual edit delegate while stale edit reports not found`() = runTest {
        val releaseId = UUID.random()
        val notes = ReleaseNotes(releaseId = releaseId)
        coEvery { repository.getByRelease(releaseId) } returns notes
        coEvery { repository.upsert(releaseId, "[]") } returns notes
        coEvery { repository.editManually(releaseId, "[{\"manual\":true}]", 3) } returns notes
        coEvery { repository.editManually(releaseId, "[]", 4) } returns null

        assertEquals(notes, service.getByRelease(releaseId))
        assertEquals(notes, service.generate(releaseId, "[]"))
        assertEquals(notes, service.editManually(releaseId, "[{\"manual\":true}]", 3))
        val failure = assertFailsWith<WorkOpsNotFoundException> {
            service.editManually(releaseId, "[]", 4)
        }
        assertEquals("ReleaseNotes", failure.type)
        assertEquals(releaseId.toString(), failure.handle)
    }

    @Test
    fun `auto generate categorizes every summary variant and caches project names`() = runTest {
        val releaseId = UUID.random()
        val projectId = UUID.random()
        val missingProjectId = UUID.random()
        val firstVersionId = UUID.random()
        val secondVersionId = UUID.random()
        val missingProjectVersionId = UUID.random()
        val project = Project(
            id = projectId,
            programId = UUID.random(),
            key = "GIT",
            name = "Git Platform",
            ownerProfileId = UUID.random(),
        )
        val categorized = linkedMapOf(
            "Fix crash" to "BUG_FIX",
            "Critical bug in release" to "BUG_FIX",
            "Add deployment view" to "NEW_FEATURE",
            "A new pipeline node" to "NEW_FEATURE",
            "Breaking API change" to "BREAKING_CHANGE",
            "Deprecate legacy endpoint" to "DEPRECATION",
            "Security hardening" to "SECURITY",
            "Resolve vuln report" to "SECURITY",
            "Perf improvements" to "PERFORMANCE",
            "Docs updated" to "DOCUMENTATION",
            "Refactor release relay" to "ENHANCEMENT",
            "Clean pipeline output" to "ENHANCEMENT",
            "Miscellaneous chores" to "OTHER",
        )
        val tasks = categorized.keys.map { task(projectId, it) }
        val missingProjectTask = task(missingProjectId, "Unclassified work")
        val sections = slot<String>()
        coEvery { releaseRepository.listVersions(releaseId) } returns listOf(
            ReleaseProjectVersion(releaseId, projectId, firstVersionId),
            ReleaseProjectVersion(releaseId, projectId, secondVersionId),
            ReleaseProjectVersion(releaseId, missingProjectId, missingProjectVersionId),
        )
        coEvery { projectRepository.getById(projectId) } returns project
        coEvery { projectRepository.getById(missingProjectId) } returns null
        coEvery { taskRepository.listTasksByFixVersion(firstVersionId) } returns tasks
        coEvery { taskRepository.listTasksByFixVersion(secondVersionId) } returns emptyList()
        coEvery { taskRepository.listTasksByFixVersion(missingProjectVersionId) } returns listOf(missingProjectTask)
        coEvery { repository.upsert(releaseId, capture(sections)) } answers {
            ReleaseNotes(releaseId = releaseId, sections = json.parseToJsonElement(sections.captured))
        }

        val notes = service.autoGenerate(releaseId)

        val decoded = json.decodeFromJsonElement(
            ListSerializer(ReleaseNotesSection.serializer()),
            notes.sections,
        )
        val categoriesBySummary = decoded
            .flatMap { section -> section.entries.map { it.summary to section.category.name } }
            .toMap()
        assertEquals(categorized + ("Unclassified work" to "OTHER"), categoriesBySummary)
        assertEquals(
            "Git Platform",
            decoded.flatMap { it.entries }.first { it.summary == "Fix crash" }.projectName,
        )
        assertEquals(
            missingProjectId.toString(),
            decoded.flatMap { it.entries }.first { it.summary == "Unclassified work" }.projectName,
        )
        coVerify(exactly = 1) { projectRepository.getById(projectId) }
        coVerify(exactly = 1) { projectRepository.getById(missingProjectId) }
    }

    @Test
    fun `localized generation compares release tags feeds bounded Git context to Kit and syncs localization`() =
        runTest {
            val releaseId = UUID.random()
            val principalId = UUID.random()
            val projectId = UUID.random()
            val localizationProjectId = UUID.random()
            val repositoryId = UUID.random()
            val previous = Version(
                id = UUID.random(), projectId = projectId, name = "1.0.0", released = true, sequenceNumber = 1,
            )
            val current = Version(
                id = UUID.random(), projectId = projectId, name = "1.1.0", sequenceNumber = 2,
            )
            val project = Project(
                id = projectId,
                programId = UUID.random(),
                key = "APP",
                name = "Companion App",
                ownerProfileId = UUID.random(),
            )
            val localizationProject = LocalizationProject(
                id = localizationProjectId,
                name = "Companion App",
                sourceLanguage = "en-US",
                attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
            )
            val variants = listOf(
                LocalizedReleaseNotes("en-US", "Faster startup", "Faster startup", "Try the faster startup"),
                LocalizedReleaseNotes("fr-FR", "Démarrage rapide", "Démarrage rapide", "Testez le démarrage"),
            )
            val commit = commit("b".repeat(40), "Improve startup")
            val comparison = ComparisonResult(
                baseRef = previous.name,
                headRef = current.name,
                commits = listOf(commit),
                files = listOf(
                    DiffFile(
                        oldPath = "src/Startup.kt",
                        newPath = "src/Startup.kt",
                        changeType = DiffChangeType.MODIFY,
                        hunks = listOf(
                            DiffHunk(
                                1,
                                1,
                                1,
                                1,
                                listOf(
                                    DiffLine(DiffLineType.DELETE, 1, null, "slowStart()"),
                                    DiffLine(DiffLineType.ADD, null, 1, "fastStart()"),
                                    DiffLine(DiffLineType.CONTEXT, 2, 2, "ready()"),
                                ),
                            ),
                        ),
                    ),
                    DiffFile(
                        oldPath = "src/Removed.kt",
                        newPath = null,
                        changeType = DiffChangeType.DELETE,
                        hunks = emptyList(),
                    ),
                    DiffFile(
                        oldPath = null,
                        newPath = null,
                        changeType = DiffChangeType.MODIFY,
                        hunks = emptyList(),
                    ),
                ),
                filesChanged = 3,
                insertions = 1,
                deletions = 1,
            )
            val generationInput = slot<ReleaseNotesGenerationInput>()
            val translations = mutableListOf<LocalizationTranslationInput>()
            val localizationStringsByKey = mutableMapOf<String, LocalizationString>()
            val persistedTranslations = mutableMapOf<UUID, MutableMap<String, LocalizationTranslation>>()
            val serialized = json.encodeToJsonElement(ListSerializer(LocalizedReleaseNotes.serializer()), variants)
            val generatedNotes = VersionReleaseNotes(
                id = current.id, versionId = current.id, sourceLocale = "en-US", variants = serialized,
            )

            coEvery { releaseRepository.listVersions(releaseId) } returns
                listOf(ReleaseProjectVersion(releaseId, projectId, current.id))
            coEvery { versionRepository.getById(current.id) } returns current
            coEvery { versionRepository.listByProject(projectId) } returns listOf(previous, current)
            coEvery { projectRepository.getById(projectId) } returns project
            coEvery { localizationService.getProjects() } returns listOf(localizationProject)
            coEvery { localizationService.getProjectLanguages(localizationProjectId) } returns listOf(
                LocalizationProjectLanguage(localizationProjectId, "fr-FR"),
                LocalizationProjectLanguage(localizationProjectId, ""),
                LocalizationProjectLanguage(localizationProjectId, "en-US"),
            )
            coEvery { projectRepositories.list(projectId) } returns listOf(
                bosca.workops.model.project.ProjectRepository(
                    id = UUID.random(), projectId = projectId, repositoryId = repositoryId,
                ),
            )
            coEvery { gitRepositories.findById(repositoryId) } returns Repository(
                id = repositoryId,
                slug = "companion-app",
                name = "Companion App",
                ownerId = UUID.random(),
            )
            coEvery { browseService.listTags(repositoryId) } returns listOf(
                TagInfo(previous.name, "a".repeat(40)),
                TagInfo(current.name, "b".repeat(40)),
            )
            coEvery { browseService.compare(repositoryId, previous.name, current.name, diffService) } returns comparison
            coEvery { releaseNotesAIService.generate(capture(generationInput)) } returns variants.first()
            coEvery { localizationService.getStringByKey(localizationProjectId, any()) } answers {
                localizationStringsByKey[secondArg()]
            }
            coEvery { localizationService.addString(any()) } answers {
                val input = firstArg<bosca.localization.model.LocalizationStringInput>()
                LocalizationString(id = UUID.random(), projectId = input.projectId, key = input.key).also {
                    localizationStringsByKey[it.key] = it
                }
            }
            coEvery { localizationService.setTranslation(capture(translations), principalId) } answers {
                val input = firstArg<LocalizationTranslationInput>()
                LocalizationTranslation(
                    stringId = input.stringId,
                    languageTag = input.languageTag,
                    text = input.text,
                    origin = input.origin,
                ).also {
                    persistedTranslations.getOrPut(it.stringId) { mutableMapOf() }[it.languageTag] = it
                }
            }
            coEvery {
                localizationService.generateAITranslations(
                    localizationProjectId,
                    any(),
                    listOf("fr-FR"),
                    principalId,
                    "WorkOps version ${current.id}",
                )
            } answers {
                secondArg<List<UUID>>().map { stringId ->
                    val key = localizationStringsByKey.values.single { it.id == stringId }.key
                    val text = when {
                        key.endsWith("play-release-notes") -> variants[1].playReleaseNotes
                        key.endsWith("app-store-whats-new") -> variants[1].appStoreWhatsNew
                        else -> variants[1].testFlightWhatToTest
                    }
                    LocalizationTranslation(
                        stringId = stringId,
                        languageTag = "fr-FR",
                        text = text,
                        origin = TranslationOrigin.AI,
                    ).also {
                        persistedTranslations.getOrPut(it.stringId) { mutableMapOf() }[it.languageTag] = it
                    }
                }
            }
            coEvery { localizationService.getTranslations(any()) } answers {
                persistedTranslations[firstArg()]?.values?.toList().orEmpty()
            }

            assertEquals(listOf(generatedNotes), service.autoGenerateLocalized(releaseId, principalId))

            assertEquals("en-US", generationInput.captured.sourceLocale)
            assertEquals(previous.name, generationInput.captured.previousVersionName)
            assertEquals("companion-app", generationInput.captured.commits.single().repository)
            assertEquals("Improve startup", generationInput.captured.commits.single().message)
            val changesByPath = generationInput.captured.changes.associateBy { it.path }
            assertEquals(listOf("fastStart()"), changesByPath.getValue("src/Startup.kt").additions)
            assertEquals(listOf("slowStart()"), changesByPath.getValue("src/Startup.kt").deletions)
            assertTrue("src/Removed.kt" in changesByPath)
            assertTrue("unknown" in changesByPath)
            assertEquals(3, translations.size)
            assertTrue(translations.all { it.origin == TranslationOrigin.AI })
            assertTrue(translations.all { it.originDetail == "WorkOps version ${current.id}" })
            coVerify(exactly = 3) { localizationService.addString(any()) }
            coVerify(exactly = 1) {
                localizationService.generateAITranslations(
                    localizationProjectId, any(), listOf("fr-FR"), principalId, any(),
                )
            }
            coVerify(exactly = 0) { repository.upsert(releaseId, any()) }
        }

    @Test
    fun `first release generation lists tagged commits without comparing`() = runTest {
        val releaseId = UUID.random()
        val principalId = UUID.random()
        val projectId = UUID.random()
        val version = Version(id = UUID.random(), projectId = projectId, name = "1.0.0", sequenceNumber = 1)
        val project = Project(
            id = projectId,
            programId = UUID.random(),
            key = "APP",
            name = "App",
            ownerProfileId = UUID.random(),
        )
        val localizationProject = LocalizationProject(
            id = UUID.random(),
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        val repositoryId = UUID.random()
        val variants = listOf(LocalizedReleaseNotes("en-US", "Notes", "What's New", "Test this"))
        val stringsByKey = releaseNoteFields.associateWith { field ->
            val key = "workops.release-notes.${version.id}.$field"
            key to LocalizationString(id = UUID.random(), projectId = localizationProject.id, key = key)
        }.values.toMap()
        val persistedTranslations = mutableMapOf<UUID, MutableMap<String, LocalizationTranslation>>()
        val generationInput = slot<ReleaseNotesGenerationInput>()

        coEvery { releaseRepository.listVersions(releaseId) } returns
            listOf(ReleaseProjectVersion(releaseId, projectId, version.id))
        coEvery { versionRepository.getById(version.id) } returns version
        coEvery { versionRepository.listByProject(projectId) } returns listOf(version)
        coEvery { projectRepository.getById(projectId) } returns project
        coEvery { localizationService.getProjects() } returns listOf(localizationProject)
        coEvery { localizationService.getProjectLanguages(localizationProject.id) } returns emptyList()
        coEvery { projectRepositories.list(projectId) } returns listOf(
            bosca.workops.model.project.ProjectRepository(projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { gitRepositories.findById(repositoryId) } returns Repository(
            id = repositoryId, slug = "app", name = "App", ownerId = UUID.random(),
        )
        coEvery { browseService.listTags(repositoryId) } returns listOf(TagInfo(version.name, "a".repeat(40)))
        coEvery { browseService.listCommits(repositoryId, version.name, null, 100, 0) } returns
            listOf(commit("a".repeat(40), "Initial release"))
        coEvery { releaseNotesAIService.generate(capture(generationInput)) } returns variants.single()
        coEvery { localizationService.getStringByKey(localizationProject.id, any()) } answers {
            stringsByKey[secondArg()]
        }
        coEvery { localizationService.setTranslation(any(), principalId) } answers {
            val input = firstArg<LocalizationTranslationInput>()
            LocalizationTranslation(
                stringId = input.stringId,
                languageTag = input.languageTag,
                text = input.text,
                origin = input.origin,
            ).also {
                persistedTranslations.getOrPut(it.stringId) { mutableMapOf() }[it.languageTag] = it
            }
        }
        coEvery { localizationService.getTranslations(any()) } answers {
            persistedTranslations[firstArg()]?.values?.toList().orEmpty()
        }

        service.autoGenerateLocalized(releaseId, principalId)

        assertEquals(null, generationInput.captured.previousVersionName)
        assertEquals("Initial release", generationInput.captured.commits.single().message)
        assertEquals(emptyList(), generationInput.captured.changes)
        coVerify(exactly = 0) { browseService.compare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { localizationService.addString(any()) }
        coVerify(exactly = 0) { localizationService.generateAITranslations(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `required localized notes name every missing locale and store field`() = runTest {
        val projectId = UUID.random()
        val version = Version(id = UUID.random(), projectId = projectId, name = "1.0.0", sequenceNumber = 1)
        val localizationProject = LocalizationProject(
            id = UUID.random(),
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        val complete = LocalizedReleaseNotes("en-US", "Play", "App Store", "TestFlight")
        stubLocalizationNotes(version, localizationProject, listOf(complete))

        assertEquals(listOf(complete), service.requireLocalized(version.id, listOf("en-US", "en-US")))
        val unrelatedDraft = stubLocalizationNotes(
            version,
            localizationProject,
            listOf(complete, LocalizedReleaseNotes("es-ES", "Play ES", "App ES", "Test ES")),
        )
        unrelatedDraft.translationsByString.values.forEach { translations ->
            translations["es-ES"] = requireNotNull(translations["es-ES"]).copy(state = TranslationState.DRAFT)
        }
        assertEquals(listOf(complete), service.requireLocalized(version.id, listOf("en-US")))
        assertTrue(
            "at least one" in assertFailsWith<IllegalArgumentException> {
                service.requireLocalized(version.id, emptyList())
            }.message.orEmpty(),
        )
        assertTrue(
            "fr-FR" in assertFailsWith<IllegalStateException> {
                service.requireLocalized(version.id, listOf("fr-FR"))
            }.message.orEmpty(),
        )

        listOf(
            complete.copy(playReleaseNotes = "") to "playReleaseNotes",
            complete.copy(appStoreWhatsNew = "") to "appStoreWhatsNew",
            complete.copy(testFlightWhatToTest = "") to "testFlightWhatToTest",
        ).forEach { (incomplete, field) ->
            stubLocalizationNotes(version, localizationProject, listOf(incomplete))
            val error = assertFailsWith<IllegalArgumentException> {
                service.requireLocalized(version.id, listOf("en-US"))
            }
            assertTrue("en-US" in error.message.orEmpty())
            assertTrue(field in error.message.orEmpty())
        }

        val missingField = stubLocalizationNotes(version, localizationProject, listOf(complete))
        val playString = missingField.stringsByKey.values.single { it.key.endsWith("play-release-notes") }
        missingField.translationsByString.getValue(playString.id).remove("en-US")
        assertTrue(
            "playReleaseNotes" in assertFailsWith<IllegalArgumentException> {
                service.requireLocalized(version.id, listOf("en-US"))
            }.message.orEmpty(),
        )

        coEvery { localizationService.getStringByKey(localizationProject.id, any()) } returns null
        assertTrue(
            "missing release notes" in assertFailsWith<IllegalStateException> {
                service.requireLocalized(version.id, listOf("en-US"))
            }.message.orEmpty(),
        )

        stubLocalizationNotes(
            version,
            localizationProject,
            listOf(complete),
            state = TranslationState.AI_GENERATED,
        )
        assertTrue(
            "must be PUBLISHED" in assertFailsWith<IllegalStateException> {
                service.requireLocalized(version.id, listOf("en-US"))
            }.message.orEmpty(),
        )
    }

    @Test
    fun `manual version edit validates bundle and source then records human localization history`() = runTest {
        val releaseId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val localizationProjectId = UUID.random()
        val principalId = UUID.random()
        val version = Version(id = versionId, projectId = projectId, name = "2.0.0", sequenceNumber = 2)
        val localizationProject = LocalizationProject(
            id = localizationProjectId,
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        val variants = listOf(
            LocalizedReleaseNotes("en-US", "Play", "App Store", "TestFlight"),
            LocalizedReleaseNotes("es-ES", "Play ES", "App Store ES", "TestFlight ES"),
        )
        val encoded = json.encodeToString(ListSerializer(LocalizedReleaseNotes.serializer()), variants)
        coEvery { releaseRepository.listVersions(releaseId) } returns
            listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        val localization = stubLocalizationNotes(version, localizationProject, variants)

        val edited = service.editVersionNotes(releaseId, versionId, encoded, principalId)
        assertEquals(versionId, edited.id)
        assertEquals("en-US", edited.sourceLocale)
        assertTrue(edited.manuallyEdited)
        assertEquals(json.parseToJsonElement(encoded), edited.variants)
        assertEquals(listOf(edited), service.listVersionNotes(releaseId))
        assertEquals(6, localization.writes.size)
        assertTrue(localization.writes.all { it.origin == TranslationOrigin.HUMAN })

        coEvery { localizationService.getTranslations(any()) } returns emptyList()
        val persistenceFailure = assertFailsWith<IllegalStateException> {
            service.editVersionNotes(releaseId, versionId, encoded, principalId)
        }
        assertTrue("did not persist edited release notes" in persistenceFailure.message.orEmpty())
    }

    @Test
    fun `manual version edit rejects unbundled duplicate incomplete and unlinked drafts`() = runTest {
        val releaseId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val principalId = UUID.random()
        val complete = LocalizedReleaseNotes("en-US", "Play", "App Store", "TestFlight")
        fun encode(variants: List<LocalizedReleaseNotes>) =
            json.encodeToString(ListSerializer(LocalizedReleaseNotes.serializer()), variants)

        coEvery { releaseRepository.listVersions(releaseId) } returns emptyList()
        assertTrue(
            "not bundled" in assertFailsWith<IllegalArgumentException> {
                service.editVersionNotes(releaseId, versionId, encode(listOf(complete)), principalId)
            }.message.orEmpty(),
        )

        coEvery { releaseRepository.listVersions(releaseId) } returns
            listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        val version = Version(id = versionId, projectId = projectId, name = "2.0.0", sequenceNumber = 2)
        val localizationProject = LocalizationProject(
            id = UUID.random(),
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        coEvery { versionRepository.getById(versionId) } returns version
        coEvery { localizationService.getProjects() } returns listOf(localizationProject)
        coEvery { localizationService.getProjectLanguages(localizationProject.id) } returns emptyList()
        val invalid = listOf(
            listOf(complete, complete.copy(playReleaseNotes = "Duplicate")) to "duplicate locales",
            listOf(complete.copy(locale = " ")) to "blank locale",
            listOf(complete.copy(playReleaseNotes = " ")) to "playReleaseNotes",
            listOf(complete.copy(appStoreWhatsNew = " ")) to "appStoreWhatsNew",
            listOf(complete.copy(testFlightWhatToTest = " ")) to "testFlightWhatToTest",
        )
        invalid.forEach { (variants, expected) ->
            assertTrue(
                expected in assertFailsWith<IllegalArgumentException> {
                    service.editVersionNotes(releaseId, versionId, encode(variants), principalId)
                }.message.orEmpty(),
            )
        }
        assertTrue(
            "unconfigured locales" in assertFailsWith<IllegalArgumentException> {
                service.editVersionNotes(
                    releaseId,
                    versionId,
                    encode(listOf(complete, complete.copy(locale = "fr-FR"))),
                    principalId,
                )
            }.message.orEmpty(),
        )

        coEvery { versionRepository.getById(versionId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.editVersionNotes(releaseId, versionId, encode(listOf(complete)), principalId)
        }

        coEvery { versionRepository.getById(versionId) } returns version
        coEvery { localizationService.getProjects() } returns emptyList()
        assertTrue(
            "no linked localization project" in assertFailsWith<IllegalStateException> {
                service.editVersionNotes(releaseId, versionId, encode(listOf(complete)), principalId)
            }.message.orEmpty(),
        )
    }

    @Test
    fun `localized generation fails loudly when release tag ownership or Kit output is incomplete`() = runTest {
        val releaseId = UUID.random()
        val principalId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val repositoryId = UUID.random()
        val version = Version(id = versionId, projectId = projectId, name = "3.0.0", sequenceNumber = 3)
        val project = Project(
            id = projectId,
            programId = UUID.random(),
            key = "APP",
            name = "App",
            ownerProfileId = UUID.random(),
        )
        val localizationProject = LocalizationProject(
            id = UUID.random(),
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        val link = bosca.workops.model.project.ProjectRepository(
            projectId = projectId, repositoryId = repositoryId,
        )
        val gitRepository = Repository(
            id = repositoryId, slug = "app", name = "App", ownerId = UUID.random(),
        )

        coEvery { releaseRepository.listVersions(releaseId) } returns emptyList()
        assertTrue(
            "no bundled versions" in assertFailsWith<IllegalArgumentException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { releaseRepository.listVersions(releaseId) } returns
            listOf(ReleaseProjectVersion(releaseId, projectId, versionId))
        coEvery { localizationService.getProjects() } returns emptyList()
        coEvery { versionRepository.getById(versionId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.autoGenerateLocalized(releaseId, principalId) }

        coEvery { versionRepository.getById(versionId) } returns version
        coEvery { projectRepository.getById(projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.autoGenerateLocalized(releaseId, principalId) }

        coEvery { projectRepository.getById(projectId) } returns project
        coEvery { localizationService.getProjects() } returns emptyList()
        assertTrue(
            "no localization project" in assertFailsWith<IllegalStateException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { localizationService.getProjects() } returns listOf(localizationProject.copy(sourceLanguage = " "))
        coEvery { localizationService.getProjectLanguages(localizationProject.id) } returns emptyList()
        assertTrue(
            "no configured release-note locales" in assertFailsWith<IllegalArgumentException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { localizationService.getProjects() } returns listOf(localizationProject)
        coEvery { localizationService.getProjectLanguages(localizationProject.id) } returns emptyList()
        coEvery { versionRepository.listByProject(projectId) } returns listOf(version)
        coEvery { projectRepositories.list(projectId) } returns emptyList()
        assertTrue(
            "no Git repositories" in assertFailsWith<IllegalArgumentException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { projectRepositories.list(projectId) } returns listOf(link)
        coEvery { gitRepositories.findById(repositoryId) } returns null
        assertTrue(
            "was not found" in assertFailsWith<IllegalStateException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { gitRepositories.findById(repositoryId) } returns gitRepository
        coEvery { browseService.listTags(repositoryId) } returns emptyList()
        assertTrue(
            "missing release tag '3.0.0'" in assertFailsWith<IllegalStateException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { browseService.listTags(repositoryId) } returns listOf(TagInfo(version.name, "c".repeat(40)))
        val previous = Version(
            id = UUID.random(), projectId = projectId, name = "2.0.0", released = true, sequenceNumber = 2,
        )
        coEvery { versionRepository.listByProject(projectId) } returns listOf(previous, version)
        assertTrue(
            "missing prior release tag '2.0.0'" in assertFailsWith<IllegalStateException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { versionRepository.listByProject(projectId) } returns listOf(version)
        coEvery { browseService.listCommits(repositoryId, version.name, null, 100, 0) } returns emptyList()
        coEvery { releaseNotesAIService.generate(any()) } returns
            LocalizedReleaseNotes("fr-FR", "Play", "App Store", "TestFlight")
        assertTrue(
            "missing configured locale 'en-US'" in assertFailsWith<IllegalArgumentException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )

        coEvery { releaseNotesAIService.generate(any()) } returns
            LocalizedReleaseNotes("en-US", " ", "App Store", "TestFlight")
        assertTrue(
            "playReleaseNotes" in assertFailsWith<IllegalArgumentException> {
                service.autoGenerateLocalized(releaseId, principalId)
            }.message.orEmpty(),
        )
    }

    @Test
    fun `version note reads distinguish unlinked empty partial target-only and complete localization state`() = runTest {
        val releaseId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val version = Version(id = versionId, projectId = projectId, name = "4.0.0", sequenceNumber = 4)
        val localizationProject = LocalizationProject(
            id = UUID.random(),
            name = "App",
            sourceLanguage = "en-US",
            attributes = buildJsonObject { put("workopsProjectId", projectId.toString()) },
        )
        val bundled = ReleaseProjectVersion(releaseId, projectId, versionId)
        coEvery { releaseRepository.listVersions(releaseId) } returns listOf(bundled)

        coEvery { versionRepository.getById(versionId) } returns null
        coEvery { localizationService.getProjects() } returns emptyList()
        assertFailsWith<WorkOpsNotFoundException> { service.listVersionNotes(releaseId) }
        assertFailsWith<WorkOpsNotFoundException> { service.requireLocalized(versionId, listOf("en-US")) }

        coEvery { versionRepository.getById(versionId) } returns version
        coEvery { localizationService.getProjects() } returns listOf(
            localizationProject.copy(attributes = JsonPrimitive("not-an-object")),
            localizationProject.copy(
                id = UUID.random(),
                attributes = buildJsonObject { put("workopsProjectId", buildJsonObject {}) },
            ),
            localizationProject.copy(
                id = UUID.random(),
                attributes = buildJsonObject { put("workopsProjectId", kotlinx.serialization.json.JsonNull) },
            ),
        )
        assertTrue(service.listVersionNotes(releaseId).isEmpty())

        coEvery { localizationService.getProjects() } returns listOf(localizationProject)
        coEvery { localizationService.getStringByKey(localizationProject.id, any()) } returns null
        assertTrue(service.listVersionNotes(releaseId).isEmpty())

        val strings = releaseNoteFields.associateWith { field ->
            LocalizationString(
                id = UUID.random(),
                projectId = localizationProject.id,
                key = "workops.release-notes.$versionId.$field",
            )
        }
        coEvery { localizationService.getStringByKey(localizationProject.id, any()) } answers {
            if (secondArg<String>().endsWith(releaseNoteFields.first())) {
                strings.getValue(releaseNoteFields.first())
            } else {
                null
            }
        }
        val partial = assertFailsWith<IllegalArgumentException> { service.listVersionNotes(releaseId) }
        assertTrue("missing localization fields" in partial.message.orEmpty())

        coEvery { localizationService.getStringByKey(localizationProject.id, any()) } answers {
            strings.entries.single { secondArg<String>().endsWith(it.key) }.value
        }
        coEvery { localizationService.getProjectLanguages(localizationProject.id) } returns listOf(
            LocalizationProjectLanguage(localizationProject.id, "fr-FR"),
        )
        coEvery { localizationService.getTranslations(any()) } returns emptyList()
        assertTrue(service.listVersionNotes(releaseId).isEmpty())

        strings.values.forEach { string ->
            coEvery { localizationService.getTranslations(string.id) } returns listOf(
                LocalizationTranslation(
                    stringId = string.id,
                    languageTag = "fr-FR",
                    text = "French notes",
                    origin = TranslationOrigin.AI,
                ),
            )
        }
        val missingSource = assertFailsWith<IllegalArgumentException> { service.listVersionNotes(releaseId) }
        assertTrue("missing source locale 'en-US'" in missingSource.message.orEmpty())

        strings.forEach { (field, string) ->
            coEvery { localizationService.getTranslations(string.id) } returns listOf(
                LocalizationTranslation(
                    stringId = string.id,
                    languageTag = "en-US",
                    text = "English $field",
                    origin = TranslationOrigin.AI,
                ),
            )
        }
        val notes = service.listVersionNotes(releaseId).single()
        assertEquals(versionId, notes.versionId)
        assertEquals("en-US", notes.sourceLocale)
        assertEquals(false, notes.manuallyEdited)
    }

    private data class LocalizationNotesStub(
        val stringsByKey: Map<String, LocalizationString>,
        val translationsByString: MutableMap<UUID, MutableMap<String, LocalizationTranslation>>,
        val writes: MutableList<LocalizationTranslationInput>,
    )

    private fun stubLocalizationNotes(
        version: Version,
        project: LocalizationProject,
        variants: List<LocalizedReleaseNotes>,
        origin: TranslationOrigin = TranslationOrigin.AI,
        state: TranslationState = TranslationState.PUBLISHED,
        configuredTargetLocales: List<String> = variants.map { it.locale }.filter { it != project.sourceLanguage },
    ): LocalizationNotesStub {
        val stringsByKey = releaseNoteFields.associate { field ->
            val key = "workops.release-notes.${version.id}.$field"
            key to LocalizationString(id = UUID.random(), projectId = project.id, key = key)
        }
        val translationsByString = mutableMapOf<UUID, MutableMap<String, LocalizationTranslation>>()
        stringsByKey.forEach { (key, string) ->
            val field = releaseNoteFields.single { key.endsWith(it) }
            variants.forEach { variant ->
                val text = when (field) {
                    "play-release-notes" -> variant.playReleaseNotes
                    "app-store-whats-new" -> variant.appStoreWhatsNew
                    else -> variant.testFlightWhatToTest
                }
                translationsByString.getOrPut(string.id) { mutableMapOf() }[variant.locale] =
                    LocalizationTranslation(
                        stringId = string.id,
                        languageTag = variant.locale,
                        text = text,
                        state = state,
                        origin = origin,
                    )
            }
        }
        val writes = mutableListOf<LocalizationTranslationInput>()
        coEvery { versionRepository.getById(version.id) } returns version
        coEvery { localizationService.getProjects() } returns listOf(project)
        coEvery { localizationService.getProjectLanguages(project.id) } returns
            configuredTargetLocales.distinct().map { LocalizationProjectLanguage(project.id, it) }
        coEvery { localizationService.getStringByKey(project.id, any()) } answers {
            stringsByKey[secondArg()]
        }
        coEvery { localizationService.getTranslations(any()) } answers {
            translationsByString[firstArg()]?.values?.toList().orEmpty()
        }
        coEvery { localizationService.setTranslation(capture(writes), any()) } answers {
            val input = firstArg<LocalizationTranslationInput>()
            LocalizationTranslation(
                stringId = input.stringId,
                languageTag = input.languageTag,
                text = input.text,
                origin = input.origin,
            ).also {
                translationsByString.getOrPut(it.stringId) { mutableMapOf() }[it.languageTag] = it
            }
        }
        return LocalizationNotesStub(stringsByKey, translationsByString, writes)
    }

    private fun commit(sha: String, message: String) = CommitInfo(
        sha = sha,
        message = message,
        authorName = "Author",
        authorEmail = "author@example.com",
        authorDate = "2026-07-22T00:00:00Z",
        committerName = "Committer",
        committerEmail = "committer@example.com",
        committerDate = "2026-07-22T00:00:00Z",
    )

    private fun task(projectId: UUID, summary: String): Task {
        val principalId = UUID.random()
        return Task(
            id = UUID.random(),
            key = "GIT-${summary.hashCode().toUInt()}",
            projectId = projectId,
            taskTypeId = UUID.random(),
            statusId = UUID.random(),
            priorityId = UUID.random(),
            summary = summary,
            reporterProfileId = UUID.random(),
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }

    private companion object {
        val releaseNoteFields = listOf(
            "play-release-notes",
            "app-store-whats-new",
            "testflight-what-to-test",
        )
    }
}
