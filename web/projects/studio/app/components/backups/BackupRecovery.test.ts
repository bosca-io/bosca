import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import Recovery from './BackupRecovery.vue'

enableAutoUnmount(afterEach)
const query = vi.fn()
const mutation = vi.fn()
const totals = { total: 3, pending: 1, running: 0, completed: 1, failed: 1, totalBytes: 12, verifiedBytes: 4 }
const run = { key: 'run-1', status: 'PARTIAL', version: 7, attempts: 1,
  path: 'manifests/first.json', created: '2026-10-27T00:00:00Z', modified: '2026-10-27T00:01:00Z',
  error: 'storage unavailable', summary: totals, result: totals }
const stubs = {
  SectionCard: { template: '<section><h2>{{ title }}</h2><slot /></section>', props: ['title'] },
  TextInput: { template: '<label>{{ label }}<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>', props: ['label', 'modelValue'] },
  Select: { template: '<label>{{ label }}<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select></label>', props: ['label', 'modelValue', 'options'] },
  Button: { template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>', props: ['disabled'] },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
  CodeEditor: { template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />', props: ['modelValue'] },
}
function page() { return mount(Recovery, { global: { stubs } }) }
async function field(wrapper: ReturnType<typeof page>, label: string, value: string) {
  await wrapper.findAll('label').find(l => l.text().startsWith(label))!.get('input').setValue(value)
}
async function click(wrapper: ReturnType<typeof page>, label: string) {
  await wrapper.findAll('button').find(b => b.text() === label)!.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  query.mockReset().mockResolvedValue({ backups: { objectRuns: { list: [run], lastSuccessful: { ...run, key: 'older-success', status: 'COMPLETED' } } } })
  mutation.mockReset()
  vi.stubGlobal('useGraphQL', () => ({ query, mutation }))
})

describe('independent backup recovery', () => {
  it('loads an exact scope with offset pagination and shows partial work separately from last success', async () => {
    const wrapper = page()
    expect(query).not.toHaveBeenCalled()
    await field(wrapper, 'Source identity', 'source')
    await field(wrapper, 'Destination identity', 'retained')
    await field(wrapper, 'Scope', 'content')
    await click(wrapper, 'Load object runs')
    expect(query.mock.calls[0]![1]).toEqual({ operation: 'RESTORE', sourceId: 'source', destinationId: 'retained', scope: 'content', offset: 0, limit: 20 })
    expect(wrapper.text()).toContain('storage unavailable')
    expect(wrapper.text()).toContain('1 pending')
    expect(wrapper.text()).toContain('1 failed')
    expect(wrapper.text()).toContain('older-success')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('uses the observed version for retry and does not dispatch it automatically', async () => {
    const wrapper = page()
    await field(wrapper, 'Source identity', 'source')
    await field(wrapper, 'Destination identity', 'recovery')
    await field(wrapper, 'Scope', 'content')
    await click(wrapper, 'Load object runs')
    mutation.mockResolvedValue({ backups: { objectRuns: { retry: { ...run, status: 'PENDING', version: 8 } } } })
    await click(wrapper, 'Retry')
    expect(mutation.mock.calls[0]![1]).toEqual({ key: 'run-1', expectedVersion: 7 })
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('PENDING')
    expect(wrapper.findAll('button').some(b => b.text() === 'Dispatch')).toBe(true)
  })

  it('refreshes one run and acknowledges dispatch without claiming completion', async () => {
    const wrapper = page()
    await field(wrapper, 'Source identity', 'source')
    await field(wrapper, 'Destination identity', 'recovery')
    await field(wrapper, 'Scope', 'content')
    await click(wrapper, 'Load object runs')
    query.mockResolvedValue({ backups: { objectRuns: { run: { ...run, status: 'PENDING', version: 8 } } } })
    await click(wrapper, 'Refresh run')
    expect(query.mock.calls.at(-1)![1]).toEqual({ key: 'run-1' })
    mutation.mockResolvedValue({ backups: { objectRuns: { dispatch: { key: 'dispatch-1' } } } })
    await click(wrapper, 'Dispatch')
    expect(mutation.mock.calls[0]![1]).toEqual({ key: 'run-1', expectedVersion: 8 })
    expect(wrapper.get('[role="status"]').text()).toContain('Queued run-1')
    expect(wrapper.text()).toContain('PENDING')
  })

  it('surfaces API errors and keeps the selected run available', async () => {
    const wrapper = page()
    await field(wrapper, 'Source identity', 'source')
    await field(wrapper, 'Destination identity', 'recovery')
    await field(wrapper, 'Scope', 'content')
    await click(wrapper, 'Load object runs')
    mutation.mockRejectedValue(new Error('Stale run version'))
    await click(wrapper, 'Retry')
    expect(wrapper.get('[role="alert"]').text()).toContain('Stale run version')
    expect(wrapper.text()).toContain('run-1')
    expect(mutation).toHaveBeenCalledTimes(1)
  })

  it('prepares a retained manifest without source credentials or an implicit dispatch', async () => {
    const wrapper = page()
    await field(wrapper, 'Request key', 'restore-2')
    await field(wrapper, 'Restore destination', 'recovery')
    await field(wrapper, 'Backup storage', 'retained')
    await field(wrapper, 'Manifest path', 'manifests/later.json')
    await wrapper.get('textarea').setValue('{"content":"recovered-content"}')
    mutation.mockResolvedValue({ backups: { objectRuns: { prepareRestoreConfigured: { ...run, status: 'PENDING' } } } })
    await click(wrapper, 'Prepare object restore')
    expect(mutation.mock.calls[0]![1]).toEqual({ requestKey: 'restore-2', destinationId: 'recovery', backupKey: 'retained', path: 'manifests/later.json', stores: [{ store: 'content', storageKey: 'recovered-content' }] })
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('PENDING')
  })

  it('rejects an invalid target mapping before making a request', async () => {
    const wrapper = page()
    await field(wrapper, 'Request key', 'restore-2')
    await field(wrapper, 'Restore destination', 'recovery')
    await field(wrapper, 'Backup storage', 'retained')
    await field(wrapper, 'Manifest path', 'manifests/later.json')
    await wrapper.get('textarea').setValue('{"content":42}')
    await click(wrapper, 'Prepare object restore')
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('storage ID')
  })
})
