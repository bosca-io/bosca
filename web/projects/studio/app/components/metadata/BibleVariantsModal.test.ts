import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BibleVariantsModal from './BibleVariantsModal.vue'

const query = vi.fn()
const mutation = vi.fn()
const toast = {
  success: vi.fn(),
  error: vi.fn(),
}

const variants = [
  {
    variant: 'reader',
    enabled: true,
    defaultVariant: true,
    name: 'Reader Edition',
    nameLocal: 'Reader Edition',
    abbreviation: 'RE',
    abbreviationLocal: 'RE',
  },
  {
    variant: 'study',
    enabled: false,
    defaultVariant: false,
    name: 'Study Edition',
    nameLocal: 'Study Edition',
    abbreviation: 'SE',
    abbreviationLocal: 'SE',
  },
]

const response = (items = variants) => ({
  content: {
    metadata: {
      bibles: items,
    },
  },
})

const stubs = {
  Modal: {
    template: '<div class="modal"><slot /><div class="modal-footer"><slot name="footer" /></div></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Button: {
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled', 'size'],
    emits: ['click'],
  },
  Switch: {
    template: '<button class="variant-switch" :disabled="disabled" :data-enabled="String(modelValue)" @click="$emit(\'update:modelValue\', !modelValue)" />',
    props: ['modelValue', 'disabled', 'accent'],
    emits: ['update:modelValue'],
  },
}

function mountModal() {
  return mount(BibleVariantsModal, {
    props: {
      metadataId: 'metadata-1',
      metadataVersion: 3,
      accent: '#abcdef',
    },
    global: { stubs },
  })
}

describe('BibleVariantsModal', () => {
  beforeEach(() => {
    query.mockReset()
    mutation.mockReset()
    toast.success.mockReset()
    toast.error.mockReset()
    vi.stubGlobal('useGraphQL', () => ({ query, mutation }))
    vi.stubGlobal('useToast', () => toast)
  })

  it('lists enabled and disabled variants for editors', async () => {
    query.mockResolvedValue(response())

    const wrapper = mountModal()
    await flushPromises()

    expect(wrapper.findAll('.variant-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('Reader Edition')
    expect(wrapper.text()).toContain('Study Edition')
    expect(wrapper.findAll<HTMLInputElement>('input[type="radio"]')[0]?.element.checked).toBe(true)
    expect(wrapper.findAll('.variant-switch')[0]?.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll<HTMLInputElement>('input[type="radio"]')[1]?.element.disabled).toBe(true)
  })

  it('enables a disabled variant immediately', async () => {
    query
      .mockResolvedValueOnce(response())
      .mockResolvedValueOnce(response([
        variants[0]!,
        { ...variants[1]!, enabled: true },
      ]))
    mutation.mockResolvedValue({})
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.findAll('.variant-switch')[1]!.trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledOnce()
    expect(mutation.mock.calls[0]?.[1]).toEqual({
      id: 'metadata-1',
      version: 3,
      variant: 'study',
      enabled: true,
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
    expect(toast.success).toHaveBeenCalledWith('Study Edition enabled')
  })

  it('selects an enabled non-default variant as the default', async () => {
    const enabledStudy = { ...variants[1]!, enabled: true }
    query
      .mockResolvedValueOnce(response([variants[0]!, enabledStudy]))
      .mockResolvedValueOnce(response([
        { ...variants[0]!, defaultVariant: false },
        { ...enabledStudy, defaultVariant: true },
      ]))
    mutation.mockResolvedValue({})
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.findAll<HTMLInputElement>('input[type="radio"]')[1]!.trigger('change')
    await flushPromises()

    expect(mutation).toHaveBeenCalledOnce()
    expect(mutation.mock.calls[0]?.[1]).toEqual({
      id: 'metadata-1',
      version: 3,
      variant: 'study',
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
    expect(toast.success).toHaveBeenCalledWith('Study Edition is now the default')
  })

  it('surfaces mutation failures without changing the list', async () => {
    query.mockResolvedValue(response())
    mutation.mockRejectedValue(new Error('Variant update denied'))
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.findAll('.variant-switch')[1]!.trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('Variant update denied')
    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.findAll('.variant-row')).toHaveLength(2)
  })

  it('offers a retry when loading fails', async () => {
    query
      .mockRejectedValueOnce(new Error('Could not load variants'))
      .mockResolvedValueOnce(response())
    const wrapper = mountModal()
    await flushPromises()

    expect(wrapper.text()).toContain('Could not load variants')
    await wrapper.get('.error-state button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.variant-row')).toHaveLength(2)
  })
})
