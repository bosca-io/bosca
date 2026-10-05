import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { DocumentNode, OperationDefinitionNode } from 'graphql'
import AnalyticsBulkPermissionsModal from './AnalyticsBulkPermissionsModal.vue'

// The component reaches for Modal/Select/Button as Nuxt auto-imports; stub them
// with minimal semantics (slots, v-model, disabled) so behavior can be asserted.
const ModalStub = { template: '<div><slot /><slot name="footer" /></div>' }
const SelectStub = {
  props: ['modelValue', 'options', 'label', 'disabled', 'placeholder', 'accent', 'searchable'],
  emits: ['update:modelValue'],
  template: `
    <select :aria-label="label" :value="modelValue" @change="$emit('update:modelValue', $event.target.value)">
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
  `,
}
const ButtonStub = {
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>',
}

function operationName(doc: DocumentNode): string {
  const op = doc.definitions.find((d): d is OperationDefinitionNode => d.kind === 'OperationDefinition')
  return op?.name?.value ?? ''
}

let gqlQuery: ReturnType<typeof vi.fn>
let gqlMutation: ReturnType<typeof vi.fn>
let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> }

beforeEach(() => {
  gqlQuery = vi.fn().mockResolvedValue({
    security: { groups: { all: [{ id: 'g1', name: 'Group One', description: null }] } },
  })
  gqlMutation = vi.fn().mockResolvedValue({})
  toast = { success: vi.fn(), error: vi.fn() }
  vi.stubGlobal('useGraphQL', () => ({ query: gqlQuery, mutation: gqlMutation }))
  vi.stubGlobal('useToast', () => toast)
})

type EntityType = 'queries' | 'dashboards' | 'visualizations'

function mountModal(props: { entityType?: EntityType; entityIds?: string[] } = {}) {
  return mount(AnalyticsBulkPermissionsModal, {
    props: { entityType: 'queries' as EntityType, entityIds: ['id-a', 'id-b'], ...props },
    global: { stubs: { Modal: ModalStub, Select: SelectStub, Button: ButtonStub } },
  })
}

// Footer button order: Cancel, Revoke, Grant.
function footerButtons(w: ReturnType<typeof mountModal>) {
  const [cancel, revoke, grant] = w.findAll('button')
  return { cancel: cancel!, revoke: revoke!, grant: grant! }
}

async function selectGroup(w: ReturnType<typeof mountModal>, id = 'g1') {
  await w.find('[aria-label="Group"]').setValue(id)
}

describe('AnalyticsBulkPermissionsModal', () => {
  it('loads groups on mount into the group select', async () => {
    const w = mountModal()
    await flushPromises()
    const options = w.find('[aria-label="Group"]').findAll('option')
    expect(options.map(o => o.text())).toEqual(['Group One'])
  })

  it('surfaces a group load failure as an error toast', async () => {
    gqlQuery.mockRejectedValue(new Error('groups unavailable'))
    mountModal()
    await flushPromises()
    expect(toast.error).toHaveBeenCalledWith('groups unavailable')
  })

  it('offers EXECUTE for queries but not for visualizations or dashboards', async () => {
    const actions = (w: ReturnType<typeof mountModal>) =>
      w.find('[aria-label="Action"]').findAll('option').map(o => o.text())
    expect(actions(mountModal({ entityType: 'queries' }))).toContain('EXECUTE')
    expect(actions(mountModal({ entityType: 'visualizations' }))).not.toContain('EXECUTE')
    expect(actions(mountModal({ entityType: 'dashboards' }))).not.toContain('EXECUTE')
  })

  it('disables Grant and Revoke until a group is selected', async () => {
    const w = mountModal()
    await flushPromises()
    let { revoke, grant } = footerButtons(w)
    expect(grant.attributes('disabled')).toBeDefined()
    expect(revoke.attributes('disabled')).toBeDefined()

    await selectGroup(w)
    ;({ revoke, grant } = footerButtons(w))
    expect(grant.attributes('disabled')).toBeUndefined()
    expect(revoke.attributes('disabled')).toBeUndefined()
  })

  it('grants the permission to every selected entity and closes on success', async () => {
    const w = mountModal({ entityType: 'visualizations' })
    await flushPromises()
    await selectGroup(w)
    await footerButtons(w).grant.trigger('click')
    await flushPromises()

    expect(gqlMutation).toHaveBeenCalledTimes(2)
    const [doc, vars] = gqlMutation.mock.calls[0]!
    expect(operationName(doc)).toBe('BulkAddAnalyticsVisualizationPermission')
    expect(vars).toEqual({ permission: { action: 'VIEW', entityId: 'id-a', groupId: 'g1' } })
    expect(gqlMutation.mock.calls[1]![1]).toEqual({ permission: { action: 'VIEW', entityId: 'id-b', groupId: 'g1' } })
    expect(toast.success).toHaveBeenCalledWith('Granted VIEW to 2 visualizations')
    expect(w.emitted('applied')).toHaveLength(1)
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('revokes using the delete mutation for the entity type', async () => {
    const w = mountModal({ entityType: 'dashboards', entityIds: ['id-a'] })
    await flushPromises()
    await selectGroup(w)
    await footerButtons(w).revoke.trigger('click')
    await flushPromises()

    expect(gqlMutation).toHaveBeenCalledTimes(1)
    expect(operationName(gqlMutation.mock.calls[0]![0])).toBe('BulkRemoveAnalyticsDashboardPermission')
    expect(toast.success).toHaveBeenCalledWith('Revoked VIEW from 1 dashboard')
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('reports partial failures, keeps the modal open, and still signals applied', async () => {
    gqlMutation.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('denied'))
    const w = mountModal()
    await flushPromises()
    await selectGroup(w)
    await footerButtons(w).grant.trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('Failed for 1 of 2 queries: denied')
    expect(w.emitted('applied')).toHaveLength(1)
    expect(w.emitted('close')).toBeUndefined()
  })

  it('does not signal applied when every entity fails', async () => {
    gqlMutation.mockRejectedValue(new Error('denied'))
    const w = mountModal()
    await flushPromises()
    await selectGroup(w)
    await footerButtons(w).grant.trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('Failed for 2 of 2 queries: denied')
    expect(w.emitted('applied')).toBeUndefined()
    expect(w.emitted('close')).toBeUndefined()
  })
})
