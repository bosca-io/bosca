import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GoalActivationEditor from './GoalActivationEditor.vue'
import ActivationEventEditor from './ActivationEventEditor.vue'
import { newGoalActivation, newOutcomeScope } from '~/utils/goalActivation'

describe('GoalActivationEditor', () => {
  const variations = [{ key: 'mid', name: 'Mid' }, { key: 'none', name: 'None' }]
  it('enables activation and explains the overall denominator', async () => {
    const wrapper = mount(GoalActivationEditor, { props: { activation: null, outcomeScope: null, variations } })
    expect(wrapper.text()).toContain('keeps everyone in the overall result')
    await wrapper.find('input[type="checkbox"]').setValue(true)
    expect(wrapper.emitted('update:activation')?.[0]).toEqual([newGoalActivation()])
  })
  it('edits explicit per-variation event, inherited and not-applicable rules', async () => {
    const activation = newGoalActivation()
    const wrapper = mount(GoalActivationEditor, { props: { activation, outcomeScope: newOutcomeScope(), variations } })
    await wrapper.find('fieldset select').setValue('EVENT')
    expect(activation.variationRules[0]).toMatchObject({ variationKey: 'mid', mode: 'EVENT', filter: { eventType: 'Impression' } })
    await wrapper.find('fieldset select').setValue('NOT_APPLICABLE')
    expect(activation.variationRules[0]).toMatchObject({ mode: 'NOT_APPLICABLE', filter: null })
    await wrapper.find('fieldset select').setValue('INHERIT')
    expect(activation.variationRules[0]?.mode).toBe('INHERIT')
    await wrapper.find('fieldset select').setValue('__default__')
    expect(activation.variationRules).toEqual([])
  })
  it('preserves exact empty and whitespace item-extra values independently of key presence', async () => {
    const filter = { eventType: 'Impression', itemExtraKey: 'location', itemExtraValue: null as string | null }
    const wrapper = mount(ActivationEventEditor, { props: { modelValue: filter } })
    const check = wrapper.findAll('label').find(label => label.text().includes('Match an exact'))!.find('input')
    await check.setValue(true)
    expect(filter.itemExtraValue).toBe('')
    await wrapper.setProps({ modelValue: { ...filter } })
    const value = wrapper.findAll('label').find(label => label.text() === 'Item extra value')!.find('input')
    await value.setValue(' recommendations ')
    expect(wrapper.props('modelValue').itemExtraValue).toBe(' recommendations ')
    await check.setValue(false)
    expect(wrapper.props('modelValue').itemExtraValue).toBeNull()
  })

})
