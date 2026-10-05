@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.jobs

import bosca.content.metadata.model.ContentEntityLink
import bosca.content.metadata.model.ContentLinkTarget
import bosca.content.metadata.model.Document as MetadataDocument
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.ContentEntityLinkService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.documents.Content
import bosca.documents.Document
import bosca.documents.MentionAttributes
import bosca.documents.MentionNode
import bosca.documents.ParagraphNode
import bosca.documents.TextNode
import bosca.documents.yjs.ProseMirrorYjsBridge
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import bosca.workops.service.SpecContextService
import bosca.workops.service.SpecService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Guards the version semantics of spec context sync: documents must be
 * resolved at the metadata's document version, never at the spec row's
 * optimistic-lock counter — the two are unrelated numbers.
 */
class SpecContextSyncExecutorTest {

    private val specService = mockk<SpecService>()
    private val entityLinkService = mockk<ContentEntityLinkService>()
    private val contextService = mockk<SpecContextService>()
    private val documentService = mockk<DocumentService>()
    private val metadataService = mockk<MetadataService>()
    private val slugService = mockk<SlugService>()
    private val profileService = mockk<ProfileService>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun executor() = SpecContextSyncExecutor(
        specService = specService,
        entityLinkService = entityLinkService,
        contextService = contextService,
        documentService = documentService,
        metadataService = metadataService,
        slugService = slugService,
        profileService = profileService,
    )

    private fun sampleSpec(lockVersion: Long) = Spec(
        id = UUID.random(), key = "SPEC-1",
        metadataId = UUID.random(),
        statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
        version = lockVersion,
    )

    private fun sampleMetadata(id: UUID, documentVersion: Int) = Metadata(
        id = id, version = documentVersion, name = "My Spec", type = MetadataType.STANDARD,
        contentType = "bosca/v-document", contentLength = null,
        languageTag = "en", workflowStateId = "draft",
    )

    @Test
    fun `execute ignores empty jobs and synchronizes identified specs`() = runTest {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json }
        val queue = mockk<JobQueue>(relaxed = true)
        val specId = UUID.random()
        coEvery { specService.getById(specId) } returns null

        suspend fun execute(definition: SpecContextSyncJob) {
            val job = Job(definition, SpecContextSyncExecutor::class)
            withContext(queue.asCoroutineContext(job)) { executor().execute() }
        }

        try {
            execute(SpecContextSyncJob())
            execute(SpecContextSyncJob(specId))
            coVerify(exactly = 1) { specService.getById(specId) }
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `sync resolves documents at the metadata version not the spec lock version`() = runTest {
        val spec = sampleSpec(lockVersion = 999)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns null
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returns null

        executor().sync(spec.id)

        coVerify(exactly = 1) { documentService.getDocument(spec.metadataId, 3) }
        coVerify(exactly = 1) { documentService.getCollaboration(spec.metadataId, 3) }
        coVerify(exactly = 0) { documentService.getDocument(spec.metadataId, 999) }
        coVerify(exactly = 0) { documentService.getCollaboration(spec.metadataId, 999) }
    }

    @Test
    fun `sync skips document resolution when the backing metadata is missing`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns null

        executor().sync(spec.id)

        coVerify(exactly = 0) { documentService.getDocument(any(), any()) }
        coVerify(exactly = 0) { documentService.getCollaboration(any(), any()) }
    }

    @Test
    fun `sync returns when the spec does not exist`() = runTest {
        val specId = UUID.random()
        coEvery { specService.getById(specId) } returns null

        executor().sync(specId)

        coVerify(exactly = 0) { contextService.listBySpec(any()) }
    }

