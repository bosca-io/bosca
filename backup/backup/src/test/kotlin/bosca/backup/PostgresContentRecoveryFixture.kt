package bosca.backup

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.graphql.MetadataContentUrlsController
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.MetadataContentUrls
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.video.service.VideoService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.ContentProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.documents.Content
import bosca.security.model.PermissionAction
import bosca.security.repository.PrincipalGroupRepositoryImpl
import bosca.security.repository.PrincipalRepositoryImpl
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.test.resources.SharedNatsContainer
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads recovered content through generated domain services with a new database and cache. */
@OptIn(InternalDI::class)
internal class PostgresContentRecoveryFixture(private val root: Path, private val json: Json) {
    private val image = UUID.parse("10000000-0000-0000-0000-000000000001")
    private val publicImage = UUID.parse("10000000-0000-0000-0000-000000000002")
    private val document = UUID.parse("10000000-0000-0000-0000-000000000003")
    private val data = UUID.parse("10000000-0000-0000-0000-000000000004")
    private val guide = UUID.parse("10000000-0000-0000-0000-000000000005")
    private val documentTemplate = UUID.parse("10000000-0000-0000-0000-000000000006")
    private val dataTemplate = UUID.parse("10000000-0000-0000-0000-000000000007")
    private val guideTemplate = UUID.parse("10000000-0000-0000-0000-000000000008")
    private val collectionTemplate = UUID.parse("10000000-0000-0000-0000-000000000009")
    private val step = UUID.parse("10000000-0000-0000-0000-000000000010")
    private val module = UUID.parse("10000000-0000-0000-0000-000000000011")
    private val collection = UUID.parse("20000000-0000-0000-0000-000000000001")
    private val supplementary = UUID.parse("30000000-0000-0000-0000-000000000001")
    private val collectionSupplementary = UUID.parse("30000000-0000-0000-0000-000000000002")
    private val readers = UUID.parse("40000000-0000-0000-0000-000000000001")
    private val reader = UUID.parse("50000000-0000-0000-0000-000000000001")
    private val outsider = UUID.parse("50000000-0000-0000-0000-000000000002")
    private val body = """{"document":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Restored document"}]}]}}"""
    private val defaults = """{"answer":7}"""
    private val configuration = """{"theme":"light"}"""
    private val recurrence = "RRULE:FREQ=DAILY;COUNT=2"

