package bosca.kubernetes.controller.util

import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.K8sEvent
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import io.fabric8.kubernetes.api.model.Event as K8sFabricEvent

/**
 * Maps a fabric8 core/v1 `Event` into our wire [K8sEvent] shape.
 *
 * The `level` mapping is a coarse `Normal → INFO` / `Warning → WARN`
 * cast. Kubernetes does not natively distinguish a third ERROR level
 * on events; we promote a Warning to ERROR only when the reason
 * matches one of the known terminal failure modes
 * (`Failed`, `FailedScheduling`, `CrashLoopBackOff`, …) so the
 * studio's event tail can colour the worst offenders distinctly.
 *
 * The `when` field is the controller's relative-time render of the
 * most recent timestamp on the event so every client renders the
 * same string; `timestamp` carries the absolute ISO-8601 value for
 * sorting and deep-link state.
 */
fun K8sFabricEvent.toEventWire(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): K8sEvent {
    val ts = lastTimestamp ?: firstTimestamp ?: eventTime?.time ?: metadata?.creationTimestamp
    val absolute = parseOffset(ts) ?: now
    val level = mapLevel(type, reason)
    val kind = involvedObject?.kind.orEmpty()
    val name = involvedObject?.name.orEmpty()
    val involved = if (kind.isNotBlank() && name.isNotBlank()) "$kind/$name" else kind.ifBlank { name }

    return K8sEvent(
        id = metadata?.uid ?: "${metadata?.namespace}/${metadata?.name}",
        level = level,
        `when` = formatRelativeTime(ts, now),
        timestamp = absolute,
        namespace = metadata?.namespace.orEmpty(),
        involvedObject = involved,
        message = message.orEmpty(),
        reason = reason.orEmpty(),
    )
}

private fun mapLevel(type: String?, reason: String?): EventLevel = when {
    type == "Warning" && reason != null && reason in TERMINAL_REASONS -> EventLevel.ERROR
    type == "Warning" -> EventLevel.WARN
    else -> EventLevel.INFO
}

private val TERMINAL_REASONS = setOf(
    "Failed",
    "FailedScheduling",
    "FailedCreate",
    "FailedMount",
    "FailedAttachVolume",
    "FailedSync",
    "CrashLoopBackOff",
    "BackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "OOMKilling",
    "Evicted",
)

private fun parseOffset(raw: String?): OffsetDateTime? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
}