    @Test
    fun `sync adds supported entity links once and tolerates isolated failures`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val duplicateTarget = UUID.random().toString()
        val taskTarget = UUID.random().toString()
        val projectTarget = UUID.random().toString()
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns listOf(
            context(spec.id, SpecContextType.METADATA, duplicateTarget),
        )
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns listOf(
            link(spec.metadataId, ContentLinkTarget.METADATA, duplicateTarget),
            link(spec.metadataId, ContentLinkTarget.TASK, taskTarget),
            link(spec.metadataId, ContentLinkTarget.REQUIREMENT, UUID.random().toString()),
            link(spec.metadataId, ContentLinkTarget.PROJECT, projectTarget),
        )
        coEvery {
            contextService.add(
                spec.id,
                match { it.contextType == SpecContextType.TASK && it.targetId == taskTarget },
                UUID.NIL,
                spec.ownerProfileId,
            )
        } returns context(spec.id, SpecContextType.TASK, taskTarget)
        coEvery {
            contextService.add(
                spec.id,
                match { it.contextType == SpecContextType.PROJECT && it.targetId == projectTarget },
                UUID.NIL,
                spec.ownerProfileId,
            )
        } throws IllegalStateException("project unavailable")
        coEvery { metadataService.getById(spec.metadataId) } returns null

        executor().sync(spec.id)

