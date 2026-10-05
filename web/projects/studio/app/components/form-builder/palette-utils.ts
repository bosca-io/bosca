import type { UiSchemaNode } from '@bosca/forms'

let fieldCounter = 0

export function nextFieldKey() {
  fieldCounter++
  return `field_${fieldCounter}`
}

export function resetFieldCounter() {
  fieldCounter = 0
}

/**
 * Builds a UiSchemaNode from the data attributes on a dragged palette element.
 * Called by LayoutCanvas when it receives a clone from the palette via SortableJS.
 */
export function buildNodeFromElement(el: HTMLElement): UiSchemaNode | null {
  const paletteType = el.getAttribute('data-palette-type')
  if (!paletteType) return null

  if (paletteType === 'field') {
    const control = el.getAttribute('data-control') ?? 'text-input'
    const label = el.getAttribute('data-label') ?? 'Field'
    return {
      type: 'field',
      property: nextFieldKey(),
      control,
      label,
    } as UiSchemaNode
  }

  if (paletteType === 'layout') {
    const nodeType = el.getAttribute('data-node-type')
    if (nodeType === 'section') {
      return { type: 'section', label: 'New Section', children: [] } as UiSchemaNode
    }
    if (nodeType === 'row') {
      return { type: 'row', children: [] } as UiSchemaNode
    }
  }

  if (paletteType === 'display') {
    const control = el.getAttribute('data-control') ?? 'alert'
    return {
      type: 'display',
      control,
      text: control === 'divider' ? undefined : `${el.getAttribute('data-label') ?? 'Display'} text`,
      variant: control === 'alert' ? 'info' : undefined,
    } as UiSchemaNode
  }

  return null
}
