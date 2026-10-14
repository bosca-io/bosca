import { describe, expect, it } from 'vitest'
import { planGuidanceReconciliation, resolveGuidanceReconciliation, describeGuidanceConflict, type Guidance } from './guidanceReconciliation'
const baseline = (): Guidance => ({ revision: 'old', policyInherited: true, policy: { requireInterfaceReview: true, requireTdd: true, requireTestQuality: true, minimumLineCoverage: 99, minimumBranchCoverage: 97 }, guides: ['INVESTIGATION', 'IMPLEMENTATION'].map(phase => ({ phase, name: 'guide', description: 'Use guide', markdown: '# Base', inherited: true })) })
describe('guidance reconciliation', () => {
  it('preserves nonoverlapping local and saved edits and the latest revision', () => {
    const base = baseline(); const latest = baseline(); latest.revision = 'new'; latest.guides[1] = { ...latest.guides[1]!, markdown: '# Saved', inherited: false }
    const local = { guides: base.guides.map(guide => ({ ...guide })), policy: { ...base.policy }, inheritPolicy: true }; local.guides[0] = { ...local.guides[0]!, markdown: '# Local', inherited: false }
    const plan = planGuidanceReconciliation(base, local, latest, latest)
    expect(plan.conflicts).toEqual([])
    expect(resolveGuidanceReconciliation(plan, {}).guides.map(guide => guide.markdown)).toEqual(['# Local', '# Saved'])
    expect(plan.latest.revision).toBe('new')
  })
  it('requires an explicit guide and policy choice when both editors changed them', () => {
    const base = baseline(); const latest = baseline(); latest.policyInherited = false; latest.policy.minimumLineCoverage = 100; latest.guides[0] = { ...latest.guides[0]!, markdown: '# Saved', inherited: false }
    const local = { guides: base.guides.map(guide => ({ ...guide })), policy: { ...base.policy, minimumLineCoverage: 98 }, inheritPolicy: false }; local.guides[0] = { ...local.guides[0]!, markdown: '# Local', inherited: false }
    const plan = planGuidanceReconciliation(base, local, latest, latest)
    expect(plan.conflicts.map(conflict => conflict.key)).toEqual(['policy', 'INVESTIGATION'])
    expect(() => resolveGuidanceReconciliation(plan, {})).toThrow('Choose')
    const draft = resolveGuidanceReconciliation(plan, { policy: 'saved', INVESTIGATION: 'local' })
    expect(draft.policy.minimumLineCoverage).toBe(100); expect(draft.guides[0]!.markdown).toBe('# Local')
    expect(local.policy.minimumLineCoverage).toBe(98); expect(latest.guides[0]!.markdown).toBe('# Saved')
  })
  it('keeps identical edits once and resolves guide and policy conflicts using either version', () => {
    const base = baseline(), latest = baseline(); latest.guides[0] = { ...latest.guides[0]!, markdown: '# Same', inherited: false }
    const local = { guides: latest.guides.map(guide => ({ ...guide })), policy: { ...base.policy }, inheritPolicy: true }
    expect(planGuidanceReconciliation(base, local, latest, latest).conflicts).toEqual([])
    latest.guides[0] = { ...latest.guides[0]!, markdown: '# Saved' }; latest.policyInherited = false; latest.policy.requireTdd = false
    local.inheritPolicy = false; local.policy.minimumLineCoverage = 100
    const plan = planGuidanceReconciliation(base, local, latest, latest)
    const result = resolveGuidanceReconciliation(plan, { policy: 'local', INVESTIGATION: 'saved' })
    expect(result.guides[0]!.markdown).toBe('# Saved'); expect(result.policy.requireTdd).toBe(true); expect(result.policy.minimumLineCoverage).toBe(100)
    expect(() => resolveGuidanceReconciliation(plan, { policy: 'local' })).toThrow('Choose')
  })
  it('refreshes inherited previews from current platform values rather than a project override', () => {
    const base = baseline(); base.guides[0]!.inherited = false
    const local = { guides: base.guides.map(guide => ({ ...guide })), policy: { ...base.policy }, inheritPolicy: true }; local.guides[0]!.inherited = true
    const latest = baseline(); latest.guides[0] = { ...latest.guides[0]!, markdown: '# Project override', inherited: false }
    const platform = baseline(); platform.policy.minimumLineCoverage = 100; platform.guides[0]!.markdown = '# Current platform'
    const plan = planGuidanceReconciliation(base, local, latest, platform)
    const result = resolveGuidanceReconciliation(plan, { INVESTIGATION: 'local' })
    expect(result.guides[0]).toMatchObject({ inherited: true, markdown: '# Current platform' }); expect(result.policy.minimumLineCoverage).toBe(100)
    expect(() => resolveGuidanceReconciliation({ ...plan, inherited: { ...platform, guides: [] } }, { INVESTIGATION: 'local' })).toThrow('Inherited')
  })
  it('rejects missing phases in either the original baseline or local draft', () => {
    const base = baseline(), latest = baseline(), local = { guides: base.guides, policy: base.policy, inheritPolicy: true }
    expect(() => planGuidanceReconciliation({ ...base, guides: [] }, local, latest, latest)).toThrow('phases changed')
    expect(() => planGuidanceReconciliation(base, { ...local, guides: [] }, latest, latest)).toThrow('phases changed')
  })
  it('describes both guide and policy choices in readable product terms', () => {
    const base = baseline()
    expect(describeGuidanceConflict(base.guides[0]!)).toContain('Inherited guide')
    expect(describeGuidanceConflict({ ...base.guides[0]!, inherited: false })).toContain('Scope override')
    expect(describeGuidanceConflict({ policy: base.policy, inheritPolicy: true })).toContain('Interface review: required')
    const text = describeGuidanceConflict({ policy: { ...base.policy, requireInterfaceReview: false, requireTdd: false, requireTestQuality: false }, inheritPolicy: false })
    expect(text).toContain('Scope override'); expect(text).toContain('TDD: optional'); expect(text).toContain('Test quality: optional'); expect(text).toContain('Interface review: optional')
  })

})
