package yks.sync

import yks.WireCompatFixtures
import yks.lib0.Decoder
import yks.lib0.Encoder
import yks.utils.*
import kotlin.test.*

class SyncProtocolTest {

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun newDoc(clientID: Int): Doc {
        val doc = Doc()
        doc.clientID = clientID
        return doc
    }

    // ── SyncProtocol encode/decode ──

    @Test
    fun testWriteSyncStep1() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf(1, 2))
        val encoder = Encoder()
        SyncProtocol.writeSyncStep1(encoder, doc)
        val bytes = encoder.toByteArray()
        val decoder = Decoder(bytes)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP1, decoder.readVarUint())
        val sv = decodeStateVector(decoder.readVarUint8Array())
        assertEquals(2, sv[100])
    }

    @Test
    fun testWriteSyncStep2() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf(1))
        val encoder = Encoder()
        SyncProtocol.writeSyncStep2(encoder, doc)
        val bytes = encoder.toByteArray()
        val decoder = Decoder(bytes)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP2, decoder.readVarUint())
        val update = decoder.readVarUint8Array()
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertEquals(1, doc2.getArray("arr").length)
    }

    @Test
    fun testWriteUpdate() {
        val encoder = Encoder()
        val update = byteArrayOf(1, 2, 3)
        SyncProtocol.writeUpdate(encoder, update)
        val bytes = encoder.toByteArray()
        val decoder = Decoder(bytes)
        assertEquals(SyncProtocol.MESSAGE_YJS_UPDATE, decoder.readVarUint())
        assertContentEquals(update, decoder.readVarUint8Array())
    }

    @Test
    fun testReadSyncStep1ProducesStep2Reply() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf("a", "b"))

        // Simulate remote sending SyncStep1 with empty state vector
        val step1Encoder = Encoder()
        SyncProtocol.writeSyncStep1(step1Encoder, Doc()) // empty doc
        val step1Bytes = step1Encoder.toByteArray()

        // Read SyncStep1 and generate reply
        val decoder = Decoder(step1Bytes)
        decoder.readVarUint() // skip message type
        val replyEncoder = Encoder()
        SyncProtocol.readSyncStep1(decoder, replyEncoder, doc)

        // The reply should be a SyncStep2 containing doc's data
        val replyDecoder = Decoder(replyEncoder.toByteArray())
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP2, replyDecoder.readVarUint())
        val update = replyDecoder.readVarUint8Array()
        val newDoc = Doc()
        applyUpdate(newDoc, update)
        assertEquals(2, newDoc.getArray("arr").length)
    }

    @Test
    fun testReadSyncMessage_dispatches() {
        val doc = newDoc(100)
        doc.getMap("m").set("key", "val")

        // SyncStep1 → should produce SyncStep2 reply
        val step1Enc = Encoder()
        SyncProtocol.writeSyncStep1(step1Enc, Doc())
        val replyEnc = Encoder()
        val decoder1 = Decoder(step1Enc.toByteArray())
        val type1 = SyncProtocol.readSyncMessage(decoder1, replyEnc, doc)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP1, type1)
        assertTrue(replyEnc.length > 0, "SyncStep1 should produce a reply")

        // SyncStep2 → should apply update (no reply)
        val step2Enc = Encoder()
        SyncProtocol.writeSyncStep2(step2Enc, doc)
        val noReplyEnc = Encoder()
        val receivingDoc = Doc()
        val decoder2 = Decoder(step2Enc.toByteArray())
        val type2 = SyncProtocol.readSyncMessage(decoder2, noReplyEnc, receivingDoc)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP2, type2)
        assertEquals("val", receivingDoc.getMap("m").get("key"))

        // Update → should apply update (no reply)
        val updateEnc = Encoder()
        SyncProtocol.writeUpdate(updateEnc, encodeStateAsUpdate(doc))
        val noReplyEnc2 = Encoder()
        val receivingDoc2 = Doc()
        val decoder3 = Decoder(updateEnc.toByteArray())
        val type3 = SyncProtocol.readSyncMessage(decoder3, noReplyEnc2, receivingDoc2)
        assertEquals(SyncProtocol.MESSAGE_YJS_UPDATE, type3)
        assertEquals("val", receivingDoc2.getMap("m").get("key"))
    }

    // ── Two-doc full sync via protocol messages ──

    @Test
    fun testTwoDocSyncViaProtocol() {
        val doc1 = newDoc(100)
        val doc2 = newDoc(200)
        doc1.getArray("arr").push(listOf(1, 2))
        doc2.getMap("m").set("key", "val")

        // doc1 sends SyncStep1
        val step1Enc = Encoder()
        SyncProtocol.writeSyncStep1(step1Enc, doc1)

        // doc2 receives SyncStep1, produces SyncStep2 reply
        val replyEnc = Encoder()
        SyncProtocol.readSyncMessage(Decoder(step1Enc.toByteArray()), replyEnc, doc2)

        // doc1 receives SyncStep2 from doc2
        SyncProtocol.readSyncMessage(Decoder(replyEnc.toByteArray()), Encoder(), doc1)

        // doc2 sends SyncStep1
        val step1Enc2 = Encoder()
        SyncProtocol.writeSyncStep1(step1Enc2, doc2)

        // doc1 receives SyncStep1, produces SyncStep2 reply
        val replyEnc2 = Encoder()
        SyncProtocol.readSyncMessage(Decoder(step1Enc2.toByteArray()), replyEnc2, doc1)

        // doc2 receives SyncStep2 from doc1
        SyncProtocol.readSyncMessage(Decoder(replyEnc2.toByteArray()), Encoder(), doc2)

        // Both docs should now have the same state
        assertEquals(listOf(1, 2), doc1.getArray("arr").toArray())
        assertEquals("val", doc1.getMap("m").get("key"))
        assertEquals(listOf(1, 2), doc2.getArray("arr").toArray())
        assertEquals("val", doc2.getMap("m").get("key"))
    }

    @Test
    fun testIncrementalUpdatesAfterSync() {
        val doc1 = newDoc(100)
        val doc2 = newDoc(200)

        // Initial sync (empty docs)
        val step1 = Encoder(); SyncProtocol.writeSyncStep1(step1, doc1)
        val reply1 = Encoder(); SyncProtocol.readSyncMessage(Decoder(step1.toByteArray()), reply1, doc2)
        SyncProtocol.readSyncMessage(Decoder(reply1.toByteArray()), Encoder(), doc1)
        val step1b = Encoder(); SyncProtocol.writeSyncStep1(step1b, doc2)
        val reply1b = Encoder(); SyncProtocol.readSyncMessage(Decoder(step1b.toByteArray()), reply1b, doc1)
        SyncProtocol.readSyncMessage(Decoder(reply1b.toByteArray()), Encoder(), doc2)

        // Now send incremental updates
        var lastUpdate: ByteArray? = null
        doc1.on2<ByteArray, Any?>("update") { update, _ -> lastUpdate = update }

        doc1.getArray("arr").push(listOf(42))
        assertNotNull(lastUpdate)

        // Send update to doc2
        val updateEnc = Encoder()
        SyncProtocol.writeUpdate(updateEnc, lastUpdate)
        SyncProtocol.readSyncMessage(Decoder(updateEnc.toByteArray()), Encoder(), doc2)

        assertEquals(listOf(42), doc2.getArray("arr").toArray())
    }

    // ── Wire compatibility with JS y-protocols ──

    @Test
    fun testWireCompat_decodeSyncStep1Message() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_SYNC_STEP1_MESSAGE)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_SYNC, decoder.readVarUint()) // top-level type
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP1, decoder.readVarUint()) // sub type
        val sv = decodeStateVector(decoder.readVarUint8Array())
        // Fixture has clientID=1000 with arr.push([1,2,3]) → clock=3
        assertEquals(3, sv[1000])
    }

    @Test
    fun testWireCompat_decodeSyncStep2Message() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_SYNC_STEP2_MESSAGE)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_SYNC, decoder.readVarUint())
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP2, decoder.readVarUint())
        val update = decoder.readVarUint8Array()
        val doc = Doc()
        applyUpdate(doc, update)
        assertEquals("value", doc.getMap("map").get("key"))
    }

    @Test
    fun testWireCompat_decodeUpdateMessage() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_UPDATE_MESSAGE)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_SYNC, decoder.readVarUint())
        assertEquals(SyncProtocol.MESSAGE_YJS_UPDATE, decoder.readVarUint())
        val update = decoder.readVarUint8Array()
        val doc = Doc()
        applyUpdate(doc, update)
        assertEquals("Hello", doc.getText("text").toString())
    }

    @Test
    fun testWireCompat_fullHandshake() {
        // Decode and apply the full handshake fixture
        val docA = newDoc(WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_CLIENT_A)
        val docB = newDoc(WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_CLIENT_B)

        // A creates arr=[1,2], B creates map={key:val}
        docA.getArray("arr").push(listOf(1, 2))
        docB.getMap("map").set("key", "val")

        // Verify A's SyncStep1 matches fixture
        val step1AtoB = MessageProtocol.encodeSyncStep1(docA)
        assertEquals(
            WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_STEP1_A_TO_B,
            toHex(step1AtoB)
        )

        // Apply B's SyncStep2 to A
        val step2BtoA = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_STEP2_B_TO_A)
        MessageProtocol.readMessage(step2BtoA, docA, null)

        // Verify B's SyncStep1 matches fixture
        val step1BtoA = MessageProtocol.encodeSyncStep1(docB)
        assertEquals(
            WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_STEP1_B_TO_A,
            toHex(step1BtoA)
        )

        // Apply A's SyncStep2 to B
        val step2AtoB = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_FULL_HANDSHAKE_STEP2_A_TO_B)
        MessageProtocol.readMessage(step2AtoB, docB, null)

        // Both should converge
        assertEquals("val", docA.getMap("map").get("key"))
        assertEquals(listOf(1, 2), docA.getArray("arr").toArray())
        assertEquals("val", docB.getMap("map").get("key"))
        assertEquals(listOf(1, 2), docB.getArray("arr").toArray())
    }

    @Test
    fun testWireCompat_queryAwareness() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_QUERY_AWARENESS_MESSAGE)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_QUERY_AWARENESS, decoder.readVarUint())
        assertFalse(decoder.hasContent) // no payload
    }

    // ── MessageProtocol encode convenience ──

    @Test
    fun testMessageProtocol_encodeSyncStep1() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf(1))
        val bytes = MessageProtocol.encodeSyncStep1(doc)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_SYNC, decoder.readVarUint())
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP1, decoder.readVarUint())
        val sv = decodeStateVector(decoder.readVarUint8Array())
        assertEquals(1, sv[100])
    }

    @Test
    fun testMessageProtocol_encodeUpdate() {
        val update = byteArrayOf(1, 2, 3)
        val bytes = MessageProtocol.encodeUpdate(update)
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_SYNC, decoder.readVarUint())
        assertEquals(SyncProtocol.MESSAGE_YJS_UPDATE, decoder.readVarUint())
        assertContentEquals(update, decoder.readVarUint8Array())
    }

    @Test
    fun testMessageProtocol_encodeQueryAwareness() {
        val bytes = MessageProtocol.encodeQueryAwareness()
        val decoder = Decoder(bytes)
        assertEquals(MessageProtocol.MESSAGE_QUERY_AWARENESS, decoder.readVarUint())
    }

    @Test
    fun testMessageProtocol_readMessageSyncStep1ProducesReply() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf(1, 2))
        val step1 = MessageProtocol.encodeSyncStep1(Doc()) // empty doc asks for everything
        val reply = MessageProtocol.readMessage(step1, doc, null)
        assertTrue(reply.isNotEmpty(), "SyncStep1 should produce a SyncStep2 reply")

        // Apply reply to a new doc — should contain doc's data
        val newDoc = Doc()
        MessageProtocol.readMessage(reply, newDoc, null)
        assertEquals(2, newDoc.getArray("arr").length)
    }

    @Test
    fun testMessageProtocol_readMessageSyncStep2NoReply() {
        val doc = newDoc(100)
        doc.getMap("m").set("x", 1)
        val step2 = MessageProtocol.encodeSyncStep2(doc)
        val receivingDoc = Doc()
        val reply = MessageProtocol.readMessage(step2, receivingDoc, null)
        assertEquals(0, reply.size, "SyncStep2 should not produce a reply")
        assertEquals(1, receivingDoc.getMap("m").get("x"))
    }

    @Test
    fun testMessageProtocol_readMessageUpdateNoReply() {
        val doc = newDoc(100)
        doc.getArray("a").push(listOf("hello"))
        val update = encodeStateAsUpdate(doc)
        val msg = MessageProtocol.encodeUpdate(update)
        val receivingDoc = Doc()
        val reply = MessageProtocol.readMessage(msg, receivingDoc, null)
        assertEquals(0, reply.size)
        assertEquals(listOf("hello"), receivingDoc.getArray("a").toArray())
    }

    // --- Error/edge paths ---

    @Test
    fun testReadSyncMessageUnknownTypeThrows() {
        val encoder = Encoder()
        encoder.writeVarUint(99) // invalid message type
        val decoder = Decoder(encoder.toByteArray())
        val replyEncoder = Encoder()
        val doc = Doc()
        assertFailsWith<IllegalStateException> {
            SyncProtocol.readSyncMessage(decoder, replyEncoder, doc, null)
        }
    }

    @Test
    fun testWriteSyncStep2WithEncodedSV() {
        val doc = newDoc(100)
        doc.getArray("arr").push(listOf(1, 2, 3))

        val encodedSV = encodeStateVector(Doc()) // empty SV
        val encoder = Encoder()
        SyncProtocol.writeSyncStep2(encoder, doc, encodedSV)

        val bytes = encoder.toByteArray()
        val decoder = Decoder(bytes)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP2, decoder.readVarUint())
        val update = decoder.readVarUint8Array()
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertEquals(3, doc2.getArray("arr").length)
    }

    @Test
    fun testReadSyncStep2WithOrigin() {
        val doc1 = newDoc(100)
        doc1.getArray("arr").push(listOf("value"))

        val encoder = Encoder()
        SyncProtocol.writeSyncStep2(encoder, doc1)
        val bytes = encoder.toByteArray()

        val receivingDoc = Doc()
        val decoder = Decoder(bytes)
        decoder.readVarUint() // skip message type
        SyncProtocol.readSyncStep2(decoder, receivingDoc, "custom-origin")
        assertEquals("value", receivingDoc.getArray("arr").get(0))
    }

    @Test
    fun testReadSyncMessageWithEmptyDoc() {
        val doc = Doc()
        val step1 = Encoder()
        SyncProtocol.writeSyncStep1(step1, doc)

        val decoder = Decoder(step1.toByteArray())
        val reply = Encoder()
        val msgType = SyncProtocol.readSyncMessage(decoder, reply, doc)
        assertEquals(SyncProtocol.MESSAGE_SYNC_STEP1, msgType)
    }
}
