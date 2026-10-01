import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TimelineMetadataField from './TimelineMetadataField.vue'

const mockQuery = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ query: mockQuery }))

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
  Badge: { template: '<span class="badge"><slot /></span>' },
  Select: {
    template: '<div class="mock-select" />',
    props: ['modelValue', 'options', 'placeholder', 'searchable', 'onSearch', 'debounce'],
  },
}

describe('TimelineMetadataField', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockQuery.mockResolvedValue({
      content: { metadata: { id: 'meta-1', name: 'Intro Video', content: { type: 'video/mp4' } } },
    })
  })

  it('resolves and displays metadata name with content type badge', async () => {
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: 'meta-1', readOnly: true },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.find('.metadata-name').text()).toBe('Intro Video')
    expect(wrapper.find('.badge').text()).toBe('video/mp4')
  })

  it('hides clear button in read-only mode', async () => {
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: 'meta-1', readOnly: true },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.find('.metadata-clear').exists()).toBe(false)
  })

  it('emits update:modelValue null on clear', async () => {
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: 'meta-1' },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.find('.metadata-clear').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([[null]])
  })

  it('falls back to the raw id when resolution fails', async () => {
    mockQuery.mockRejectedValue(new Error('not found'))
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: 'meta-404', readOnly: true },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.find('.metadata-raw').text()).toBe('meta-404')
  })

  it('shows placeholder dash when read-only with no value', () => {
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: null, readOnly: true },
      global: { stubs },
    })
    expect(wrapper.find('.metadata-empty').text()).toBe('—')
    expect(mockQuery).not.toHaveBeenCalled()
  })

  it('renders a searchable select when editable with no value', () => {
    const wrapper = mount(TimelineMetadataField, {
      props: { modelValue: null },
      global: { stubs },
    })
    expect(wrapper.find('.mock-select').exists()).toBe(true)
  })
})
