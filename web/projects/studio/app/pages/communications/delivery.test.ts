import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DeliveryPage from './delivery.vue'

const gqlQuery = vi.fn()
const searchProfiles = vi.fn()
const toastError = vi.fn()

vi.stubGlobal('useGraphQL', () => ({ query: gqlQuery }))
vi.stubGlobal('useProfileSearch', () => ({ searchProfiles }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#38bdf8' }))
vi.stubGlobal('useToast', () => ({ error: toastError }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const delivery = {
  messageId: '00000000-0000-0000-0000-000000000001',
  recipientId: '00000000-0000-0000-0000-000000000002',
  channel: 'EMAIL',
  status: 'DELIVERED',
  attempts: 1,
  lastAttemptAt: '2026-07-29T12:00:00Z',
  deliveredAt: '2026-07-29T12:01:00Z',
  bouncedAt: null,
  openedAt: null,
  clickedAt: null,
  errorCode: null,
  errorMessage: null,
  bmlTemplate: {
    project: 'bosca-messages',
    templateKey: 'welcome',
    version: '20260804-v1',
    parameters: { name: 'Ada Lovelace' },
  },
  createdAt: '2026-07-29T12:00:00Z',
  updatedAt: '2026-07-29T12:01:00Z',
}

const identifiedDelivery = {
  ...delivery,
  recipientName: 'Ada Lovelace',
  recipientEmail: 'ada@example.com',
}

const globalConfig = {
  mocks: {
    buildBreadcrumb: (...parts: string[]) => parts,
  },
  stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { template: '<header />' },
    SectionCard: {
      props: ['title'],
      template: '<section><h2>{{ title }}</h2><slot /></section>',
    },
    Modal: {
      props: ['title', 'icon', 'accent', 'width'],
      emits: ['close'],
      template: '<div class="delivery-modal"><slot /></div>',
    },
    Select: {
      props: ['modelValue', 'onSearch'],
      emits: ['update:modelValue'],
      template: `<button class="recipient-select" @click="$emit('update:modelValue', '${delivery.recipientId}')">Select</button>`,
    },
    GlassTable: {
      props: ['rows'],
      emits: ['row-click'],
      template: `
        <div class="delivery-rows">
          <button v-for="row in rows" :key="row.messageId + row.recipientId" class="delivery-row" @click="$emit('row-click', row)">
            <slot name="col-recipient" :row="row" />
            <slot name="col-template" :row="row" />
          </button>
        </div>
      `,
    },
    Pagination: {
      props: ['page', 'totalPages'],
      emits: ['prev', 'next'],
      template: '<button class="next-page" @click="$emit(\'next\')">{{ page }} / {{ totalPages }}</button>',
    },
  },
}

beforeEach(() => {
  gqlQuery.mockReset()
  toastError.mockReset()
  gqlQuery.mockResolvedValue({
    communications: {
      deliveryStatuses: {
        statuses: [{
          recipientName: 'Ada Lovelace',
          recipientEmail: 'ada@example.com',
          delivery,
        }],
        total: 26,
      },
    },
  })
})

describe('Communications Delivery Page', () => {
  it('loads a paged recent-delivery view when no recipient is selected', async () => {
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()

    expect(gqlQuery).toHaveBeenCalledWith(expect.anything(), { offset: 0, limit: 25 })
    expect(wrapper.text()).toContain('26 Recent Deliveries')
    expect(wrapper.find('.recipient-name').text()).toBe('Ada Lovelace')
    expect(wrapper.find('.recipient-email').text()).toBe('ada@example.com')
    expect(wrapper.find('.next-page').text()).toBe('1 / 2')
  })

  it('loads the next recent-delivery page from the calculated offset', async () => {
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()

    await wrapper.find('.next-page').trigger('click')
    await flushPromises()

    expect(gqlQuery).toHaveBeenLastCalledWith(expect.anything(), { offset: 25, limit: 25 })
  })

  it('shows the chosen template version and render parameters in delivery details', async () => {
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()

    expect(wrapper.text()).toContain('bosca-messages/welcome')
    await wrapper.find('.delivery-row').trigger('click')

    expect(wrapper.find('.template-name').text()).toContain('bosca-messages/welcome')
    expect(wrapper.find('.delivery-modal').text()).toContain('20260804-v1')
    expect(wrapper.find('.template-parameters').text()).toContain('"name": "Ada Lovelace"')
  })

  it('does not let a stale recent request overwrite a selected recipient', async () => {
    let resolveRecent: ((value: unknown) => void) | undefined
    gqlQuery
      .mockImplementationOnce(() => new Promise(resolve => { resolveRecent = resolve }))
      .mockResolvedValueOnce({
        communications: {
          recipientHistory: [delivery],
        },
      })
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()

    await wrapper.find('.recipient-select').trigger('click')
    await flushPromises()
    expect(wrapper.find('.recipient-name').text()).toBe(delivery.recipientId)

    resolveRecent?.({
      communications: {
        deliveryStatuses: {
          statuses: [{
            recipientName: 'Ada Lovelace',
            recipientEmail: 'ada@example.com',
            delivery,
          }],
          total: 1,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.recipient-name').text()).toBe(delivery.recipientId)
    expect(wrapper.text()).not.toContain('ada@example.com')
  })

  it('shows recipient identity in message search results', async () => {
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()
    gqlQuery.mockResolvedValueOnce({
      communications: { messageStatuses: [identifiedDelivery] },
    })

    await wrapper.findAll('.mode-btn')[1]!.trigger('click')
    await wrapper.find('.search-input').setValue(delivery.messageId)
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.recipient-name').text()).toBe('Ada Lovelace')
    expect(wrapper.find('.recipient-email').text()).toBe('ada@example.com')
  })

  it('shows recipient identity in recipient history results', async () => {
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()
    gqlQuery.mockResolvedValueOnce({
      communications: { recipientHistory: [identifiedDelivery] },
    })

    await wrapper.find('.recipient-select').trigger('click')
    await flushPromises()

    expect(wrapper.find('.recipient-name').text()).toBe('Ada Lovelace')
    expect(wrapper.find('.recipient-email').text()).toBe('ada@example.com')
  })

  it('invalidates a recipient request when switching to message search', async () => {
    let resolveRecipient: ((value: unknown) => void) | undefined
    const wrapper = mount(DeliveryPage, { global: globalConfig })
    await flushPromises()
    gqlQuery.mockImplementationOnce(() => new Promise(resolve => { resolveRecipient = resolve }))

    await wrapper.find('.recipient-select').trigger('click')
    await wrapper.findAll('.mode-btn')[1]!.trigger('click')
    resolveRecipient?.({
      communications: { recipientHistory: [identifiedDelivery] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('0 Results')
    expect(wrapper.text()).not.toContain('Ada Lovelace')
    expect(wrapper.text()).not.toContain('ada@example.com')
  })
})