    fun seed(sql: (String) -> Unit) {
        sql("""
            insert into groups (id, name, description) values ('$readers', 'recovery.readers', 'Private content readers');
            insert into principals (id, verified, anonymous) values ('$reader', true, false), ('$outsider', true, false);
            insert into principal_groups (principal, group_id) values ('$reader', '$readers');
            update metadata set workflow_state_id = 'published', public = true, public_content = true
                where id = '$publicImage';
            insert into metadata_versions (id, version, name, content_type, content_length)
                values ('$image', 6, 'Earlier image', 'image/png', 7);
            insert into metadata_supplementary (id, metadata_id, key, name, content_type, content_length, uploaded)
                values ('$supplementary', '$image', 'thumbnail', 'Thumbnail', 'image/png', 9, now());
            insert into collections (id, name) values ('$collection', 'Recovered collection');
            insert into collection_supplementary (id, collection_id, key, name, content_type, content_length, uploaded)
                values ('$collectionSupplementary', '$collection', 'cover', 'Cover', 'image/png', 5, now());
            insert into metadata (id, name, content_type) values ('$document', 'Document', 'bosca/v-document');
            insert into documents (metadata_id, version, title, content)
                values ('$document', 1, 'Recovered document', '$body');
            insert into metadata (id, name, content_type, attributes) values
                ('$data', 'Data', 'bosca/v-data', '{"answer":42}'),
                ('$guide', 'Guide', 'bosca/v-guide', '{}'),
                ('$documentTemplate', 'Document template', 'bosca/v-document-template', '{}'),
                ('$dataTemplate', 'Data template', 'bosca/v-data-template', '{}'),
                ('$guideTemplate', 'Guide template', 'bosca/v-guide-template', '{}'),
                ('$collectionTemplate', 'Collection template', 'bosca/v-collection-template', '{}'),
                ('$step', 'Guide step', 'bosca/v-guide-step', '{}'),
                ('$module', 'Guide module', 'bosca/v-guide-step-module', '{}');
            insert into document_templates (metadata_id, version, content, configuration, schema, default_attributes)
                values ('$documentTemplate', 1, '$body', '$configuration', '{"type":"object"}', '$defaults');
            insert into data_templates (metadata_id, version, type, default_attributes)
                values ('$dataTemplate', 1, 'attributes', '$defaults');
            insert into data (metadata_id, version, type, template_metadata_id, template_metadata_version)
                values ('$data', 1, 'attributes', '$dataTemplate', 1);
            insert into guide_templates (metadata_id, version, rrule, type, configuration, default_attributes)
                values ('$guideTemplate', 1, '$recurrence', 'linear_progress', '$configuration', '$defaults');
            insert into guides (metadata_id, version, rrule, type, template_metadata_id, template_metadata_version)
                values ('$guide', 1, '$recurrence', 'linear_progress', '$guideTemplate', 1);
            insert into documents (metadata_id, version, title, content) values
                ('$step', 1, 'Recovered step', '$body'), ('$module', 1, 'Recovered module', '$body');
            insert into guide_steps (metadata_id, version, id, step_metadata_id, step_metadata_version, sort)
                values ('$guide', 1, 13, '$step', 1, 0);
            insert into guide_step_modules (metadata_id, version, step, id, module_metadata_id, module_metadata_version, sort)
                values ('$guide', 1, 13, 17, '$module', 1, 0);
            insert into collection_templates (metadata_id, version, configuration, default_attributes)
                values ('$collectionTemplate', 1, '$configuration', '$defaults');
            insert into metadata_relationships (metadata1_id, metadata2_id, relationship, attributes)
                values ('$document', '$image', 'illustration', '{"caption":"Preserved"}');
            insert into collection_items (collection_id, child_metadata_id, attributes)
                values ('$collection', '$document', '{"sort":10}'), ('$collection', '$image', '{"sort":20}');
            insert into metadata_permissions (metadata_id, group_id, action)
                values ('$image', '$readers', 'view'), ('$document', '$readers', 'view');
            insert into collection_permissions (collection_id, group_id, action)
                values ('$collection', '$readers', 'view');
        """.trimIndent())
        asset("metadata/$image/6/content", "earlier")
        asset("metadata/$image/7/supplementary/$supplementary", "thumbnail")
        asset("collection/$collection/supplementary/$collectionSupplementary", "cover")
    }

    private fun asset(key: String, value: String) {
        val file = root.resolve("operation-source/$key")
        Files.createDirectories(file.parent)
        file.writeText(value)
    }

