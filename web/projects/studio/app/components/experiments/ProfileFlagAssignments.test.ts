import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { buildSchema, parse, validate } from 'graphql'
import { readFileSync } from 'node:fs'
import ProfileFlagAssignments from './ProfileFlagAssignments.vue'
import type { ProfileAssignmentFlag } from '~/utils/profileFlagAssignments'

const flag = (): ProfileAssignmentFlag => ({
  id: 'flag-1', key: 'checkout', name: 'Checkout', description: 'Checkout experience',
  type: 'BOOLEAN', status: 'ENABLED', defaultVariationKey: 'off',
  variations: [{ key: 'off', name: 'Off', value: false }, { key: 'on', name: 'On', value: true }],
  targetingRules: [], experiments: [],
})
const query = vi.fn()
const mutation = vi.fn()
const error = vi.fn()
const success = vi.fn()
const Select = defineComponent({
  props: ['modelValue', 'options', 'disabled', 'label'], emits: ['update:modelValue'],
  template: '<select :aria-label="label" :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :value="option.value">{{ option.label }}</option></select>',
})
function mountEditor(principalId: string | null = 'principal-1') {
  return mount(ProfileFlagAssignments, {
    props: { principalId, accent: '#da9e30' },
    global: { stubs: {
      SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
      Button: { template: '<button><slot /></button>' },
      Select,
      TextInput: true,
      NuxtLink: { template: '<a><slot /></a>' },
    } },
  })
}
const button = (wrapper: ReturnType<typeof mountEditor>, label: string) => wrapper.findAll('button').find((item) => item.text() === label)!

describe('ProfileFlagAssignments', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('useGraphQL', () => ({ query, mutation }))
    vi.stubGlobal('useToast', () => ({ error, success }))
    query.mockResolvedValue({ featureFlags: { all: [flag()] } })
  })

  it('requires a linked principal and never queries for a profile without one', async () => {
    const wrapper = mountEditor(null)
    await flushPromises()
    expect(wrapper.text()).toContain('Link a principal')
    expect(query).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('saves a persistent choice for the principal using fresh configuration and can remove it', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    expect(button(wrapper, 'Save').attributes('disabled')).toBeDefined()
    await wrapper.find('select').setValue('variation:on')
    const latest = { ...flag(), description: 'Updated elsewhere' }
    query.mockResolvedValueOnce({ featureFlags: { flag: latest } })
    mutation.mockImplementationOnce(async (_doc, vars) => ({ featureFlags: { edit: { ...latest, ...vars.flag } } }))
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    const input = mutation.mock.calls[0]?.[1].flag
    expect(input.description).toBe('Updated elsewhere')
    expect(input.targetingRules[0].conditions).toEqual([{ type: 'Principal', principalIds: ['principal-1'] }])
    expect(input.targetingRules[0].rollout.variationWeights).toEqual([{ variationKey: 'on', weight: 1 }])
    expect(success).toHaveBeenCalledWith('Feature flag assignment saved')
    await wrapper.find('select').setValue('automatic')
    query.mockResolvedValueOnce({ featureFlags: { flag: { ...latest, ...input } } })
    mutation.mockResolvedValueOnce({ featureFlags: { edit: latest } })
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].flag.targetingRules).toEqual([])
    expect(success).toHaveBeenCalledWith('Manual assignment removed')
    wrapper.unmount()
  })

  it('surfaces loading errors and retries', async () => {
    query.mockRejectedValueOnce(new Error('Unavailable'))
    const wrapper = mountEditor()
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('Could not load')
    await button(wrapper, 'Retry').trigger('click')
    await flushPromises()
    expect(wrapper.find('select').exists()).toBe(true)
    wrapper.unmount()
  })

  it('refreshes the flags and handles an empty catalog', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    query.mockResolvedValueOnce({ featureFlags: { all: [] } })
    await button(wrapper, 'Refresh').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('No feature flags.')
    expect(wrapper.find('select').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not submit an assignment for a deleted flag', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.find('select').setValue('variation:on')
    query.mockResolvedValueOnce({ featureFlags: { flag: null } })
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(mutation).not.toHaveBeenCalled()
    expect(error).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('keeps the unsaved selection after mutation failure', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.find('select').setValue('variation:on')
    query.mockResolvedValueOnce({ featureFlags: { flag: flag() } })
    mutation.mockRejectedValueOnce(new Error('Forbidden'))
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(error).toHaveBeenCalledOnce()
    expect(success).not.toHaveBeenCalled()
    expect(wrapper.find('select').element.value).toBe('variation:on')
    expect(button(wrapper, 'Save').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('uses offset pagination without skipping the lookahead flag', async () => {
    query.mockResolvedValueOnce({ featureFlags: { all: Array.from({ length: 51 }, (_, i) => ({ ...flag(), id: `flag-${i}` })) } })
    const wrapper = mountEditor()
    await flushPromises()
    expect(wrapper.findAll('select')).toHaveLength(50)
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(query.mock.calls[1]?.[1]).toEqual({ offset: 50, limit: 51 })
    expect(wrapper.text()).toContain('Page 2')
    wrapper.unmount()
  })

  it('discards old profile results and cancels a pending save before mutation when the principal changes', async () => {
    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.find('select').setValue('variation:on')
    let finish: (value: unknown) => void = () => {}
    query.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    await button(wrapper, 'Save').trigger('click')
    await wrapper.setProps({ principalId: null })
    finish({ featureFlags: { flag: flag() } })
    await flushPromises()
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Link a principal')
    wrapper.unmount()
  })

  it('uses operations valid against the Studio schema', () => {
    const source = readFileSync('app/components/experiments/ProfileFlagAssignments.vue', 'utf8')
    const documents = [...source.matchAll(/gql`([\s\S]*?)`/g)].map((match) => match[1]!)
    const fragment = documents[0]!
    const schema = buildSchema(readFileSync('schema.graphqls', 'utf8'))
    for (const document of documents.slice(1)) {
      expect(validate(schema, parse(document.replace('${fields}', fragment)))).toEqual([])
    }
  })
})
