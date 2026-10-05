@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.localization.service

import bosca.di.annotation.InternalDI
import bosca.graphql.Batch
import bosca.localization.LocalizationTestFixture
import bosca.localization.export.ExportEngine
import bosca.localization.model.LocalizationAITranslationRequest
import bosca.localization.model.LocalizationAITranslationResult
import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportRequest
import bosca.localization.model.LocalizationDocumentTranslationInput
import bosca.localization.model.LocalizationPluralTranslationInput
import bosca.localization.model.LocalizationPlaceholder
import bosca.localization.model.LocalizationProjectDocumentInput
import bosca.localization.model.PluralCategory
import bosca.localization.model.LocalizationProjectInput
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.repository.LocalizationDocumentTranslationRepositoryImpl
import bosca.localization.repository.LocalizationHistoryRepositoryImpl
import bosca.localization.repository.LocalizationPluralTranslationRepositoryImpl
import bosca.localization.repository.LocalizationProjectDocumentRepositoryImpl
import bosca.localization.repository.LocalizationProjectPermissionRepositoryImpl
import bosca.localization.repository.LocalizationProjectFormatRepositoryImpl
import bosca.localization.repository.LocalizationProjectLanguageRepositoryImpl
import bosca.localization.repository.LocalizationProjectRepositoryImpl
import bosca.localization.repository.LocalizationStringMetadataRepositoryImpl
import bosca.localization.repository.LocalizationStringRepositoryImpl
import bosca.localization.repository.LocalizationTranslationRepositoryImpl
import bosca.localization.security.ProjectGroupNames
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Integration tests for [LocalizationServiceImpl] against a real Postgres. These
 * tests assert the behaviour that the GraphQL / admin UI layers rely on:
 *
 *  - projects materialise with their three project-scoped security groups and
 *    default permission grants on insert
 *  - translation origin drives the initial workflow state
 *  - every state change and text edit appends a `translation_history` row
 *  - bulkTransition walks matching rows, transitions each, and records history
 *  - state-machine violations fail loudly instead of persisting garbage
 *  - addPermission / deletePermission invalidate the permission cache
 *  - getTranslationProgress aggregates state and origin counters
 *
 * SecurityService is the only mocked collaborator — it normally provisions the
 * groups that [LocalizationServiceImpl.addProject] inserts, and we verify those
 * calls directly rather than materialising a full SecurityServiceImpl.
 */
class LocalizationServiceIntegrationTest {

    private lateinit var service: LocalizationServiceImpl
    private lateinit var securityService: SecurityService
    private lateinit var localizationAIService: LocalizationAIService
    private val viewerGroupId = UUID.random()
    private val contributorGroupId = UUID.random()
    private val managerGroupId = UUID.random()

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        LocalizationTestFixture.seedLanguage("es")
        LocalizationTestFixture.seedLanguage("fr")

        securityService = mockk(relaxed = false)
        localizationAIService = mockk(relaxed = false)
        val groupSlot = slot<Group>()
        coEvery { securityService.addGroup(capture(groupSlot)) } answers {
            val captured = groupSlot.captured
            val id = when {
                captured.name.startsWith("translator-viewer:") -> viewerGroupId
                captured.name.startsWith("translator-contributor:") -> contributorGroupId
                captured.name.startsWith("translator-manager:") -> managerGroupId
                else -> UUID.random()
            }
            captured.copy(id = id)
        }
        coEvery { securityService.getGroupByName(any(), any()) } answers {
            val name = firstArg<String>()
            val id = when {
                name.startsWith("translator-viewer:") -> viewerGroupId
                name.startsWith("translator-contributor:") -> contributorGroupId
                name.startsWith("translator-manager:") -> managerGroupId
                else -> null
            }
            id?.let { Group(id = it, name = name, description = "", type = GroupType.SYSTEM) }
        }
        coEvery { securityService.deleteGroup(any()) } returns Unit

