import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import * as Y from 'yjs'
import AttributesDateTimeInput from './AttributesDateTimeInput.vue'
import { AttributeState } from '~/utils/editor/attribute'
import { markYDocSaved, trackYDocChanges } from '~/utils/editor/ydoc'
import type { TemplateAttribute } from '~/types/graphql'

const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach(cleanup => cleanup()))

function openDate(value?: number, editable = true, type = 'DATETIME') {
  const doc = new Y.Doc()
  if (value !== undefined) doc.getMap('dateAttributes').set('date', value)
  const attribute = new AttributeState(doc, 'Metadata', 'item-1', ref({}), ref([]), ref([]), {
    key: 'date', name: 'Date', type, list: false,
  } as TemplateAttribute)
  trackYDocChanges(doc)
  const updates = vi.fn()
  doc.on('update', updates)
  const wrapper = mount(AttributesDateTimeInput, {
    props: { item: null, state: null, attribute, editable, toolsEnabled: false, onRunTool: vi.fn() },
    global: { stubs: { AttributesTitlebar: true } },
  })
  cleanups.push(() => { wrapper.unmount(); attribute.dispose(); doc.destroy() })
  return { doc, wrapper, updates }
}

describe('AttributesDateTimeInput', () => {
  it.each([true, false])('displays a saved date without changing it (editable: %s)', async (editable) => {
    const value = new Date(2026, 10, 11, 14, 32, 45, 123).getTime()
    const { doc, wrapper, updates } = openDate(value, editable)
    await nextTick()

    expect(wrapper.get('input').element.value).toBe('2026-11-11T14:32')
    expect(doc.getMap('dateAttributes').get('date')).toBe(value)
    expect(updates).not.toHaveBeenCalled()
    expect(doc.getText('changes').getAttribute('changes')).not.toBe('true')
  })

  it('displays external date changes without writing them back or losing seconds', async () => {
    const { doc, wrapper, updates } = openDate()
    const collaborator = new Y.Doc()
    cleanups.push(() => collaborator.destroy())
    trackYDocChanges(collaborator)
    Y.applyUpdate(collaborator, Y.encodeStateAsUpdate(doc))
    const value = new Date(2026, 10, 12, 10, 5, 37, 456).getTime()
    collaborator.getMap('dateAttributes').set('date', value)
    markYDocSaved(collaborator)
    Y.applyUpdate(doc, Y.encodeStateAsUpdate(collaborator), 'remote-provider')
    updates.mockClear()
    await nextTick()

    expect(wrapper.get('input').element.value).toBe('2026-11-12T10:05')
    expect(doc.getMap('dateAttributes').get('date')).toBe(value)
    expect(updates).not.toHaveBeenCalled()
    expect(doc.getText('changes').getAttribute('changes')).toBe('false')
  })

  it.each(['DATE', 'DATETIME', 'DATE_TIME'])('opens an empty %s field without creating changes', async (type) => {
    const { doc, wrapper, updates } = openDate(undefined, true, type)
    await nextTick()

    expect(wrapper.get('input').element.value).toBe('')
    expect(doc.getMap('dateAttributes').has('date')).toBe(false)
    expect(updates).not.toHaveBeenCalled()
  })

  it('preserves an existing unsaved marker when opening a date field', async () => {
    const { doc, updates } = openDate(new Date(2026, 10, 11, 14, 32, 45, 123).getTime())
    doc.getText('changes').setAttribute('changes', 'true')
    updates.mockClear()
    await nextTick()

    expect(doc.getText('changes').getAttribute('changes')).toBe('true')
    expect(updates).not.toHaveBeenCalled()
  })

  it('marks user edits and clearing the date as changes', async () => {
    const { doc, wrapper } = openDate()
    await wrapper.get('input').setValue('2026-11-13T09:15')

    expect(doc.getMap('dateAttributes').get('date')).toBe(new Date(2026, 10, 13, 9, 15).getTime())
    expect(doc.getText('changes').getAttribute('changes')).toBe('true')

    markYDocSaved(doc)
    expect(doc.getText('changes').getAttribute('changes')).toBe('false')
    await wrapper.get('input').setValue('')
    expect(doc.getMap('dateAttributes').has('date')).toBe(false)
    expect(doc.getText('changes').getAttribute('changes')).toBe('true')
  })
})
