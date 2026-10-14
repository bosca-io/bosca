export interface Policy { requireInterfaceReview: boolean; requireTdd: boolean; requireTestQuality: boolean; minimumLineCoverage: number; minimumBranchCoverage: number }
export interface Guide { phase: string; name: string; description: string; markdown: string; inherited: boolean }
export interface Guidance { revision: string; policy: Policy; policyInherited: boolean; guides: Guide[] }
export interface GuidanceDraft { guides: Guide[]; policy: Policy; inheritPolicy: boolean }
interface PolicyDraft { policy: Policy; inheritPolicy: boolean }
export interface GuidanceConflict { key: string; label: string; local: Guide | PolicyDraft; saved: Guide | PolicyDraft }
export interface GuidanceReconciliation { latest: Guidance; inherited: Guidance; draft: GuidanceDraft; conflicts: GuidanceConflict[] }
export type GuidanceChoice = 'local' | 'saved'

function policySignature(value: PolicyDraft) { return JSON.stringify(value.inheritPolicy ? null : value.policy) }
function guideSignature(value: Guide) { return JSON.stringify(value.inherited ? { phase: value.phase, inherited: true } : value) }
function copy(draft: GuidanceDraft): GuidanceDraft { return { guides: draft.guides.map(guide => ({ ...guide })), policy: { ...draft.policy }, inheritPolicy: draft.inheritPolicy } }

/** Compares draft changes to their original baseline; simultaneous changes need an explicit choice. */
export function planGuidanceReconciliation(baseline: Guidance, local: GuidanceDraft, latest: Guidance, inherited: Guidance): GuidanceReconciliation {
  const conflicts: GuidanceConflict[] = []
  function merge<T extends Guide | PolicyDraft>(key: string, label: string, before: T, draft: T, saved: T, signature: (value: T) => string): T {
    const a = signature(before), b = signature(draft), c = signature(saved)
    if (a === b) return saved
    if (a === c || b === c) return draft
    conflicts.push({ key, label, local: draft, saved })
    return draft
  }
  const policy = merge('policy', 'Verification policy', { policy: baseline.policy, inheritPolicy: baseline.policyInherited }, { policy: local.policy, inheritPolicy: local.inheritPolicy }, { policy: latest.policy, inheritPolicy: latest.policyInherited }, policySignature)
  const guides = latest.guides.map(saved => {
    const before = baseline.guides.find(guide => guide.phase === saved.phase)
    const draft = local.guides.find(guide => guide.phase === saved.phase)
    if (!before || !draft) throw new Error('Guidance phases changed. Reopen the settings before reconciling.')
    const label = `${saved.phase.charAt(0)}${saved.phase.slice(1).toLowerCase()} guide`
    return merge(saved.phase, label, before, draft, saved, guideSignature)
  })
  return { latest, inherited, draft: copy({ guides, ...policy }), conflicts }
}

/** Applies every explicit conflict choice and refreshes the preview of inherited settings. */
export function resolveGuidanceReconciliation(plan: GuidanceReconciliation, choices: Record<string, GuidanceChoice>): GuidanceDraft {
  const draft = copy(plan.draft)
  for (const conflict of plan.conflicts) {
    const choice = choices[conflict.key]
    if (choice !== 'local' && choice !== 'saved') throw new Error(`Choose which version to keep for ${conflict.label}.`)
    const value = conflict[choice]
    if ('policy' in value) { draft.policy = { ...value.policy }; draft.inheritPolicy = value.inheritPolicy }
    else draft.guides = draft.guides.map(guide => guide.phase === conflict.key ? { ...value } : guide)
  }
  if (draft.inheritPolicy) draft.policy = { ...plan.inherited.policy }
  draft.guides = draft.guides.map(guide => {
    if (!guide.inherited) return guide
    const inherited = plan.inherited.guides.find(value => value.phase === guide.phase)
    if (!inherited) throw new Error(`Inherited ${guide.phase.toLowerCase()} guide is missing.`)
    return { ...inherited, inherited: true }
  })
  return draft
}

/** Readable conflict preview without exposing revision identifiers or persistence details. */
export function describeGuidanceConflict(value: Guide | PolicyDraft): string {
  if ('markdown' in value) return `${value.inherited ? 'Inherited guide' : 'Scope override'}\n${value.name}\n${value.description}\n\n${value.markdown}`
  const policy = value.policy
  return `${value.inheritPolicy ? 'Inherited policy' : 'Scope override'}\nInterface review: ${policy.requireInterfaceReview ? 'required' : 'optional'}\nTDD: ${policy.requireTdd ? 'required' : 'optional'}\nTest quality: ${policy.requireTestQuality ? 'required' : 'optional'}\nLine coverage: ${policy.minimumLineCoverage}%\nBranch coverage: ${policy.minimumBranchCoverage}%`
}
