@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.UploadChunkPath
import bosca.artifacts.model.UploadSessionPath
import bosca.artifacts.repository.ArtifactRepoRepositoryImpl
import bosca.artifacts.repository.ArtifactsMigration
import bosca.artifacts.repository.BlobRepositoryImpl
import bosca.artifacts.repository.NamespacePermissionRepository
import bosca.artifacts.repository.NamespaceRepositoryImpl
import bosca.artifacts.repository.TagRepository
import bosca.artifacts.repository.UploadSessionRepository
import bosca.artifacts.repository.UploadSessionRepositoryImpl
import bosca.artifacts.repository.VersionRepository
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.ArtifactRepositoryServiceImpl
import bosca.artifacts.service.BlobStorageService
import bosca.artifacts.service.BlobStorageServiceImpl
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationProviders
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestHeaders
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.storage.configuration.ContentConfiguration
import bosca.storage.service.S3ObjectStorageService
import bosca.storage.service.UrlSigner
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.OutputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** Exercises the Docker multipart route lifecycle against PostgreSQL and an S3-compatible server. */
class DockerMultipartUploadEndToEndTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var s3Proxy: GenericContainer<*>
    private lateinit var connectionPool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var s3Client: S3Client
    private lateinit var repositoryService: ArtifactRepositoryService
    private lateinit var blobStorageService: BlobStorageService
    private lateinit var uploadSessionRepository: UploadSessionRepository
    private lateinit var permissionEvaluator: ArtifactPermissionEvaluator

    @BeforeTest
    fun setup() {
        runBlocking {
            assumeTrue("Docker is required for the multipart upload end-to-end test", dockerAvailable())

            postgres = SharedPostgreSQLContainer()
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "test")
                .withEnv("POSTGRES_PASSWORD", "test")
                .withEnv("POSTGRES_DB", "artifacts_multipart")
                .withReuse(true)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
            postgres.start()

            s3Proxy = GenericContainer("andrewgaul/s3proxy:3.0.0")
                .withExposedPorts(S3_PORT)
                .withEnv("S3PROXY_AUTHORIZATION", "aws-v2-or-v4")
                .withEnv("S3PROXY_IDENTITY", ACCESS_KEY)
                .withEnv("S3PROXY_CREDENTIAL", SECRET_KEY)
                .withEnv("S3PROXY_ENDPOINT", "http://0.0.0.0:$S3_PORT")
                .withEnv("JCLOUDS_PROVIDER", "transient")
                .withEnv("S3PROXY_IGNORE_UNKNOWN_HEADERS", "true")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
            s3Proxy.start()

            connectionPool = ConnectionPool(
                ConnectionFactoryImpl(
                    ConnectionConfig(
                        url = postgres.jdbcUrl,
                        user = postgres.username,
                        password = postgres.password,
                        maxConnections = 2,
                    ),
                    key = "docker-multipart-e2e",
                ),
            )
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS artifacts CASCADE") }
            }
            FlywayMigration(connectionPool).migrate(listOf(CoreMigration(), ArtifactsMigration()))

            ProviderRegistry.clear()
            provides<ConnectionPool>(singleton = true) { connectionPool }
            provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
            provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
            provides<SecurityService>(singleton = true) { mockk(relaxed = true) }
            provides<AuthenticationProviders>(singleton = true) { AuthenticationProviders(emptyArray()) }

            application = BoscaApplication(
                ApplicationConfig.load(
                    """
                    content:
                      storage:
                        type: s3
                        urlPrefix: ""
                        bucket: $BUCKET
                        forcePathStyle: true
                        region: us-east-1
                        endpoint: http://${s3Proxy.host}:${s3Proxy.getMappedPort(S3_PORT)}
                        accessKeyId: $ACCESS_KEY
                        accessKeySecret: $SECRET_KEY
                    """.trimIndent().byteInputStream(),
                ),
            )
            s3Client = ContentConfiguration().amazonS3(application)
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())

            val objectStorage = S3ObjectStorageService(
                bucket = BUCKET,
                client = s3Client,
                urlPrefix = "",
                urlUploadPrefix = "",
                urlSigner = UnusedProvider(UrlSigner::class),
                securityService = UnusedProvider(SecurityService::class),
            )
            val blobRepository = BlobRepositoryImpl()
            blobStorageService = BlobStorageServiceImpl(objectStorage, blobRepository)
            uploadSessionRepository = UploadSessionRepositoryImpl()
            repositoryService = ArtifactRepositoryServiceImpl(
                namespaceRepo = NamespaceRepositoryImpl(),
                namespacePermissionRepo = mockk<NamespacePermissionRepository>(relaxed = true),
                repoRepo = ArtifactRepoRepositoryImpl(),
                versionRepo = mockk<VersionRepository>(relaxed = true),
                tagRepo = mockk<TagRepository>(relaxed = true),
                uploadSessionRepo = uploadSessionRepository,
                blobStorage = blobStorageService,
                objectStorage = objectStorage,
                pubSubService = mockk<PubSubService>(relaxed = true),
            )

            permissionEvaluator = mockk(relaxed = true)
            coEvery {
                permissionEvaluator.evaluate(
                    any(),
                    "docker",
                    NAMESPACE,
                    REPOSITORY,
                    null,
                    ArtifactAction.PUSH,
                    false,
                )
            } returns true
        }
    }

    @AfterTest
    fun teardown() {
        runBlocking {
            if (::connectionPool.isInitialized) connectionPool.close()
            if (::s3Client.isInitialized) s3Client.close()
            if (::s3Proxy.isInitialized) s3Proxy.stop()
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `streaming PATCH without length persists and serves one multipart Docker blob`() = runTest(timeout = 2.minutes) {
        val init = call()
        DockerInitBlobUpload(repositoryService, permissionEvaluator).execute(init.call)

        assertEquals(HttpStatusCode.Accepted, init.response.status())
        assertEquals((5L * 1024L * 1024L).toString(), init.header("OCI-Chunk-Min-Length"))
        val uploadId = assertNotNull(init.header("Docker-Upload-UUID"))
        val sessionId = UUID.parse(uploadId)

        val firstPart = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val finalPart = ByteArray(8193) { index -> (index % 197).toByte() }
        val bytes = firstPart + finalPart
        val digest = "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).toHex()}"

        val patch = call(
            uploadId = uploadId,
            body = firstPart,
        )
        DockerChunkBlobUpload(repositoryService, permissionEvaluator, objectStorage()).execute(patch.call)

        assertEquals(HttpStatusCode.Accepted, patch.response.status())
        assertEquals("0-${firstPart.lastIndex}", patch.header("Range"))
        assertEquals(listOf(UploadSessionPath(uploadId).toString()), activeMultipartKeys())
        assertEquals(emptyList(), objectKeys(UploadChunkPath(uploadId, 0).toString()))
        withConnection {
            val persisted = assertNotNull(repositoryService.getUploadSession(sessionId))
            assertEquals(firstPart.size.toLong(), persisted.byteOffset)
            assertEquals(1, persisted.chunkCount)
            assertNotNull(persisted.digestState)
        }

        val replay = call(
            uploadId = uploadId,
            body = firstPart,
            headers = mapOf(
                HttpHeaders.ContentLength to firstPart.size.toString(),
                HttpHeaders.ContentRange to "0-${firstPart.lastIndex}",
            ),
        )
        DockerChunkBlobUpload(repositoryService, permissionEvaluator, objectStorage()).execute(replay.call)

        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, replay.response.status())
        assertEquals("0-${firstPart.lastIndex}", replay.header("Range"))
        withConnection {
            val persisted = assertNotNull(repositoryService.getUploadSession(sessionId))
            assertEquals(firstPart.size.toLong(), persisted.byteOffset)
            assertEquals(1, persisted.chunkCount)
        }

        val completion = call(
            uploadId = uploadId,
            body = finalPart,
            headers = mapOf(
                HttpHeaders.ContentLength to finalPart.size.toString(),
                HttpHeaders.ContentRange to "${firstPart.size}-${bytes.lastIndex}",
            ),
            query = mapOf("digest" to digest),
        )
        DockerCompleteBlobUpload(
            repositoryService,
            blobStorageService,
            permissionEvaluator,
            objectStorage(),
        ).execute(completion.call)

        assertEquals(HttpStatusCode.Created, completion.response.status())
        assertEquals(digest, completion.header("Docker-Content-Digest"))
        withConnection {
            assertNull(repositoryService.getUploadSession(sessionId))
            val blob = assertNotNull(blobStorageService.get(digest))
            assertEquals(UploadSessionPath(uploadId).toString(), blob.storagePath)
            val stored = blobStorageService.getInputStream(digest).use { it.readBytes() }
            assertContentEquals(bytes, stored)
        }
    }

    @Test
    fun `fixed-length PATCH persists and serves one multipart Docker blob`() = runTest(timeout = 2.minutes) {
        val init = call()
        DockerInitBlobUpload(repositoryService, permissionEvaluator).execute(init.call)

        assertEquals(HttpStatusCode.Accepted, init.response.status())
        val uploadId = assertNotNull(init.header("Docker-Upload-UUID"))
        val sessionId = UUID.parse(uploadId)

        val firstPart = ByteArray(5 * 1024 * 1024) { index -> (index % 239).toByte() }
        val finalPart = ByteArray(4097) { index -> (index % 181).toByte() }
        val bytes = firstPart + finalPart
        val digest = "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).toHex()}"

        val patch = call(
            uploadId = uploadId,
            body = firstPart,
            headers = mapOf(
                HttpHeaders.ContentLength to firstPart.size.toString(),
                HttpHeaders.ContentRange to "0-${firstPart.lastIndex}",
            ),
        )
        DockerChunkBlobUpload(repositoryService, permissionEvaluator, objectStorage()).execute(patch.call)

        assertEquals(HttpStatusCode.Accepted, patch.response.status())
        assertEquals("0-${firstPart.lastIndex}", patch.header("Range"))
        assertEquals(listOf(UploadSessionPath(uploadId).toString()), activeMultipartKeys())
        assertEquals(emptyList(), objectKeys(UploadChunkPath(uploadId, 0).toString()))
        withConnection {
            val persisted = assertNotNull(repositoryService.getUploadSession(sessionId))
            assertEquals(firstPart.size.toLong(), persisted.byteOffset)
            assertEquals(1, persisted.chunkCount)
            assertNotNull(persisted.digestState)
        }

        val completion = call(
            uploadId = uploadId,
            body = finalPart,
            headers = mapOf(
                HttpHeaders.ContentLength to finalPart.size.toString(),
                HttpHeaders.ContentRange to "${firstPart.size}-${bytes.lastIndex}",
            ),
            query = mapOf("digest" to digest),
        )
        DockerCompleteBlobUpload(
            repositoryService,
            blobStorageService,
            permissionEvaluator,
            objectStorage(),
        ).execute(completion.call)

        assertEquals(HttpStatusCode.Created, completion.response.status())
        assertEquals(digest, completion.header("Docker-Content-Digest"))
        withConnection {
            assertNull(repositoryService.getUploadSession(sessionId))
            val blob = assertNotNull(blobStorageService.get(digest))
            assertEquals(UploadSessionPath(uploadId).toString(), blob.storagePath)
            val stored = blobStorageService.getInputStream(digest).use { it.readBytes() }
            assertContentEquals(bytes, stored)
        }
    }

    @Test
    fun `digest mismatch cancels the database session and S3 multipart upload`() = runTest(timeout = 2.minutes) {
        val init = call()
        DockerInitBlobUpload(repositoryService, permissionEvaluator).execute(init.call)
        val uploadId = assertNotNull(init.header("Docker-Upload-UUID"))
        val sessionId = UUID.parse(uploadId)
        val body = ByteArray(8193) { index -> (index % 211).toByte() }

        val completion = call(
            uploadId = uploadId,
            body = body,
            headers = mapOf(HttpHeaders.ContentLength to body.size.toString()),
            query = mapOf("digest" to "sha256:${"0".repeat(64)}"),
        )
        DockerCompleteBlobUpload(
            repositoryService,
            blobStorageService,
            permissionEvaluator,
            objectStorage(),
        ).execute(completion.call)

        assertEquals(HttpStatusCode.BadRequest, completion.response.status())
        withConnection {
            assertNull(uploadSessionRepository.findActiveIncludingExpired(sessionId))
        }
        assertEquals(emptyList(), activeMultipartKeys())
    }

    @Test
    fun `expired session cleanup aborts its S3 multipart upload`() = runTest(timeout = 2.minutes) {
        val init = call()
        DockerInitBlobUpload(repositoryService, permissionEvaluator).execute(init.call)
        val uploadId = assertNotNull(init.header("Docker-Upload-UUID"))
        val sessionId = UUID.parse(uploadId)
        val sessionPath = UploadSessionPath(uploadId).toString()
        val stagingPath = UploadChunkPath(uploadId, 0).toString()
        s3Client.putObject(
            PutObjectRequest.builder().bucket(BUCKET).key(stagingPath).build(),
            RequestBody.fromBytes("completed staging data".encodeToByteArray()),
        )
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(BUCKET).key(stagingPath).build(),
        )
        assertEquals(setOf(sessionPath, stagingPath), activeMultipartKeys().toSet())

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE artifacts.upload_sessions SET expires = now() - INTERVAL '1 minute' " +
                        "WHERE id = '$sessionId'::uuid",
                )
            }
        }

        assertEquals(1, withConnection { repositoryService.cleanupExpiredUploadSessions() })
        withConnection {
            assertNull(uploadSessionRepository.findActiveIncludingExpired(sessionId))
        }
        assertEquals(emptyList(), activeMultipartKeys())
        assertEquals(
            emptyList(),
            objectKeys(stagingPath),
        )
    }

    private fun activeMultipartKeys(): List<String> =
        s3Client.listMultipartUploads(
            ListMultipartUploadsRequest.builder().bucket(BUCKET).build(),
        ).uploads().map { it.key() }

    private fun objectKeys(prefix: String): List<String> =
        s3Client.listObjectsV2(
            ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build(),
        ).contents().map { it.key() }

    private fun objectStorage() = S3ObjectStorageService(
        bucket = BUCKET,
        client = s3Client,
        urlPrefix = "",
        urlUploadPrefix = "",
        urlSigner = UnusedProvider(UrlSigner::class),
        securityService = UnusedProvider(SecurityService::class),
    )

    private suspend fun <T> withConnection(block: suspend () -> T): T {
        val manager = ConnectionManager(connectionPool)
        return withContext(manager.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    private fun call(
        uploadId: String? = null,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): TestCall {
        val request = mockk<ServerRequest>()
        val nettyHeaders = DefaultHttpHeaders()
        headers.forEach(nettyHeaders::set)
        every { request.headers } returns RequestHeaders(nettyHeaders)
        every { request.queryParameters } returns Parameters.fromSingleValueMap(query)
        coEvery { request.bodyStreamTo(any()) } coAnswers {
            body.inputStream().use { input -> input.copyTo(firstArg<OutputStream>()) }
        }

        val messages = mutableListOf<Any>()
        val context = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { context.channel() } returns channel
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { context.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { context.writeAndFlush(capture(messages)) } returns future
        every { context.write(any()) } returns future
        val response = ServerResponse::class.java
            .getDeclaredConstructor(ChannelHandlerContext::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

        val pathParameters = buildMap {
            put("namespace", NAMESPACE)
            put("repo", REPOSITORY)
            if (uploadId != null) put("uuid", uploadId)
        }
        val serverCall = ServerCall(
            request = request,
            response = response,
            pathParameters = Parameters.fromSingleValueMap(pathParameters),
            application = application,
        )
        return TestCall(serverCall, response, messages)
    }

    private fun dockerAvailable(): Boolean = try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    private data class TestCall(
        val call: ServerCall,
        val response: ServerResponse,
        val messages: List<Any>,
    ) {
        fun header(name: String): String? =
            messages.filterIsInstance<HttpResponse>().lastOrNull()?.headers()?.get(name)
    }

    private class UnusedProvider<T : Any>(override val type: KClass<T>) : ObjectProvider<T> {
        override suspend fun get(): T = error("Provider is not used by this test")
    }

    private companion object {
        const val ACCESS_KEY = "bosca-s3"
        const val SECRET_KEY = "bosca-s3"
        const val S3_PORT = 8000
        const val BUCKET = "docker-multipart-e2e"
        const val NAMESPACE = "bosca"
        const val REPOSITORY = "runner"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
