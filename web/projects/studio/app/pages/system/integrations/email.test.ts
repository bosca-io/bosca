import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import EmailPage from './email.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()
const configuration = ref<unknown>(null)
const status = ref('success')
const loadError = ref<Error | null>(null)

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: configuration, refresh, status, error: loadError }),
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
    Select: {
      props: ['modelValue', 'label', 'options'],
      emits: ['update:modelValue'],
      template: '<select :data-field="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>',
    },
    NuxtLink: { template: '<a><slot /></a>' },
    Button: {
      props: ['disabled'],
      emits: ['click'],
      template: '<button class="save-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    },
  },
}

beforeEach(() => {
  vi.clearAllMocks()
  mutation.mockReset().mockResolvedValue({})
  refresh.mockResolvedValue(undefined)
  status.value = 'success'
  loadError.value = null
  configuration.value = {
    configurations: {
      configuration: {
        id: 'mailer-id', value: { type: 'mailgun' },
        permissions: [{ action: 'edit', group: { id: 'admin' } }],
      },
    },
  }
})

describe('Email Provider Page', () => {
  it.each(['smtp', 'sendgrid', 'mailgun'])('saves %s as the provider while preserving permissions', async (provider) => {
    const wrapper = mount(EmailPage, { global: globalConfig })
    await wrapper.get('[data-field="Provider"]').setValue(provider)
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'mailer', description: 'Email Provider Configuration', public: false,
        value: { type: provider }, permissions: [{ action: 'edit', groupId: 'admin', entityId: 'mailer-id' }],
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Email provider saved')
  })

  it('uses the deployment choice when no override exists and can restore it', async () => {
    configuration.value = { configurations: { configuration: null } }
    const wrapper = mount(EmailPage, { global: globalConfig })
    expect((wrapper.get('[data-field="Provider"]').element as HTMLSelectElement).value).toBe('default')
    await wrapper.get('.save-button').trigger('click')
    expect(mutation.mock.calls[0]?.[1].configuration).toMatchObject({ value: { type: null }, permissions: [] })
  })

  it('blocks saves while loading or after a load error', async () => {
    status.value = 'pending'
    const wrapper = mount(EmailPage, { global: globalConfig })
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    status.value = 'error'
    loadError.value = new Error('offline')
    await flushPromises()
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Email settings could not be loaded')
  })

  it('reports a failed save and allows retry', async () => {
    mutation.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(EmailPage, { global: globalConfig })
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith('Failed to save email provider: offline')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeUndefined()
  })
})
