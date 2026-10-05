@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.community.repository

import bosca.community.model.PrayedByEntry
import bosca.community.model.PrayerFeedFilter
import bosca.community.model.PrayerLike
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
import bosca.serialization.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrayerRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_prayer_test")
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
                key = "prayer-test",
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val prayerRepo = PrayerRepositoryImpl()
    private val shareRepo = PrayerShareRepositoryImpl()
    private val anniversaryRepo = PrayerAnniversaryRepositoryImpl()
    private val commentRepo = PrayerCommentRepositoryImpl()
    private val communityGroupRepo = PrayerCommunityGroupRepositoryImpl()
    private val permissionRepo = PrayerPermissionRepositoryImpl()

    private val profileId1 = UUID.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val profileId2 = UUID.parse("aaaaaaaa-0000-0000-0000-000000000002")
    private val groupId1 = UUID.parse("bbbbbbbb-0000-0000-0000-000000000001")
    private val groupId2 = UUID.parse("bbbbbbbb-0000-0000-0000-000000000002")
    private val securityGroupId = UUID.parse("cccccccc-0000-0000-0000-000000000001")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration(), CommunityMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM community.prayer_anniversaries") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_shares") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_likes") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_prayed_by") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_comments") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_permissions") { it.execute() }
                connection().useStatement("DELETE FROM community.prayer_community_groups") { it.execute() }
                connection().useStatement("DELETE FROM community.prayers") { it.execute() }
            }
            transaction {
                connection().useStatement(
                    """
                    INSERT INTO profiles (id, name) VALUES
                        ('$profileId1', 'user1'),
                        ('$profileId2', 'user2')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                ) { it.execute() }
                connection().useStatement(
                    """
                    INSERT INTO community_groups (id, name, description, visibility, type)
                    VALUES
                        ('$groupId1', 'Group 1', 'Test group 1', 'public', 'small_group'),
                        ('$groupId2', 'Group 2', 'Test group 2', 'private', 'custom')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                ) { it.execute() }
                connection().useStatement(
                    """
                    INSERT INTO groups (id, name, description, type)
                    VALUES ('$securityGroupId', 'test-group', 'Test security group', 'system')
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

    // --- PrayerRepository: CRUD ---

    @Test
    fun `addRequest inserts and returns prayer with generated id`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "My prayer", JsonPrimitive("help me"), null)
        assertNotNull(prayer.id)
        assertEquals(profileId1, prayer.profileId)
        assertEquals("My prayer", prayer.title)
        assertEquals(JsonPrimitive("help me"), prayer.content)
    }

    @Test
    fun `getRequest returns inserted prayer`() = withDb {
        val created = prayerRepo.addRequest(profileId1, "Find me", JsonPrimitive("content"), null)
        val fetched = prayerRepo.getRequest(created.id)
        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals("Find me", fetched.title)
    }

    @Test
    fun `getRequest returns null for nonexistent id`() = withDb {
        val result = prayerRepo.getRequest(UUID.parse("deadbeef-0000-0000-0000-000000000000"))
        assertNull(result)
    }

    @Test
    fun `deleteRequest removes prayer`() = withDb {
        val created = prayerRepo.addRequest(profileId1, "Delete me", JsonPrimitive("bye"), null)
        prayerRepo.deleteRequest(created.id)
        assertNull(prayerRepo.getRequest(created.id))
    }

    // --- Status updates ---

    @Test
    fun `updateStatus changes status and returns updated prayer`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Status test", JsonPrimitive("content"), null)
        val updated = prayerRepo.updateStatus(prayer.id, "answered")
        assertNotNull(updated)
        assertEquals("answered", updated.status.name.lowercase())
        assertNotNull(updated.answeredAt)
    }

    @Test
    fun `updateStatus to cancelled does not set answeredAt`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Cancel me", JsonPrimitive("content"), null)
        val updated = prayerRepo.updateStatus(prayer.id, "cancelled")
        assertNotNull(updated)
        assertEquals("cancelled", updated.status.name.lowercase())
        assertNull(updated.answeredAt)
    }

    // --- Community group queries ---

    @Test
    fun `getRequests returns prayers linked to a community group`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "Prayer 1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "Prayer 2", JsonPrimitive("b"), null)
        communityGroupRepo.addCommunityGroup(p1.id, groupId1)
        communityGroupRepo.addCommunityGroup(p2.id, groupId1)

        val results = prayerRepo.getRequests(groupId1, 10, 0)
        assertEquals(2, results.size)
    }

    @Test
    fun `getRequests respects pagination`() = withDb {
        repeat(3) { i ->
            val p = prayerRepo.addRequest(profileId1, "Prayer $i", JsonPrimitive("c$i"), null)
            communityGroupRepo.addCommunityGroup(p.id, groupId1)
        }
        val page1 = prayerRepo.getRequests(groupId1, 2, 0)
        val page2 = prayerRepo.getRequests(groupId1, 2, 2)
        assertEquals(2, page1.size)
        assertEquals(1, page2.size)
    }

    @Test
    fun `getRequestsByStatus filters by status`() = withDb {
        val active = prayerRepo.addRequest(profileId1, "Active", JsonPrimitive("a"), null)
        val answered = prayerRepo.addRequest(profileId1, "Answered", JsonPrimitive("b"), null)
        prayerRepo.updateStatus(answered.id, "answered")
        communityGroupRepo.addCommunityGroup(active.id, groupId1)
        communityGroupRepo.addCommunityGroup(answered.id, groupId1)

        val results = prayerRepo.getRequestsByStatus(groupId1, "answered", 10, 0)
        assertEquals(1, results.size)
        assertEquals(answered.id, results[0].id)
    }

    @Test
    fun `countRequests and countRequestsByStatus return correct counts`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "P1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "P2", JsonPrimitive("b"), null)
        prayerRepo.updateStatus(p2.id, "answered")
        communityGroupRepo.addCommunityGroup(p1.id, groupId1)
        communityGroupRepo.addCommunityGroup(p2.id, groupId1)

        assertEquals(2L, prayerRepo.countRequests(groupId1))
        assertEquals(1L, prayerRepo.countRequestsByStatus(groupId1, "active"))
        assertEquals(1L, prayerRepo.countRequestsByStatus(groupId1, "answered"))
    }

    // --- Multi-group feed queries ---

    @Test
    fun `getRequestsByGroups returns prayers across multiple groups`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "G1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "G2", JsonPrimitive("b"), null)
        communityGroupRepo.addCommunityGroup(p1.id, groupId1)
        communityGroupRepo.addCommunityGroup(p2.id, groupId2)

        val filter = PrayerFeedFilter(listOf(groupId1, groupId2), null, 50, 0)
        val results = prayerRepo.getRequestsByGroups(filter)
        assertEquals(2, results.size)
    }

    @Test
    fun `getRequestsByGroupsAndStatus filters by status across groups`() = withDb {
        val active = prayerRepo.addRequest(profileId1, "Active", JsonPrimitive("a"), null)
        val answered = prayerRepo.addRequest(profileId1, "Answered", JsonPrimitive("b"), null)
        prayerRepo.updateStatus(answered.id, "answered")
        communityGroupRepo.addCommunityGroup(active.id, groupId1)
        communityGroupRepo.addCommunityGroup(answered.id, groupId2)

        val filter = PrayerFeedFilter(listOf(groupId1, groupId2), "answered", 50, 0)
        val results = prayerRepo.getRequestsByGroupsAndStatus(filter)
        assertEquals(1, results.size)
        assertEquals(answered.id, results[0].id)
    }

    @Test
    fun `countRequestsByGroups and countRequestsByGroupsAndStatus`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "P1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "P2", JsonPrimitive("b"), null)
        prayerRepo.updateStatus(p2.id, "answered")
        communityGroupRepo.addCommunityGroup(p1.id, groupId1)
        communityGroupRepo.addCommunityGroup(p2.id, groupId2)

        val filterAll = PrayerFeedFilter(listOf(groupId1, groupId2), null, 50, 0)
        assertEquals(2L, prayerRepo.countRequestsByGroups(filterAll))

        val filterAnswered = PrayerFeedFilter(listOf(groupId1, groupId2), "answered", 50, 0)
        assertEquals(1L, prayerRepo.countRequestsByGroupsAndStatus(filterAnswered))
    }

    // --- Prayed-by tracking (community schema) ---

    @Test
    fun `addPrayedBy and hasPrayed round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Pray", JsonPrimitive("x"), null)
        assertFalse(prayerRepo.hasPrayed(prayer.id, profileId2))

        prayerRepo.addPrayedBy(prayer.id, profileId2)
        assertTrue(prayerRepo.hasPrayed(prayer.id, profileId2))
    }

    @Test
    fun `getPrayedBy returns entries ordered by prayed_at desc`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Pray", JsonPrimitive("x"), null)
        prayerRepo.addPrayedBy(prayer.id, profileId1)
        prayerRepo.addPrayedBy(prayer.id, profileId2)

        val entries = prayerRepo.getPrayedBy(prayer.id, 10, 0)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.prayerId == prayer.id })
    }

    @Test
    fun `deletePrayedBy removes tracking row`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Pray", JsonPrimitive("x"), null)
        prayerRepo.addPrayedBy(prayer.id, profileId2)
        assertTrue(prayerRepo.hasPrayed(prayer.id, profileId2))

        val deleted = prayerRepo.deletePrayedBy(prayer.id, profileId2)
        assertNotNull(deleted)
        assertFalse(prayerRepo.hasPrayed(prayer.id, profileId2))
    }

    @Test
    fun `deletePrayedBy returns null when not tracked`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Pray", JsonPrimitive("x"), null)
        val result = prayerRepo.deletePrayedBy(prayer.id, profileId2)
        assertNull(result)
    }

    @Test
    fun `incrementPrayerActionCount and decrementPrayerActionCount`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Count", JsonPrimitive("x"), null)
        assertEquals(0, prayer.prayerActionCount)

        val after1 = prayerRepo.incrementPrayerActionCount(prayer.id)
        assertEquals(1, after1)

        val after2 = prayerRepo.incrementPrayerActionCount(prayer.id)
        assertEquals(2, after2)

        val after3 = prayerRepo.decrementPrayerActionCount(prayer.id)
        assertEquals(1, after3)

        prayerRepo.decrementPrayerActionCount(prayer.id)
        val afterFloor = prayerRepo.decrementPrayerActionCount(prayer.id)
        assertEquals(0, afterFloor)
    }

    // --- Likes (community schema) ---

    @Test
    fun `addLike and hasLiked round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Like", JsonPrimitive("x"), null)
        assertFalse(prayerRepo.hasLiked(prayer.id, profileId2))

        prayerRepo.addLike(prayer.id, profileId2)
        assertTrue(prayerRepo.hasLiked(prayer.id, profileId2))
    }

    @Test
    fun `getLikes returns entries`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Like", JsonPrimitive("x"), null)
        prayerRepo.addLike(prayer.id, profileId1)
        prayerRepo.addLike(prayer.id, profileId2)

        val likes = prayerRepo.getLikes(prayer.id, 10, 0)
        assertEquals(2, likes.size)
        assertTrue(likes.all { it.prayerId == prayer.id })
    }

    @Test
    fun `deleteLike removes like row`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Like", JsonPrimitive("x"), null)
        prayerRepo.addLike(prayer.id, profileId2)

        val deleted = prayerRepo.deleteLike(prayer.id, profileId2)
        assertNotNull(deleted)
        assertFalse(prayerRepo.hasLiked(prayer.id, profileId2))
    }

    @Test
    fun `incrementLikeCount and decrementLikeCount`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Count", JsonPrimitive("x"), null)

        val after1 = prayerRepo.incrementLikeCount(prayer.id)
        assertEquals(1, after1)

        prayerRepo.decrementLikeCount(prayer.id)
        val afterFloor = prayerRepo.decrementLikeCount(prayer.id)
        assertEquals(0, afterFloor)
    }

    // --- Comment count denormalization ---

    @Test
    fun `incrementCommentCount and decrementCommentCount`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Comments", JsonPrimitive("x"), null)

        val after1 = prayerRepo.incrementCommentCount(prayer.id)
        assertEquals(1, after1)

        val after2 = prayerRepo.decrementCommentCount(prayer.id)
        assertEquals(0, after2)
    }

    // --- Anniversary support ---

    @Test
    fun `getAnsweredPrayersForAnniversaryScan returns answered non-suppressed prayers`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "Answered", JsonPrimitive("a"), null)
        prayerRepo.updateStatus(p1.id, "answered")

        val p2 = prayerRepo.addRequest(profileId1, "Suppressed", JsonPrimitive("b"), null)
        prayerRepo.updateStatus(p2.id, "answered")
        prayerRepo.updateSuppressAnniversaries(p2.id, true)

        val p3 = prayerRepo.addRequest(profileId1, "Active", JsonPrimitive("c"), null)

        val results = prayerRepo.getAnsweredPrayersForAnniversaryScan()
        val ids = results.map { it.id }
        assertTrue(p1.id in ids)
        assertFalse(p2.id in ids)
        assertFalse(p3.id in ids)
    }

    @Test
    fun `updateSuppressAnniversaries toggles flag`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Suppress", JsonPrimitive("x"), null)
        assertFalse(prayer.suppressAnniversaries)

        val suppressed = prayerRepo.updateSuppressAnniversaries(prayer.id, true)
        assertNotNull(suppressed)
        assertTrue(suppressed.suppressAnniversaries)

        val unsuppressed = prayerRepo.updateSuppressAnniversaries(prayer.id, false)
        assertNotNull(unsuppressed)
        assertFalse(unsuppressed.suppressAnniversaries)
    }

    // --- PrayerShareRepository (community schema) ---

    @Test
    fun `addShare and getShares round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Share", JsonPrimitive("x"), null)
        shareRepo.addShare(prayer.id, profileId2)

        val shares = shareRepo.getShares(prayer.id)
        assertEquals(1, shares.size)
        assertEquals(profileId2, shares[0].profileId)
    }

    @Test
    fun `addShare is idempotent`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Share", JsonPrimitive("x"), null)
        shareRepo.addShare(prayer.id, profileId2)
        shareRepo.addShare(prayer.id, profileId2)

        assertEquals(1, shareRepo.getShares(prayer.id).size)
    }

    @Test
    fun `deleteShare removes the share`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Share", JsonPrimitive("x"), null)
        shareRepo.addShare(prayer.id, profileId2)
        shareRepo.deleteShare(prayer.id, profileId2)

        assertTrue(shareRepo.getShares(prayer.id).isEmpty())
    }

    @Test
    fun `getSharedWithProfile returns prayers shared with a profile`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "Shared 1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "Shared 2", JsonPrimitive("b"), null)
        shareRepo.addShare(p1.id, profileId2)
        shareRepo.addShare(p2.id, profileId2)

        val shared = shareRepo.getSharedWithProfile(profileId2, 10, 0)
        assertEquals(2, shared.size)
    }

    @Test
    fun `countSharedWithProfile returns correct count`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Shared", JsonPrimitive("a"), null)
        shareRepo.addShare(prayer.id, profileId2)

        assertEquals(1L, shareRepo.countSharedWithProfile(profileId2))
    }

    // --- PrayerAnniversaryRepository (community schema) ---

    @Test
    fun `addAnniversary and getAnniversaries round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Anniversary", JsonPrimitive("x"), null)
        anniversaryRepo.addAnniversary(prayer.id, "3_months")
        anniversaryRepo.addAnniversary(prayer.id, "6_months")

        val anniversaries = anniversaryRepo.getAnniversaries(prayer.id)
        assertEquals(2, anniversaries.size)
        assertTrue(anniversaries.any { it.milestone == "3_months" })
        assertTrue(anniversaries.any { it.milestone == "6_months" })
    }

    @Test
    fun `addAnniversary is idempotent`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Anniversary", JsonPrimitive("x"), null)
        anniversaryRepo.addAnniversary(prayer.id, "1_year")
        anniversaryRepo.addAnniversary(prayer.id, "1_year")

        assertEquals(1, anniversaryRepo.getAnniversaries(prayer.id).size)
    }

    // --- PrayerCommentRepository ---

    @Test
    fun `addComment and getComments round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Comment", JsonPrimitive("x"), null)
        val comment = commentRepo.addComment(prayer.id, null, profileId2, "Praying!", null)

        assertNotNull(comment.id)
        assertEquals(prayer.id, comment.prayerId)
        assertEquals("Praying!", comment.content)

        val fetched = commentRepo.getComment(comment.id)
        assertNotNull(fetched)
        assertEquals(comment, fetched)

        val comments = commentRepo.getComments(prayer.id, 10, 0)
        assertEquals(1, comments.size)
        assertEquals(comment.id, comments[0].id)
    }

    @Test
    fun `getReplies returns child comments`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Comment", JsonPrimitive("x"), null)
        val parent = commentRepo.addComment(prayer.id, null, profileId1, "Parent", null)
        val reply = commentRepo.addComment(prayer.id, parent.id, profileId2, "Reply", null)

        val replies = commentRepo.getReplies(parent.id, 10, 0)
        assertEquals(1, replies.size)
        assertEquals(reply.id, replies[0].id)
    }

    @Test
    fun `deleteComment soft-deletes and hides from getComments`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Comment", JsonPrimitive("x"), null)
        val comment = commentRepo.addComment(prayer.id, null, profileId1, "Delete me", null)

        commentRepo.deleteComment(comment.id)

        val comments = commentRepo.getComments(prayer.id, 10, 0)
        assertTrue(comments.isEmpty())
        assertTrue(commentRepo.getComment(comment.id)?.deleted == true)
    }

    @Test
    fun `countComments excludes soft-deleted comments`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Comment", JsonPrimitive("x"), null)
        val c1 = commentRepo.addComment(prayer.id, null, profileId1, "Keep", null)
        val c2 = commentRepo.addComment(prayer.id, null, profileId2, "Delete", null)
        commentRepo.deleteComment(c2.id)

        assertEquals(1L, commentRepo.countComments(prayer.id))
    }

    // --- PrayerCommunityGroupRepository ---

    @Test
    fun `addCommunityGroup and getCommunityGroupIds round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Group", JsonPrimitive("x"), null)
        communityGroupRepo.addCommunityGroup(prayer.id, groupId1)
        communityGroupRepo.addCommunityGroup(prayer.id, groupId2)

        val ids = communityGroupRepo.getCommunityGroupIds(prayer.id)
        assertEquals(2, ids.size)
        assertTrue(groupId1 in ids)
        assertTrue(groupId2 in ids)
    }

    @Test
    fun `addCommunityGroup is idempotent`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Group", JsonPrimitive("x"), null)
        communityGroupRepo.addCommunityGroup(prayer.id, groupId1)
        communityGroupRepo.addCommunityGroup(prayer.id, groupId1)

        assertEquals(1, communityGroupRepo.getCommunityGroupIds(prayer.id).size)
    }

    @Test
    fun `removeCommunityGroup unlinks group`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Group", JsonPrimitive("x"), null)
        communityGroupRepo.addCommunityGroup(prayer.id, groupId1)
        communityGroupRepo.removeCommunityGroup(prayer.id, groupId1)

        assertTrue(communityGroupRepo.getCommunityGroupIds(prayer.id).isEmpty())
    }

    @Test
    fun `getFirstCommunityGroupId returns one id or null`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Group", JsonPrimitive("x"), null)
        assertNull(communityGroupRepo.getFirstCommunityGroupId(prayer.id))

        communityGroupRepo.addCommunityGroup(prayer.id, groupId1)
        assertEquals(groupId1, communityGroupRepo.getFirstCommunityGroupId(prayer.id))
    }

    // --- PrayerPermissionRepository ---

    @Test
    fun `addPermission and getPermissionsByPrayerId round-trip`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Perm", JsonPrimitive("x"), null)
        permissionRepo.addPermission(prayer.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)

        val perms = permissionRepo.getPermissionsByPrayerId(prayer.id)
        assertEquals(1, perms.size)
        assertEquals(bosca.security.model.PermissionAction.VIEW, perms[0].action)
    }

    @Test
    fun `addPermission is idempotent`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Perm", JsonPrimitive("x"), null)
        permissionRepo.addPermission(prayer.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)
        permissionRepo.addPermission(prayer.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)

        assertEquals(1, permissionRepo.getPermissionsByPrayerId(prayer.id).size)
    }

    @Test
    fun `deletePermission removes permission`() = withDb {
        val prayer = prayerRepo.addRequest(profileId1, "Perm", JsonPrimitive("x"), null)
        permissionRepo.addPermission(prayer.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)
        permissionRepo.deletePermission(prayer.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)

        assertTrue(permissionRepo.getPermissionsByPrayerId(prayer.id).isEmpty())
    }

    @Test
    fun `getPermissionsByPrayerIds batch-loads across prayers`() = withDb {
        val p1 = prayerRepo.addRequest(profileId1, "P1", JsonPrimitive("a"), null)
        val p2 = prayerRepo.addRequest(profileId1, "P2", JsonPrimitive("b"), null)
        permissionRepo.addPermission(p1.id, securityGroupId, bosca.security.model.PermissionAction.VIEW)
        permissionRepo.addPermission(p2.id, securityGroupId, bosca.security.model.PermissionAction.MANAGE)

        val perms = permissionRepo.getPermissionsByPrayerIds(listOf(p1.id, p2.id))
        assertEquals(2, perms.size)
    }
}
