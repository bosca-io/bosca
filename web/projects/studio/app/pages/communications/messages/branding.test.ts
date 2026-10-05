import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import BrandingPage from './branding.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()
const configuration = ref<unknown>(null)

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: configuration, refresh }),
  mutation,
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#2272f2' }))
vi.stubGlobal('useToast', () => ({ success: toastSuccess, error: toastError }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const globalConfig = {
  mocks: {
    buildBreadcrumb: (...parts: string[]) => parts,
  },
  stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { template: '<header><slot name="actions" /></header>' },
    SectionCard: {
      props: ['title'],
      template: '<section><h2>{{ title }}</h2><slot /></section>',
    },
    TextInput: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: '<label>{{ label }}<input :data-field="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    },
    ColorPicker: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: '<label>{{ label }}<input :data-field="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    },
    Switch: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: '<label>{{ label }}<input type="checkbox" data-field="Logo Only" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /></label>',
    },
    Button: {
      props: ['disabled'],
      emits: ['click'],
      template: '<button class="save-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    },
  },
}

beforeEach(() => {
  mutation.mockReset()
  refresh.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  mutation.mockResolvedValue({})
  refresh.mockResolvedValue(undefined)
  configuration.value = {
    configurations: {
      configuration: {
        id: 'configuration-id',
        key: 'bosca.messages.branding',
        description: 'Existing branding',
        public: false,
        value: {
          title: 'Acme',
          logoUrl: 'https://cdn.acme.example/logo.png',
          logoOnly: true,
          primaryColor: '#123456',
          accentColor: '#abcdef',
        },
        permissions: [{ action: 'edit', group: { id: 'group-id', name: 'Administrators' } }],
      },
    },
  }
})

describe('Communications Message Branding Page', () => {
  it('loads and saves the branding configuration while preserving permissions', async () => {
    const wrapper = mount(BrandingPage, { global: globalConfig })
    await flushPromises()

    expect((wrapper.get('[data-field="Title"]').element as HTMLInputElement).value).toBe('Acme')
    expect((wrapper.get('[data-field="Logo URL"]').element as HTMLInputElement).value)
      .toBe('https://cdn.acme.example/logo.png')
    expect((wrapper.get('[data-field="Logo Only"]').element as HTMLInputElement).checked).toBe(true)

    await wrapper.get('[data-field="Title"]').setValue('Acme Publishing')
    await wrapper.get('[data-field="Primary Color"]').setValue('#112233')
    await wrapper.get('[data-field="Accent Color"]').setValue('#abc')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'bosca.messages.branding',
        description: 'Bosca transactional message branding',
        public: false,
        value: {
          title: 'Acme Publishing',
          logoUrl: 'https://cdn.acme.example/logo.png',
          logoOnly: true,
          primaryColor: '#112233',
          accentColor: '#abc',
        },
        permissions: [{ action: 'edit', groupId: 'group-id', entityId: 'configuration-id' }],
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Message branding saved')
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('starts with backend-compatible defaults when the configuration does not exist', async () => {
    configuration.value = { configurations: { configuration: null } }
    const wrapper = mount(BrandingPage, { global: globalConfig })
    await flushPromises()

    expect((wrapper.get('[data-field="Title"]').element as HTMLInputElement).value).toBe('Bosca')
    expect((wrapper.get('[data-field="Logo URL"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-field="Logo Only"]').element as HTMLInputElement).checked).toBe(false)
    expect((wrapper.get('[data-field="Primary Color"]').element as HTMLInputElement).value).toBe('#0e1019')
    expect((wrapper.get('[data-field="Accent Color"]').element as HTMLInputElement).value).toBe('#047a52')
  })

  it('blocks invalid URLs and colors before submitting', async () => {
    const wrapper = mount(BrandingPage, { global: globalConfig })
    await flushPromises()

    await wrapper.get('[data-field="Logo URL"]').setValue('javascript:alert(1)')
    await wrapper.get('[data-field="Primary Color"]').setValue('navy')
    await flushPromises()

    expect(wrapper.text()).toContain('Logo URL must use http or https.')
    expect(wrapper.text()).toContain('Primary color must be a CSS hex color.')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    await wrapper.get('.save-button').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
  })
})
