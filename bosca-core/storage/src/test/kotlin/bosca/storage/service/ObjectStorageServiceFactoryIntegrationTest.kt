package bosca.storage.service

import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.configuration.StorageConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import software.amazon.awssdk.services.s3.model.S3Exception

/** Exercises generated DI, independent filesystems and actual HTTP-bound S3 SDK clients. */
@OptIn(InternalDI::class)
class ObjectStorageServiceFactoryIntegrationTest {
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        StorageProviderRegistrar().register()
        directory = createTempDirectory("bosca-scoped-object-storage-")
    }

    @AfterTest
    fun tearDown() {
        if (::directory.isInitialized) directory.toFile().deleteRecursively()
        ProviderRegistry.clear()
    }

    @Test
    fun `content provider delegates configuration to the factory and closes the shared store at shutdown`(): Unit = runBlocking {
        val configuration = filesystem("application")
        val storage = mockk<ObjectStorageService>(relaxed = true)
        val factory = mockk<ObjectStorageServiceFactory>()
        coEvery { factory.create(configuration) } returns storage
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) { factory }
        val application = BoscaApplication(ApplicationConfig.load("""
            content:
              storage:
                type: fs
                urlPrefix: ""
                basePath: "${configuration.basePath}"
        """.trimIndent().byteInputStream()))
        try {
            assertSame(storage, provide<ObjectStorageService>())
            assertSame(storage, provide<ObjectStorageService>())
            coVerify(exactly = 1) { factory.create(configuration) }
            verify(exactly = 0) { storage.close() }
        } finally {
            application.shutdown()
        }
        verify(exactly = 1) { storage.close() }
    }

    @Test
    fun `created S3 stores stay open until their caller closes them`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(content = "source").use { sourceEndpoint ->
            ObjectStorageS3TestEndpoint(content = "destination").use { targetEndpoint ->
                val source = factory.create(s3(sourceEndpoint, key = "SOURCE_KEY"))
                try {
                    factory.create(s3(targetEndpoint, key = "DESTINATION_KEY")).use { target ->
                        assertEquals("source", source.getString(StringObjectPath("asset")))
                        assertEquals("destination", target.getString(StringObjectPath("asset")))
                        assertNotSame(source, target)
                    }
                    assertEquals("source", source.getString(StringObjectPath("asset")))
                } finally {
                    source.close()
                }
                assertClosed(source, sourceEndpoint)
                assertTrue(sourceEndpoint.authorizationHeaders.all { it.contains("Credential=SOURCE_KEY/") })
                assertTrue(targetEndpoint.authorizationHeaders.all { it.contains("Credential=DESTINATION_KEY/") })
            }
        }
    }

    @Test
    fun `generated factory can open a filesystem without application content or security services`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        val result = factory.withStorage(filesystem("source")) { storage ->
            assertIs<FileSystemObjectStorageService>(storage)
            write(storage, "asset", "source content")
            storage.getString(StringObjectPath("asset"))
        }

        assertEquals("source content", result)
    }

    @Test
    fun `nested filesystem stores retain independent content at identical keys`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        factory.withStorage(filesystem("source")) { source ->
            write(source, "asset", "source")
            factory.withStorage(filesystem("destination")) { target ->
                assertNotSame(source, target)
                write(target, "asset", "destination")
                assertEquals("source", source.getString(StringObjectPath("asset")))
                assertEquals("destination", target.getString(StringObjectPath("asset")))
            }
            assertEquals("source", source.getString(StringObjectPath("asset")))
        }
    }

    @Test
    fun `unsupported types and incomplete filesystem configuration fail before work`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        var invoked = false
        for (configuration in listOf(
            StorageConfiguration("unsupported", ""),
            StorageConfiguration("fs", ""),
        )) {
            assertFailsWith<IllegalArgumentException> {
                factory.withStorage(configuration) { invoked = true }
            }
        }
        assertEquals(false, invoked)
    }

    @Test
    fun `no-op storage remains available without an S3 client`(): Unit = runBlocking {
        provide<ObjectStorageServiceFactory>().withStorage(StorageConfiguration("noop", "")) {
            assertIs<NoOpObjectStorageService>(it)
        }
    }

    @Test
    fun `S3 scopes use the configured endpoint bucket and credentials`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(bucket = "source-bucket", content = "source data").use { endpoint ->
            factory.withStorage(s3(endpoint, "source-bucket", "SOURCE_KEY")) { storage ->
                assertIs<S3ObjectStorageService>(storage)
                assertEquals("source data", storage.getString(StringObjectPath("asset")))
            }

            assertEquals(listOf("/source-bucket/asset"), endpoint.paths)
            assertTrue(endpoint.authorizationHeaders.single().contains("Credential=SOURCE_KEY/"))
            assertEquals(0, endpoint.bucketChecks.get())
            assertEquals(0, endpoint.bucketCreations.get())
        }
    }

    @Test
    fun `nested S3 scopes use independent clients and leave the outer client open`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(content = "source").use { sourceEndpoint ->
            ObjectStorageS3TestEndpoint(content = "destination").use { targetEndpoint ->
                factory.withStorage(s3(sourceEndpoint, key = "SOURCE_KEY")) { source ->
                    assertEquals("source", source.getString(StringObjectPath("asset")))
                    factory.withStorage(s3(targetEndpoint, key = "DESTINATION_KEY")) { target ->
                        assertNotSame(source, target)
                        assertEquals("destination", target.getString(StringObjectPath("asset")))
                    }
                    assertEquals("source", source.getString(StringObjectPath("asset")))
                }
                assertTrue(sourceEndpoint.authorizationHeaders.all { it.contains("Credential=SOURCE_KEY/") })
                assertTrue(targetEndpoint.authorizationHeaders.all { it.contains("Credential=DESTINATION_KEY/") })
            }
        }
    }

    @Test
    fun `scoped stores preserve the singleton content store until application shutdown`(): Unit = runBlocking {
        ObjectStorageS3TestEndpoint(content = "application content").use { contentEndpoint ->
            ObjectStorageS3TestEndpoint(content = "backup content").use { backupEndpoint ->
                val application = BoscaApplication(ApplicationConfig.load("""
                    bosca:
                      server:
                        development: false
                    content:
                      storage:
                        type: s3
                        urlPrefix: ""
                        bucket: test-bucket
                        forcePathStyle: true
                        region: us-east-1
                        endpoint: "${contentEndpoint.endpoint}"
                        accessKeyId: CONTENT_KEY
                        accessKeySecret: test-secret
                """.trimIndent().byteInputStream()))
                val content = provide<ObjectStorageService>()
                try {
                    assertEquals("application content", content.getString(StringObjectPath("asset")))

                    provide<ObjectStorageServiceFactory>().withStorage(s3(backupEndpoint, key = "BACKUP_KEY")) { backup ->
                        assertEquals("backup content", backup.getString(StringObjectPath("asset")))
                        assertSame(content, provide<ObjectStorageService>())
                    }

                    assertEquals("application content", content.getString(StringObjectPath("asset")))
                    assertTrue(contentEndpoint.authorizationHeaders.all { it.contains("Credential=CONTENT_KEY/") })
                    assertTrue(backupEndpoint.authorizationHeaders.all { it.contains("Credential=BACKUP_KEY/") })
                } finally {
                    application.shutdown()
                }
                assertClosed(content, contentEndpoint)
            }
        }
    }

    @Test
    fun `S3 content can stream into an independently configured filesystem destination`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(content = "portable data").use { endpoint ->
            factory.withStorage(s3(endpoint)) { source ->
                factory.withStorage(filesystem("backup")) { target ->
                    source.getInputStream(StringObjectPath("asset")).use {
                        assertEquals(13L, target.setInputStream(StringObjectPath("retained/asset"), it, 13))
                    }
                }
            }
        }
        factory.withStorage(filesystem("backup")) {
            assertEquals("portable data", it.getString(StringObjectPath("retained/asset")))
        }
    }

    @Test
    fun `S3 clients close after successful scoped work`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint().use { endpoint ->
            lateinit var captured: ObjectStorageService
            val result = factory.withStorage(s3(endpoint)) {
                captured = it
                it.getString(StringObjectPath("asset"))
            }

            assertEquals("data", result)
            assertClosed(captured, endpoint)
        }
    }

    @Test
    fun `work failure propagates unchanged and closes the S3 client`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        val failure = IOException("work failed")
        ObjectStorageS3TestEndpoint().use { endpoint ->
            lateinit var captured: ObjectStorageService
            val thrown = assertFailsWith<IOException> {
                factory.withStorage(s3(endpoint)) {
                    captured = it
                    assertEquals("data", it.getString(StringObjectPath("asset")))
                    throw failure
                }
            }

            assertSame(failure, thrown.cause ?: thrown)
            assertClosed(captured, endpoint)
        }
    }

    @Test
    fun `cancellation propagates unchanged and closes the S3 client`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        val cancellation = CancellationException("cancelled work")
        ObjectStorageS3TestEndpoint().use { endpoint ->
            lateinit var captured: ObjectStorageService
            val thrown = assertFailsWith<CancellationException> {
                factory.withStorage(s3(endpoint)) {
                    captured = it
                    assertEquals("data", it.getString(StringObjectPath("asset")))
                    throw cancellation
                }
            }

            assertSame(cancellation, thrown.cause ?: thrown)
            assertClosed(captured, endpoint)
        }
    }

    @Test
    fun `actual coroutine cancellation closes the active scoped client`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint().use { endpoint ->
            val ready = CompletableDeferred<ObjectStorageService>()
            val work = async {
                factory.withStorage(s3(endpoint)) {
                    assertEquals("data", it.getString(StringObjectPath("asset")))
                    ready.complete(it)
                    awaitCancellation()
                }
            }
            try {
                val captured = withTimeout(10_000) { ready.await() }
                work.cancelAndJoin()
                assertTrue(work.isCancelled)
                assertClosed(captured, endpoint)
            } finally {
                work.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancellation during bucket setup prevents work from starting`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val invoked = AtomicBoolean()
        ObjectStorageS3TestEndpoint(onHead = {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
        }).use { endpoint ->
            val work = async {
                factory.withStorage(s3(endpoint).copy(createBucket = true)) { invoked.set(true) }
            }
            try {
                withTimeout(10_000) { entered.await() }
                work.cancel()
                release.countDown()
                work.join()
                assertEquals(false, invoked.get())
            } finally {
                release.countDown()
                work.cancelAndJoin()
            }
        }
    }

    @Test
    fun `incomplete S3 configuration fails before scoped work`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        val valid = StorageConfiguration(
            "s3", "", bucket = "test-bucket", endpoint = "http://127.0.0.1:1", region = "us-east-1",
            accessKeyId = "TEST_KEY", accessKeySecret = "test-secret",
        )
        var invoked = false
        for (configuration in listOf(
            valid.copy(bucket = null),
            valid.copy(accessKeySecret = null),
            valid.copy(endpoint = "not a valid URI"),
        )) {
            assertFailsWith<IllegalArgumentException> {
                factory.withStorage(configuration) { invoked = true }
            }
        }
        assertEquals(false, invoked)
    }

    @Test
    fun `bucket creation is opt-in and checks existing buckets`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint().use { endpoint ->
            factory.withStorage(s3(endpoint).copy(createBucket = true)) {
                assertEquals("data", it.getString(StringObjectPath("asset")))
            }

            assertEquals(1, endpoint.bucketChecks.get())
            assertEquals(0, endpoint.bucketCreations.get())
        }
    }

    @Test
    fun `missing buckets are created only when requested`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(headStatus = 404).use { endpoint ->
            factory.withStorage(s3(endpoint).copy(createBucket = true)) {
                assertEquals("data", it.getString(StringObjectPath("asset")))
            }

            assertEquals(1, endpoint.bucketChecks.get())
            assertEquals(1, endpoint.bucketCreations.get())
        }
    }

    @Test
    fun `denied bucket setup propagates and does not create a bucket or invoke work`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(headStatus = 403).use { endpoint ->
            var invoked = false
            val failure = assertFailsWith<S3Exception> {
                factory.withStorage(s3(endpoint).copy(createBucket = true)) { invoked = true }
            }

            assertEquals(403, failure.statusCode())
            assertEquals(false, invoked)
            assertEquals(0, endpoint.bucketCreations.get())
        }
    }

    @Test
    fun `S3 read failures propagate instead of becoming empty or missing content`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        ObjectStorageS3TestEndpoint(getStatus = 403).use { endpoint ->
            val failure = assertFailsWith<S3Exception> {
                factory.withStorage(s3(endpoint)) { it.getString(StringObjectPath("asset")) }
            }
            assertEquals(403, failure.statusCode())
        }
    }

    @Test
    fun `S3 metadata uses a signed HEAD without downloading a multi-gigabyte object`(): Unit = runBlocking {
        val length = 5L * 1024 * 1024 * 1024 + 17
        ObjectStorageS3TestEndpoint(
            getStatus = 403, objectLength = length, objectEtag = "\"multipart-3\"",
            objectVersion = "opaque/+version", objectModified = "Sat, 17 Oct 2026 01:02:03 GMT",
        ).use { endpoint ->
            val configuration = s3(endpoint, key = "METADATA_KEY")
            val info = provide<ObjectStorageServiceFactory>().withStorage(configuration) {
                it.getObjectInfo(StringObjectPath("asset"))
            }
            assertEquals(ObjectStorageInfo(length, "\"multipart-3\"", "opaque/+version",
                OffsetDateTime.parse("2026-10-17T01:02:03Z")), info)
            assertEquals(1, endpoint.objectHeads.get())
            assertEquals(0, endpoint.objectGets.get())
            assertEquals(0, endpoint.bucketChecks.get())
            assertEquals(0, endpoint.bucketCreations.get())
            assertTrue(endpoint.authorizationHeaders.all { it.contains("Credential=METADATA_KEY/") })
        }
    }

    @Test
    fun `S3 metadata distinguishes real missing and access denied HTTP responses`(): Unit = runBlocking {
        val factory = provide<ObjectStorageServiceFactory>()
        for (status in listOf(404, 403)) {
            ObjectStorageS3TestEndpoint(objectHeadStatus = status).use { endpoint ->
                factory.withStorage(s3(endpoint)) { storage ->
                    if (status == 404) {
                        assertEquals("asset", assertFailsWith<ObjectNotFoundException> {
                            storage.getObjectInfo(StringObjectPath("asset"))
                        }.path.toString())
                    } else {
                        assertEquals(403, assertFailsWith<S3Exception> {
                            storage.getObjectInfo(StringObjectPath("asset"))
                        }.statusCode())
                    }
                }
                assertEquals(1, endpoint.objectHeads.get())
                assertEquals(0, endpoint.objectGets.get())
            }
        }
    }

    @Test
    fun `S3 metadata without retention identifiers does not invent a version`(): Unit = runBlocking {
        ObjectStorageS3TestEndpoint(objectVersion = "null").use { endpoint ->
            val info = provide<ObjectStorageServiceFactory>().withStorage(s3(endpoint)) {
                it.getObjectInfo(StringObjectPath("asset"))
            }
            assertEquals(ObjectStorageInfo(4), info)
            assertEquals(1, endpoint.objectHeads.get())
            assertEquals(0, endpoint.objectGets.get())
        }
    }

    @Test
    fun `S3 listing crosses signed provider pages with literal prefixes without reading assets`(): Unit = runBlocking {
        val prefix = "assets/🖼 +%/"
        ObjectStorageS3TestEndpoint(onList = { query ->
            200 to if (query["continuation-token"] == null) {
                listingPage(listOf(prefix + "a" to 1L, prefix + "b" to 7L), "next/+token")
            } else {
                listingPage(listOf(prefix + "c" to Long.MAX_VALUE))
            }
        }).use { endpoint ->
            val entries = provide<ObjectStorageServiceFactory>().withStorage(s3(endpoint, key = "LISTING_KEY")) {
                it.listObjects(prefix, 1, 2)
            }
            assertEquals(listOf(prefix + "b", prefix + "c"), entries.map { it.key })
            assertEquals(ObjectStorageInfo(Long.MAX_VALUE, "\"multipart-3\"",
                modified = OffsetDateTime.parse("2026-10-17T01:02:03Z")), entries.last().info)
            assertEquals(listOf("3", "1"), endpoint.listQueries.map { it["max-keys"] })
            assertEquals(listOf(null, "next/+token"), endpoint.listQueries.map { it["continuation-token"] })
            assertTrue(endpoint.listQueries.all { it["prefix"] == prefix && it["list-type"] == "2" && "delimiter" !in it })
            assertEquals(listOf("/test-bucket", "/test-bucket"), endpoint.paths.toList())
            assertEquals(0, endpoint.objectHeads.get())
            assertEquals(0, endpoint.objectGets.get())
            assertEquals(0, endpoint.bucketChecks.get())
            assertEquals(0, endpoint.bucketCreations.get())
            assertTrue(endpoint.authorizationHeaders.all { it.contains("Credential=LISTING_KEY/") })
        }
    }

    @Test
    fun `S3 listing does not return partial inventory after a later page is denied`(): Unit = runBlocking {
        ObjectStorageS3TestEndpoint(onList = { query ->
            if (query["continuation-token"] == null) 200 to listingPage(listOf("a" to 1L), "denied")
            else 403 to "<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>"
        }).use { endpoint ->
            val failure = assertFailsWith<S3Exception> {
                provide<ObjectStorageServiceFactory>().withStorage(s3(endpoint)) { it.listObjects("", 0, 2) }
            }
            assertEquals(403, failure.statusCode())
            assertEquals(2, endpoint.listQueries.size)
            assertEquals(0, endpoint.objectGets.get())
        }
    }

    @Test
    fun `S3 missing listing namespace stays a provider error`(): Unit = runBlocking {
        ObjectStorageS3TestEndpoint(onList = {
            404 to "<Error><Code>NoSuchBucket</Code><Message>Missing</Message></Error>"
        }).use { endpoint ->
            val failure = assertFailsWith<S3Exception> {
                provide<ObjectStorageServiceFactory>().withStorage(s3(endpoint)) { it.listObjects("", 0, 1) }
            }
            assertEquals(404, failure.statusCode())
            assertEquals(1, endpoint.listQueries.size)
            assertEquals(0, endpoint.objectHeads.get())
        }
    }

    @Test
    fun `S3 empty listings remain empty beyond large offsets`(): Unit = runBlocking {
        ObjectStorageS3TestEndpoint(onList = { 200 to listingPage(emptyList()) }).use { endpoint ->
            val entries = provide<ObjectStorageServiceFactory>().withStorage(s3(endpoint)) {
                it.listObjects("assets/", Long.MAX_VALUE, Int.MAX_VALUE)
            }
            assertTrue(entries.isEmpty())
            assertEquals(1, endpoint.listQueries.size)
            assertEquals(0, endpoint.objectGets.get())
        }
    }

    private fun listingPage(entries: List<Pair<String, Long>>, next: String? = null): String = buildString {
        append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<IsTruncated>${next != null}</IsTruncated>")
        if (next != null) append("<NextContinuationToken>${xml(next)}</NextContinuationToken>")
        for ((key, length) in entries) {
            append("<Contents><Key>${xml(key)}</Key><Size>$length</Size>")
            append("<ETag>&quot;multipart-3&quot;</ETag><LastModified>2026-10-17T01:02:03Z</LastModified></Contents>")
        }
        append("</ListBucketResult>")
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun filesystem(name: String) = StorageConfiguration("fs", "", basePath = directory.resolve(name).toString())

    private fun s3(
        endpoint: ObjectStorageS3TestEndpoint,
        bucket: String = "test-bucket",
        key: String = "TEST_KEY",
    ) = StorageConfiguration(
        "s3", "", bucket = bucket, forcePathStyle = true, region = "us-east-1",
        endpoint = endpoint.endpoint, accessKeyId = key, accessKeySecret = "test-secret",
    )

    private suspend fun write(storage: ObjectStorageService, path: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(path), bytes.inputStream(), bytes.size.toLong())
    }

    private suspend fun assertClosed(storage: ObjectStorageService, endpoint: ObjectStorageS3TestEndpoint) {
        val count = endpoint.paths.size
        assertFailsWith<Exception> { storage.getString(StringObjectPath("asset")) }
        assertEquals(count, endpoint.paths.size)
    }
}
