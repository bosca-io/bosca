package bosca.kubernetes.controller.util

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [formatAge] and [formatRelativeTime] — the two time
 * formatters that turn fabric8 creation timestamps into the strings
 * the studio renders directly. The contract surface they pin:
 *
 *   * Null / blank input → `"-"` (renders as a dash placeholder client-side).
 *   * Malformed timestamp → `"-"` (no parse exceptions bubble out of
 *     a list call — one bad resource shouldn't 500 the whole page).
 *   * Time-unit ladder: `>0d → d`, else `>0h → h`, else `>0m → m`, else `s`.
 *   * `formatRelativeTime` adds the "ago" suffix and a "just now" tier
 *     for `<5s` so a barely-stale event doesn't render as `0s ago`.
 *   * Negative durations (clock skew between cluster and controller)
 *     coerce to 0 — never negative output.
 */
class AgeTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-15T12:00:00Z")

    // ===== formatAge =====

    @Test
    fun `formatAge returns dash for null timestamp`() {
        assertEquals("-", formatAge(null, now))
    }

    @Test
    fun `formatAge returns dash for blank timestamp`() {
        assertEquals("-", formatAge("", now))
        assertEquals("-", formatAge("   ", now))
    }

    @Test
    fun `formatAge returns dash for malformed timestamp instead of throwing`() {
        assertEquals("-", formatAge("not-a-timestamp", now))
        assertEquals("-", formatAge("2026-13-45T99:99:99Z", now))
    }

    @Test
    fun `formatAge for 30-day-old resource renders days`() {
        assertEquals("30d", formatAge("2026-04-15T12:00:00Z", now))
    }

    @Test
    fun `formatAge for 1-day-old resource renders 1d not 24h`() {
        assertEquals("1d", formatAge("2026-05-14T12:00:00Z", now))
    }

    @Test
    fun `formatAge for resources under a day renders hours`() {
        assertEquals("23h", formatAge("2026-05-14T13:00:00Z", now))
        assertEquals("2h", formatAge("2026-05-15T10:00:00Z", now))
        assertEquals("1h", formatAge("2026-05-15T11:00:00Z", now))
    }

    @Test
    fun `formatAge for resources under an hour renders minutes`() {
        assertEquals("30m", formatAge("2026-05-15T11:30:00Z", now))
        assertEquals("1m", formatAge("2026-05-15T11:59:00Z", now))
    }

    @Test
    fun `formatAge for resources under a minute renders seconds`() {
        assertEquals("45s", formatAge("2026-05-15T11:59:15Z", now))
        assertEquals("5s", formatAge("2026-05-15T11:59:55Z", now))
        assertEquals("0s", formatAge("2026-05-15T12:00:00Z", now))
    }

    @Test
    fun `formatAge clamps negative durations to 0s`() {
        // Resource creation timestamp is in the future — clock skew between
        // cluster and controller. We never render `-5s` to the operator.
        assertEquals("0s", formatAge("2026-05-15T12:00:05Z", now))
    }

    @Test
    fun `formatAge no-arg overload uses now-utc`() {
        val recent = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2).toString()
        val out = formatAge(recent)
        // Either "1m" or "2m" depending on millisecond rounding — pin the unit.
        assertTrue(out.endsWith("m"), "expected minutes unit, got $out")
    }

    // ===== formatRelativeTime =====

    @Test
    fun `formatRelativeTime returns dash for null timestamp`() {
        assertEquals("-", formatRelativeTime(null, now))
    }

    @Test
    fun `formatRelativeTime returns dash for malformed timestamp`() {
        assertEquals("-", formatRelativeTime("invalid", now))
    }

    @Test
    fun `formatRelativeTime returns just now for under 5 seconds`() {
        assertEquals("just now", formatRelativeTime("2026-05-15T12:00:00Z", now))
        assertEquals("just now", formatRelativeTime("2026-05-15T11:59:56Z", now))
    }

    @Test
    fun `formatRelativeTime under a minute renders seconds with ago suffix`() {
        assertEquals("5s ago", formatRelativeTime("2026-05-15T11:59:55Z", now))
        assertEquals("30s ago", formatRelativeTime("2026-05-15T11:59:30Z", now))
    }

    @Test
    fun `formatRelativeTime under an hour renders minutes`() {
        assertEquals("5m ago", formatRelativeTime("2026-05-15T11:55:00Z", now))
        assertEquals("59m ago", formatRelativeTime("2026-05-15T11:01:00Z", now))
    }

    @Test
    fun `formatRelativeTime under a day renders hours`() {
        assertEquals("2h ago", formatRelativeTime("2026-05-15T10:00:00Z", now))
        assertEquals("23h ago", formatRelativeTime("2026-05-14T13:00:00Z", now))
    }

    @Test
    fun `formatRelativeTime over a day renders days`() {
        assertEquals("1d ago", formatRelativeTime("2026-05-14T12:00:00Z", now))
        assertEquals("30d ago", formatRelativeTime("2026-04-15T12:00:00Z", now))
    }

    @Test
    fun `formatRelativeTime clamps negative durations`() {
        // Future timestamp — same clock-skew tolerance as formatAge,
        // but here it renders as "just now" rather than "0s ago" because
        // the under-5s rule fires first.
        assertEquals("just now", formatRelativeTime("2026-05-15T12:00:10Z", now))
    }

    @Test
    fun `formatRelativeTime default now arg is now-utc`() {
        val recent = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2).toString()
        // Within the 5-second "just now" window
        assertEquals("just now", formatRelativeTime(recent))
    }
}
