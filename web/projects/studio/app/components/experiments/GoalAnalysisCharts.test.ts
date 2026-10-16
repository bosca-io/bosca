import { describe, it, expect } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
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

async function openView(wrapper: VueWrapper, name: string) {
  const tab = wrapper.findAll('.tab').find(t => t.text() === name)
  expect(tab, `tab ${name}`).toBeDefined()
  await tab!.trigger('click')
}

const kv = (wrapper: VueWrapper, label: string) => wrapper.findAll('.ga-kv > div').find(d => d.find('dt').text() === label)?.find('dd')

describe('GoalAnalysisCharts', () => {
  it('renders measured stages and exact accessible denominators from persisted aggregates', async () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison('INCONCLUSIVE')] } })
    expect(wrapper.findAll('.tab').map(t => t.text())).toEqual(['Comparison', 'Populations', 'Funnel', 'Reach', 'Placement', 'Exposure', 'Data quality'])
    expect(wrapper.find('time').attributes('datetime')).toBe('2026-09-17T13:43:36.544Z')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('[role="img"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('1 − p')
    expect(wrapper.text()).toContain('INCONCLUSIVE')
    let charts = 2
    let captions = wrapper.findAll('table caption').length
    for (const view of ['Funnel', 'Reach', 'Exposure']) {
      await openView(wrapper, view)
      charts += wrapper.findAll('[role="img"]').length
      captions += wrapper.findAll('table caption').length
    }
    expect(charts).toBe(5)
    expect(captions).toBeGreaterThanOrEqual(5)
    await openView(wrapper, 'Reach')
    expect(wrapper.text()).toContain('31 / 301')
    expect(wrapper.text()).toContain('13 / 31')
    await openView(wrapper, 'Funnel')
    expect(wrapper.text()).toContain('1 / 13')
    expect(wrapper.text()).toContain('1,016')
    await wrapper.findAll('.ga-toggle button').find(b => b.text() === 'Percentages')!.trigger('click')
    expect(wrapper.text()).toContain('31 / 301')
    expect(wrapper.text()).toContain('10.30%')
    await openView(wrapper, 'Data quality')
    expect(kv(wrapper, 'Unmatched destinations')?.text()).toBe('34')
  })
  it.each(['CONCLUSIVE', 'INCONCLUSIVE', 'INSUFFICIENT_DATA', 'EQUIVALENT', 'DATA_QUALITY_BLOCKED'])('preserves the deterministic %s conclusion', (conclusion) => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison(conclusion)] } })
    expect(wrapper.text()).toContain(conclusion.replaceAll('_', ' '))
  })
  it('shows two populations and one reason for a variation without activation', async () => {
    const none = analysis({ activationConfigured: false, notApplicable: true, activated: null, notActivated: null, reach: null, reachInterval: null, exposureEvents: null, exposureIntensity: null, attribution: null })
    const zero = analysis({ activated: { ...analysis().activated!, subjects: 0, conversions: 0, observationCount: 0, estimate: null, interval: null }, reach: 0 })
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'None', assignments: 979, goalAnalysis: none }, { variationKey: 'Zero', assignments: 301, goalAnalysis: zero }, { variationKey: 'Missing', assignments: 0 }, { variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }] } })
    for (const view of wrapper.findAll('.tab').map(t => t.text())) {
      await openView(wrapper, view)
      expect(wrapper.text()).not.toContain('NaN')
      const variationNames = wrapper.findAll('tbody th, .ga-dq-name, .ga-row-label, .ga-funnel-name, svg text.label').map(n => n.text())
      expect(variationNames).not.toContain('Missing')
    }
    await openView(wrapper, 'Populations')
    const populations = wrapper.findAll('tbody tr td:first-of-type').map(t => t.text())
    expect(populations).toEqual([
      'All eligible', 'Activation is marked not applicable for this variation.',
      'All eligible', 'Reached activation event',
      'All eligible', 'Reached activation event',
    ])
    expect(wrapper.text()).not.toContain('Not exposed')
    expect(wrapper.text()).not.toContain('Not configured')
    await openView(wrapper, 'Funnel')
    expect(wrapper.text()).toContain('Activation is marked not applicable for this variation.')
    await openView(wrapper, 'Reach')
    expect(wrapper.text()).toContain('0 / 301')
  })
  it('explains that nobody reached activation and points at application scope', async () => {
    const reachedNone = analysis({
      activated: { ...analysis().activated!, subjects: 0, conversions: 0, observationCount: 0, estimate: null, interval: null },
      reach: 0, reachInterval: null, exposureEvents: 0, exposureIntensity: null, attribution: null,
      placement: { expectedOnly: 0, unexpectedOnly: 0, both: 0, neither: 2445 },
      diagnostics: { ...analysis().diagnostics, outsideApplication: 1048 },
    })
    const control = analysis({ activationConfigured: false, activated: null, notActivated: null, reach: null, reachInterval: null, placement: null, exposureEvents: null, exposureIntensity: null, attribution: null })
    const wrapper = mount(GoalAnalysisCharts, { props: {
      metricType: 'EVENT_COUNT',
      variationNames: { 'bottom-article': 'Bottom Article', none: 'None' },
      rows: [{ variationKey: 'bottom-article', assignments: 13116, goalAnalysis: reachedNone }, { variationKey: 'none', assignments: 13360, goalAnalysis: control }],
    } })
    const alert = wrapper.find('[role="alert"]').text()
    expect(alert).toContain('No subject reached the activation event in Bottom Article.')
    expect(alert).toContain('1,048 matching events were rejected by an application rule that no longer applies')
    const scoped = mount(GoalAnalysisCharts, { props: {
      metricType: 'EVENT_COUNT', activation: { defaultFilter: null, variationRules: [], sameSession: false, maxDelaySeconds: null, applicationIds: ['site'] },
      rows: [{ variationKey: 'bottom-article', assignments: 13116, goalAnalysis: reachedNone }],
    } })
    expect(scoped.find('[role="alert"]').text()).toContain('1,048 matching events came from applications outside the configured Application IDs')
    expect(wrapper.findAll('.tab').map(t => t.text())).toEqual(['Comparison', 'Populations', 'Funnel', 'Distribution', 'Data quality'])
    expect(wrapper.text()).not.toContain('bottom-article')
    await openView(wrapper, 'Funnel')
    expect(wrapper.text()).toContain('13,116')
    expect(wrapper.text()).toContain('This variation has no activation event.')
    await openView(wrapper, 'Distribution')
    expect(wrapper.find('.ga-toggle').exists()).toBe(false)
    await openView(wrapper, 'Data quality')
    expect(kv(wrapper, 'Other application')?.text()).toBe('1,048')
    expect(wrapper.text()).toContain('outside the configured Application IDs')
  })
  it('shows unexpected placement and unresolved attribution without crediting destinations', async () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis({ placement: { expectedOnly: 30, unexpectedOnly: 2, both: 1, neither: 268 }, attribution: { ...analysis().attribution!, unresolvedTargets: 3, unsupportedTypes: 2, missingSlugs: 1, missingSessions: 4 } }) }] } })
    await openView(wrapper, 'Placement')
    expect(wrapper.findAll('td.flagged').map(t => t.text())).toEqual(['2', '1'])
    await openView(wrapper, 'Data quality')
    expect(wrapper.find('[role="status"]').text()).toContain('2 unexpected only; 1 observed both')
    expect(kv(wrapper, 'Unresolved targets')?.text()).toBe('3')
    expect(kv(wrapper, 'Unresolved targets')?.classes()).toContain('flagged')
    expect(kv(wrapper, 'Missing route slugs')?.text()).toBe('1')
    expect(kv(wrapper, 'Other application')?.text()).toBe('3')
    expect(kv(wrapper, 'Duplicate deliveries')?.text()).toBe('2')
    expect(kv(wrapper, 'Verified destination')?.text()).toContain('1 / 13')
    expect(kv(wrapper, 'Subjects')?.text()).toBe('270')
  })
  it('compares the long tail across populations while keeping the five-minute floor visible', async () => {
    const d = { count: 301, mean: 344.5, standardDeviation: 140, p50: 300.009, p75: 325.3, p90: 426.7, p95: 574.9, p99: 939.4, maximum: 1303.8 }
    const a = analysis(); a.overall.distribution = d; a.overall.estimate = d.mean
    a.activated!.distribution = { ...d, count: 31, p95: 939.4, p99: 965.1 }
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'SESSION_DURATION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: a }] } })
    expect(wrapper.findAll('.tab').map(t => t.text())).not.toContain('Reach')
    await openView(wrapper, 'Distribution')
    expect(wrapper.text()).toContain('300s inactivity floor')
    expect(wrapper.text()).toContain('574.9')
    const toggle = (label: string) => wrapper.findAll('section[aria-label="Outcome distributions"] .ga-toggle button').find(b => b.text() === label)
    expect(toggle('Not exposed (descriptive)')).toBeUndefined()
    await toggle('Reached activation event')!.trigger('click'); expect(wrapper.text()).toContain('965.1')
    expect(toggle('Reached activation event')!.attributes('aria-pressed')).toBe('true')
  })
  it('formats binary rates, intervals, and planned differences on the percent scale', async () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison('INCONCLUSIVE')] } })
    const overall = wrapper.find('table tbody tr').text()
    expect(overall).toContain('4.32%')
    expect(overall).toContain('2.00% to 7.00%')
    expect(wrapper.text()).toContain('-2.00 pp')
    expect(wrapper.text()).toContain('-5.00 pp to 1.00 pp')
    expect(wrapper.text()).toContain('in percentage points')
    expect(wrapper.findAll('thead th').map(t => t.text())).not.toContain('Observations')
    await openView(wrapper, 'Reach')
    expect(wrapper.text()).toContain('7.35% to 14.25%')
    expect(wrapper.text()).toContain('26.42% to 59.23%')
    await openView(wrapper, 'Data quality')
    expect(kv(wrapper, '95% interval')?.text()).toBe('1.37% to 33.31%')
  })
  it('labels outcome counts by metric and keeps continuous differences in metric units', async () => {
    const eventCount = mount(GoalAnalysisCharts, { props: { metricType: 'EVENT_COUNT', cuped: true, rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }], comparisons: [comparison('INCONCLUSIVE')] } })
    expect(eventCount.findAll('thead th').map(t => t.text())).toContain('Subjects with events')
    expect(eventCount.text()).toContain('-0.02')
    expect(eventCount.text()).not.toContain(' pp')
    expect(eventCount.text()).toContain('CUPED-adjusted')
    const session = mount(GoalAnalysisCharts, { props: { metricType: 'SESSION_DURATION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }] } })
    const headers = session.findAll('thead th').map(t => t.text())
    expect(headers).not.toContain('Conversions')
    expect(headers).toContain('Observations')
  })
  it('reports exclusions once and skips activation checks for a variation without activation', async () => {
    const none = analysis({ activationConfigured: false, notApplicable: true, activated: null, notActivated: null, attribution: null })
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'None', assignments: 979, goalAnalysis: none }, { variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis() }] } })
    await openView(wrapper, 'Data quality')
    expect(wrapper.text()).toContain('Configured exclusions: 50 principals and 70 linked installations')
    const cards = wrapper.findAll('.ga-dq')
    expect(cards[0]?.text()).toContain('Activation event checks do not apply')
    expect(cards[0]?.text()).not.toContain('Duplicate deliveries')
    expect(cards[1]?.text()).toContain('Duplicate deliveries')
  })
  it('omits funnel stages that no variation measures', async () => {
    const wrapper = mount(GoalAnalysisCharts, { props: { metricType: 'UNIQUE_CONVERSION', rows: [{ variationKey: 'Bottom', assignments: 1016, goalAnalysis: analysis({ attribution: null }) }] } })
    await openView(wrapper, 'Funnel')
    expect(wrapper.findAll('.ga-row-label--stage').map(t => t.text())).toEqual(['Assigned', 'Eligible', 'Reached activation event', 'Outcome after activation'])
  })
})
