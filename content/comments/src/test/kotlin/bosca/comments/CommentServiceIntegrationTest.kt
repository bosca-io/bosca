@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.comments

import bosca.comments.model.CommentInput
import bosca.comments.model.CommentStatus
import bosca.comments.repository.CommentRepositoryImpl
import bosca.comments.service.CommentServiceImpl
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test for the Kotlin port of the Rust comments
 * datastore. Runs the full `CoreMigration` (V1–current) so the
 * `metadata`, `profiles`, `metadata_comments`, and
 * `metadata_comment_likes` tables exist, then exercises the round
 * trip the Rust impl tests:
 *
 *   1. add comment → returns id, persisted with the given fields
 *   2. like / unlike → counter denormalization stays consistent
 *      with `metadata_comment_likes` rows
 *   3. visibility filtering — public / for-profile / manager paths
 *      return the expected subset
 *   4. soft delete + author-scoped soft delete
 *
 * Running CoreMigration here is the right shape because the
 * comment tables live in the public schema (V55, V56).
 */
class CommentServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_comments_port_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "comments-port-test",
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = CommentRepositoryImpl()
    private val service = CommentServiceImpl(repository)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration()))
            }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("DELETE FROM metadata_comment_likes") { it.execute() }
                connection().useStatement("DELETE FROM metadata_comments") { it.execute() }
            }
            // Seed prerequisite rows for FKs. The CoreMigration's
            // V3__security_and_profiles.sql declares `profiles.name`
            // NOT NULL, and `metadata` has its own required columns,
            // so the seeds set every NOT NULL field explicitly.
            transaction {
                connection().useStatement(
                    """
                    INSERT INTO profiles (id, name) VALUES
                        ('${authorId}', 'author'),
                        ('${otherId}', 'other')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                ) { it.execute() }
                connection().useStatement(
                    """
                    INSERT INTO metadata (id, name, content_type, version)
                    VALUES ('${metadataId}', 'fixture', 'text/plain', 1)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                ) { it.execute() }
            }
        }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    private val metadataId: UUID = UUID.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val authorId: UUID = UUID.parse("bbbbbbbb-0000-0000-0000-000000000001")
    private val otherId: UUID = UUID.parse("bbbbbbbb-0000-0000-0000-000000000002")

    private fun publicInput(parentId: Long? = null, content: String = "hello") = CommentInput(
        parentId = parentId,
        visibility = ProfileVisibility.PUBLIC,
        content = content,
        attributes = null,
        systemAttributes = null,
        impersonateId = null,
    )

    @Test
    fun `addMetadataComment returns id and persists`() = withDb {
        val id = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "first"))
        assertTrue(id > 0L)
        val fetched = service.getMetadataCommentById(id)
        assertNotNull(fetched)
        assertEquals(metadataId, fetched.metadataId)
        assertEquals(1, fetched.version)
        assertEquals(authorId, fetched.profileId)
        assertEquals("first", fetched.content)
        assertEquals(0, fetched.likes)
        assertEquals(CommentStatus.PENDING, fetched.status)
        assertEquals(ProfileVisibility.PUBLIC, fetched.visibility)
    }

    @Test
    fun `reply rejects when parent is in a different thread`() = withDb {
        val parentId = service.addMetadataComment(authorId, null, metadataId, 1, publicInput())
        // Try to reply against a different version — service must reject.
        assertFails {
            service.addMetadataComment(
                authorId,
                null,
                metadataId,
                version = 2,
                publicInput(parentId = parentId, content = "wrong-thread"),
            )
        }
    }

    @Test
    fun `like increments counter and decrements on unlike`() = withDb {
        val id = service.addMetadataComment(authorId, null, metadataId, 1, publicInput())
        // Approve so visibility filtering doesn't affect the read.
        service.setMetadataCommentStatus(metadataId, 1, id, CommentStatus.APPROVED)

        val afterLike = service.addMetadataCommentLike(metadataId, 1, otherId, id)
        assertEquals(1, afterLike)

        val withLike = service.getMetadataComment(otherId, metadataId, 1, id, manager = false)
        assertNotNull(withLike)
        assertEquals(1, withLike.likes)

        val likedIds = service.getMetadataCommentLikeIdsByProfile(otherId)
        assertTrue(id in likedIds)

        val afterUnlike = service.deleteMetadataCommentLike(metadataId, 1, otherId, id)
        assertEquals(0, afterUnlike)

        val nowEmpty = service.getMetadataCommentLikeIdsByProfile(otherId)
        assertTrue(nowEmpty.isEmpty())
    }

    @Test
    fun `visibility filtering hides pending comments from non-managers`() = withDb {
        val pendingId = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "pending"))
        // Default status is PENDING, so a public viewer should NOT see it.
        val publicView = service.getMetadataComment(profileId = null, metadataId = metadataId, version = 1, id = pendingId, manager = false)
        assertNull(publicView, "pending comment must be invisible to anonymous viewers")

        // The author can see their own pending comment.
        val authorView = service.getMetadataComment(authorId, metadataId, 1, pendingId, manager = false)
        assertNotNull(authorView)

        // A manager sees it too.
        val managerView = service.getMetadataComment(null, metadataId, 1, pendingId, manager = true)
        assertNotNull(managerView)

        // After approval the public view is unblocked.
        service.setMetadataCommentStatus(metadataId, 1, pendingId, CommentStatus.APPROVED)
        val approved = service.getMetadataComment(null, metadataId, 1, pendingId, manager = false)
        assertNotNull(approved)
    }

    @Test
    fun `setMetadataCommentVisibility gates public visibility independently of status`() = withDb {
        // A comment authored with a non-public visibility (e.g. from a USER-visibility
        // profile). Approving it is NOT enough to make it visible to the public — the
        // read queries require visibility = 'public' AND status = 'approved'.
        val id = service.addMetadataComment(
            authorId, null, metadataId, 1,
            CommentInput(
                parentId = null,
                visibility = ProfileVisibility.USER,
                content = "restricted",
                attributes = null,
                systemAttributes = null,
                impersonateId = null,
            ),
        )
        service.setMetadataCommentStatus(metadataId, 1, id, CommentStatus.APPROVED)
        assertNull(
            service.getMetadataComment(null, metadataId, 1, id, manager = false),
            "an approved but non-public comment must stay hidden from the public",
        )

        // Moderator flips it to PUBLIC → the public can now see it.
        service.setMetadataCommentVisibility(metadataId, 1, id, ProfileVisibility.PUBLIC)
        val publicView = service.getMetadataComment(null, metadataId, 1, id, manager = false)
        assertNotNull(publicView)
        assertEquals(ProfileVisibility.PUBLIC, publicView.visibility)

        // Flipping to a restricted scope hides it from the public again…
        service.setMetadataCommentVisibility(metadataId, 1, id, ProfileVisibility.FRIENDS)
        assertNull(service.getMetadataComment(null, metadataId, 1, id, manager = false))
        // …but a manager still sees it regardless of visibility.
        val managerView = service.getMetadataComment(null, metadataId, 1, id, manager = true)
        assertNotNull(managerView)
        assertEquals(ProfileVisibility.FRIENDS, managerView.visibility)
    }

    @Test
    fun `author-scoped visibility change only affects rows owned by the profile`() = withDb {
        val ownId = service.addMetadataComment(
            authorId, null, metadataId, 1,
            CommentInput(null, ProfileVisibility.USER, "mine", null, null, null),
        )
        val notOwnId = service.addMetadataComment(
            otherId, null, metadataId, 1,
            CommentInput(null, ProfileVisibility.USER, "theirs", null, null, null),
        )

        // authorId tries to change a comment they didn't write — no-op.
        service.setMetadataCommentVisibilityByProfileId(metadataId, 1, notOwnId, authorId, ProfileVisibility.PUBLIC)
        assertEquals(ProfileVisibility.USER, service.getMetadataCommentById(notOwnId)?.visibility)

        // Their own comment updates.
        service.setMetadataCommentVisibilityByProfileId(metadataId, 1, ownId, authorId, ProfileVisibility.PUBLIC)
        assertEquals(ProfileVisibility.PUBLIC, service.getMetadataCommentById(ownId)?.visibility)
    }

    @Test
    fun `soft delete hides the comment from every read shape`() = withDb {
        val id = service.addMetadataComment(authorId, null, metadataId, 1, publicInput())
        service.setMetadataCommentStatus(metadataId, 1, id, CommentStatus.APPROVED)
        // Sanity: visible before delete.
        assertNotNull(service.getMetadataComment(null, metadataId, 1, id, manager = false))
        service.deleteMetadataComment(metadataId, 1, id)
        assertNull(service.getMetadataComment(null, metadataId, 1, id, manager = false))
        assertNull(service.getMetadataComment(null, metadataId, 1, id, manager = true))
    }

    @Test
    fun `author-scoped soft delete only affects rows owned by the profile`() = withDb {
        val ownId = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "mine"))
        val notOwnId = service.addMetadataComment(otherId, null, metadataId, 1, publicInput(content = "theirs"))
        service.setMetadataCommentStatus(metadataId, 1, ownId, CommentStatus.APPROVED)
        service.setMetadataCommentStatus(metadataId, 1, notOwnId, CommentStatus.APPROVED)

        // authorId tries to delete a comment they didn't write — no-op.
        service.deleteMetadataCommentByProfileId(metadataId, 1, notOwnId, authorId)
        assertNotNull(service.getMetadataComment(null, metadataId, 1, notOwnId, manager = false))

        // Their own comment vanishes.
        service.deleteMetadataCommentByProfileId(metadataId, 1, ownId, authorId)
        assertNull(service.getMetadataComment(null, metadataId, 1, ownId, manager = false))
    }

    @Test
    fun `attributes can be set and merged`() = withDb {
        val id = service.addMetadataComment(authorId, null, metadataId, 1, publicInput())
        service.setMetadataCommentSystemAttributes(
            metadataId, 1, id,
            JsonObject(mapOf("k" to JsonPrimitive("v1")))
        )
        // Merge adds k2 without dropping k1.
        service.mergeMetadataCommentSystemAttributes(
            metadataId, 1, id,
            JsonObject(mapOf("k2" to JsonPrimitive("v2")))
        )
        val fetched = service.getMetadataCommentById(id)
        val sys = fetched?.systemAttributes as? JsonObject
        assertNotNull(sys)
        assertEquals(JsonPrimitive("v1"), sys["k"])
        assertEquals(JsonPrimitive("v2"), sys["k2"])
    }

    @Test
    fun `manager read now includes blocked top-level comments`() = withDb {
        val approvedId = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "ok"))
        val blockedId = service.addMetadataComment(otherId, null, metadataId, 1, publicInput(content = "spam"))
        service.setMetadataCommentStatus(metadataId, 1, approvedId, CommentStatus.APPROVED)
        service.setMetadataCommentStatus(metadataId, 1, blockedId, CommentStatus.BLOCKED)

        // A manager (moderator) now sees blocked comments — they must, to reverse them.
        val managerView = service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50)
        assertEquals(setOf(approvedId, blockedId), managerView.map { it.id }.toSet())

        // The public path still hides blocked.
        val publicView = service.getMetadataComments(null, metadataId, 1, manager = false, offset = 0, limit = 50)
        assertEquals(listOf(approvedId), publicView.map { it.id })
    }

    @Test
    fun `manager reply read now includes blocked replies`() = withDb {
        val root = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "root"))
        service.setMetadataCommentStatus(metadataId, 1, root, CommentStatus.APPROVED)
        val okReply = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(parentId = root, content = "ok"))
        val blockedReply = service.addMetadataComment(otherId, null, metadataId, 1, publicInput(parentId = root, content = "bad"))
        service.setMetadataCommentStatus(metadataId, 1, okReply, CommentStatus.APPROVED)
        service.setMetadataCommentStatus(metadataId, 1, blockedReply, CommentStatus.BLOCKED)

        // Manager replies now include the blocked reply (was filtered before).
        val managerReplies = service.getMetadataCommentsByParentId(null, metadataId, 1, root, manager = true, offset = 0, limit = 50)
        assertEquals(setOf(okReply, blockedReply), managerReplies.map { it.id }.toSet())

        // Public replies still hide blocked.
        val publicReplies = service.getMetadataCommentsByParentId(null, metadataId, 1, root, manager = false, offset = 0, limit = 50)
        assertEquals(listOf(okReply), publicReplies.map { it.id })
    }

    @Test
    fun `hasUnmoderatedComments is true with pending or pending-approval, false once all decided`() = withDb {
        // No comments yet → nothing awaiting a decision.
        assertFalse(service.hasUnmoderatedComments(metadataId, 1))

        // A new comment defaults to PENDING → unmoderated.
        val c = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "pending"))
        assertTrue(service.hasUnmoderatedComments(metadataId, 1))

        // PENDING_APPROVAL (flagged, awaiting a human) also counts as unmoderated.
        service.setMetadataCommentStatus(metadataId, 1, c, CommentStatus.PENDING_APPROVAL)
        assertTrue(service.hasUnmoderatedComments(metadataId, 1))

        // Once every comment is decided (approved/blocked), nothing is unmoderated.
        service.setMetadataCommentStatus(metadataId, 1, c, CommentStatus.APPROVED)
        assertFalse(service.hasUnmoderatedComments(metadataId, 1))
    }

    @Test
    fun `the pinned filter returns only pinned and the main list stays chronological`() = withDb {
        val first = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "first"))
        val second = service.addMetadataComment(authorId, null, metadataId, 1, publicInput(content = "second"))
        service.setMetadataCommentStatus(metadataId, 1, first, CommentStatus.APPROVED)
        service.setMetadataCommentStatus(metadataId, 1, second, CommentStatus.APPROVED)

        // Nothing pinned yet → the pinned-only filter is empty; default order is newest-first.
        assertTrue(service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50, pinnedOnly = true).isEmpty())
        assertEquals(0L, service.getMetadataCommentsCount(null, metadataId, 1, manager = true, pinnedOnly = true))
        assertEquals(listOf(second, first), service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50).map { it.id })

        // Pin the OLDER comment.
        service.setMetadataCommentPinned(metadataId, 1, first, true)

        // The main list stays chronological — the pinned comment is NOT floated to the top
        // (the UI surfaces pinned items separately via the filter below).
        assertEquals(listOf(second, first), service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50).map { it.id })
        assertTrue(service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50).first { it.id == first }.pinned)

        // The pinned-only filter returns just it.
        assertEquals(listOf(first), service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50, pinnedOnly = true).map { it.id })
        assertEquals(1L, service.getMetadataCommentsCount(null, metadataId, 1, manager = true, pinnedOnly = true))

        // Unpinning empties the filter again.
        service.setMetadataCommentPinned(metadataId, 1, first, false)
        assertTrue(service.getMetadataComments(null, metadataId, 1, manager = true, offset = 0, limit = 50, pinnedOnly = true).isEmpty())
    }
}