    suspend fun verify(pool: ConnectionPool) {
        SharedNatsContainer().apply { start() }.use { nats ->
            val cache = NatsCacheManager(nats.newConnectionPool(1))
            val serializer = RequestCacheSerializerImpl(json)
            provides<ConnectionPool>(singleton = true, overrideExisting = true) { pool }
            provides<CacheManager>(singleton = true, overrideExisting = true) { cache }
            provides<RequestCacheSerializer>(singleton = true, overrideExisting = true) { serializer }
            ContentProviderRegistrar().register()
            // These services are outside the read checks; unexpected calls fail.
            provides<BibleService>(singleton = true, overrideExisting = true) { mockk() }
            provides<VideoService>(singleton = true, overrideExisting = true) { mockk() }
            provides<SecurityService>(singleton = true, overrideExisting = true) { mockk() }
            provides<SlugService>(singleton = true, overrideExisting = true) { mockk() }
            val metadata = provide<MetadataService>()
            val collections = provide<CollectionService>()
            val documents = provide<DocumentService>()
            val storage = provide<ObjectStorageService>()
            request(pool, cache, serializer) {
                val current = assertNotNull(metadata.getById(image))
                assertEquals(7, current.version)
                assertEquals("Saved image", current.name)
                assertEquals("image", storage.download(current).use { it.reader().readText() })
                val history = metadata.getStorageReferences(0, 100).single {
                    it.contentId == image && it.version == 6
                }
                assertEquals(7L, history.contentLength)
                assertEquals("earlier", storage.download(current.copy(version = assertNotNull(history.version))).use { it.reader().readText() })
                val thumbnail = metadata.getSupplementary(image).single()
                assertEquals(supplementary, thumbnail.id)
                assertEquals("thumbnail", storage.download(current, thumbnail.id).use { it.reader().readText() })
                val restoredDocument = assertNotNull(documents.getDocument(document, 1))
                assertEquals("Recovered document", restoredDocument.title)
                assertEquals(json.parseToJsonElement(body), json.encodeToJsonElement(Content.serializer(), assertNotNull(restoredDocument.content)))
                val restoredData = assertNotNull(provide<DataService>().getData(data, 1))
                assertEquals(DataType.ATTRIBUTES, restoredData.type)
                assertEquals(dataTemplate, restoredData.templateMetadataId)
                assertEquals(1, restoredData.templateMetadataVersion)
                assertEquals(JsonPrimitive(42), assertNotNull(metadata.getById(data)).attributes?.jsonObject?.get("answer"))
                val restoredDataTemplate = assertNotNull(provide<DataTemplateService>().getTemplate(dataTemplate, 1))
                assertEquals(DataType.ATTRIBUTES, restoredDataTemplate.type)
                assertEquals(json.parseToJsonElement(defaults), restoredDataTemplate.defaultAttributes)
                val restoredDocumentTemplate = assertNotNull(provide<DocumentTemplateService>().getTemplate(documentTemplate, 1))
                assertEquals(json.parseToJsonElement(body), json.encodeToJsonElement(Content.serializer(), assertNotNull(restoredDocumentTemplate.content)))
                assertEquals(json.parseToJsonElement(configuration), restoredDocumentTemplate.configuration)
                assertEquals(json.parseToJsonElement("""{"type":"object"}"""), restoredDocumentTemplate.schema)
                assertEquals(json.parseToJsonElement(defaults), restoredDocumentTemplate.defaultAttributes)
                val restoredCollectionTemplate = assertNotNull(provide<CollectionTemplateService>().getCollectionTemplate(collectionTemplate, 1))
                assertEquals(json.parseToJsonElement(configuration), restoredCollectionTemplate.configuration)
                assertEquals(json.parseToJsonElement(defaults), restoredCollectionTemplate.defaultAttributes)
                val restoredGuideTemplate = assertNotNull(provide<GuideTemplateService>().getTemplate(guideTemplate, 1))
                assertEquals(recurrence, restoredGuideTemplate.rrule)
                assertEquals(GuideType.LINEAR_PROGRESS, restoredGuideTemplate.type)
                assertEquals(json.parseToJsonElement(configuration), restoredGuideTemplate.configuration)
                assertEquals(json.parseToJsonElement(defaults), restoredGuideTemplate.defaultAttributes)
                val guides = provide<GuideService>()
                val restoredGuide = assertNotNull(guides.getGuide(guide, 1))
                assertEquals(recurrence, restoredGuide.rrule)
                assertEquals(GuideType.LINEAR_PROGRESS, restoredGuide.type)
                assertEquals(guideTemplate, restoredGuide.templateMetadataId)
                assertEquals(1, restoredGuide.templateMetadataVersion)
                assertEquals(1L, guides.getStepCount(guide, 1))
                val restoredStep = guides.getGuideSteps(guide, 1, 0, 10).single()
                assertEquals(13L, restoredStep.id)
                assertEquals(step, restoredStep.stepMetadataId)
                assertEquals(1, restoredStep.stepMetadataVersion)
                val restoredModule = guides.getGuideStepModules(guide, 1, restoredStep.id).single()
                assertEquals(17L, restoredModule.id)
                assertEquals(module, restoredModule.moduleMetadataId)
                assertEquals(1, restoredModule.moduleMetadataVersion)
                assertEquals("Recovered step", assertNotNull(documents.getDocument(step, 1)).title)
                assertEquals("Recovered module", assertNotNull(documents.getDocument(module, 1)).title)
                val link = metadata.getRelationships(document).single()
                assertEquals(image, link.metadataId2)
                assertEquals("illustration", link.relationship)
                assertEquals(JsonPrimitive("Preserved"), link.attributes?.jsonObject?.get("caption"))
                val parent = assertNotNull(collections.getById(collection))
                val items = collections.getItems(collection, null, 0, 20, null, null)
                assertEquals(listOf(document, image), items.map { it.childMetadataId })
                assertEquals(listOf(10, 20), items.map { it.attributes?.jsonObject?.get("sort")?.toString()?.toInt() })
                val cover = collections.getSupplementary(collection).single()
                assertEquals(collectionSupplementary, cover.id)
                assertEquals("cover", storage.download(parent, cover.id).use { it.reader().readText() })
                val security = provide<SecurityService>()
                val groups = GroupEvaluator(security)
                val metadataPermissions = MetadataPermissionEvaluator(metadata, security, groups)
                val collectionPermissions = CollectionPermissionEvaluator(collections, security, groups)
                val allowed = authentication(reader)
                val denied = authentication(outsider)
                assertEquals(listOf(readers), metadata.getPermissions(current).map { it.groupId })
                assertTrue(metadataPermissions.isContentAllowed(allowed, current, PermissionAction.VIEW))
                assertFalse(metadataPermissions.isContentAllowed(denied, current, PermissionAction.VIEW))
                assertFalse(metadataPermissions.isContentAllowed(null, current, PermissionAction.VIEW))
                assertTrue(collectionPermissions.isAllowed(allowed, parent, PermissionAction.VIEW))
                assertFalse(collectionPermissions.isAllowed(denied, parent, PermissionAction.VIEW))
                val public = assertNotNull(metadata.getById(publicImage))
                assertTrue(metadataPermissions.isContentAllowed(null, public, PermissionAction.VIEW))
                val identity = assertNotNull(allowed.principal())
                val restoredGroups = PrincipalGroupRepositoryImpl().getPrincipalGroups(reader)
                val restricted = object : AuthenticationContext(null, null) {
                    override fun principal() = ScopedAuthenticatedPrincipal(identity.asPrincipal(), restoredGroups, listOf("collections:view"), null, 1)
                }
                assertFalse(metadataPermissions.isContentAllowed(restricted, current, PermissionAction.VIEW))
                val urls = MetadataContentUrlsController(metadataPermissions, storage)
                assertNull(urls.download(denied, MetadataContentUrls(current), false))
                assertNull(urls.download(null, MetadataContentUrls(current), false))
                val url = assertNotNull(urls.download(null, MetadataContentUrls(public), false)).url
                assertTrue(url.startsWith("https://recovered.invalid/"), url)
                assertFalse(url.contains("source.invalid"), url)
            }
            assertEquals(0, pool.activeConnections)
        }
    }

    private suspend fun authentication(id: UUID): AuthenticationContext {
        val principal = assertNotNull(PrincipalRepositoryImpl().getPrincipalById(id))
        val groups = PrincipalGroupRepositoryImpl().getPrincipalGroups(id)
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private suspend fun request(
        pool: ConnectionPool,
        cache: CacheManager,
        serializer: RequestCacheSerializer,
        block: suspend () -> Unit,
    ) {
        val manager = ConnectionManager(pool)
        withContext(manager.asCoroutineContext() + RequestCache(cache, serializer).asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }
}
