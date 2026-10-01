package bosca.kubernetes.controller.util

import bosca.kubernetes.model.EventLevel
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.MicroTime
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the EventLevel ladder and timestamp-source priority used by
 * [io.fabric8.kubernetes.api.model.Event.toEventWire]. The level
 * mapping is the studio's hook for colouring rows red:
 *
 *   * `type == "Warning"` AND reason in the terminal set → ERROR
 *   * `type == "Warning"` (any other reason) → WARN
 *   * Anything else → INFO (Kubernetes only emits Normal/Warning, so
 *     INFO is the catch-all for Normal *and* nullable types).
 *
 * Timestamp source priority: `lastTimestamp` → `firstTimestamp` →
 * `eventTime` → `metadata.creationTimestamp` → `now`. We pick the
 * latest available so the studio renders "when this event last fired".
 */
class EventMapperTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-15T12:00:00Z")

    @Test
    fun `normal-type event maps to INFO regardless of reason`() {
        val event = EventBuilder()
            .withNewMetadata().withUid("e-1").withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withReason("Pulled")
            .withMessage("Successfully pulled image")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("api").build())
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()

        val wire = event.toEventWire(now)
        assertEquals(EventLevel.INFO, wire.level)
        assertEquals("5m ago", wire.`when`)
        assertEquals("Pod/api", wire.involvedObject)
        assertEquals("Successfully pulled image", wire.message)
        assertEquals("Pulled", wire.reason)
        assertEquals(OffsetDateTime.parse("2026-05-15T11:55:00Z"), wire.timestamp)
    }

    @Test
    fun `warning-type event with a non-terminal reason maps to WARN`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Warning")
            .withReason("Unhealthy")
            .withMessage("Readiness probe failed")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("api").build())
            .withLastTimestamp("2026-05-15T11:00:00Z")
            .build()

        assertEquals(EventLevel.WARN, event.toEventWire(now).level)
    }

    @Test
    fun `warning-type event with a terminal reason is promoted to ERROR`() {
        for (terminal in listOf(
            "Failed",
            "FailedScheduling",
            "FailedCreate",
            "FailedMount",
            "CrashLoopBackOff",
            "BackOff",
            "ImagePullBackOff",
            "ErrImagePull",
            "OOMKilling",
            "Evicted",
            "FailedAttachVolume",
            "FailedSync",
        )) {
            val event = EventBuilder()
                .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
                .withType("Warning")
                .withReason(terminal)
                .withLastTimestamp("2026-05-15T11:30:00Z")
                .build()
            assertEquals(
                EventLevel.ERROR,
                event.toEventWire(now).level,
                "expected $terminal to promote Warning to ERROR",
            )
        }
    }

    @Test
    fun `null type defaults to INFO`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withReason("Whatever")
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()

        assertEquals(EventLevel.INFO, event.toEventWire(now).level)
    }

    @Test
    fun `involvedObject renders as kind slash name when both present`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Deployment").withName("api").build())
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()

        assertEquals("Deployment/api", event.toEventWire(now).involvedObject)
    }

    @Test
    fun `involvedObject falls back to whichever side is populated`() {
        val kindOnly = EventBuilder()
            .withNewMetadata().withName("a").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").build())
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        assertEquals("Pod", kindOnly.toEventWire(now).involvedObject)

        val nameOnly = EventBuilder()
            .withNewMetadata().withName("b").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withInvolvedObject(ObjectReferenceBuilder().withName("dangling").build())
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        assertEquals("dangling", nameOnly.toEventWire(now).involvedObject)

        val neither = EventBuilder()
            .withNewMetadata().withName("c").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        assertEquals("", neither.toEventWire(now).involvedObject)
    }

    @Test
    fun `timestamp source priority is last firstTimestamp then eventTime then creationTimestamp`() {
        // 1. lastTimestamp wins when present
        val withLast = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns")
                .withCreationTimestamp("2026-05-15T11:00:00Z")
            .endMetadata()
            .withType("Normal")
            .withFirstTimestamp("2026-05-15T11:30:00Z")
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        assertEquals(OffsetDateTime.parse("2026-05-15T11:55:00Z"), withLast.toEventWire(now).timestamp)

        // 2. firstTimestamp wins when lastTimestamp is absent
        val withFirst = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns")
                .withCreationTimestamp("2026-05-15T11:00:00Z")
            .endMetadata()
            .withType("Normal")
            .withFirstTimestamp("2026-05-15T11:30:00Z")
            .build()
        assertEquals(OffsetDateTime.parse("2026-05-15T11:30:00Z"), withFirst.toEventWire(now).timestamp)

        // 3. eventTime wins when both first/last are absent
        val withEventTime = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns")
                .withCreationTimestamp("2026-05-15T11:00:00Z")
            .endMetadata()
            .withType("Normal")
            .withEventTime(MicroTime("2026-05-15T11:45:00Z"))
            .build()
        assertEquals(OffsetDateTime.parse("2026-05-15T11:45:00Z"), withEventTime.toEventWire(now).timestamp)

        // 4. creationTimestamp is the final fallback
        val withCreation = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns")
                .withCreationTimestamp("2026-05-15T11:00:00Z")
            .endMetadata()
            .withType("Normal")
            .build()
        assertEquals(OffsetDateTime.parse("2026-05-15T11:00:00Z"), withCreation.toEventWire(now).timestamp)
    }

    @Test
    fun `timestamp falls back to now when every source is null`() {
        val empty = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .build()
        assertEquals(now, empty.toEventWire(now).timestamp)
    }

    @Test
    fun `event id falls back to namespace and name when uid is missing`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        assertEquals("ns/e", event.toEventWire(now).id)
    }

    @Test
    fun `malformed timestamp renders as dash for when and now for absolute`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withLastTimestamp("not-a-timestamp")
            .build()
        val wire = event.toEventWire(now)
        assertEquals("-", wire.`when`)
        // parseOffset returned null → absolute coerces to `now`
        assertEquals(now, wire.timestamp)
    }

    @Test
    fun `null reason and message render as empty strings not null`() {
        val event = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("ns").endMetadata()
            .withType("Normal")
            .withLastTimestamp("2026-05-15T11:55:00Z")
            .build()
        val wire = event.toEventWire(now)
        assertEquals("", wire.reason)
        assertEquals("", wire.message)
        assertTrue(wire.`when`.endsWith("ago") || wire.`when` == "just now")
    }
}
