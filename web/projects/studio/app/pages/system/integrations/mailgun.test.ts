import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import MailgunPage from './mailgun.vue'

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
        key: 'mailgun',
        description: 'Existing Mailgun configuration',
        public: false,
        value: {
          apiKey: 'stored-secret',
          domain: 'mg.example.com',
          apiBaseUrl: 'https://api.eu.mailgun.net',
        },
        permissions: [{ action: 'edit', group: { id: 'group-id', name: 'Administrators' } }],
      },
    },
  }
})

describe('Mailgun Integration Page', () => {
  it('loads configuration and saves non-secret changes without replacing the stored API key', async () => {
    const wrapper = mount(MailgunPage, { global: globalConfig })
    await flushPromises()

    expect((wrapper.get('[data-field="API Key"]').element as HTMLInputElement).value).toBe('••••••••')
    expect((wrapper.get('[data-field="Domain"]').element as HTMLInputElement).value).toBe('mg.example.com')
    expect((wrapper.get('[data-field="API Base URL"]').element as HTMLInputElement).value)
      .toBe('https://api.eu.mailgun.net')

    await wrapper.get('[data-field="Domain"]').setValue('email.example.com')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'mailgun',
        description: 'Mailgun Email Integration Configuration',
        public: false,
        value: {
          apiKey: 'stored-secret',
          domain: 'email.example.com',
          apiBaseUrl: 'https://api.eu.mailgun.net',
        },
        permissions: [{ action: 'edit', groupId: 'group-id', entityId: 'configuration-id' }],
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Mailgun configuration saved')
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('uses the US API origin for a new configuration and saves a newly entered API key', async () => {
    configuration.value = { configurations: { configuration: null } }
    const wrapper = mount(MailgunPage, { global: globalConfig })
    await flushPromises()

    expect((wrapper.get('[data-field="API Base URL"]').element as HTMLInputElement).value)
      .toBe('https://api.mailgun.net')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-field="API Key"]').setValue('new-secret')
    await wrapper.get('[data-field="Domain"]').setValue('mg.example.com')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'mailgun',
        description: 'Mailgun Email Integration Configuration',
        public: false,
        value: {
          apiKey: 'new-secret',
          domain: 'mg.example.com',
          apiBaseUrl: 'https://api.mailgun.net',
        },
        permissions: [],
      },
    })
  })

  it('blocks an invalid API base URL before submitting', async () => {
    const wrapper = mount(MailgunPage, { global: globalConfig })
    await flushPromises()

    await wrapper.get('[data-field="API Base URL"]').setValue('not a URL')
    await flushPromises()

    expect(wrapper.text()).toContain('API base URL must be an absolute URL.')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    await wrapper.get('.save-button').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('surfaces configuration save failures and allows another attempt', async () => {
    mutation.mockRejectedValueOnce(new Error('configuration unavailable'))
    const wrapper = mount(MailgunPage, { global: globalConfig })
    await flushPromises()

    await wrapper.get('.save-button').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith(
      'Failed to save Mailgun configuration: configuration unavailable',
    )
    expect(wrapper.get('.save-button').attributes('disabled')).toBeUndefined()
  })
})
