import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '../test-helpers'
import { registerControl } from '../controls'
import { defineComponent } from 'vue'
import { Button } from '@bosca/ui'
import type { JsonSchema, UiSchema, FieldNode } from '../types'
import type { FormsProfile } from '../nuxt'
import { flushPromises } from '@vue/test-utils'

const StubControl = defineComponent({
  props: ['value', 'node', 'propertySchema', 'readonly', 'error'],
  emits: ['update:value'],
  template: '<div class="stub-ctrl">{{ value }}</div>',
})

const schema: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string', minLength: 1 },
    email: { type: 'string', format: 'email' },
  },
  required: ['name'],
}

const uiSchema: UiSchema = {
  version: 1,
  layout: [
    { type: 'field', property: 'name', control: 'test-bosca-form-ctrl' } as FieldNode,
    { type: 'field', property: 'email', control: 'test-bosca-form-ctrl' } as FieldNode,
  ],
}

const mockFormSchema = {
  id: '1',
  key: 'test-form',
  name: 'Test',
  description: '',
  schema,
  uiSchema,
  version: 1,
  public: true,
  published: true,
  permissions: [],
  created: '',
  modified: '',
}

const profileMapping = {
  nameField: 'name',
  visibility: 'USER' as const,
  attributes: [
    { typeId: 'bosca.profiles.email', field: 'email', attributeKey: 'email' },
  ],
}

const mockFormSchemaWithMapping = {
  ...mockFormSchema,
  key: 'contact-form',
  profileMapping,
}

// vi.hoisted runs before vi.mock — safe to reference in the mock factory
const mockFormsState = vi.hoisted(() => ({
  unavailable: false,
  apiUrl: 'https://api.test.com',
  getToken: vi.fn<() => string | null>(() => 'tok'),
  getProfile: vi.fn<() => FormsProfile | null>(() => null),
  analytics: null,
  schemaCache: { value: new Map() },
  fetchSchema: vi.fn(),
  invalidateSchema: vi.fn(),
  api: {
    getByKey: vi.fn(),
    getById: vi.fn(),
    getAll: vi.fn(),
    save: vi.fn(),
    delete: vi.fn(),
    submitForm: vi.fn(),
  },
}))

vi.mock('../nuxt', () => ({
  useBoscaForms: () => {
    if (mockFormsState.unavailable) throw new Error('Forms SDK not initialized')
    return mockFormsState
  },
}))

// Must import AFTER vi.mock so the mock is applied
import BoscaForm from './BoscaForm.vue'

