import { describe, expect, it } from 'vitest'
import { profileAssignmentInput, profileAssignmentVariation } from './profileFlagAssignments'
import type { ProfileAssignmentFlag } from './profileFlagAssignments'

const assignmentFlag = (): ProfileAssignmentFlag => ({
  id: 'flag-1', key: 'checkout', name: 'Checkout', description: 'Checkout experience',
  type: 'BOOLEAN', status: 'ENABLED', defaultVariationKey: 'off',
  variations: [{ key: 'off', name: 'Off', value: false }, { key: 'on', name: 'On', value: true }],
  targetingRules: [], experiments: [],
})

const rule = (id = 'manual', principal = 'principal-1') => ({
  id, conditions: [{ type: 'Principal', principalIds: [principal] }],
  rollout: { variationWeights: [{ variationKey: 'on', weight: 1 }] },
})

describe('profile flag assignments', () => {
  it('prepends a principal choice while preserving other configuration and rules', () => {
    const flag = assignmentFlag()
    flag.targetingRules = [{ id: 'segment', conditions: [{ type: 'Segment', segmentId: 'segment-1' }] }]
    const input = profileAssignmentInput(flag, 'principal-1', 'on', 'new-rule')
    expect(input).toMatchObject({ name: flag.name, status: flag.status, variations: flag.variations })
    expect(input.targetingRules).toEqual([expect.objectContaining(rule('new-rule')), ...flag.targetingRules])
    expect(flag.targetingRules).toHaveLength(1)
  })

  it('updates an existing dedicated rule and keeps its identity and labels', () => {
    const flag = assignmentFlag()
    flag.targetingRules = [{ ...rule(), name: 'Tester', description: 'Manual QA' }]
    expect(profileAssignmentVariation(flag, 'principal-1')).toBe('on')
    const input = profileAssignmentInput(flag, 'principal-1', 'off', 'unused')
    expect(input.targetingRules).toEqual([{
      ...rule(), name: 'Tester', description: 'Manual QA',
      rollout: { variationWeights: [{ variationKey: 'off', weight: 1 }] },
    }])
  })

  it('removes dedicated choices without deleting other principals or experiment targeting', () => {
    const flag = assignmentFlag()
    const retained = [
      rule('other', 'principal-2'),
      rule('experiment'),
      { ...rule('negated'), conditions: [{ type: 'Principal', principalIds: ['principal-1'], negate: true }] },
      { ...rule('shared'), conditions: [{ type: 'Principal', principalIds: ['principal-1', 'principal-2'] }] },
      { ...rule('conditional'), conditions: [...rule().conditions, { type: 'Segment', segmentId: 's' }] },
      { ...rule('split'), rollout: { variationWeights: [{ variationKey: 'on', weight: 1 }, { variationKey: 'off', weight: 1 }] } },
    ]
    flag.targetingRules = [rule(), ...retained, rule('duplicate')]
    flag.experiments = [{ targetingRuleId: 'experiment' }]
    expect(profileAssignmentInput(flag, 'principal-1', undefined, 'unused').targetingRules).toEqual(retained)
  })

  it('handles null rules and omitted default weights', () => {
    const flag = assignmentFlag()
    flag.targetingRules = null
    expect(profileAssignmentVariation(flag, 'principal-1')).toBeUndefined()
    expect(profileAssignmentInput(flag, 'principal-1', undefined, 'unused').targetingRules).toEqual([])
    flag.targetingRules = [{ ...rule(), rollout: { variationWeights: [{ variationKey: 'on' }] } }]
    expect(profileAssignmentVariation(flag, 'principal-1')).toBe('on')
  })

  it('rejects deleted variations and missing principals', () => {
    expect(() => profileAssignmentInput(assignmentFlag(), 'principal-1', 'removed', 'new')).toThrow('no longer available')
    expect(() => profileAssignmentInput(assignmentFlag(), '', 'on', 'new')).toThrow('linked principal')
  })
})
