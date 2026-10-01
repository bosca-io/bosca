import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import CampaignEmailContentEditor from './CampaignEmailContentEditor.vue'

const hostedData = ref({
  communications: {
    bmlMessageHostedProjects: [
      {
        project: 'product-emails',
        activeVersion: '2.0.0',
        pinnedVersion: '1.9.0' as string | null,
        templates: [
          { key: 'newsletter', samplePayload: { title: 'Weekly update' }, supportsEmail: true },
          { key: 'announcement', samplePayload: null, supportsEmail: true },
        ],
      },
    ],
  },
})

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: hostedData, status: ref('success') }),
}))

const SelectStub = {
  name: 'Select',
  props: ['modelValue', 'options', 'disabled', 'placeholder'],
  emits: ['update:modelValue'],
  template: '<select class="select-stub" :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)" />',
}

const JsonEditorStub = {
  name: 'JsonEditorVue',
  props: ['modelValue', 'mode', 'mainMenuBar', 'statusBar', 'readOnly'],
  emits: ['update:modelValue'],
  template: '<div class="json-editor-stub" />',
}

function mountEditor(modelValue: Record<string, unknown> = {}, disabled = false) {
  return mount(CampaignEmailContentEditor, {
    props: { modelValue, disabled },
    global: { stubs: { Select: SelectStub, JsonEditorVue: JsonEditorStub } },
  })
}

describe('CampaignEmailContentEditor', () => {
  beforeEach(() => {
    hostedData.value.communications.bmlMessageHostedProjects = [
      {
        project: 'product-emails',
        activeVersion: '2.0.0',
        pinnedVersion: '1.9.0' as string | null,
        templates: [
          { key: 'newsletter', samplePayload: { title: 'Weekly update' }, supportsEmail: true },
          { key: 'announcement', samplePayload: null, supportsEmail: true },
        ],
      },
    ]
  })

  it('lists hosted BML message projects and project templates', () => {
    const wrapper = mountEditor({ project: 'product-emails', templateKey: 'newsletter' })
    const selects = wrapper.findAllComponents(SelectStub)

    expect(selects[0]!.props('options')).toEqual([
      { value: 'product-emails', label: 'product-emails (pinned 1.9.0)' },
    ])
    expect(selects[1]!.props('options')).toEqual([
      { value: 'newsletter', label: 'newsletter' },
      { value: 'announcement', label: 'announcement' },
    ])
    expect(wrapper.text()).toContain('Sends use pinned version 1.9.0.')
  })

  it('loads the selected template sample payload', async () => {
    const wrapper = mountEditor({ project: 'product-emails' })
    const templateSelect = wrapper.findAllComponents(SelectStub)[1]!

    templateSelect.vm.$emit('update:modelValue', 'newsletter')
    await nextTick()

    const updates = wrapper.emitted('update:modelValue')!
    expect(updates[updates.length - 1]![0]).toEqual({
      project: 'product-emails',
      templateKey: 'newsletter',
      payload: { title: 'Weekly update' },
    })
  })

  it('excludes push-only templates and projects from email selection', () => {
    hostedData.value.communications.bmlMessageHostedProjects.push({
      project: 'push-messages',
      activeVersion: '1.0.0',
      pinnedVersion: null,
      templates: [{ key: 'device-alert', samplePayload: null, supportsEmail: false }],
    })
    hostedData.value.communications.bmlMessageHostedProjects[0]!.templates.push({
      key: 'mobile-newsletter',
      samplePayload: null,
      supportsEmail: false,
    })

    const wrapper = mountEditor({ project: 'product-emails' })
    const selects = wrapper.findAllComponents(SelectStub)

    expect(selects[0]!.props('options')).toEqual([
      { value: 'product-emails', label: 'product-emails (pinned 1.9.0)' },
    ])
    expect(selects[1]!.props('options')).toEqual([
      { value: 'newsletter', label: 'newsletter' },
      { value: 'announcement', label: 'announcement' },
    ])
  })

  it('clears an incompatible template when the project changes', async () => {
    const wrapper = mountEditor({ project: 'old-project', templateKey: 'old-template', payload: { old: true } })
    const projectSelect = wrapper.findAllComponents(SelectStub)[0]!

    projectSelect.vm.$emit('update:modelValue', 'product-emails')
    await nextTick()

    const updates = wrapper.emitted('update:modelValue')!
    expect(updates[updates.length - 1]![0]).toEqual({ project: 'product-emails' })
  })

  it('disables selection and payload editing when read only', () => {
    const wrapper = mountEditor({ project: 'product-emails', templateKey: 'newsletter' }, true)
    const selects = wrapper.findAllComponents(SelectStub)

    expect(selects[0]!.props('disabled')).toBe(true)
    expect(selects[1]!.props('disabled')).toBe(true)
    expect(wrapper.findComponent(JsonEditorStub).props('readOnly')).toBe(true)
  })

  it('surfaces when no BML message projects are hosted', () => {
    hostedData.value.communications.bmlMessageHostedProjects = []

    expect(mountEditor().text()).toContain('No hosted BML message templates support email.')
  })
})
