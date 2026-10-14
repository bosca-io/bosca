import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GoalAnalysisCharts from './GoalAnalysisCharts.vue'
import type { GoalPopulationAnalysis, PlannedComparison } from '~/utils/goalActivation'

function analysis(overrides: Partial<GoalPopulationAnalysis> = {}): GoalPopulationAnalysis {
  return {
    cutoff: '2026-09-17T13:43:36.544Z', activationConfigured: true, notApplicable: false,
    overall: { subjects: 301, conversions: 13, observationCount: 301, estimate: 13 / 301, variance: null, interval: { lower: .02, upper: .07 }, distribution: null },
    activated: { subjects: 31, conversions: 13, observationCount: 31, estimate: 13 / 31, variance: null, interval: { lower: .2642, upper: .5923 }, distribution: null },
    notActivated: { subjects: 270, conversions: 0, observationCount: 270, estimate: 0, variance: null, interval: { lower: 0, upper: .02 }, distribution: null },
    reach: 31 / 301, reachInterval: { lower: .0735, upper: .1425 },
    placement: { expectedOnly: 31, unexpectedOnly: 0, both: 0, neither: 270 },
    exposureEvents: 153, exposureIntensity: { count: 31, mean: 4.94, standardDeviation: 5, p50: 4, p75: 8, p90: 12, p95: 14, p99: 28, maximum: 28 },
    attribution: { engagedSubjects: 13, interactions: 35, attributedSubjects: 1, attributedInteractions: 1, interval: { lower: .0137, upper: .3331 }, unresolvedTargets: 0, unsupportedTypes: 0, missingSlugs: 0, missingSessions: 0, unmatchedDestinations: 34 },
    diagnostics: { excludedPrincipals: 50, excludedInstallations: 70, duplicateDeliveries: 2, outsideApplication: 3, missingApplication: 0 }, ...overrides,
  }
}
const comparison = (conclusion: string): PlannedComparison => ({ goalId: 'goal', leftVariationKey: 'Mid', rightVariationKey: 'None', leftSubjects: 272, rightSubjects: 305, difference: -.02, interval: { lower: -.05, upper: .01 }, evidence: .77, evidenceLabel: '1 − p', threshold: .9833, conclusion, reason: 'The overall comparison does not clear the threshold.' })

describe('GoalAnalysisCharts', () => {
  it('renders measured stages and exact accessible denominators from persisted aggregates', async () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison('INCONCLUSIVE')] } })
    expect(wrapper.findAll('svg[role="img"]')).toHaveLength(6)
    expect(wrapper.text()).toContain('31 / 301')
    expect(wrapper.text()).toContain('13 / 31')
    expect(wrapper.text()).toContain('1 / 13')
    expect(wrapper.text()).toContain('1 − p')
    expect(wrapper.text()).toContain('INCONCLUSIVE')
    expect(wrapper.text()).toContain('Unmatched'.toLowerCase() + ' destinations: 34')
    expect(wrapper.findAll('table caption').length).toBeGreaterThanOrEqual(6)
    expect(wrapper.text()).toContain('2026-09-17T13:43:36.544Z')
    await wrapper.find('select').setValue('percentages')
    expect(wrapper.text()).toContain('31 / 301')
    expect(wrapper.text()).not.toContain('rendered')
  })
  it.each(['CONCLUSIVE', 'INCONCLUSIVE', 'INSUFFICIENT_DATA', 'EQUIVALENT', 'DATA_QUALITY_BLOCKED'])('preserves the deterministic %s conclusion', (conclusion) => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison(conclusion)] } })
    expect(wrapper.text()).toContain(conclusion.replaceAll('_', ' '))
  })
  it('distinguishes not applicable, zero activation, and missing aggregates', () => {
    const none = analysis({ activationConfigured: false, notApplicable: true, activated: null, notActivated: null, reach: null, reachInterval: null, exposureEvents: null, exposureIntensity: null, attribution: null })
    const zero = analysis({ activated: { ...analysis().activated!, subjects: 0, conversions: 0, observationCount: 0, estimate: null, interval: null }, reach: 0 })
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'None', assignments: 979, goalAnalysis: none }, { variationKey: 'Zero', assignments: 301, goalAnalysis: zero }, { variationKey: 'Missing', assignments: 0 }] } })
    expect(wrapper.text()).toContain('Not applicable')
    expect(wrapper.text()).toContain('0 / 301')
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).not.toContain('Missing')
  })
  it('shows unexpected exposure and unresolved attribution without crediting destinations', () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis({ placement: { expectedOnly: 30, unexpectedOnly: 2, both: 1, neither: 268 }, attribution: { ...analysis().attribution!, unresolvedTargets: 3, unsupportedTypes: 2, missingSlugs: 1, missingSessions: 4 } }) }] } })
    expect(wrapper.find('[role="status"]').text()).toContain('2 unexpected only; 1 observed both')
    expect(wrapper.text()).toContain('Unresolved targets: 3')
    expect(wrapper.text()).toContain('missing route slugs: 1')
    expect(wrapper.text()).toContain('Outside application scope: 3')
    expect(wrapper.text()).toContain('Duplicate deliveries: 2')
  })
  it('compares the long tail across descriptive populations while keeping the five-minute floor visible', async () => {
    const d = { count: 301, mean: 344.5, standardDeviation: 140, p50: 300.009, p75: 325.3, p90: 426.7, p95: 574.9, p99: 939.4, maximum: 1303.8 }
    const a = analysis(); a.overall.distribution = d; a.overall.estimate = d.mean
    a.activated!.distribution = { ...d, count: 31, p95: 939.4, p99: 965.1 }
    a.notActivated!.distribution = { ...d, count: 270, p95: 486.6, p99: 730.8 }
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'SESSION_DURATION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: a }] } })
    expect(wrapper.text()).toContain('300s inactivity floor')
    expect(wrapper.text()).toContain('574.9')
    const select = wrapper.find('section[aria-label="Outcome distributions"] select')
    await select.setValue('activated'); expect(wrapper.text()).toContain('965.1')
    await select.setValue('notActivated'); expect(wrapper.text()).toContain('730.8')
    expect(wrapper.text()).toContain('descriptive')
  })
})
