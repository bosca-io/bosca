package bosca.content.graphql

import bosca.category.graphql.Categories
import bosca.comments.model.CommentedMetadata
import bosca.comments.service.CommentService
import bosca.content.collection.graphql.Collections
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindQueryInput
import bosca.content.healthcheck.ContentHealthCheck
import bosca.content.guide.graphql.Guides
import bosca.content.metadata.graphql.CollectionTemplates
import bosca.content.metadata.graphql.DataTemplates
import bosca.content.metadata.graphql.DocumentTemplates
import bosca.content.metadata.graphql.GuideTemplates
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.graphql.WorkflowStates
import bosca.content.tools.graphql.TemplateAttributeTools
import bosca.content.transition.graphql.Transitions
import bosca.di.ObjectProvider
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.source.graphql.Sources
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContentControllerCoverageTest {

    private val slugService = mockk<SlugService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val commentService = mockk<CommentService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val metadataServiceProvider = mockk<ObjectProvider<MetadataService>>()
    private val metadataPermissionEvaluatorProvider = mockk<ObjectProvider<MetadataPermissionEvaluator>>()
    private val collectionServiceProvider = mockk<ObjectProvider<CollectionService>>()
    private val collectionPermissionEvaluatorProvider = mockk<ObjectProvider<CollectionPermissionEvaluator>>()
    private val profileServiceProvider = mockk<ObjectProvider<ProfileService>>()
    private val profilePermissionEvaluatorProvider = mockk<ObjectProvider<ProfilePermissionEvaluator>>()
    private val commentServiceProvider = mockk<ObjectProvider<CommentService>>()

    private val controller = ContentController(
        slugService,
        metadataServiceProvider,
        metadataPermissionEvaluatorProvider,
        collectionServiceProvider,
        collectionPermissionEvaluatorProvider,
        profileServiceProvider,
        profilePermissionEvaluatorProvider,
        commentServiceProvider,
        groupEvaluator
    )

    private val authentication = mockk<AuthenticationContext>()

    @BeforeTest
    fun setup() {
        coEvery { metadataServiceProvider.get() } returns metadataService
        coEvery { metadataPermissionEvaluatorProvider.get() } returns metadataPermissionEvaluator
        coEvery { collectionServiceProvider.get() } returns collectionService
        coEvery { collectionPermissionEvaluatorProvider.get() } returns collectionPermissionEvaluator
        coEvery { profileServiceProvider.get() } returns profileService
        coEvery { profilePermissionEvaluatorProvider.get() } returns profilePermissionEvaluator
        coEvery { commentServiceProvider.get() } returns commentService

        // transaction { block } → run the block directly (no real DB / connection context)
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        clearAllMocks()
    }

    private fun metadata(id: UUID = UUID.random(), version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Meta",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun collection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "Coll",
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun variant(id: UUID = UUID.random(), languageTag: String = "es") = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = "Variant"
    )

    @Test
    fun `guides returns global guide queries`() {
        assertSame(Guides, controller.guides())
    }

    // ---- simple singleton @Field resolvers ----

    @Test
    fun `singleton field resolvers return their objects`() {
        // touch the marker object so it is class-loaded/covered
        assertSame(Content, Content)
        assertSame(Collections, controller.collections())
        assertSame(Categories, controller.categories())
        assertSame(Sources, controller.sources())
        assertSame(CollectionTemplates, controller.collectionTemplates())
        assertSame(DocumentTemplates, controller.documentTemplates())
        assertSame(DataTemplates, controller.dataTemplates())
        assertSame(GuideTemplates, controller.guideTemplates())
        assertSame(WorkflowStates, controller.states())
        assertSame(Transitions, controller.transitions())
        assertSame(TemplateAttributeTools, controller.templateAttributeTools())
        assertSame(ContentHealthCheck, controller.healthCheck())
    }

    // ---- checkCollectionActions ----

    @Test
    fun `checkCollectionActions collects ids that pass and skips missing`() = runTest {
        val id1 = UUID.random()
        val missing = UUID.random()
        val col1 = collection(id1)

        coEvery { collectionService.getById(id1) } returns col1
        coEvery { collectionService.getById(missing) } returns null
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col1, PermissionAction.VIEW) } returns true

        val result = controller.checkCollectionActions(
            authentication,
            listOf(PermissionAction.VIEW),
            listOf(id1, missing)
        )

        assertEquals(listOf(id1), result)
    }

    @Test
    fun `checkCollectionActions omits id when action denied`() = runTest {
        val id1 = UUID.random()
        val col1 = collection(id1)

        coEvery { collectionService.getById(id1) } returns col1
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col1, PermissionAction.VIEW) } returns false

        val result = controller.checkCollectionActions(
            authentication,
            listOf(PermissionAction.VIEW),
            listOf(id1)
        )

        assertTrue(result.isEmpty())
    }

    // ---- checkMetadataActions ----

    @Test
    fun `checkMetadataActions collects ids that pass and skips missing`() = runTest {
        val id1 = UUID.random()
        val missing = UUID.random()
        val meta1 = metadata(id1)

        coEvery { metadataService.getById(id1) } returns meta1
        coEvery { metadataService.getById(missing) } returns null
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta1, PermissionAction.VIEW) } returns true

        val result = controller.checkMetadataActions(
            authentication,
            listOf(PermissionAction.VIEW),
            listOf(id1, missing)
        )

        assertEquals(listOf(id1), result)
    }

    @Test
    fun `checkMetadataActions omits id when action denied`() = runTest {
        val id1 = UUID.random()
        val meta1 = metadata(id1)

        coEvery { metadataService.getById(id1) } returns meta1
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta1, PermissionAction.VIEW) } returns false

        val result = controller.checkMetadataActions(
            authentication,
            listOf(PermissionAction.VIEW),
            listOf(id1)
        )

        assertTrue(result.isEmpty())
    }

    // ---- slugAvailable ----

    @Test
    fun `slugAvailable true when no slug exists`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { slugService.get("free-slug") } returns null

        assertTrue(controller.slugAvailable(authentication, "free-slug"))
    }

    @Test
    fun `slugAvailable false when slug exists`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { slugService.get("taken") } returns Slug(slug = "taken")

        assertFalse(controller.slugAvailable(authentication, "taken"))
    }

    // ---- slug ----

    @Test
    fun `slug returns null when slug not found`() = runTest {
        coEvery { slugService.get("missing") } returns null

        assertNull(controller.slug(authentication, "missing"))
    }

    @Test
    fun `slug returns metadata when metadataId set and allowed`() = runTest {
        val metadataId = UUID.random()
        val meta = metadata(metadataId)

        coEvery { slugService.get("m") } returns Slug(slug = "m", metadataId = metadataId)
        coEvery { metadataService.getById(metadataId) } returns meta
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta, PermissionAction.VIEW) } returns true

        assertSame(meta, controller.slug(authentication, "m"))
    }

    @Test
    fun `slug returns null when metadataId set but metadata missing`() = runTest {
        val metadataId = UUID.random()

        coEvery { slugService.get("m") } returns Slug(slug = "m", metadataId = metadataId)
        coEvery { metadataService.getById(metadataId) } returns null

        assertNull(controller.slug(authentication, "m"))
    }

    @Test
    fun `slug falls through when metadata not allowed and nothing else set`() = runTest {
        val metadataId = UUID.random()
        val meta = metadata(metadataId)

        coEvery { slugService.get("m") } returns Slug(slug = "m", metadataId = metadataId)
        coEvery { metadataService.getById(metadataId) } returns meta
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta, PermissionAction.VIEW) } returns false

        assertNull(controller.slug(authentication, "m"))
    }

    @Test
    fun `slug returns collection by id when allowed and no language tag`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)

        coEvery { slugService.get("c") } returns Slug(slug = "c", collectionId = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW) } returns true

        assertSame(col, controller.slug(authentication, "c"))
    }

    @Test
    fun `slug returns null when collection by id missing`() = runTest {
        val collectionId = UUID.random()

        coEvery { slugService.get("c") } returns Slug(slug = "c", collectionId = collectionId)
        coEvery { collectionService.getById(collectionId) } returns null

        assertNull(controller.slug(authentication, "c"))
    }

    @Test
    fun `slug uses language variant and returns parent with default variant set`() = runTest {
        val collectionId = UUID.random()
        val languageVariant = variant(languageTag = "es")
        val parent = collection(collectionId)

        coEvery { slugService.get("cv") } returns Slug(slug = "cv", collectionId = collectionId, languageTag = "es")
        coEvery { collectionService.getLanguageVariant(collectionId, "es") } returns languageVariant
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, languageVariant, PermissionAction.VIEW) } returns true
        coEvery { collectionService.getById(collectionId) } returns parent

        val result = controller.slug(authentication, "cv")

        assertSame(parent, result)
        assertSame(languageVariant, (result as Collection).defaultLanguageVariant)
    }

    @Test
    fun `slug returns null when language variant allowed but parent missing`() = runTest {
        val collectionId = UUID.random()
        val languageVariant = variant(languageTag = "es")

        coEvery { slugService.get("cv") } returns Slug(slug = "cv", collectionId = collectionId, languageTag = "es")
        coEvery { collectionService.getLanguageVariant(collectionId, "es") } returns languageVariant
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, languageVariant, PermissionAction.VIEW) } returns true
        coEvery { collectionService.getById(collectionId) } returns null

        assertNull(controller.slug(authentication, "cv"))
    }

    @Test
    fun `slug falls back to getById when language variant not found`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)

        coEvery { slugService.get("cv") } returns Slug(slug = "cv", collectionId = collectionId, languageTag = "es")
        coEvery { collectionService.getLanguageVariant(collectionId, "es") } returns null
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW) } returns true

        assertSame(col, controller.slug(authentication, "cv"))
    }

    @Test
    fun `slug falls through when collection not allowed`() = runTest {
        val collectionId = UUID.random()
        val col = collection(collectionId)

        coEvery { slugService.get("c") } returns Slug(slug = "c", collectionId = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW) } returns false

        assertNull(controller.slug(authentication, "c"))
    }

    @Test
    fun `slug returns profile when profileId set and allowed`() = runTest {
        val profileId = UUID.random()
        val profile = mockk<Profile>()

        coEvery { slugService.get("p") } returns Slug(slug = "p", profileId = profileId)
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true

        assertSame(profile, controller.slug(authentication, "p"))
    }

    @Test
    fun `slug returns null when profile not allowed`() = runTest {
        val profileId = UUID.random()
        val profile = mockk<Profile>()

        coEvery { slugService.get("p") } returns Slug(slug = "p", profileId = profileId)
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false

        assertNull(controller.slug(authentication, "p"))
    }

    // ---- collection ----

    @Test
    fun `collection returns null when missing`() = runTest {
        val id = UUID.random()
        coEvery { collectionService.getById(id) } returns null

        assertNull(controller.collection(authentication, id))
    }

    @Test
    fun `collection returns null when not allowed`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW) } returns false

        assertNull(controller.collection(authentication, id))
    }

    @Test
    fun `collection returns collection when allowed`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, col, PermissionAction.VIEW) } returns true

        assertSame(col, controller.collection(authentication, id))
    }

    // ---- collectionSupplementary ----

    @Test
    fun `collectionSupplementary returns null when supplementary missing`() = runTest {
        val supplementaryId = UUID.random()
        coEvery { collectionService.getSupplementaryById(supplementaryId) } returns null

        assertNull(controller.collectionSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `collectionSupplementary returns null when collection missing`() = runTest {
        val supplementaryId = UUID.random()
        val collectionId = UUID.random()
        val supplementary = mockk<CollectionSupplementary>()
        every { supplementary.collectionId } returns collectionId

        coEvery { collectionService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { collectionService.getById(collectionId) } returns null

        assertNull(controller.collectionSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `collectionSupplementary returns null when not allowed`() = runTest {
        val supplementaryId = UUID.random()
        val collectionId = UUID.random()
        val supplementary = mockk<CollectionSupplementary>()
        every { supplementary.collectionId } returns collectionId
        val col = collection(collectionId)

        coEvery { collectionService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { collectionPermissionEvaluator.isSupplementaryAllowed(authentication, col, PermissionAction.VIEW) } returns false

        assertNull(controller.collectionSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `collectionSupplementary returns context when allowed`() = runTest {
        val supplementaryId = UUID.random()
        val collectionId = UUID.random()
        val supplementary = mockk<CollectionSupplementary>()
        every { supplementary.collectionId } returns collectionId
        val col = collection(collectionId)

        coEvery { collectionService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { collectionPermissionEvaluator.isSupplementaryAllowed(authentication, col, PermissionAction.VIEW) } returns true

        val result = controller.collectionSupplementary(authentication, supplementaryId)

        assertSame(col, result?.collection)
        assertSame(supplementary, result?.supplementary)
    }

    // ---- findCollectionsCount ----

    @Test
    fun `findCollectionsCount delegates to service`() = runTest {
        val query = FindQueryInput()
        coEvery { collectionService.findCount(query) } returns 7L

        assertEquals(7L, controller.findCollectionsCount(query))
    }

    // ---- findCollections ----

    @Test
    fun `findCollections filters allowed and casts to Collection`() = runTest {
        val query = FindQueryInput()
        val col = collection()
        coEvery { collectionService.find(query) } returns listOf(col)
        coEvery { collectionPermissionEvaluator.filterAllowed(authentication, listOf(col), PermissionAction.VIEW) } returns listOf(col)

        assertEquals(listOf(col), controller.findCollections(authentication, query))
    }

    // ---- findCollectionsBySystem ----

    @Test
    fun `findCollectionsBySystem filters allowed and casts to Collection`() = runTest {
        val query = FindQueryInput()
        val col = collection()
        coEvery { collectionService.findBySystem(query) } returns listOf(col)
        coEvery { collectionPermissionEvaluator.filterAllowed(authentication, listOf(col), PermissionAction.VIEW) } returns listOf(col)

        assertEquals(listOf(col), controller.findCollectionsBySystem(authentication, query))
    }

    // ---- findMetadataCount ----

    @Test
    fun `findMetadataCount delegates to service`() = runTest {
        val query = FindQueryInput()
        coEvery { metadataService.findCount(query) } returns 3L

        assertEquals(3L, controller.findMetadataCount(query))
    }

    // ---- findMetadata ----

    @Test
    fun `findMetadata filters allowed`() = runTest {
        val query = FindQueryInput()
        val meta = metadata()
        coEvery { metadataService.find(query) } returns listOf(meta)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(meta), PermissionAction.VIEW) } returns listOf(meta)

        assertEquals(listOf(meta), controller.findMetadata(authentication, query))
    }

    // ---- findMetadataBySystem ----

    @Test
    fun `findMetadataBySystem filters allowed`() = runTest {
        val query = FindQueryInput()
        val meta = metadata()
        coEvery { metadataService.findBySystem(query) } returns listOf(meta)
        coEvery { metadataPermissionEvaluator.filterAllowed(authentication, listOf(meta), PermissionAction.VIEW) } returns listOf(meta)

        assertEquals(listOf(meta), controller.findMetadataBySystem(authentication, query))
    }

    // ---- metadata ----

    @Test
    fun `metadata returns null when missing`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 2) } returns null

        assertNull(controller.metadata(authentication, id, 2))
    }

    @Test
    fun `metadata returns null when not allowed`() = runTest {
        val id = UUID.random()
        val meta = metadata(id)
        coEvery { metadataService.getById(id, null) } returns meta
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta, PermissionAction.VIEW) } returns false

        assertNull(controller.metadata(authentication, id, null))
    }

    @Test
    fun `metadata returns metadata when allowed`() = runTest {
        val id = UUID.random()
        val meta = metadata(id)
        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, meta, PermissionAction.VIEW) } returns true

        assertSame(meta, controller.metadata(authentication, id, 1))
    }

    // ---- commentedMetadata ----

    @Test
    fun `commentedMetadata maps allowed and skips missing and denied`() = runTest {
        val allowedId = UUID.random()
        val missingId = UUID.random()
        val deniedId = UUID.random()
        val allowedMeta = metadata(allowedId, version = 2)
        val deniedMeta = metadata(deniedId, version = 3)

        coEvery { commentService.getCommentedMetadata(0L, 10L) } returns listOf(
            CommentedMetadata(metadataId = allowedId, version = 2),
            CommentedMetadata(metadataId = missingId, version = 1),
            CommentedMetadata(metadataId = deniedId, version = 3)
        )
        coEvery { metadataService.getById(allowedId, 2) } returns allowedMeta
        coEvery { metadataService.getById(missingId, 1) } returns null
        coEvery { metadataService.getById(deniedId, 3) } returns deniedMeta
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, allowedMeta, PermissionAction.VIEW) } returns true
        coEvery { metadataPermissionEvaluator.isAllowed(authentication, deniedMeta, PermissionAction.VIEW) } returns false

        val result = controller.commentedMetadata(authentication, 0, 10)

        assertEquals(listOf(allowedMeta), result)
    }

    // ---- metadataSupplementary ----

    @Test
    fun `metadataSupplementary returns null when supplementary missing`() = runTest {
        val supplementaryId = UUID.random()
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns null

        assertNull(controller.metadataSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `metadataSupplementary returns null when metadata missing`() = runTest {
        val supplementaryId = UUID.random()
        val metadataId = UUID.random()
        val supplementary = mockk<MetadataSupplementary>()
        every { supplementary.metadataId } returns metadataId

        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { metadataService.getById(metadataId) } returns null

        assertNull(controller.metadataSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `metadataSupplementary returns null when not allowed`() = runTest {
        val supplementaryId = UUID.random()
        val metadataId = UUID.random()
        val supplementary = mockk<MetadataSupplementary>()
        every { supplementary.metadataId } returns metadataId
        val meta = metadata(metadataId)

        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { metadataService.getById(metadataId) } returns meta
        coEvery { metadataPermissionEvaluator.isSupplementaryAllowed(authentication, meta, PermissionAction.VIEW) } returns false

        assertNull(controller.metadataSupplementary(authentication, supplementaryId))
    }

    @Test
    fun `metadataSupplementary returns context when allowed`() = runTest {
        val supplementaryId = UUID.random()
        val metadataId = UUID.random()
        val supplementary = mockk<MetadataSupplementary>()
        every { supplementary.metadataId } returns metadataId
        val meta = metadata(metadataId)

        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary
        coEvery { metadataService.getById(metadataId) } returns meta
        coEvery { metadataPermissionEvaluator.isSupplementaryAllowed(authentication, meta, PermissionAction.VIEW) } returns true

        val result = controller.metadataSupplementary(authentication, supplementaryId)

        assertSame(meta, result?.metadata)
        assertSame(supplementary, result?.supplementary)
    }
}
