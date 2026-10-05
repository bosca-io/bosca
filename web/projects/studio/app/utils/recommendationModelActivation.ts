export function pinnedRecommendationModelVersion(configuration: unknown): number | null {
  if (configuration === null || typeof configuration !== 'object' || Array.isArray(configuration)) return null
  const value = (configuration as Record<string, unknown>).modelVersion
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

export function withPinnedRecommendationModelVersion(
  configuration: unknown,
  version: number | null,
): Record<string, unknown> {
  const updated = configuration !== null && typeof configuration === 'object' && !Array.isArray(configuration)
    ? { ...(configuration as Record<string, unknown>) }
    : {}
  if (version === null) delete updated.modelVersion
  else updated.modelVersion = version
  return updated
}