        service = LocalizationServiceImpl(
            projectRepository = LocalizationProjectRepositoryImpl(),
            permissionRepository = LocalizationProjectPermissionRepositoryImpl(),
            stringRepository = LocalizationStringRepositoryImpl(),
            translationRepository = LocalizationTranslationRepositoryImpl(),
            pluralTranslationRepository = LocalizationPluralTranslationRepositoryImpl(),
            projectDocumentRepository = LocalizationProjectDocumentRepositoryImpl(),
            documentTranslationRepository = LocalizationDocumentTranslationRepositoryImpl(),
            historyRepository = LocalizationHistoryRepositoryImpl(),
            projectLanguageRepository = LocalizationProjectLanguageRepositoryImpl(),
            projectFormatRepository = LocalizationProjectFormatRepositoryImpl(),
            stringMetadataRepository = LocalizationStringMetadataRepositoryImpl(),
            securityService = securityService,
            localizationAIService = localizationAIService,
            exportEngine = ExportEngine(),
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        )
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    // ---- Project lifecycle --------------------------------------------------

    @Test
    fun `addProject creates three project-scoped groups with the correct permission grants`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "Test App", sourceLanguage = "en"))

        // Exactly three groups were provisioned with the spec'd names.
        coVerify(exactly = 1) { securityService.addGroup(match { it.name == ProjectGroupNames.viewer(project.id) && it.type == GroupType.SYSTEM }) }
        coVerify(exactly = 1) { securityService.addGroup(match { it.name == ProjectGroupNames.contributor(project.id) && it.type == GroupType.SYSTEM }) }
        coVerify(exactly = 1) { securityService.addGroup(match { it.name == ProjectGroupNames.manager(project.id) && it.type == GroupType.SYSTEM }) }

        // Permission grants landed in the ACL table keyed by group → action.
        val permissions = service.getPermissions(project).associateBy { it.groupId }
        assertEquals(PermissionAction.VIEW, permissions[viewerGroupId]!!.action)
        assertEquals(PermissionAction.EDIT, permissions[contributorGroupId]!!.action)
        assertEquals(PermissionAction.MANAGE, permissions[managerGroupId]!!.action)
    }

    @Test
    fun `editProject updates mutable fields and preserves id`() = LocalizationTestFixture.withDb {
        val created = service.addProject(LocalizationProjectInput(name = "Original", sourceLanguage = "en"))
        val updated = service.editProject(created.id, LocalizationProjectInput(name = "Renamed", description = "new", sourceLanguage = "es"))
        assertEquals(created.id, updated.id)
        assertEquals("Renamed", updated.name)
        assertEquals("new", updated.description)
        assertEquals("es", updated.sourceLanguage)
    }

    @Test
    fun `editProject throws NoSuchElementException for a missing id`() = LocalizationTestFixture.withDb {
        assertFailsWith<NoSuchElementException> {
            service.editProject(UUID.random(), LocalizationProjectInput(name = "x", sourceLanguage = "en"))
        }
    }

    @Test
    fun `deleteProject cascades and the permission cache sees the gone project`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "X", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id
        service.setTranslation(LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "x"), createdBy = null)

        service.deleteProject(project.id)

        assertEquals(emptyList(), service.getStrings(project.id, 0, 100))
        assertTrue(service.getPermissions(project).isEmpty())
    }

    // ---- Translation workflow ----------------------------------------------

    @Test
    fun `setTranslation with AI origin enters AI_GENERATED state so review is forced`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "greet")).id

        val translation = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "hola", origin = TranslationOrigin.AI, originDetail = "gpt-4"),
            createdBy = null
        )
        assertEquals(TranslationState.AI_GENERATED, translation.state, "AI origin must enter AI_GENERATED")
    }

    @Test
    fun `generateAITranslations owns validation persistence workflow and attribution`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        service.addProjectLanguage(project.id, "es")
        service.addProjectLanguage(project.id, "fr")
        val greeting = service.addString(
            LocalizationStringInput(projectId = project.id, key = "greeting", context = "Friendly welcome"),
        )
        val farewell = service.addString(LocalizationStringInput(projectId = project.id, key = "farewell"))
        service.setTranslation(
            LocalizationTranslationInput(greeting.id, "en", "Hello"), createdBy = null,
        )
        service.setTranslation(
            LocalizationTranslationInput(farewell.id, "en", "Goodbye"), createdBy = null,
        )
        val request = slot<LocalizationAITranslationRequest>()
        coEvery { localizationAIService.translate(capture(request)) } returns listOf(
            LocalizationAITranslationResult(greeting.id, "es", "Hola"),
            LocalizationAITranslationResult(farewell.id, "es", "Adiós"),
            LocalizationAITranslationResult(greeting.id, "fr", "Bonjour"),
            LocalizationAITranslationResult(farewell.id, "fr", "Au revoir"),
        )
        val creator = UUID.random()

        val translations = service.generateAITranslations(
            project.id,
            listOf(greeting.id, farewell.id, greeting.id),
            listOf("es", "fr", "es"),
            creator,
            "release notes",
        )

        assertEquals("en", request.captured.sourceLanguageTag)
        assertEquals(listOf("es", "fr"), request.captured.targetLanguageTags)
        assertEquals(listOf("Hello", "Goodbye"), request.captured.sources.map { it.text })
        assertEquals("Friendly welcome", request.captured.sources.first().context)
        assertEquals(listOf("es", "es", "fr", "fr"), translations.map { it.languageTag })
        assertTrue(translations.all { it.origin == TranslationOrigin.AI })
        assertTrue(translations.all { it.originDetail == "release notes" })
        assertTrue(translations.all { it.createdBy == creator })
        assertTrue(translations.all { it.state == TranslationState.AI_GENERATED })
        assertTrue(translations.all { service.getTranslationHistory(it.id).size == 1 })
    }

    @Test
    fun `generateAITranslations rejects invalid targets and incomplete provider output`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        service.addProjectLanguage(project.id, "es")
        val string = service.addString(LocalizationStringInput(projectId = project.id, key = "greeting"))
        service.setTranslation(
            LocalizationTranslationInput(string.id, "en", "Hello"), createdBy = null,
        )

        assertFailsWith<IllegalArgumentException> {
            service.generateAITranslations(project.id, listOf(string.id), listOf(""), null)
        }
        assertFailsWith<IllegalArgumentException> {
            service.generateAITranslations(project.id, listOf(string.id), listOf("en"), null)
        }
        assertFailsWith<IllegalArgumentException> {
            service.generateAITranslations(project.id, listOf(string.id), listOf("fr"), null)
        }

        coEvery { localizationAIService.translate(any()) } returns emptyList()
        val error = assertFailsWith<IllegalArgumentException> {
            service.generateAITranslations(project.id, listOf(string.id), listOf("es"), null)
        }
        assertTrue("incomplete result set" in error.message.orEmpty())
        coVerify(exactly = 1) { localizationAIService.translate(any()) }
    }

    @Test
    fun `setTranslation with human origin enters DRAFT`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id

        val translation = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "hola"),
            createdBy = UUID.random()
        )
        assertEquals(TranslationState.DRAFT, translation.state)
    }

    @Test
    fun `setTranslation appends history only when text or state actually changes`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id
        val creator = UUID.random()

        val first = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "hola"),
            createdBy = creator
        )
        val afterFirst = service.getTranslationHistory(first.id).size

        // Re-set with identical text and state — this is the no-op path. No new history row.
        service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "hola"),
            createdBy = creator
        )
        assertEquals(afterFirst, service.getTranslationHistory(first.id).size, "setting to the same text must not append history")

        // Changing the text should append.
        service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "adios"),
            createdBy = creator
        )
        val log = service.getTranslationHistory(first.id)
        assertEquals(afterFirst + 1, log.size)
        assertEquals("hola", log.last().previousText, "history must capture the previous text")
        assertEquals("adios", log.last().newText)
    }

    @Test
    fun `setTranslation preserves createdBy from the original insert on a later edit that omits createdBy`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id
        val author = UUID.random()

        val first = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "1"),
            createdBy = author
        )
        // Subsequent edit with no createdBy (e.g. system-driven sync update).
        val second = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "2"),
            createdBy = null
        )
        assertEquals(first.id, second.id)
        assertEquals(author, second.createdBy, "a null createdBy on a later edit must not erase the original author")
    }

    @Test
    fun `transitionTranslation enforces the state machine`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id
        val t = service.setTranslation(
            LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "hola"),
            createdBy = null
        )

        // DRAFT → PUBLISHED is not allowed; must not silently succeed.
        assertFailsWith<IllegalStateException> {
            service.transitionTranslation(t.id, TranslationState.PUBLISHED, null)
        }

        // DRAFT → IN_REVIEW is allowed.
        val inReview = service.transitionTranslation(t.id, TranslationState.IN_REVIEW, null)
        assertEquals(TranslationState.IN_REVIEW, inReview.state)

        // IN_REVIEW → APPROVED with a reviewer records the reviewer.
        val reviewer = UUID.random()
        val approved = service.transitionTranslation(t.id, TranslationState.APPROVED, reviewer)
        assertEquals(TranslationState.APPROVED, approved.state)
        assertEquals(reviewer, approved.reviewedBy)

        // APPROVED → PUBLISHED.
        val published = service.transitionTranslation(t.id, TranslationState.PUBLISHED, reviewer)
        assertEquals(TranslationState.PUBLISHED, published.state)

        // History captures the transitions.
        val log = service.getTranslationHistory(t.id)
        val toStates = log.map { it.toState }
        assertTrue(toStates.contains(TranslationState.IN_REVIEW))
        assertTrue(toStates.contains(TranslationState.APPROVED))
        assertTrue(toStates.contains(TranslationState.PUBLISHED))
    }

    @Test
    fun `bulkTransition walks only rows in the source state and appends a history row per transition`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringA = service.addString(LocalizationStringInput(projectId = project.id, key = "a")).id
        val stringB = service.addString(LocalizationStringInput(projectId = project.id, key = "b")).id
        val stringC = service.addString(LocalizationStringInput(projectId = project.id, key = "c")).id

        // Two APPROVED, one DRAFT — only the APPROVED ones should transition.
        val ta = service.setTranslation(LocalizationTranslationInput(stringId = stringA, languageTag = "es", text = "a"), null)
        val tb = service.setTranslation(LocalizationTranslationInput(stringId = stringB, languageTag = "es", text = "b"), null)
        val tc = service.setTranslation(LocalizationTranslationInput(stringId = stringC, languageTag = "es", text = "c"), null)
        service.transitionTranslation(ta.id, TranslationState.IN_REVIEW, null)
        service.transitionTranslation(ta.id, TranslationState.APPROVED, UUID.random())
        service.transitionTranslation(tb.id, TranslationState.IN_REVIEW, null)
        service.transitionTranslation(tb.id, TranslationState.APPROVED, UUID.random())

        val affected = service.bulkTransition(project.id, "es", TranslationState.APPROVED, TranslationState.PUBLISHED, UUID.random())
        assertEquals(2, affected, "only the two APPROVED rows must be affected; the DRAFT one is skipped")

        // Confirm the DRAFT row is untouched.
        val cState = service.getTranslationById(tc.id)!!.state
        assertEquals(TranslationState.DRAFT, cState, "the DRAFT row must not transition")

        // A history row was appended to each affected translation.
        assertTrue(service.getTranslationHistory(ta.id).any { it.toState == TranslationState.PUBLISHED })
        assertTrue(service.getTranslationHistory(tb.id).any { it.toState == TranslationState.PUBLISHED })
    }

    @Test
    fun `bulkTransition rejects state-machine-invalid transitions without touching any row`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "k")).id
        service.setTranslation(LocalizationTranslationInput(stringId = stringId, languageTag = "es", text = "x"), null)

        assertFailsWith<IllegalStateException> {
            service.bulkTransition(project.id, "es", TranslationState.DRAFT, TranslationState.PUBLISHED, null)
        }
    }

    // ---- Plural translation -------------------------------------------------

    @Test
    fun `plural translation carries its own history and transition lifecycle`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(LocalizationStringInput(projectId = project.id, key = "items", plural = true)).id

        val plural = service.setPluralTranslation(
            LocalizationPluralTranslationInput(stringId = stringId, languageTag = "es", pluralCategory = PluralCategory.ONE, text = "1 item"),
            createdBy = UUID.random()
        )
        assertEquals(TranslationState.DRAFT, plural.state)

        service.transitionPluralTranslation(plural.id, TranslationState.IN_REVIEW, null)
        val reviewer = UUID.random()
        val approved = service.transitionPluralTranslation(plural.id, TranslationState.APPROVED, reviewer)
        assertEquals(TranslationState.APPROVED, approved.state)
        assertEquals(reviewer, approved.reviewedBy)
    }

    @Test
    fun `plural forms may omit count but must preserve other declared placeholders`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringId = service.addString(
            LocalizationStringInput(
                projectId = project.id,
                key = "items",
                plural = true,
                placeholders = listOf(
                    LocalizationPlaceholder("count", "number"),
                    LocalizationPlaceholder("name"),
                ),
            ),
        ).id

        assertFailsWith<IllegalArgumentException> {
            service.setPluralTranslation(
                LocalizationPluralTranslationInput(
                    stringId = stringId,
                    languageTag = "es",
                    pluralCategory = PluralCategory.ONE,
                    text = "Un elemento",
                ),
                createdBy = null,
            )
        }

        service.setPluralTranslation(
            LocalizationPluralTranslationInput(
                stringId = stringId,
                languageTag = "es",
                pluralCategory = PluralCategory.ONE,
                text = "Un elemento para {name}",
            ),
            createdBy = null,
        )
    }

    // ---- Document translation ----------------------------------------------

    @Test
    fun `document translation records origin-driven state and appends history on content change`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val document = service.addProjectDocument(
            LocalizationProjectDocumentInput(projectId = project.id, metadataId = UUID.random())
        )

        val first = service.setDocumentTranslation(
            LocalizationDocumentTranslationInput(
                documentId = document.id,
                languageTag = "es",
                content = buildJsonObject { put("type", JsonPrimitive("doc")) },
                origin = TranslationOrigin.AI
            ),
            createdBy = null
        )
        assertEquals(TranslationState.AI_GENERATED, first.state)

        // Same content on a second call: no new history row.
        service.setDocumentTranslation(
            LocalizationDocumentTranslationInput(
                documentId = document.id,
                languageTag = "es",
                content = buildJsonObject { put("type", JsonPrimitive("doc")) },
                origin = TranslationOrigin.AI
            ),
            createdBy = null
        )
        val afterFirst = service.getTranslationHistory(first.id).size

        // Content change: history grows.
        service.setDocumentTranslation(
            LocalizationDocumentTranslationInput(
                documentId = document.id,
                languageTag = "es",
                content = buildJsonObject { put("type", JsonPrimitive("doc")); put("changed", JsonPrimitive(true)) },
                origin = TranslationOrigin.AI
            ),
            createdBy = null
        )
        assertEquals(afterFirst + 1, service.getTranslationHistory(first.id).size)
    }

    // ---- Permission cache ---------------------------------------------------

    @Test
    fun `addPermission and deletePermission invalidate the permission cache correctly`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val firstGrantCount = service.getPermissions(project).size

        val newGroup = UUID.random()
        service.addPermission(project.id, newGroup, PermissionAction.EDIT)
        assertEquals(firstGrantCount + 1, service.getPermissions(project).size, "cache must see the new grant immediately")

        service.deletePermission(project.id, newGroup, PermissionAction.EDIT)
        assertEquals(firstGrantCount, service.getPermissions(project).size, "cache must see the revoke immediately")
    }

    @Test
    fun `addPermissionsToBatch populates batch with per-project permission lists`() = LocalizationTestFixture.withDb {
        val p1 = service.addProject(LocalizationProjectInput(name = "A", sourceLanguage = "en"))
        val p2 = service.addProject(LocalizationProjectInput(name = "B", sourceLanguage = "en"))
        val batch = Batch<UUID, List<EntityPermission>>(listOf(p1.id, p2.id))
        service.addPermissionsToBatch(batch)
        batch.ensureNotNull(emptyList())
        val results = batch.getResults()
        assertEquals(2, results.size)
        assertNotNull(results[0])
        assertNotNull(results[1])
        assertEquals(3, results[0]!!.size, "each project should surface its three auto-created grants")
        assertEquals(3, results[1]!!.size)
    }

    // ---- Progress and export ------------------------------------------------

    @Test
    fun `getTranslationProgress aggregates counts and percentage`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringA = service.addString(LocalizationStringInput(projectId = project.id, key = "a")).id
        val stringB = service.addString(LocalizationStringInput(projectId = project.id, key = "b")).id
        val stringC = service.addString(LocalizationStringInput(projectId = project.id, key = "c")).id // untranslated
        val _c = stringC  // used only to ensure the row exists; keeps total at 3

        val tA = service.setTranslation(LocalizationTranslationInput(stringId = stringA, languageTag = "es", text = "a"), null)
        service.transitionTranslation(tA.id, TranslationState.IN_REVIEW, null)
        service.transitionTranslation(tA.id, TranslationState.APPROVED, UUID.random())
        service.transitionTranslation(tA.id, TranslationState.PUBLISHED, UUID.random())

        service.setTranslation(LocalizationTranslationInput(stringId = stringB, languageTag = "es", text = "b", origin = TranslationOrigin.AI), null)

        val progress = service.getTranslationProgress(project.id, "es")
        assertEquals(3, progress.totalStrings)
        assertEquals(2, progress.translatedStrings)
        assertEquals(1, progress.approvedStrings, "published translations count as approved too")
        assertEquals(1, progress.publishedStrings)
        assertEquals(1, progress.aiGeneratedStrings)
        assertEquals(1, progress.humanTranslatedStrings)
        // 2 / 3 = 66.666...%
        assertTrue(progress.percentage > 66.0 && progress.percentage < 67.0, "percentage should be 66.67%, got ${progress.percentage}")
    }

    @Test
    fun `getTranslationProgress returns a clean zero when no strings exist`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "Empty", sourceLanguage = "en"))
        val progress = service.getTranslationProgress(project.id, "es")
        assertEquals(0, progress.totalStrings)
        assertEquals(0, progress.translatedStrings)
        assertEquals(0.0, progress.percentage)
    }

    @Test
    fun `export only includes translations whose state is in the filter`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val stringA = service.addString(LocalizationStringInput(projectId = project.id, key = "welcome")).id
        val stringB = service.addString(LocalizationStringInput(projectId = project.id, key = "draft_only")).id

        // Published A
        val tA = service.setTranslation(LocalizationTranslationInput(stringId = stringA, languageTag = "es", text = "bienvenido"), null)
        service.transitionTranslation(tA.id, TranslationState.IN_REVIEW, null)
        service.transitionTranslation(tA.id, TranslationState.APPROVED, UUID.random())
        service.transitionTranslation(tA.id, TranslationState.PUBLISHED, UUID.random())

        // Draft-only B
        service.setTranslation(LocalizationTranslationInput(stringId = stringB, languageTag = "es", text = "borrador"), null)

        val default = service.export(ExportRequest(projectId = project.id, languageTag = "es", format = ExportFormat.JSON_FLAT))
        assertTrue(default.content.contains("welcome"), "default export should contain the PUBLISHED string")
        assertTrue(!default.content.contains("draft_only"), "default export should exclude the DRAFT string")

        val includingDrafts = service.export(
            ExportRequest(
                projectId = project.id,
                languageTag = "es",
                format = ExportFormat.JSON_FLAT,
                statesFilter = listOf(TranslationState.PUBLISHED, TranslationState.DRAFT)
            )
        )
        assertTrue(includingDrafts.content.contains("welcome"))
        assertTrue(includingDrafts.content.contains("draft_only"), "when DRAFT is in the filter, draft translations are included")
    }

    // ---- Document HTML round-trip through the service layer -----------------

    @Test
    fun `exportDocumentAsHtml followed by importDocumentFromHtml round-trips the translation`() = LocalizationTestFixture.withDb {
        val project = service.addProject(LocalizationProjectInput(name = "P", sourceLanguage = "en"))
        val document = service.addProjectDocument(LocalizationProjectDocumentInput(projectId = project.id, metadataId = UUID.random()))

        val original = buildJsonObject {
            put("type", JsonPrimitive("doc"))
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("paragraph"))
                    put("content", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Hola mundo"))
                        })
                    })
                })
            })
        }

        service.setDocumentTranslation(
            LocalizationDocumentTranslationInput(documentId = document.id, languageTag = "es", content = original),
            createdBy = null
        )

        val html = service.exportDocumentAsHtml(document.id, "es")
        assertTrue(html.contains("Hola mundo"), "exported HTML should contain the translated text")

        val reimported = service.importDocumentFromHtml(
            documentId = document.id,
            languageTag = "es",
            html = html,
            origin = TranslationOrigin.IMPORT,
            originDetail = "round-trip",
            createdBy = null
        )
        val rendered = reimported.content.toString()
        assertTrue(rendered.contains("Hola mundo"), "reimported content should still contain the translated text")
    }
}
