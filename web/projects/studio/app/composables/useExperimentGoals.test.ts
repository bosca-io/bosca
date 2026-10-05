import { describe, expect, it } from 'vitest'
import { buildConversionGoalInput, type ConversionGoalFormValues } from './useExperimentGoals'

function form(overrides: Partial<ConversionGoalFormValues> = {}): ConversionGoalFormValues {
  return {
    name: 'Engagement',
    eventType: 'Interaction',
    elementType: 'button',
    elementId: 'recommendation-card',
    pagePath: '/discover',
    pagePathPrefixes: '/articles/\n/talks/, /studies/',
    itemExtraKey: 'experiment_source',
    itemExtraValueEnabled: true,
    itemExtraValue: 'recommendations',
    metricType: 'EVENT_COUNT',
    role: 'PRIMARY',
    cupedEnabled: true,
    cupedEventType: 'Interaction',
    cupedElementType: 'button',
    cupedElementId: 'recommendation-card',
    cupedPagePath: '/discover',
    cupedLookbackWindow: 'P14D',
    ...overrides,
  }
}

describe('buildConversionGoalInput', () => {
  it('normalizes the key and preserves the exact item extra value', () => {
    const input = buildConversionGoalInput(form({
      itemExtraKey: '  experiment_source ',
      itemExtraValue: ' recommendations  ',
    }))
    expect(input.itemExtraKey).toBe('experiment_source')
    expect(input.itemExtraValue).toBe(' recommendations  ')
    expect(input.pagePathPrefixes).toEqual(['/articles/', '/talks/', '/studies/'])
  })

  it('distinguishes presence matching from an exact empty value', () => {
    expect(buildConversionGoalInput(form({ itemExtraValueEnabled: false, itemExtraValue: '' })).itemExtraValue)
      .toBeNull()
    expect(buildConversionGoalInput(form({ itemExtraValueEnabled: true, itemExtraValue: '' })).itemExtraValue)
      .toBe('')
  })

  it('clears every incompatible hidden field for session duration', () => {
    const input = buildConversionGoalInput(form({ metricType: 'SESSION_DURATION' }))
    expect(input).toMatchObject({
      eventType: null,
      elementType: null,
      elementId: null,
      pagePath: null,
      pagePathPrefixes: [],
      itemExtraKey: null,
      itemExtraValue: null,
      metricType: 'SESSION_DURATION',
      cupedCovariate: null,
    })
  })

  it('rejects item values without keys and malformed keys', () => {
    expect(() => buildConversionGoalInput(form({ itemExtraKey: '', itemExtraValueEnabled: true, itemExtraValue: 'recommendations' })))
      .toThrow('requires a key')
    expect(() => buildConversionGoalInput(form({ itemExtraKey: 'Experiment-Source' })))
      .toThrow('[a-z][a-z0-9_]*')
  })
})
