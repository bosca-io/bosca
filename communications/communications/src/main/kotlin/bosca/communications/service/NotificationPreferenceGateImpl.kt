package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationTypeKeys
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@ServiceImplementation
open class NotificationPreferenceGateImpl(
    private val preferences: NotificationPreferenceService,
    private val deliveryTracking: DeliveryTrackingService,
) : NotificationPreferenceGate {

    override suspend fun evaluate(
        profileId: UUID,
        channel: DeliveryChannel,
        type: String?,
        emailAddress: String?,
        highPriority: Boolean,
    ): GateDecision {
        if (channel == DeliveryChannel.EMAIL && emailAddress != null && deliveryTracking.isSuppressed(emailAddress)) {
            return GateDecision.Suppressed(GateReasons.SUPPRESSED_ADDRESS)
        }
        val resolvedType = type ?: NotificationTypeKeys.TRANSACTIONAL
        if (preferences.isOptedOut(profileId, channel, resolvedType)) {
            return GateDecision.Suppressed(GateReasons.PREFERENCE)
        }
        if (channel == DeliveryChannel.PUSH && !highPriority && resolvedType != NotificationTypeKeys.SECURITY) {
            quietHoursDeferral(profileId, preferences.getSettings(profileId))?.let { return it }
        }
        return GateDecision.Allow
    }

    private fun quietHoursDeferral(profileId: UUID, settings: NotificationSettings): GateDecision.Deferred? {
        val timeZone = settings.timeZone ?: return null
        val startText = settings.dndStartLocal ?: return null
        val endText = settings.dndEndLocal ?: return null
        // Fail open on malformed stored values — a bad setting must never
        // silently swallow notifications.
        val zone = runCatching { ZoneId.of(timeZone) }.getOrElse {
            log.warn("Unknown time zone {} for profile {}; sending without quiet hours", timeZone, profileId)
            return null
        }
        val start = runCatching { LocalTime.parse(startText) }.getOrElse {
            log.warn("Malformed dndStartLocal {} for profile {}; sending without quiet hours", startText, profileId)
            return null
        }
        val end = runCatching { LocalTime.parse(endText) }.getOrElse {
            log.warn("Malformed dndEndLocal {} for profile {}; sending without quiet hours", endText, profileId)
            return null
        }
        val recipientNow = now(zone)
        if (!isInQuietWindow(recipientNow.toLocalTime(), start, end)) return null
        return GateDecision.Deferred(quietWindowEnd(recipientNow, end).toOffsetDateTime())
    }

    protected open fun now(zone: ZoneId): ZonedDateTime = ZonedDateTime.now(zone)

    companion object {
        private val log = LoggerFactory.getLogger(NotificationPreferenceGateImpl::class.java)

        /**
         * Whether [now] falls inside the quiet window [start, end),
         * including windows that cross midnight (start > end).
         */
        fun isInQuietWindow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean =
            if (start <= end) now >= start && now < end
            else now >= start || now < end

        /**
         * The next moment the quiet window ends, relative to a [now]
         * that is inside the window: today at [end] if that is still
         * ahead, otherwise tomorrow at [end].
         */
        fun quietWindowEnd(now: ZonedDateTime, end: LocalTime): ZonedDateTime {
            val todayEnd = now.with(end)
            return if (todayEnd.isAfter(now)) todayEnd else todayEnd.plusDays(1)
        }
    }
}
