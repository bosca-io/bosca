/**
 * Date helpers for calendar guides (CALENDAR / CALENDAR_PROGRESS).
 *
 * A scheduled guide step carries a `date` whose UTC calendar day is the
 * step's nominal day (the scheduler anchors steps at midnight UTC), so all
 * step-side math reads UTC fields. "Today", by contrast, is the user's local
 * calendar day: an editor expects the entry labeled with the date on their
 * wall clock, regardless of the server's or scheduler's timezone.
 *
 * Pages are 1-based across the guide UI: page 1 is the introduction and
 * steps[i] lives on page i + 2.
 */

export interface DatedGuideStep {
  date?: string | null
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** Builds a sortable YYYY-MM-DD key from calendar-day parts (month is 1-based). */
export function dayKey(year: number, month: number, day: number): string {
  return `${year}-${pad(month)}-${pad(day)}`
}

/** The UTC calendar day of a step date as a YYYY-MM-DD key, or null when absent or unparsable. */
export function stepDayKey(date: string | null | undefined): string | null {
  if (!date) return null
  const parsed = new Date(date)
  if (isNaN(parsed.getTime())) return null
  return dayKey(parsed.getUTCFullYear(), parsed.getUTCMonth() + 1, parsed.getUTCDate())
}

/** The local calendar day of `now` as a YYYY-MM-DD key. */
export function localDayKey(now: Date = new Date()): string {
  return dayKey(now.getFullYear(), now.getMonth() + 1, now.getDate())
}

/**
 * Maps each dated step's day to its guide page. When several steps share a
 * day the first one keeps the slot, matching the step strip's order.
 */
export function stepPagesByDay(steps: DatedGuideStep[]): Map<string, number> {
  const pages = new Map<string, number>()
  steps.forEach((step, index) => {
    const key = stepDayKey(step.date)
    if (key && !pages.has(key)) pages.set(key, index + 2)
  })
  return pages
}

/**
 * The page a calendar guide should land on for a day: that day's step, else
 * the latest earlier step (a gap — e.g. a weekend in a weekday-only guide —
 * falls back to the most recent entry), else the introduction when the day
 * precedes every step.
 */
export function pageForDay(steps: DatedGuideStep[], day: string): number {
  let page = 1
  let bestKey: string | null = null
  steps.forEach((step, index) => {
    const key = stepDayKey(step.date)
    if (!key || key > day) return
    if (bestKey === null || key > bestKey) {
      bestKey = key
      page = index + 2
    }
  })
  return page
}
