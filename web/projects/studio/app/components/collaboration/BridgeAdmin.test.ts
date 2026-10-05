import type { DocumentNode, OperationDefinitionNode } from 'graphql'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BridgeAdmin from './BridgeAdmin.vue'

const gqlQuery = vi.fn()
const gqlMutation = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()

vi.stubGlobal('useGraphQL', () => ({ query: gqlQuery, mutation: gqlMutation }))
vi.stubGlobal('useToast', () => ({ success: toastSuccess, error: toastError }))

const ButtonStub = {
  props: ['disabled', 'icon'],
  emits: ['click'],
  template: '<button :data-icon="icon" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
}

const TextInputStub = {
  props: ['modelValue', 'label', 'type'],
  emits: ['update:modelValue'],
  template: `
    <label>
      {{ label }}
      <input
        :data-field="label"
        :type="type || 'text'"
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
      >
    </label>
  `,
}

const SelectStub = {
  props: ['modelValue', 'label', 'options'],
  emits: ['update:modelValue'],
  template: `
    <label>
      {{ label }}
      <select
        :data-field="label"
        :value="modelValue"
        @change="$emit('update:modelValue', $event.target.value)"
      >
        <option
          v-for="option in options"
          :key="option.value"
          :value="option.value"
          :disabled="option.disabled"
        >
          {{ option.label }}
        </option>
      </select>
    </label>
  `,
}

const ModalStub = {
  props: ['title'],
  emits: ['close'],
  template: `
    <section class="modal">
      <h2>{{ title }}</h2>
      <button class="modal-close" @click="$emit('close')">Close</button>
      <slot />
      <footer><slot name="footer" /></footer>
    </section>
  `,
}

const ConfirmModalStub = {
  props: ['title', 'confirmLabel', 'loading'],
  emits: ['close', 'confirm'],
  template: `
    <section class="confirm-modal">
      <h2>{{ title }}</h2>
      <slot />
      <button class="confirm-close" @click="$emit('close')">Cancel</button>
      <button class="confirm-action" :disabled="loading" @click="$emit('confirm')">{{ confirmLabel }}</button>
    </section>
  `,
}

const globalConfig = {
  stubs: {
    Button: ButtonStub,
    TextInput: TextInputStub,
    Select: SelectStub,
    Modal: ModalStub,
    ConfirmModal: ConfirmModalStub,
    Icon: true,
  },
}

const slackBinding = {
  id: 'binding-id',
  platform: 'SLACK',
  externalChannelId: 'C01234567',
  workspaceId: 'T01234567',
  active: true,
  createdAt: '2026-08-01T12:00:00Z' as string | null,
}

function bindingsResult(bindings = [slackBinding]) {
  return { collaboration: { bridge: { bindings } } }
}

function operationName(document: DocumentNode): string {
  const operation = document.definitions.find(
    (definition): definition is OperationDefinitionNode => definition.kind === 'OperationDefinition',
  )
  return operation?.name?.value ?? ''
}

function mountAdmin() {
  return mount(BridgeAdmin, {
    props: { channelId: 'bosca-channel-id' },
    global: globalConfig,
  })
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  const match = wrapper.findAll('button').find(candidate => candidate.text() === label)
  if (!match) throw new Error(`Button not found: ${label}`)
  return match
}

beforeEach(() => {
  gqlQuery.mockReset()
  gqlMutation.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  gqlQuery.mockResolvedValue(bindingsResult())
  gqlMutation.mockResolvedValue({})
})

