@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.chat.repository

import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatObjectType
import bosca.chat.model.ChatChannelType
import bosca.security.model.PermissionAction
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
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChatChannelInvitationRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_chat_channel_invitation_test")
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
                key = "chat-channel-invitation-test",
            ),
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val invitationRepository = ChatChannelInvitationRepositoryImpl()
    private val channelRepository = ChatChannelRepositoryImpl()
    private val permissionRepository = ChatChannelPermissionRepositoryImpl()
    private val inviterId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000001")
    private val inviteeId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000002")
    private val permissionGroupId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000003")
    private val principalId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000004")

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), ChatMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("delete from chat.channel_invitations") { it.execute() }
                connection().useStatement("delete from chat.channel_members") { it.execute() }
                connection().useStatement("delete from chat.channels") { it.execute() }
                connection().useStatement(
                    "insert into public.principals (id) values ('$principalId') on conflict (id) do nothing"
                ) { it.execute() }
                connection().useStatement(
                    "insert into public.profiles (id, principal, name) " +
                        "values ('$inviterId', '$principalId', 'Inviter'), ('$inviteeId', '$principalId', 'Invitee') " +
                        "on conflict (id) do update set principal = excluded.principal"
                ) { it.execute() }
                connection().useStatement(
                    "insert into public.groups (id, name, description, type) " +
                        "values ('$permissionGroupId', '$principalId.user', 'Chat permission test', 'principal') " +
                        "on conflict (id) do update set name = excluded.name, type = excluded.type"
                ) { it.execute() }
            }
        }
    }

    @Test
    fun `generated repository maps pending invitation pages and transitions`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }
        val invitation = transaction {
            invitationRepository.add(channel.id, inviterId, inviteeId, "member")
        }

        assertEquals(ChatChannelInvitationStatus.PENDING, invitation.status)
        assertEquals(listOf(invitation), invitationRepository.getIncoming(inviteeId, 0, 10))
        assertEquals(listOf(invitation), invitationRepository.getOutgoing(inviterId, 0, 10))

        val accepted = transaction {
            invitationRepository.transition(
                invitation.id,
                invitation.version,
                ChatChannelInvitationStatus.ACCEPTED,
            )
        }
        assertNotNull(accepted)
        assertEquals(ChatChannelInvitationStatus.ACCEPTED, accepted.status)
        assertNull(
            transaction {
                invitationRepository.transition(
                    invitation.id,
                    invitation.version,
                    ChatChannelInvitationStatus.CANCELLED,
                )
            }
        )
    }

    @Test
    fun `profile cleanup cancels pending incoming and outgoing invitations`() = withDb {
        val incomingChannel = transaction {
            channelRepository.createChannel(null, "Incoming", ChatChannelType.GROUP, null)
        }
        val outgoingChannel = transaction {
            channelRepository.createChannel(null, "Outgoing", ChatChannelType.GROUP, null)
        }
        val incoming = transaction {
            invitationRepository.add(incomingChannel.id, inviterId, inviteeId, "member")
        }
        val outgoing = transaction {
            invitationRepository.add(outgoingChannel.id, inviteeId, inviterId, "member")
        }

        assertEquals(2, transaction { invitationRepository.cancelPendingByProfile(inviteeId) })
        assertEquals(ChatChannelInvitationStatus.CANCELLED, invitationRepository.getById(incoming.id)?.status)
        assertEquals(ChatChannelInvitationStatus.CANCELLED, invitationRepository.getById(outgoing.id)?.status)
        assertEquals(emptyList(), invitationRepository.getIncoming(inviteeId, 0, 10))
        assertEquals(emptyList(), invitationRepository.getOutgoing(inviteeId, 0, 10))
    }

    @Test
    fun `object channel insertion is atomic for an object identity`() = withDb {
        val objectId = UUID.random()
        val first = transaction {
            channelRepository.createObjectChannelIfAbsent("Object thread", ChatObjectType.METADATA, objectId)
        }
        val duplicate = transaction {
            channelRepository.createObjectChannelIfAbsent("Ignored name", ChatObjectType.METADATA, objectId)
        }

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(first, channelRepository.getByObject(ChatObjectType.METADATA, objectId))
    }

    @Test
    fun `pending uniqueness permits another invitation after the first is decided`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }
        val first = transaction {
            invitationRepository.add(channel.id, inviterId, inviteeId, "member")
        }
        assertFailsWith<Exception> {
            transaction { invitationRepository.add(channel.id, inviterId, inviteeId, "member") }
        }
        transaction {
            invitationRepository.transition(first.id, first.version, ChatChannelInvitationStatus.DECLINED)
        }

        val second = transaction {
            invitationRepository.add(channel.id, inviterId, inviteeId, "member")
        }
        assertEquals(ChatChannelInvitationStatus.PENDING, second.status)
    }

    @Test
    fun `member insertion reports only the first join and preserves its role`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }

        assertEquals(
            1,
            transaction { channelRepository.addMemberIfAbsent(channel.id, inviterId, "member") },
        )
        assertEquals(
            0,
            transaction { channelRepository.addMemberIfAbsent(channel.id, inviterId, "admin") },
        )
        assertEquals("member", transaction { channelRepository.getMember(channel.id, inviterId) }?.role)
        assertEquals(
            1,
            transaction { channelRepository.updateMemberRole(channel.id, inviterId, "admin") },
        )
        assertEquals("admin", transaction { channelRepository.getMember(channel.id, inviterId) }?.role)
        assertEquals(
            0,
            transaction { channelRepository.updateMemberRole(channel.id, inviterId, "admin") },
        )
        assertEquals(
            listOf(inviterId),
            transaction { channelRepository.getMembers(channel.id, listOf(inviterId, inviteeId)) }
                .map { it.profileId },
        )
        assertEquals(1, transaction { channelRepository.removeMember(channel.id, inviterId) })
        assertEquals(0, transaction { channelRepository.removeMember(channel.id, inviterId) })
    }

    @Test
    fun `member pages respect offset and limit`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }
        transaction {
            channelRepository.addMemberIfAbsent(channel.id, inviterId, "member")
            channelRepository.addMemberIfAbsent(channel.id, inviteeId, "member")
        }

        val firstPage = transaction { channelRepository.getMembers(channel.id, 0, 1) }
        val secondPage = transaction { channelRepository.getMembers(channel.id, 1, 1) }

        assertEquals(listOf(inviterId), firstPage.map { it.profileId })
        assertEquals(listOf(inviteeId), secondPage.map { it.profileId })
    }

    @Test
    fun `permission insertion is idempotent`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }

        transaction {
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.VIEW)
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.VIEW)
        }

        assertEquals(
            listOf(PermissionAction.VIEW),
            transaction { permissionRepository.getPermissionsByChannelId(channel.id) }.map { it.action },
        )
    }

    @Test
    fun `permission deletion removes the grant`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }

        transaction {
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.EXECUTE)
            permissionRepository.deletePermission(channel.id, permissionGroupId, PermissionAction.EXECUTE)
        }

        assertEquals(
            emptyList(),
            transaction { permissionRepository.getPermissionsByChannelId(channel.id) },
        )
    }

    @Test
    fun `canonical direct channel insertion returns only the database winner`() = withDb {
        val channelId = UUID.random()

        val created = transaction {
            channelRepository.createDirectChannelIfAbsent(channelId)
        }
        val duplicate = transaction {
            channelRepository.createDirectChannelIfAbsent(channelId)
        }

        assertNotNull(created)
        assertEquals(channelId, created.id)
        assertEquals(ChatChannelType.DIRECT, created.type)
        assertNull(duplicate)
        assertEquals(created, transaction { channelRepository.getById(channelId) })
    }

    @Test
    fun `profile deletion leaves an incomplete direct channel for asynchronous cleanup`() = withDb {
        val channelId = UUID.random()
        transaction {
            channelRepository.createDirectChannelIfAbsent(channelId)
            channelRepository.addMemberIfAbsent(channelId, inviterId, "member")
            channelRepository.addMemberIfAbsent(channelId, inviteeId, "member")
        }

        transaction {
            connection().useStatement("delete from public.profiles where id = '$inviterId'") { it.execute() }
        }

        assertEquals(
            listOf(channelId),
            transaction { channelRepository.getIncompleteDirectChannels() }.map { it.id },
        )
        assertEquals(1, transaction { channelRepository.deleteChannel(channelId) })
        assertNull(transaction { channelRepository.getById(channelId) })
    }

    @Test
    fun `profile deletion removes non-direct membership without changing its group ACL`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }
        transaction {
            channelRepository.addMemberIfAbsent(channel.id, inviterId, "member")
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.VIEW)
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.EXECUTE)
        }

        transaction {
            connection().useStatement("delete from public.profiles where id = '$inviterId'") { it.execute() }
        }

        assertNotNull(transaction { channelRepository.getById(channel.id) })
        assertNull(transaction { channelRepository.getMember(channel.id, inviterId) })
        assertEquals(
            setOf(PermissionAction.VIEW, PermissionAction.EXECUTE),
            transaction { permissionRepository.getPermissionsByChannelId(channel.id) }
                .mapTo(mutableSetOf()) { it.action },
        )
    }

    @Test
    fun `profile deletion preserves principal ACL represented by another member`() = withDb {
        val channel = transaction {
            channelRepository.createChannel(null, "Planning", ChatChannelType.GROUP, null)
        }
        transaction {
            channelRepository.addMemberIfAbsent(channel.id, inviterId, "member")
            channelRepository.addMemberIfAbsent(channel.id, inviteeId, "member")
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.VIEW)
            permissionRepository.addPermission(channel.id, permissionGroupId, PermissionAction.EXECUTE)
        }

        transaction {
            connection().useStatement("delete from public.profiles where id = '$inviterId'") { it.execute() }
        }

        assertNull(transaction { channelRepository.getMember(channel.id, inviterId) })
        assertNotNull(transaction { channelRepository.getMember(channel.id, inviteeId) })
        assertEquals(
            setOf(PermissionAction.VIEW, PermissionAction.EXECUTE),
            transaction { permissionRepository.getPermissionsByChannelId(channel.id) }.mapTo(mutableSetOf()) { it.action },
        )
    }

    @Test
    fun `direct channel and nested membership roll back together`() = withDb {
        val channelId = UUID.random()

        assertFailsWith<IllegalStateException> {
            transaction {
                channelRepository.createDirectChannelIfAbsent(channelId)
                transaction {
                    channelRepository.addMemberIfAbsent(channelId, inviterId, "member")
                }
                error("force rollback")
            }
        }

        assertNull(transaction { channelRepository.getById(channelId) })
    }

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