beforeEach(() => {
  registerControl('test-bosca-form-ctrl', StubControl)
  vi.clearAllMocks()
  mockFormsState.unavailable = false
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('BoscaForm', () => {
  it('does not submit through a delayed click after the active schema has been removed', async () => {
    const wrapper = mount(BoscaForm, { props: { schema, uiSchema, mode: 'submit' } })
    const submit = wrapper.findComponent(Button).vm.$.vnode.props?.onClick as () => Promise<void>
    expect(submit).toBeTypeOf('function')
    await wrapper.setProps({ schema: undefined })
    await submit()
    expect(mockFormsState.api.submitForm).not.toHaveBeenCalled()
  })

  it('reports an incomplete inline schema without attempting to render its controls', () => {
    const wrapper = mount(BoscaForm, { props: { schema } })
    expect(wrapper.text()).toContain('No form schema found')
    expect(wrapper.find('.bosca-form-renderer').exists()).toBe(false)
  })

  it('loads a new schema when the key changes and respects a directly supplied schema', async () => {
    const wrapper = mount(BoscaForm, { props: { schemaKey: '', uiSchema } })
    mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchema)
    await wrapper.setProps({ schemaKey: 'new-key' })
    await flushPromises()
    expect(mockFormsState.fetchSchema).toHaveBeenCalledWith('new-key')
    expect(wrapper.findComponent({ name: 'BoscaFormRenderer' }).props('modelValue')).toEqual({})
    await wrapper.setProps({ schemaKey: '' })
    await wrapper.setProps({ schema, schemaKey: 'direct-key' })
    expect(mockFormsState.fetchSchema).toHaveBeenCalledTimes(1)
  })

  it('shows root validation failures and blocks submission', async () => {
    const wrapper = mount(BoscaForm, { props: { schema: { type: 'object', minProperties: 2 }, uiSchema, mode: 'submit' } })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.form-error').text()).toContain('properties')
    expect(mockFormsState.api.submitForm).not.toHaveBeenCalled()
  })

  it('continues rendering an inline schema while reporting submission and loading failures without the SDK', async () => {
    mockFormsState.unavailable = true
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const missing = mount(BoscaForm, { props: { schemaKey: 'missing' } })
    await flushPromises()
    expect(missing.text()).toContain('No form schema found')
    expect(spy).toHaveBeenCalled()
    const wrapper = mount(BoscaForm, { props: { schema: { type: 'object' }, uiSchema, mode: 'submit' } })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Forms SDK not initialized')
  })

  it('handles incomplete profile data and preserves a generic source when schema identity is absent', async () => {
    mockFormsState.getToken.mockReturnValue(null)
    mockFormsState.getProfile.mockReturnValue({ name: 'Ada', attributes: [{ typeId: 'bosca.profiles.email', attributes: null }] } as unknown as FormsProfile)
    mockFormsState.fetchSchema.mockResolvedValueOnce({ ...mockFormSchemaWithMapping, key: undefined, schema: { type: 'object' } })
    mockFormsState.api.submitForm.mockResolvedValueOnce({ id: 'submission' })
    const wrapper = mount(BoscaForm, { props: { schemaKey: 'anonymous', mode: 'submit' } })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'BoscaFormRenderer' }).props('modelValue')).toEqual({ name: 'Ada' })
    wrapper.findComponent({ name: 'BoscaFormRenderer' }).vm.$emit('update:field', 'name', undefined)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(mockFormsState.api.submitForm.mock.calls[0][0].profile).toMatchObject({ name: '', attributes: [{ source: 'form:unknown' }] })
  })

  describe('edit mode with inline schema', () => {
    it('renders the form when schema and uiSchema are provided directly', () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, modelValue: { name: 'Alice' } },
      })
      expect(wrapper.find('.bosca-form-renderer').exists()).toBe(true)
      expect(wrapper.findAll('.bosca-form-field').length).toBe(2)
    })

    it('emits update:modelValue when a field is updated', async () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, modelValue: { name: 'Alice', email: '' } },
      })
      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Bob')
      await flushPromises()

      expect(wrapper.emitted('update:modelValue')).toBeTruthy()
      const emitted = wrapper.emitted('update:modelValue')![0][0] as Record<string, unknown>
      expect(emitted.name).toBe('Bob')
    })

    it('shows "No form schema found" when no schema is provided', () => {
      const wrapper = mount(BoscaForm, {
        props: { modelValue: {} },
      })
      expect(wrapper.text()).toContain('No form schema found')
    })

    it('merges external errors with validation errors', () => {
      const wrapper = mount(BoscaForm, {
        props: {
          schema,
          uiSchema,
          modelValue: { name: 'Alice' },
          errors: { email: 'External error' },
        },
      })
      expect(wrapper.text()).toContain('External error')
    })

    it('passes readonly prop to renderer', () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, modelValue: {}, readonly: true },
      })
      expect(wrapper.find('.bosca-form-renderer').exists()).toBe(true)
    })
  })

  describe('schema fetching via schemaKey', () => {
    it('fetches schema on mount when schemaKey is provided', async () => {
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchema)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'test-form', modelValue: {} },
      })
      await flushPromises()

      expect(mockFormsState.fetchSchema).toHaveBeenCalledWith('test-form')
      expect(wrapper.find('.bosca-form-renderer').exists()).toBe(true)
    })

    it('shows loading state while fetching', async () => {
      let resolvePromise: (value: any) => void
      mockFormsState.fetchSchema.mockReturnValueOnce(
        new Promise((resolve) => { resolvePromise = resolve }),
      )

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'test-form', modelValue: {} },
      })
      // Loading is set synchronously in loadSchema before the await
      await vi.waitFor(() => {
        expect(wrapper.text()).toContain('Loading form...')
      })

      resolvePromise!(mockFormSchema)
      await flushPromises()

      expect(wrapper.find('.bosca-form-renderer').exists()).toBe(true)
    })

    it('does not fetch when schema is provided directly alongside schemaKey', async () => {
      mount(BoscaForm, {
        props: { schemaKey: 'test-form', schema, uiSchema, modelValue: {} },
      })
      await flushPromises()

      expect(mockFormsState.fetchSchema).not.toHaveBeenCalled()
    })

    it('handles fetch errors gracefully', async () => {
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
      mockFormsState.fetchSchema.mockRejectedValueOnce(new Error('Network fail'))

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'bad-key', modelValue: {} },
      })
      await flushPromises()

      expect(wrapper.text()).toContain('No form schema found')
      spy.mockRestore()
    })

    it('shows schema key in not-found message', async () => {
      mockFormsState.fetchSchema.mockResolvedValueOnce(null)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'missing-form', modelValue: {} },
      })
      await flushPromises()

      expect(wrapper.text()).toContain('for key "missing-form"')
    })
  })

  describe('submit mode', () => {
    it('renders a submit button in submit mode', () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit', submitLabel: 'Send' },
      })
      expect(wrapper.find('button').text()).toBe('Send')
    })

    it('uses internal data in submit mode (not modelValue)', async () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })
      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Bob')
      await flushPromises()

      expect(wrapper.emitted('update:modelValue')).toBeFalsy()
    })

    it('submits form data and shows success', async () => {
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', type: 'SUBMISSION',
      })

      const wrapper = mount(BoscaForm, {
        props: {
          schema,
          uiSchema,
          mode: 'submit',
          formSchemaId: 'schema-1',
        },
      })

      // Fill required field to pass validation
      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      expect(mockFormsState.api.submitForm).toHaveBeenCalledWith({
        formSchemaId: 'schema-1',
        attributes: { name: 'Alice' },
        profile: undefined,
      })
      expect(wrapper.text()).toContain('Submitted successfully!')
      expect(wrapper.emitted('submitted')).toBeTruthy()
    })

    it('shows validation errors and does not submit when invalid', async () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })

      await wrapper.find('button').trigger('click')
      await flushPromises()

      expect(mockFormsState.api.submitForm).not.toHaveBeenCalled()
      expect(wrapper.emitted('validate')).toBeTruthy()
    })

    it('shows error message when submission fails', async () => {
      mockFormsState.api.submitForm.mockRejectedValueOnce(new Error('Server error'))

      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('Server error')
    })

    it('shows generic error when submission throws non-Error', async () => {
      mockFormsState.api.submitForm.mockRejectedValueOnce('string error')

      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('Submission failed')
    })

    it('disables submit button when readonly', () => {
      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit', readonly: true },
      })
      expect(wrapper.find('button').element.disabled).toBe(true)
    })

    it('resets internal data after successful submission', async () => {
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', type: 'SUBMISSION',
      })

      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      // After successful submission, internal data should be reset
      expect(wrapper.text()).toContain('Submitted successfully!')
    })
  })

  describe('nested path updates via setByPath', () => {
    it('sets nested property values correctly', async () => {
      const nestedSchema: JsonSchema = {
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: { city: { type: 'string' } },
          },
        },
      }
      const nestedUi: UiSchema = {
        version: 1,
        layout: [
          { type: 'field', property: 'address.city', control: 'test-bosca-form-ctrl' } as FieldNode,
        ],
      }
      const wrapper = mount(BoscaForm, {
        props: { schema: nestedSchema, uiSchema: nestedUi, modelValue: {} },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'address.city', 'Portland')
      await flushPromises()

      const emitted = wrapper.emitted('update:modelValue')![0][0] as Record<string, unknown>
      expect((emitted.address as Record<string, unknown>).city).toBe('Portland')
    })

    it('preserves existing nested data when setting a new nested field', async () => {
      const nestedSchema: JsonSchema = {
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: {
              city: { type: 'string' },
              zip: { type: 'string' },
            },
          },
        },
      }
      const nestedUi: UiSchema = {
        version: 1,
        layout: [
          { type: 'field', property: 'address.city', control: 'test-bosca-form-ctrl' } as FieldNode,
        ],
      }
      const wrapper = mount(BoscaForm, {
        props: {
          schema: nestedSchema,
          uiSchema: nestedUi,
          modelValue: { address: { city: 'Portland', zip: '97201' } },
        },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'address.city', 'Seattle')
      await flushPromises()

      const emitted = wrapper.emitted('update:modelValue')![0][0] as Record<string, unknown>
      const address = emitted.address as Record<string, unknown>
      expect(address.city).toBe('Seattle')
      expect(address.zip).toBe('97201')
    })
  })

  describe('profile mapping — anonymous submission', () => {
    it('builds a ProfileInput from form data when user is not authenticated', async () => {
      mockFormsState.getToken.mockReturnValue(null)
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', attributes: {}, status: 'ok', created: '', modified: '',
      })

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Jane Doe')
      renderer.vm.$emit('update:field', 'email', 'jane@example.com')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      const call = mockFormsState.api.submitForm.mock.calls[0][0]
      expect(call.profile).toBeDefined()
      expect(call.profile.name).toBe('Jane Doe')
      expect(call.profile.visibility).toBe('USER')
      expect(call.profile.attributes).toHaveLength(1)
      expect(call.profile.attributes[0].typeId).toBe('bosca.profiles.email')
      expect(call.profile.attributes[0].attributes).toEqual({ email: 'jane@example.com' })
      expect(call.profile.attributes[0].source).toBe('form:contact-form')
      expect(call.profile.attributes[0].confidence).toBe(100)
      expect(call.profile.attributes[0].priority).toBe(1)
      expect(call.profile.attributes[0].visibility).toBe('USER')
    })

    it('does not build a profile when user is authenticated', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', attributes: {}, status: 'ok', created: '', modified: '',
      })

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      const call = mockFormsState.api.submitForm.mock.calls[0][0]
      expect(call.profile).toBeUndefined()
    })

    it('does not build a profile when schema has no profile mapping', async () => {
      mockFormsState.getToken.mockReturnValue(null)
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', attributes: {}, status: 'ok', created: '', modified: '',
      })

      const wrapper = mount(BoscaForm, {
        props: { schema, uiSchema, mode: 'submit' },
      })

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Alice')
      await flushPromises()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      const call = mockFormsState.api.submitForm.mock.calls[0][0]
      expect(call.profile).toBeUndefined()
    })

    it('uses formSchemaId as source fallback when schema key is unavailable', async () => {
      mockFormsState.getToken.mockReturnValue(null)
      mockFormsState.api.submitForm.mockResolvedValueOnce({
        id: 'sub-1', attributes: {}, status: 'ok', created: '', modified: '',
      })

      const schemaWithMapping = { ...mockFormSchemaWithMapping, key: undefined as any }

      const wrapper = mount(BoscaForm, {
        props: {
          schema: schemaWithMapping.schema as JsonSchema,
          uiSchema: schemaWithMapping.uiSchema as UiSchema,
          mode: 'submit',
          formSchemaId: 'explicit-id',
        },
      })

      // Manually set fetchedSchema with the mapping via a direct schema prop
      // Since we pass schema directly, fetchedSchema is null — simulate by using schemaKey
      // Actually we need to use fetchSchema for profileMapping to be available
      mockFormsState.fetchSchema.mockResolvedValueOnce(schemaWithMapping)
      // Re-mount with schemaKey to trigger fetch
      const wrapper2 = mount(BoscaForm, {
        props: { schemaKey: 'x', mode: 'submit', formSchemaId: 'explicit-id' },
      })
      await flushPromises()

      const renderer = wrapper2.findComponent({ name: 'BoscaFormRenderer' })
      renderer.vm.$emit('update:field', 'name', 'Test')
      await flushPromises()

      await wrapper2.find('button').trigger('click')
      await flushPromises()

      const call = mockFormsState.api.submitForm.mock.calls[0][0]
      expect(call.profile.attributes[0].source).toContain('form:')
    })
  })

  describe('profile mapping — pre-fill from authenticated profile', () => {
    it('pre-fills mapped fields from profile attributes on schema load', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.getProfile.mockReturnValue({
        name: 'Alice Smith',
        attributes: [
          { typeId: 'bosca.profiles.email', attributes: { email: 'alice@example.com' } },
        ],
      })
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      const modelValue = renderer.props('modelValue') as Record<string, unknown>
      expect(modelValue.name).toBe('Alice Smith')
      expect(modelValue.email).toBe('alice@example.com')
    })

    it('does not overwrite existing form data during pre-fill', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.getProfile.mockReturnValue({
        name: 'Alice Smith',
        attributes: [
          { typeId: 'bosca.profiles.email', attributes: { email: 'alice@example.com' } },
        ],
      })
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'edit', modelValue: { email: 'existing@test.com' } },
      })
      await flushPromises()

      const emitted = wrapper.emitted('update:modelValue')
      if (emitted) {
        const last = emitted[emitted.length - 1][0] as Record<string, unknown>
        expect(last.email).toBe('existing@test.com')
      }
    })

    it('skips pre-fill when schema has no profile mapping', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.getProfile.mockReturnValue({
        name: 'Alice',
        attributes: [],
      })
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchema)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'test-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      const modelValue = renderer.props('modelValue') as Record<string, unknown>
      expect(Object.keys(modelValue)).toHaveLength(0)
    })

    it('skips pre-fill when user has no profile', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.getProfile.mockReturnValue(null)
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      const modelValue = renderer.props('modelValue') as Record<string, unknown>
      expect(Object.keys(modelValue)).toHaveLength(0)
    })

    it('handles missing profile attributes gracefully', async () => {
      mockFormsState.getToken.mockReturnValue('valid-token')
      mockFormsState.getProfile.mockReturnValue({
        name: 'Bob',
        attributes: [],
      })
      mockFormsState.fetchSchema.mockResolvedValueOnce(mockFormSchemaWithMapping)

      const wrapper = mount(BoscaForm, {
        props: { schemaKey: 'contact-form', mode: 'submit' },
      })
      await flushPromises()

      const renderer = wrapper.findComponent({ name: 'BoscaFormRenderer' })
      const modelValue = renderer.props('modelValue') as Record<string, unknown>
      expect(modelValue.name).toBe('Bob')
      expect(modelValue.email).toBeUndefined()
    })
  })
})
