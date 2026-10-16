import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import SmtpPage from './smtp.vue'

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
        id: 'smtp-id',
        value: { host: 'smtp.example.com', port: 587, security: 'STARTTLS', username: 'user', password: ' stored secret ' },
        permissions: [{ action: 'edit', group: { id: 'admin', name: 'Administrators' } }],
      },
    },
  }
})

describe('SMTP Integration Page', () => {
  it('masks the password and preserves it and permissions when changing the host', async () => {
    const wrapper = mount(SmtpPage, { global: globalConfig })
    await flushPromises()
    expect((wrapper.get('[data-field="Password"]').element as HTMLInputElement).value).toBe('••••••••')
    await wrapper.get('[data-field="Host"]').setValue('relay.example.com')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'smtp', description: 'SMTP Email Integration Configuration', public: false,
        value: { host: 'relay.example.com', port: 587, security: 'STARTTLS', username: 'user', password: ' stored secret ' },
        permissions: [{ action: 'edit', groupId: 'admin', entityId: 'smtp-id' }],
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('SMTP configuration saved')
  })

  it('supports plain SMTP without authentication', async () => {
    configuration.value = { configurations: { configuration: null } }
    const wrapper = mount(SmtpPage, { global: globalConfig })
    await wrapper.get('[data-field="Host"]').setValue('mail.internal')
    await wrapper.get('[data-field="Port"]').setValue('25')
    await wrapper.get('[data-field="Connection Security"]').setValue('NONE')
    await wrapper.get('.save-button').trigger('click')
    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      configuration: {
        key: 'smtp', description: 'SMTP Email Integration Configuration', public: false,
        value: { host: 'mail.internal', port: 25, security: 'NONE', username: '', password: '' }, permissions: [],
      },
    })
  })

  it('replaces passwords without trimming and can clear authentication', async () => {
    const wrapper = mount(SmtpPage, { global: globalConfig })
    await wrapper.get('[data-field="Password"]').setValue(' new secret ')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].configuration.value.password).toBe(' new secret ')
    await wrapper.get('[data-field="Password"]').setValue('')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-field="Username"]').setValue('')
    await wrapper.get('.save-button').trigger('click')
    expect(mutation.mock.calls[1]?.[1].configuration.value).toMatchObject({ username: '', password: '' })
  })

  it.each(['0', '65536', '25.5'])('rejects invalid port %s', async (port) => {
    const wrapper = mount(SmtpPage, { global: globalConfig })
    await wrapper.get('[data-field="Port"]').setValue(port)
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Port must be between 1 and 65535.')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('blocks saves after loading fails', async () => {
    status.value = 'error'
    loadError.value = new Error('offline')
    const wrapper = mount(SmtpPage, { global: globalConfig })
    expect(wrapper.get('.save-button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('SMTP settings could not be loaded')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('reports save errors and permits a retry', async () => {
    mutation.mockRejectedValueOnce(new Error('offline'))
    const wrapper = mount(SmtpPage, { global: globalConfig })
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith('Failed to save SMTP configuration: offline')
    expect(wrapper.get('.save-button').attributes('disabled')).toBeUndefined()
  })
})
