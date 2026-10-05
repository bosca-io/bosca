export interface ProfileAssignmentFlag {
  id: string
  key: string
  name: string
  description: string
  type: string
  status: string
  defaultVariationKey: string
  variations: Array<{ key: string; name: string; value: unknown }>
  targetingRules: Array<Record<string, unknown> & { id: string }> | null
  experiments: Array<{ targetingRuleId: string | null }>
}

/** Finds unconditional, single-variation targeting rules dedicated to this principal. */
export function profileAssignmentRules(flag: ProfileAssignmentFlag, principalId: string) {
  return (flag.targetingRules ?? []).filter((rule) => {
    if (flag.experiments.some((experiment) => experiment.targetingRuleId === rule.id)) return false
    const conditions = rule.conditions as Array<Record<string, unknown>> | undefined
    const condition = conditions?.[0]
    if (conditions?.length !== 1 || condition?.type !== 'Principal' || condition.negate) return false
    const principals = condition.principalIds
    if (!Array.isArray(principals) || principals.length !== 1 || principals[0] !== principalId) return false
    const rollout = rule.rollout as { variationWeights?: Array<{ variationKey: string; weight?: number }> } | undefined
    const weights = rollout?.variationWeights
    return weights?.length === 1 && (weights[0]?.weight ?? 1) > 0
  })
}

/** Returns the explicit variation choice, which may be preceded by other targeting rules. */
export function profileAssignmentVariation(flag: ProfileAssignmentFlag, principalId: string): string | undefined {
  const rule = profileAssignmentRules(flag, principalId)[0]
  const rollout = rule?.rollout as { variationWeights: Array<{ variationKey: string }> } | undefined
  return rollout?.variationWeights[0]?.variationKey
}

/** Builds a flag edit preserving configuration and experiment rules, with this choice first. */
export function profileAssignmentInput(
  flag: ProfileAssignmentFlag,
  principalId: string,
  variationKey: string | undefined,
  newRuleId: string,
) {
  if (!principalId) throw new Error('A linked principal is required')
  if (variationKey !== undefined && !flag.variations.some((variation) => variation.key === variationKey)) {
    throw new Error('The selected variation is no longer available')
  }
  const assignments = profileAssignmentRules(flag, principalId)
  const assignmentIds = new Set(assignments.map((rule) => rule.id))
  const targetingRules = (flag.targetingRules ?? []).filter((rule) => !assignmentIds.has(rule.id))
  if (variationKey !== undefined) {
    targetingRules.unshift({
      ...assignments[0],
      id: assignments[0]?.id ?? newRuleId,
      name: assignments[0]?.name ?? 'Profile assignment',
      conditions: [{ type: 'Principal', principalIds: [principalId] }],
      rollout: { variationWeights: [{ variationKey, weight: 1 }] },
    })
  }
  return {
    key: flag.key,
    name: flag.name,
    description: flag.description,
    type: flag.type,
    status: flag.status,
    variations: flag.variations,
    defaultVariationKey: flag.defaultVariationKey,
    targetingRules,
  }
}
