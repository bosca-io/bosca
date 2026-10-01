import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RecommendationContextEditor from './RecommendationContextEditor.vue'
import { createRecommendationContextForm } from '~/utils/recommendationContextForm'

const stubs = {
  SectionCard: {
    template: '<section><slot /></section>',
    props: ['title', 'subtitle', 'padded'],
  },
  TextInput: {
    template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)">',
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
  },
  Textarea: {
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
  NumberInput: {
    template: '<input type="number" :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))">',
    props: ['modelValue', 'label', 'min', 'max', 'step'],
    emits: ['update:modelValue'],
  },
  TagInput: {
    template: '<div class="tag-input" />',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
  Switch: {
    template: '<button class="switch" @click="$emit(\'update:modelValue\', !modelValue)">{{ label }}</button>',
    props: ['modelValue', 'label'],
    emits: ['update:modelValue'],
  },
  Button: {
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled'],
    emits: ['click'],
  },
}

function mountEditor(props: Record<string, unknown> = {}) {
  return mount(RecommendationContextEditor, {
    props: { modelValue: createRecommendationContextForm(), ...props },
    global: { stubs },
  })
}

describe('RecommendationContextEditor', () => {
  it('preserves draft changes when switching editor sections', async () => {
    const form = createRecommendationContextForm()
    const wrapper = mountEditor({ modelValue: form, section: 'General' })
    await wrapper.get('input').setValue('Unsaved name')
    expect(wrapper.find('.tag-input').exists()).toBe(false)
    await wrapper.setProps({ section: 'Weights' })
    expect(wrapper.find('textarea').exists()).toBe(false)
    await wrapper.get('input[aria-label="Shared collections"]').setValue('0')
    await wrapper.setProps({ section: 'Eligibility' })
    expect(wrapper.find('.tag-input').exists()).toBe(true)
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    await wrapper.setProps({ section: 'General' })
    expect(wrapper.get('input').element.value).toBe('Unsaved name')
    expect(form.weights.similarity.collections).toBe(0)
  })

  it('edits a type preference independently of type similarity and resets defaults', async () => {
    const form = createRecommendationContextForm()
    const wrapper = mountEditor({ modelValue: form })
    await wrapper.findAll('button').find(button => button.text() === 'Add type preference')?.trigger('click')
    expect(form.weights.typePreferences).toEqual([{ type: '', weight: 0.5 }])
    await wrapper.find('input[aria-label="Preference"]').setValue('0')
    expect(form.weights.typePreferences[0]?.weight).toBe(0)
    expect(form.weights.similarity.type).toBe(0.2)
    await wrapper.findAll('button').find(button => button.text() === 'Reset weights to defaults')?.trigger('click')
    expect(form.weights.typePreferences).toEqual([])
  })

  it('disables the request-facing type when editing the default context', () => {
    const wrapper = mountEditor({ typeDisabled: true })

    expect(wrapper.find('input:disabled').exists()).toBe(true)
  })

  it('shows the effect of Study preferences as values change independently of source type matching', async () => {
    const form = createRecommendationContextForm()
    form.weights.typePreferences = [{ type: 'Study', weight: 0.8 }]
    const wrapper = mountEditor({ modelValue: form, section: 'Weights' })
    expect(wrapper.text()).toContain('Changed weights apply when the trained version becomes active.')
    expect(wrapper.get('.preference-effect').text()).toBe('20% higher content score than unlisted types at equal relatedness.')
    await wrapper.get('input[aria-label="Match source editorial type"]').setValue('0.5')
    expect(wrapper.get('.preference-effect').text()).toContain('20% higher')
    await wrapper.get('input[aria-label="Unlisted or missing type"]').setValue('0')
    expect(wrapper.get('.preference-effect').text()).toContain('80% higher')
    await wrapper.get('input[aria-label="Preference"]').setValue('1')
    expect(wrapper.get('.preference-effect').text()).toContain('100% higher')
    await wrapper.get('button[aria-label="Remove Study preference"]').trigger('click')
    expect(form.weights.typePreferences).toEqual([])
  })

  it('hides collection facets when collections are not eligible', () => {
    const form = createRecommendationContextForm()
    form.collectionsEnabled = false

    const wrapper = mountEditor({ modelValue: form })

    expect(wrapper.findAll('.tag-input')).toHaveLength(4)
    expect(wrapper.text()).toContain('Collections will not be assigned to this context.')
  })

  it('emits submit and cancel actions', async () => {
    const wrapper = mountEditor()
    const buttons = wrapper.findAll('button')

    await buttons.at(-2)!.trigger('click')
    await buttons.at(-1)!.trigger('click')

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('disables submission and reports errors while saving', () => {
    const wrapper = mountEditor({ saving: true, error: 'Save failed' })

    expect(wrapper.find('button:disabled').text()).toBe('Saving…')
    expect(wrapper.text()).toContain('Save failed')
  })
})
