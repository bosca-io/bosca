import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import ConfigPage from './index.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const config = { id: 'policy', key: 'kit.harness', description: 'Harness policy', public: false,
  value: { defaultProfile: 'kit', allowedProfiles: ['kit'] },
  permissions: [{ action: 'EXECUTE', group: { id: 'advanced', name: 'Advanced users' } }],
}
vi.stubGlobal('useGraphQL', () => ({
  mutation,
  useAsyncQuery: (key: string) => ({ refresh, status: ref('success'), data: ref(key === 'configuration-groups'
    ? { security: { groups: { all: [{ id: 'advanced', name: 'Advanced users' }, { id: 'managers', name: 'Managers' }] } } }
    : { configurations: { all: [config] } }) }),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#2272f2' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)
const Button = { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' }
const Select = { props: ['modelValue', 'options', 'label'], emits: ['update:modelValue'],
  template: '<select :aria-label="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :value="option.value">{{ option.label }}</option></select>' }
const global = { mocks: { buildBreadcrumb: (...parts: string[]) => parts }, stubs: {
  PageShell: { template: '<main><slot name="header" /><slot /></main>' },
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  Button, Select, TextInput: true, CodeEditor: true, Checkbox: true, Icon: true, Badge: true, ConfirmModal: true,
} }
beforeEach(() => { mutation.mockReset(); mutation.mockResolvedValue({}); refresh.mockReset() })

describe('Configuration group permissions', () => {
  it('preserves existing grants and saves an explicitly added group grant', async () => {
    const wrapper = mount(ConfigPage, { global })
    await wrapper.get('.config-row').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === 'Save')!.trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].config.permissions).toEqual([{ action: 'EXECUTE', groupId: 'advanced', entityId: 'policy' }])
    await wrapper.get('.config-row').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === 'Add permission')!.trigger('click')
    await wrapper.findAll('select[aria-label="Group"]')[1]!.setValue('managers')
    await wrapper.findAll('select[aria-label="Action"]')[1]!.setValue('MANAGE')
    await wrapper.findAll('button').find(button => button.text() === 'Save')!.trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].config.permissions).toEqual([
      { action: 'EXECUTE', groupId: 'advanced', entityId: 'policy' }, { action: 'MANAGE', groupId: 'managers', entityId: 'policy' },
    ])
    wrapper.unmount()
  })
})