describe('BridgeAdmin', () => {
  it('loads current binding fields and renders the Slack destination', async () => {
    const wrapper = mountAdmin()
    await flushPromises()

    expect(gqlQuery).toHaveBeenCalledOnce()
    expect(operationName(gqlQuery.mock.calls[0]![0])).toBe('GetBridgeBindings')
    expect(gqlQuery.mock.calls[0]![1]).toEqual({ channelId: 'bosca-channel-id' })
    expect(wrapper.text()).toContain('Slack')
    expect(wrapper.text()).toContain('T01234567')
    expect(wrapper.text()).toContain('C01234567')
    expect(wrapper.text()).toContain('Active')
  })

  it('creates a Slack binding with the current input type and required identifiers', async () => {
    gqlQuery.mockResolvedValue(bindingsResult([]))
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Add Slack').trigger('click')
    const createButton = button(wrapper, 'Create Bridge')
    expect(createButton.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-field="Platform"] option[value="TEAMS"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-field="Slack workspace ID"]').setValue(' T76543210 ')
    await wrapper.get('[data-field="Slack channel ID"]').setValue(' C76543210 ')
    await wrapper.get('[data-field="Bot token"]').setValue(' xoxb-secret ')
    expect(createButton.attributes('disabled')).toBeUndefined()

    await createButton.trigger('click')
    await flushPromises()

    expect(gqlMutation).toHaveBeenCalledOnce()
    expect(operationName(gqlMutation.mock.calls[0]![0])).toBe('CreateBridgeBinding')
    expect(gqlMutation.mock.calls[0]![1]).toEqual({
      input: {
        channelId: 'bosca-channel-id',
        platform: 'SLACK',
        externalChannelId: 'C76543210',
        workspaceId: 'T76543210',
        botToken: 'xoxb-secret',
      },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Slack bridge created')
    expect(gqlQuery).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.bridge-create').exists()).toBe(false)
  })

  it('keeps the create form open on failure and resets it when cancelled', async () => {
    gqlQuery.mockResolvedValue(bindingsResult([]))
    gqlMutation.mockRejectedValueOnce(new Error('creation denied'))
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Add Slack').trigger('click')
    await wrapper.get('[data-field="Platform"]').setValue('SLACK')
    await wrapper.get('[data-field="Slack workspace ID"]').setValue('T76543210')
    await wrapper.get('[data-field="Slack channel ID"]').setValue('C76543210')
    await wrapper.get('[data-field="Bot token"]').setValue('xoxb-secret')
    await button(wrapper, 'Create Bridge').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith('creation denied')
    expect(wrapper.find('.bridge-create').exists()).toBe(true)

    await button(wrapper, 'Cancel').trigger('click')
    await button(wrapper, 'Add Slack').trigger('click')
    expect((wrapper.get('[data-field="Slack workspace ID"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-field="Slack channel ID"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-field="Bot token"]').element as HTMLInputElement).value).toBe('')
  })

  it('rotates a bot token without exposing the existing credential', async () => {
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Update token').trigger('click')
    const tokenInput = wrapper.get('[data-field="New bot token"]')
    expect(tokenInput.attributes('type')).toBe('password')
    expect((tokenInput.element as HTMLInputElement).value).toBe('')

    await tokenInput.setValue(' xoxb-rotated ')
    await button(wrapper, 'Save Token').trigger('click')
    await flushPromises()

    expect(operationName(gqlMutation.mock.calls[0]![0])).toBe('SetBridgeBotToken')
    expect(gqlMutation.mock.calls[0]![1]).toEqual({ id: 'binding-id', token: 'xoxb-rotated' })
    expect(toastSuccess).toHaveBeenCalledWith('Slack bot token updated')
    expect(wrapper.find('.modal').exists()).toBe(false)
  })

  it('closes the token editor without changing the credential', async () => {
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Update token').trigger('click')
    await wrapper.get('[data-field="New bot token"]').setValue('not-saved')
    await wrapper.get('.modal-close').trigger('click')

    expect(gqlMutation).not.toHaveBeenCalled()
    expect(wrapper.find('.modal').exists()).toBe(false)
  })

  it('requires confirmation before deactivating and refreshes the list', async () => {
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Deactivate').trigger('click')
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)
    expect(gqlMutation).not.toHaveBeenCalled()

    await wrapper.get('.confirm-action').trigger('click')
    await flushPromises()

    expect(operationName(gqlMutation.mock.calls[0]![0])).toBe('DeactivateBridgeBinding')
    expect(gqlMutation.mock.calls[0]![1]).toEqual({ id: 'binding-id' })
    expect(toastSuccess).toHaveBeenCalledWith('Slack bridge deactivated')
    expect(gqlQuery).toHaveBeenCalledTimes(2)
  })

  it('can cancel deactivation and keeps the confirmation open after a failure', async () => {
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Deactivate').trigger('click')
    await wrapper.get('.confirm-close').trigger('click')
    expect(wrapper.find('.confirm-modal').exists()).toBe(false)

    gqlMutation.mockRejectedValueOnce(new Error('deactivation denied'))
    await button(wrapper, 'Deactivate').trigger('click')
    await wrapper.get('.confirm-action').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith('deactivation denied')
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)
  })

  it('keeps editors open and reports mutation failures', async () => {
    gqlMutation.mockRejectedValueOnce(new Error('bridge unavailable'))
    const wrapper = mountAdmin()
    await flushPromises()

    await button(wrapper, 'Update token').trigger('click')
    await wrapper.get('[data-field="New bot token"]').setValue('xoxb-new')
    await button(wrapper, 'Save Token').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith('bridge unavailable')
    expect(wrapper.find('.modal').exists()).toBe(true)
  })

  it('shows a retryable load error instead of silently hiding it', async () => {
    gqlQuery.mockRejectedValueOnce(new Error('bindings unavailable'))
    const wrapper = mountAdmin()
    await flushPromises()

    expect(toastError).toHaveBeenCalledWith('bindings unavailable')
    expect(wrapper.text()).toContain('bindings unavailable')

    await button(wrapper, 'Retry').trigger('click')
    await flushPromises()

    expect(gqlQuery).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('C01234567')
  })

  it('labels existing Teams bindings without offering unsupported token controls', async () => {
    gqlQuery.mockResolvedValue(bindingsResult([
      {
        ...slackBinding,
        id: 'teams-null-date',
        platform: 'TEAMS',
        createdAt: null,
      },
      {
        ...slackBinding,
        id: 'teams-invalid-date',
        platform: 'TEAMS',
        active: false,
        createdAt: 'invalid',
      },
    ]))

    const wrapper = mountAdmin()
    await flushPromises()

    expect(wrapper.text()).toContain('Microsoft Teams')
    expect(wrapper.text()).toContain('Inactive')
    expect(wrapper.text()).not.toContain('Update token')
  })
})
