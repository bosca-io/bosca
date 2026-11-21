package bosca.workops.ui.collaboration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import yks.sync.MessageProtocol
import yks.utils.Doc
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkOpsCollaborationProtocolTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/collaboration/provider-3.4.4.json"))
        .use { it.readAllBytes().decodeToString() }).jsonObject
    private val name = fixture.getValue("name").jsonPrimitive.content
    private fun bytes(key: String) = fixture.getValue(key).jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()

    @Test
    fun `Studio provider authentication and server replies retain the exact wire format`() {
        val message = WorkOpsCollaborationProtocol.decode(bytes("authentication"))
        assertEquals(name, message.name)
        assertEquals(3, message.room.version)
        assertEquals("11111111-2222-4333-8444-555555555555", message.room.metadataId.toString())
        assertEquals("fixture-token", WorkOpsCollaborationProtocol.token(message))
        assertContentEquals(bytes("authenticated"), WorkOpsCollaborationProtocol.authenticated(name))
        assertContentEquals(bytes("denied"), WorkOpsCollaborationProtocol.denied(name))
        assertContentEquals(bytes("acknowledged"), WorkOpsCollaborationProtocol.acknowledged(name))
        assertContentEquals(bytes("authentication"), WorkOpsCollaborationProtocol.frame(name, message.payload))
    }

    @Test
    fun `Studio updates apply through YKS without interpreting or changing their payload`() {
        val document = Doc()
        try {
            val update = WorkOpsCollaborationProtocol.decode(bytes("update"))
            assertEquals(2, WorkOpsCollaborationProtocol.syncType(update))
            assertTrue(MessageProtocol.readMessage(update.payload, document, null).isEmpty())
            assertEquals("Shared", document.getText("body").toString())
            val sync = WorkOpsCollaborationProtocol.decode(bytes("sync"))
            assertEquals(0, WorkOpsCollaborationProtocol.syncType(sync))
            val response = MessageProtocol.readMessage(sync.payload, document, null)
            assertEquals(WorkOpsCollaborationProtocol.SYNC, WorkOpsCollaborationProtocol.decode(
                WorkOpsCollaborationProtocol.frame(name, response),
            ).type)
            assertEquals(WorkOpsCollaborationProtocol.AWARENESS, WorkOpsCollaborationProtocol.decode(bytes("awareness")).type)
        } finally { document.destroy() }
    }

    @Test
    fun `room identity is canonical while provider spelling is retained for routing replies`() {
        val alias = name.substringBeforeLast('.') + ".003"
        val decoded = WorkOpsCollaborationProtocol.decode(WorkOpsCollaborationProtocol.frame(alias, byteArrayOf(3)))
        assertEquals(alias, decoded.name)
        assertEquals(WorkOpsCollaborationRoom.parse(name), decoded.room)
        assertEquals("/api/v1/content/metadata/11111111-2222-4333-8444-555555555555/document/collaboration?version=3", decoded.room.contentPath)
    }

    @Test
    fun `invalid identities versions and token frames fail before accessing Content`() {
        for (value in listOf("../content.1", "invalid.1", name + ".extra", name.substringBeforeLast('.') + ".0",
            name.substringBeforeLast('.') + ".2147483648", name.substringBeforeLast('.') + ".+1")) {
            assertFailsWith<IllegalStateException> { WorkOpsCollaborationRoom.parse(value) }
        }
        assertFails { WorkOpsCollaborationProtocol.decode(byteArrayOf()) }
        assertFails { WorkOpsCollaborationProtocol.decode(WorkOpsCollaborationProtocol.frame(name, byteArrayOf())) }
        assertFailsWith<IllegalArgumentException> {
            WorkOpsCollaborationProtocol.token(WorkOpsCollaborationProtocol.decode(bytes("sync")))
        }
        assertFailsWith<IllegalArgumentException> {
            WorkOpsCollaborationProtocol.token(WorkOpsCollaborationProtocol.decode(
                WorkOpsCollaborationProtocol.frame(name, byteArrayOf(2, 1)),
            ))
        }
        val authentication = WorkOpsCollaborationProtocol.decode(bytes("authentication"))
        assertFailsWith<IllegalArgumentException> {
            WorkOpsCollaborationProtocol.token(WorkOpsCollaborationProtocol.decode(
                WorkOpsCollaborationProtocol.frame(name, authentication.payload + byteArrayOf(0)),
            ))
        }
    }
}