        coVerify(exactly = 0) {
            contextService.add(
                spec.id,
                match { it.contextType == SpecContextType.METADATA },
                any(),
                any(),
            )
        }
        coVerify(exactly = 2) { contextService.add(spec.id, any(), UUID.NIL, spec.ownerProfileId) }
    }

    @Test
    fun `sync resolves slug mentions into document nodes and contexts`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val profileTarget = UUID.random()
        val metadataTarget = UUID.random()
        val collectionTarget = UUID.random()
        val content = Content(
            document = Document(
                content = listOf(
                    ParagraphNode(
                        content = listOf(
                            MentionNode(attributes = MentionAttributes(label = "Existing")),
                            TextNode(text = " @existing @profile @metadata @collection @unknown"),
                        ),
                    ),
                ),
            ),
        )
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns MetadataDocument(
            metadataId = spec.metadataId,
            version = 3,
            title = "Spec",
            content = content,
        )
        coEvery { slugService.get("profile") } returns Slug("profile", profileId = profileTarget)
        coEvery { slugService.get("metadata") } returns Slug("metadata", metadataId = metadataTarget)
        coEvery { slugService.get("collection") } returns Slug("collection", collectionId = collectionTarget)
        coEvery { slugService.get("unknown") } returns null
        coEvery { profileService.getById(profileTarget) } returns profile(profileTarget, "Profile Name")
        coEvery { profileService.getAll(0, 1000) } returns emptyList()
        coEvery { metadataService.getById(metadataTarget) } returns sampleMetadata(metadataTarget, 1)
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { documentService.setDocument(metadata, any(), any()) } just Runs
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returns null

        executor().sync(spec.id)

        val children = assertIs<ParagraphNode>(content.document.content.single()).content
        assertEquals(
            listOf("Existing", "Profile Name", "My Spec", "collection"),
            children.filterIsInstance<MentionNode>().map { it.attributes.label },
        )
        coVerify(exactly = 0) { slugService.get("existing") }
        coVerify(exactly = 1) {
            contextService.add(spec.id, match { it.contextType == SpecContextType.PROFILE && it.targetId == profileTarget.toString() }, UUID.NIL, profileId)
            contextService.add(spec.id, match { it.contextType == SpecContextType.METADATA && it.targetId == metadataTarget.toString() }, UUID.NIL, profileId)
            contextService.add(spec.id, match { it.contextType == SpecContextType.COLLECTION && it.targetId == collectionTarget.toString() }, UUID.NIL, profileId)
            documentService.setDocument(metadata, match { it.title == "Spec" && it.content == content }, any())
        }
    }

    @Test
    fun `sync falls back to profile attributes and display names`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val aliasProfile = profile(UUID.random(), "Not Alias")
        val displayProfile = profile(UUID.random(), "Display")
        val content = Content(
            document = Document(
                content = listOf(ParagraphNode(content = listOf(TextNode(text = "@Alias @display")))),
            ),
        )
        stubDocumentSync(spec, metadata, content)
        coEvery { slugService.get(any()) } returns null
        coEvery { profileService.getAll(0, 1000) } returns listOf(aliasProfile, displayProfile)
        coEvery { profileService.getAttributes(aliasProfile.id) } returns listOf(nameAttribute(aliasProfile.id, "Alias"))
        coEvery { profileService.getAttributes(displayProfile.id) } returns emptyList()
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { documentService.setDocument(metadata, any(), any()) } just Runs

        executor().sync(spec.id)

        val mentions = assertIs<ParagraphNode>(content.document.content.single()).content.filterIsInstance<MentionNode>()
        assertEquals(listOf("Alias", "Display"), mentions.map { it.attributes.label })
    }

    @Test
    fun `sync leaves the document untouched when mentions cannot be resolved`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val content = Content(
            document = Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "@unknown"))))),
        )
        stubDocumentSync(spec, metadata, content)
        coEvery { slugService.get("unknown") } returns Slug("unknown")
        coEvery { profileService.getAll(0, 1000) } returns emptyList()

        executor().sync(spec.id)

        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    @Test
    fun `sync skips absent content and documents containing only already resolved mentions`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, 3) } returnsMany listOf(
            MetadataDocument(metadataId = spec.metadataId, version = 3, title = "No content", content = null),
            MetadataDocument(
                metadataId = spec.metadataId,
                version = 3,
                title = "Resolved",
                content = Content(
                    document = Document(
                        content = listOf(
                            ParagraphNode(
                                content = listOf(
                                    MentionNode(attributes = MentionAttributes(label = "Known")),
                                    MentionNode(attributes = MentionAttributes(label = null)),
                                    TextNode(text = "@known plain text"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returns null

        executor().sync(spec.id)
        executor().sync(spec.id)

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    @Test
    fun `sync leaves resolved text in memory when metadata disappears before persistence`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val content = Content(
            document = Document(
                content = listOf(
                    ParagraphNode(
                        content = listOf(
                            TextNode(text = "before @collection after"),
                            TextNode(text = "unchanged"),
                        ),
                    ),
                ),
            ),
        )
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returnsMany listOf(metadata, null)
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns
            MetadataDocument(metadataId = spec.metadataId, version = 3, title = "Spec", content = content)
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returns null
        val target = UUID.random()
        coEvery { slugService.get("collection") } returns Slug("collection", collectionId = target)
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)

        executor().sync(spec.id)

        val children = assertIs<ParagraphNode>(content.document.content.single()).content
        assertEquals("before ", assertIs<TextNode>(children[0]).text)
        assertEquals("collection", assertIs<MentionNode>(children[1]).attributes.label)
        assertEquals(" after", assertIs<TextNode>(children[2]).text)
        assertEquals("unchanged", assertIs<TextNode>(children[3]).text)
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    @Test
    fun `sync resolves collaboration mentions and stores the merged update`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val profileTarget = UUID.random()
        val original = ProseMirrorYjsBridge.toYDocUpdate(
            Content(
                document = Document(
                    content = listOf(ParagraphNode(content = listOf(TextNode(text = "@profile")))),
                ),
            ),
        )
        val collaboration = DocumentCollaboration(spec.metadataId, 3, original)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns null
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returnsMany listOf(collaboration, collaboration)
        coEvery { slugService.get("profile") } returns Slug("profile", profileId = profileTarget)
        coEvery { profileService.getById(profileTarget) } returns profile(profileTarget, "Profile Name")
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { documentService.setCollaboration(any()) } returns true

        executor().sync(spec.id)

        coVerify(exactly = 1) {
            documentService.setCollaboration(match {
                it.metadataId == spec.metadataId && it.version == 3 && it.content?.isNotEmpty() == true
            })
        }
    }

    @Test
    fun `collaboration sync stops when the document disappears before merge`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val profileTarget = UUID.random()
        val original = ProseMirrorYjsBridge.toYDocUpdate(
            Content(
                document = Document(
                    content = listOf(ParagraphNode(content = listOf(TextNode(text = "@profile")))),
                ),
            ),
        )
        val collaboration = DocumentCollaboration(spec.metadataId, 3, original)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns null
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returnsMany listOf(collaboration, null)
        coEvery { slugService.get("profile") } returns Slug("profile", profileId = profileTarget)
        coEvery { profileService.getById(profileTarget) } returns profile(profileTarget, "Profile Name")
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)

        executor().sync(spec.id)

        coVerify(exactly = 0) { documentService.setCollaboration(any()) }
    }

    @Test
    fun `slugged metadata mentions remain text when their target disappears`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val metadataTarget = UUID.random()
        val content = Content(
            document = Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "@metadata"))))),
        )
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { metadataService.getById(metadataTarget) } returns null
        coEvery { documentService.getDocument(spec.metadataId, 3) } returns MetadataDocument(
            metadataId = spec.metadataId,
            version = 3,
            title = "Spec",
            content = content,
        )
        coEvery { documentService.getCollaboration(spec.metadataId, 3) } returns null
        coEvery { slugService.get("metadata") } returns Slug("metadata", metadataId = metadataTarget)
        coEvery { profileService.getAll(0, 1000) } returns emptyList()

        executor().sync(spec.id)

        assertEquals("@metadata", assertIs<TextNode>(assertIs<ParagraphNode>(content.document.content.single()).content.single()).text)
        coVerify(exactly = 0) { contextService.add(any(), any(), any(), any()) }
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    @Test
    fun `collaboration sync ignores documents without resolvable mentions and isolates read failures`() = runTest {
        val plain = ProseMirrorYjsBridge.toYDocUpdate(
            Content(
                document = Document(
                    content = listOf(ParagraphNode(content = listOf(TextNode(text = "plain")))),
                ),
            ),
        )
        val unresolved = ProseMirrorYjsBridge.toYDocUpdate(
            Content(
                document = Document(
                    content = listOf(ParagraphNode(content = listOf(TextNode(text = "@unknown")))),
                ),
            ),
        )

        suspend fun prepare(spec: Spec, metadata: Metadata) {
            coEvery { specService.getById(spec.id) } returns spec
            coEvery { contextService.listBySpec(spec.id) } returns emptyList()
            coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
            coEvery { metadataService.getById(spec.metadataId) } returns metadata
            coEvery { documentService.getDocument(spec.metadataId, metadata.version) } returns null
        }

        val plainSpec = sampleSpec(1)
        val plainMetadata = sampleMetadata(plainSpec.metadataId, 2)
        prepare(plainSpec, plainMetadata)
        coEvery { documentService.getCollaboration(plainSpec.metadataId, 2) } returns
            DocumentCollaboration(plainSpec.metadataId, 2, plain)
        executor().sync(plainSpec.id)

        val unresolvedSpec = sampleSpec(1)
        val unresolvedMetadata = sampleMetadata(unresolvedSpec.metadataId, 2)
        prepare(unresolvedSpec, unresolvedMetadata)
        coEvery { documentService.getCollaboration(unresolvedSpec.metadataId, 2) } returns
            DocumentCollaboration(unresolvedSpec.metadataId, 2, unresolved)
        coEvery { slugService.get("unknown") } returns null
        coEvery { profileService.getAll(0, 1000) } returns emptyList()
        executor().sync(unresolvedSpec.id)

        val failedSpec = sampleSpec(1)
        val failedMetadata = sampleMetadata(failedSpec.metadataId, 2)
        prepare(failedSpec, failedMetadata)
        coEvery { documentService.getCollaboration(failedSpec.metadataId, 2) } throws
            IllegalStateException("collaboration unavailable")
        executor().sync(failedSpec.id)

        val cancelledSpec = sampleSpec(1)
        val cancelledMetadata = sampleMetadata(cancelledSpec.metadataId, 2)
        prepare(cancelledSpec, cancelledMetadata)
        coEvery { documentService.getCollaboration(cancelledSpec.metadataId, 2) } throws
            CancellationException("cancelled")
        assertFailsWith<CancellationException> { executor().sync(cancelledSpec.id) }

        coVerify(exactly = 0) { documentService.setCollaboration(any()) }
    }

    @Test
    fun `sync isolates document update failures but propagates cancellation`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val metadata = sampleMetadata(spec.metadataId, documentVersion = 3)
        val content = Content(
            document = Document(content = listOf(ParagraphNode(content = listOf(TextNode(text = "@profile"))))),
        )
        stubDocumentSync(spec, metadata, content)
        val target = UUID.random()
        coEvery { slugService.get("profile") } returns Slug("profile", collectionId = target)
        coEvery { contextService.add(any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { documentService.setDocument(metadata, any(), any()) } throws IllegalStateException("write failed")

        executor().sync(spec.id)

        coVerify(exactly = 1) { documentService.getCollaboration(spec.metadataId, 3) }

        content.document.content = listOf(ParagraphNode(content = listOf(TextNode(text = "@profile"))))
        coEvery { documentService.setDocument(metadata, any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { executor().sync(spec.id) }
    }

    @Test
    fun `sync propagates cancellation while adding a context`() = runTest {
        val spec = sampleSpec(lockVersion = 1)
        val target = UUID.random().toString()
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns listOf(
            link(spec.metadataId, ContentLinkTarget.TASK, target),
        )
        coEvery { contextService.add(any(), any(), any(), any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { executor().sync(spec.id) }
    }

    @Test
    fun `every content link target maps to its supported context type`() {
        val expected = mapOf(
            ContentLinkTarget.METADATA to SpecContextType.METADATA,
            ContentLinkTarget.COLLECTION to SpecContextType.COLLECTION,
            ContentLinkTarget.PROFILE to SpecContextType.PROFILE,
            ContentLinkTarget.TASK to SpecContextType.TASK,
            ContentLinkTarget.SPEC to SpecContextType.SPEC,
            ContentLinkTarget.PROJECT to SpecContextType.PROJECT,
            ContentLinkTarget.GIT_REPOSITORY to SpecContextType.GIT_RESOURCE,
            ContentLinkTarget.CHAT_CHANNEL to SpecContextType.CHAT_CHANNEL,
            ContentLinkTarget.AI_SESSION to SpecContextType.AI_SESSION,
            ContentLinkTarget.EXTERNAL_URI to SpecContextType.EXTERNAL_URI,
            ContentLinkTarget.REQUIREMENT to null,
            ContentLinkTarget.PROGRAM to null,
        )

        assertEquals(expected, ContentLinkTarget.entries.associateWith(SpecContextSyncExecutor::mapLinkTarget))
    }

    private fun stubDocumentSync(spec: Spec, metadata: Metadata, content: Content) {
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { entityLinkService.listByMetadata(spec.metadataId) } returns emptyList()
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { documentService.getDocument(spec.metadataId, metadata.version) } returns MetadataDocument(
            metadataId = spec.metadataId,
            version = metadata.version,
            title = "Spec",
            content = content,
        )
        coEvery { documentService.getCollaboration(spec.metadataId, metadata.version) } returns null
    }

    private fun context(specId: UUID, type: SpecContextType, targetId: String) = SpecContext(
        id = UUID.random(),
        specId = specId,
        contextType = type,
        targetId = targetId,
        addedByProfileId = profileId,
    )

    private fun link(metadataId: UUID, type: ContentLinkTarget, targetId: String) = ContentEntityLink(
        metadataId = metadataId,
        metadataVersion = 1,
        targetType = type,
        targetId = targetId,
    )

    private fun profile(id: UUID, name: String) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
    )

    private fun nameAttribute(profileId: UUID, name: String) = ProfileAttribute(
        profile = profileId,
        typeId = "bosca.profiles.name",
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 0,
        source = "test",
        attributes = JsonObject(mapOf("value" to JsonPrimitive(name))),
    )
}
