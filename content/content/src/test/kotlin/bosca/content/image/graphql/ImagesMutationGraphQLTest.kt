package bosca.content.image.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.content.graphql.ContentMutation
import bosca.content.graphql.ContentMutationController
import bosca.content.graphql.ContentMutationControllerDispatcher
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttachment
import bosca.content.image.model.TemplateImageAttribute
import bosca.content.image.service.ImageGenerationService
import bosca.content.image.service.ImageService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.UUID
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

@OptIn(bosca.di.annotation.InternalDI::class)
class ImagesMutationGraphQLTest {
    private val attachments = mockk<ImageService>()
    private val images = mockk<ImageGenerationService>()
    private val metadata = mockk<MetadataService>(relaxed = true)
    private val collections = mockk<CollectionService>(relaxed = true)
    private val group = Group(UUID.random(), "image-users", "Image users", GroupType.SYSTEM)
    private val parent = Collection(UUID.random(), "Images", "en", workflowStateId = "draft")
    private val source = Metadata(UUID.random(), name = "Source", type = MetadataType.STANDARD, contentType = "image/png", contentLength = 10, languageTag = "en", workflowStateId = "published")
    private val target = source.copy(id = UUID.random(), name = "Article", contentType = "bosca/v-document")
    private val variant = CollectionLanguageVariant(id = parent.id, languageTag = "fr", name = "Images FR", workflowStateId = "draft")
    private val attribute = TemplateImageAttribute("hero", "Hero", "Cover image", "image.featured", false, listOf(740, 416), 16.0 / 9.0)
    private val result = source.copy(id = UUID.random(), name = "Generated")
    private lateinit var graphql: GraphQL
    private fun auth(scopes: List<String>, editor: Boolean = false) = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(Principal(UUID.random(), anonymous = false),
            listOf(if (editor) group.copy(name = "editors") else group), scopes, null, 7)
    }

    @BeforeTest fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("image-graphql-test") }
        val manager = mockk<ConnectionManager>(relaxed = true)
        provides<ConnectionPool> { mockk { every { connection() } returns manager } }
        val groups = GroupEvaluator(mockk())
        val controller = ImagesMutationController(images, attachments, metadata, collections,
            MetadataPermissionEvaluator(metadata, mockk(), groups), CollectionPermissionEvaluator(collections, mockk(), groups), groups)
        coEvery { collections.getById(parent.id) } returns parent
        coEvery { metadata.getById(source.id) } returns source
        coEvery { metadata.getById(target.id) } returns target
        coEvery { metadata.getPermissions(target) } returns listOf(Permission(group.id, PermissionAction.EDIT))
        coEvery { collections.getLanguageVariant(parent.id, "fr") } returns variant
        coEvery { collections.getPermissions(variant) } returns listOf(Permission(group.id, PermissionAction.EDIT))
        coEvery { attachments.attach(source, target, any(), any()) } returns ImageAttachment(true, true, attribute = attribute,
            availableAttributes = listOf(attribute), crop = Coordinates(1f, 2f, 300f, 200f), collaborationUpdated = true, optimized = true)
        coEvery { attachments.attach(source, parent, any(), any(), any()) } returns ImageAttachment(true, true, targetLanguageTag = "fr")
        coEvery { collections.getPermissions(parent) } returns listOf(Permission(group.id, PermissionAction.EDIT))
        coEvery { metadata.getPermissions(source) } returns listOf(Permission(group.id, PermissionAction.VIEW))
        coEvery { images.generate(any(), any(), any(), any()) } returns result
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", bosca.graphql.scalars.UUID.Type)
            type("Query") { field("ok") { true } }
            type("Mutation") { field("content") { ContentMutation } }
            type("ContentMutation") { field("images", ContentMutationControllerDispatcher(ContentMutationController()).type.fieldResolvers.getValue("images")) }
            type(ImagesMutationControllerDispatcher(controller).type)
            type(ImageAttachmentControllerDispatcher(ImageAttachmentController()).type)
            type(TemplateImageAttributeControllerDispatcher(TemplateImageAttributeController()).type)
            type(ImageCropControllerDispatcher(ImageCropController()).type)
            type("Metadata") { field("id") { (it.source as Metadata).id } }
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/content.graphqls")).bufferedReader().use { it.readText() }
        val imageType = listOf("type ImagesMutation", "type ImageAttachment", "type TemplateImageAttribute", "input ImageCropInput", "type ImageCrop")
            .joinToString("\n") { requireNotNull(Regex("$it \\{[^}]*}").find(sdl)).value }
        graphql = GraphQL(ExecutableSchema.fromSdl("""
            scalar UUID
            type Query { ok: Boolean! }
            type Mutation { content: ContentMutation! }
            type ContentMutation { images: ImagesMutation! }
            type Metadata { id: UUID! }
            $imageType
        """.trimIndent(), wiring), exceptionHandler = DataFetcherExceptionHandler { exception, path, location, _ ->
            GraphQLError(exception.toString(), path = path, locations = listOfNotNull(location))
        })
    }
    @AfterTest fun cleanup() { ProviderRegistry.clear() }
    private suspend fun call(field: String, authentication: AuthenticationContext) = graphql.execute(GraphQLRequest(
        "mutation { content { images { $field { id } } } }", context = GraphQLContext(mapOf("authenticationContext" to authentication))))

    @Test fun `generated namespace dispatch uses destination permissions and SDL defaults`() = runTest {
        val caller = auth(listOf("content:edit", "content:view", "collections:edit"))
        assertTrue(call("generate(prompt:\"a bird\",parentCollectionId:\"${parent.id}\")", caller).errors.isEmpty())
        assertTrue(call("edit(sourceId:\"${source.id}\",prompt:\"make it blue\",parentCollectionId:\"${parent.id}\")", caller).errors.isEmpty())
        coVerify { images.generate("a bird", "Generated Image", null, parent) }
        coVerify { images.generate("make it blue", "Edited Image", source, parent) }
        assertTrue(call("generate(prompt:\"a bird\",name:\"Bird\")", auth(listOf("content:edit"), editor = true)).errors.isEmpty())
        coVerify { images.generate("a bird", "Bird", null, null) }
    }

    @Test fun `missing scopes grants source or destination fail before provider execution`() = runTest {
        val generate = "generate(prompt:\"a bird\",parentCollectionId:\"${parent.id}\")"
        val edit = "edit(sourceId:\"${source.id}\",prompt:\"blue\",parentCollectionId:\"${parent.id}\")"
        assertTrue(call(generate, auth(listOf("content:view"))).errors.isNotEmpty())
        assertTrue(call(generate, auth(listOf("content:edit", "content:view"))).errors.isNotEmpty())
        assertTrue(call("generate(prompt:\"a bird\")", auth(listOf("content:edit", "collections:edit"))).errors.isNotEmpty())
        assertTrue(call(edit, auth(listOf("content:edit", "collections:edit"))).errors.isNotEmpty())
        coEvery { metadata.getPermissions(source) } returns emptyList()
        assertTrue(call(edit, auth(listOf("content:edit", "content:view", "collections:edit"))).errors.isNotEmpty())
        coEvery { metadata.getById(source.id) } returns null
        assertTrue(call(edit, auth(listOf("content:edit", "content:view", "collections:edit"))).errors.isNotEmpty())
        coEvery { collections.getPermissions(parent) } returns emptyList()
        assertTrue(call(generate, auth(listOf("content:edit", "collections:edit"))).errors.isNotEmpty())
        coEvery { collections.getById(parent.id) } returns null
        assertTrue(call(generate, auth(listOf("content:edit", "collections:edit"))).errors.isNotEmpty())
        coVerify(exactly = 0) { images.generate(any(), any(), any(), any()) }
    }
    private suspend fun attach(arguments: String, authentication: AuthenticationContext) = graphql.execute(GraphQLRequest(
        """mutation { content { images { attach(imageMetadataId:"${source.id}", $arguments) {
            success attached targetLanguageTag collaborationUpdated optimized warning error
            attribute { key name description relationship list targetSize defaultAspectRatio }
            availableAttributes { key } crop { top left width height }
        } } } }""", context = GraphQLContext(mapOf("authenticationContext" to authentication))))

    @Test fun `attachment dispatch authorizes targets and serializes template choices and crop`() = runTest {
        val caller = auth(listOf("content:view", "content:edit", "collections:edit"))
        val response = attach("""targetMetadataId:"${target.id}", attributeKey:"hero", crop:{top:1,left:2,width:300,height:200}""", caller)
        assertTrue(response.errors.isEmpty(), response.errors.toString())
        assertContains(response.toString(), "image.featured")
        assertContains(response.toString(), "740")
        coVerify { attachments.attach(source, target, "hero", Coordinates(1f, 2f, 300f, 200f)) }
        val translated = attach("""targetCollectionId:"${parent.id}", targetLanguageTag:"fr"""", caller)
        assertTrue(translated.errors.isEmpty(), translated.errors.toString())
        coVerify { attachments.attach(source, parent, "fr", null, null) }
    }

    @Test fun `attachment rejects missing scope denied source and denied target before writes`() = runTest {
        val targetArgs = """targetMetadataId:"${target.id}""""
        assertTrue(attach(targetArgs, auth(listOf("content:edit"))).errors.isNotEmpty())
        assertTrue(attach(targetArgs, auth(listOf("content:view"))).errors.isNotEmpty())
        coEvery { metadata.getPermissions(source) } returns emptyList()
        assertTrue(attach(targetArgs, auth(listOf("content:view", "content:edit"))).errors.isNotEmpty())
        coEvery { metadata.getPermissions(source) } returns listOf(Permission(group.id, PermissionAction.VIEW))
        coEvery { metadata.getPermissions(target) } returns emptyList()
        assertTrue(attach(targetArgs, auth(listOf("content:view", "content:edit"))).errors.isNotEmpty())
        coEvery { collections.getPermissions(variant) } returns emptyList()
        coEvery { collections.getPermissions(parent) } returns emptyList()
        assertTrue(attach("""targetCollectionId:"${parent.id}", targetLanguageTag:"fr"""", auth(listOf("content:view", "collections:edit"))).errors.isNotEmpty())
        coVerify(exactly = 0) { attachments.attach(any(), any<Metadata>(), any(), any()) }
        coVerify(exactly = 0) { attachments.attach(any(), any<Collection>(), any(), any(), any()) }
    }

    @Test fun `invalid attachment target and language combinations fail without writes`() = runTest {
        val caller = auth(listOf("content:view", "content:edit", "collections:edit"))
        assertTrue(attach("attributeKey:null", caller).errors.isNotEmpty())
        assertTrue(attach("""targetMetadataId:"${target.id}", targetCollectionId:"${parent.id}"""", caller).errors.isNotEmpty())
        assertTrue(attach("""targetMetadataId:"${target.id}", targetLanguageTag:"fr"""", caller).errors.isNotEmpty())
        coVerify(exactly = 0) { attachments.attach(any(), any<Metadata>(), any(), any()) }
    }

}
