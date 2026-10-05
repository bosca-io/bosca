/**
 * Editor helpers for the COHORT_CO_ENGAGEMENT ("People like you") strategy's `configuration`. It holds two
 * tuning caps that bound the nightly co-engagement computation, so the strategy form edits them as number
 * fields instead of raw JSON. These map the flat editable caps <-> the stored configuration object, and
 * default/clamp anything missing or invalid so an older strategy (no configuration) still edits cleanly.
 */

/** The backend default for both caps (mirrors CohortCoEngagementConfiguration). */
export const DEFAULT_COHORT_CAP = 200

export interface CohortCaps {
  /** How many of each viewer's most-recently-engaged items are considered when finding "people like you". */
  perUserItemCap: number
  /** How many recommendations are kept per source item, per cohort. */
  perSourceCap: number
}

/** A positive integer cap, falling back to [DEFAULT_COHORT_CAP] for anything missing/invalid/non-positive. */
function sanitizeCap(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) && n >= 1 ? Math.floor(n) : DEFAULT_COHORT_CAP
}

/** Reads the caps out of a strategy's raw `configuration`, defaulting each to [DEFAULT_COHORT_CAP]. */
export function readCohortCaps(configuration: unknown): CohortCaps {
  const cfg = (configuration ?? {}) as Record<string, unknown>
  return { perUserItemCap: sanitizeCap(cfg.perUserItemCap), perSourceCap: sanitizeCap(cfg.perSourceCap) }
}

/** The `configuration` object to store for the given caps (sanitized to positive integers). */
export function buildCohortConfiguration(caps: CohortCaps): CohortCaps {
  return { perUserItemCap: sanitizeCap(caps.perUserItemCap), perSourceCap: sanitizeCap(caps.perSourceCap) }
}
