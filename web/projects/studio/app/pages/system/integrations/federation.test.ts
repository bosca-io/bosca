import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import FederationPage from './federation.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()
const queryData = ref<unknown>(null)
const queryStatus = ref('success')

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: queryData, status: queryStatus, refresh }),
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
    PageHeader: {
      props: ['title', 'subtitle'],
      template: '<header><h1>{{ title }}</h1><p>{{ subtitle }}</p><slot name="actions" /></header>',
    },
    SectionCard: {
      props: ['title'],
      template: '<section><h2>{{ title }}</h2><slot /></section>',
    },
    Button: {
      props: ['disabled', 'icon'],
      emits: ['click'],
      template: '<button :data-icon="icon" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    },
    TextInput: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: '<label>{{ label }}<input :data-field="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    },
    GlassTable: {
      props: ['rows', 'rowActions'],
      emits: ['row-action'],
      template: `
        <div class="peer-table">
          <div v-for="row in rows" :key="row.id" class="peer-row">
            <slot name="col-name" :row="row" />
            <span>{{ row.natsUrl }}</span>
            <span>{{ row.apiUrl }}</span>
            <slot name="col-createdAt" :row="row" />
            <span class="action-count">{{ rowActions(row).length }}</span>
            <button class="secret-action" @click="$emit('row-action', { action: 'secret', row })">Secret</button>
            <button class="deactivate-action" @click="$emit('row-action', { action: 'deactivate', row })">Deactivate</button>
          </div>
        </div>
      `,
    },
    Modal: {
      props: ['title'],
      emits: ['close'],
      template: '<section class="modal"><h2>{{ title }}</h2><button class="modal-close" @click="$emit(\'close\')">Close</button><slot /><footer><slot name="footer" /></footer></section>',
    },
    ConfirmModal: {
      props: ['title', 'confirmLabel', 'loading'],
      emits: ['close', 'confirm'],
      template: '<section class="confirm-modal"><h2>{{ title }}</h2><button class="confirm-close" @click="$emit(\'close\')">Close</button><button class="confirm-action" :disabled="loading" @click="$emit(\'confirm\')">{{ confirmLabel }}</button></section>',
    },
  },
}

const peer = {
  id: 'peer-id',
  name: 'Remote Bosca',
  natsUrl: 'nats://peer.example.org:4222',
  apiUrl: 'https://peer.example.org',
  active: true,
  createdAt: '2026-08-01T12:00:00Z',
}

beforeEach(() => {
  mutation.mockReset()
  refresh.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  mutation.mockResolvedValue({})
  refresh.mockResolvedValue(undefined)
  queryStatus.value = 'success'
  queryData.value = {
    collaboration: { federation: { peers: [peer] } },
  }
})

function button(wrapper: ReturnType<typeof mount>, label: string) {
  const match = wrapper.findAll('button').find(candidate => candidate.text() === label)
  if (!match) throw new Error(`Button not found: ${label}`)
  return match
}

describe('Federation Integration Page', () => {
  it('renders active federation peers returned by the collaboration API', () => {
    const wrapper = mount(FederationPage, { global: globalConfig })

    expect(wrapper.text()).toContain('Remote Bosca')
    expect(wrapper.text()).toContain('nats://peer.example.org:4222')
    expect(wrapper.text()).toContain('https://peer.example.org')
    expect(wrapper.text()).toContain('1 active peer')
  })

  it('registers a peer and refreshes the active peer list', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })
    await button(wrapper, 'Register Peer').trigger('click')

    await wrapper.get('[data-field="Name"]').setValue('New Peer')
    await wrapper.get('[data-field="NATS URL"]').setValue('nats://new.example.org:4222')
    await wrapper.get('[data-field="API URL"]').setValue('https://new.example.org')
    await wrapper.get('[data-field="Shared Secret"]').setValue('shared-secret')
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      input: {
        name: 'New Peer',
        natsUrl: 'nats://new.example.org:4222',
        apiUrl: 'https://new.example.org',
        sharedSecret: 'shared-secret',
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Federation peer registered')
    expect(refresh).toHaveBeenCalledOnce()
    expect(wrapper.find('.modal').exists()).toBe(false)
  })

  it('updates an encrypted peer secret', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })
    await wrapper.get('.secret-action').trigger('click')
    await wrapper.get('[data-field="Shared Secret"]').setValue('rotated-secret')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      id: 'peer-id',
      secret: 'rotated-secret',
    })
    expect(toastSuccess).toHaveBeenCalledWith('Federation shared secret updated')
  })

  it('can clear a stored peer secret', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })
    await wrapper.get('.secret-action').trigger('click')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      id: 'peer-id',
      secret: null,
    })
  })

  it('confirms peer deactivation and refreshes the list', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })
    await wrapper.get('.deactivate-action').trigger('click')
    expect(wrapper.text()).toContain('Deactivate Remote Bosca?')

    await wrapper.get('.confirm-action').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), { id: 'peer-id' })
    expect(toastSuccess).toHaveBeenCalledWith('Federation peer deactivated')
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('blocks invalid peer URLs and surfaces mutation failures', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })
    await button(wrapper, 'Register Peer').trigger('click')
    await wrapper.get('[data-field="Name"]').setValue('New Peer')
    await wrapper.get('[data-field="NATS URL"]').setValue('not a URL')
    await wrapper.get('[data-field="API URL"]').setValue('ftp://new.example.org')
    await wrapper.get('[data-field="Shared Secret"]').setValue('shared-secret')

    expect(wrapper.text()).toContain('Enter an absolute URL.')
    expect(wrapper.text()).toContain('URL must use HTTP or HTTPS.')
    expect(button(wrapper, 'Register').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-field="NATS URL"]').setValue('nats://new.example.org:4222')
    await wrapper.get('[data-field="API URL"]').setValue('https://new.example.org')
    mutation.mockRejectedValueOnce(new Error('federation unavailable'))
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith('Failed to register peer: federation unavailable')
    expect(button(wrapper, 'Register').attributes('disabled')).toBeUndefined()
  })

  it('keeps secret and deactivation dialogs retryable when mutations fail', async () => {
    const wrapper = mount(FederationPage, { global: globalConfig })

    mutation.mockRejectedValueOnce('secret service unavailable')
    await wrapper.get('.secret-action').trigger('click')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith(
      'Failed to update shared secret: secret service unavailable',
    )
    expect(wrapper.find('.modal').exists()).toBe(true)
    await wrapper.get('.modal-close').trigger('click')
    expect(wrapper.find('.modal').exists()).toBe(false)

    mutation.mockRejectedValueOnce(new Error('deactivation unavailable'))
    await wrapper.get('.deactivate-action').trigger('click')
    await wrapper.get('.confirm-action').trigger('click')
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith('Failed to deactivate peer: deactivation unavailable')
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)
    await wrapper.get('.confirm-close').trigger('click')
    expect(wrapper.find('.confirm-modal').exists()).toBe(false)
  })
})
