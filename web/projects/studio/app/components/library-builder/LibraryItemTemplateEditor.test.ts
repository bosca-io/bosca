import { describe, it, expect } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import LibraryItemTemplateEditor from './LibraryItemTemplateEditor.vue'
import {
  ITEM_VARIANTS,
  VARIANT_DEFAULTS,
  cloneTemplate,
  type ItemTemplate,
  type ItemVariant,
  type TemplateSource,
} from './library-utils'

// Minimal stub for the @bosca/ui Select so we can read the options it's given
// and drive its update:model-value. Kept a real component (not auto-stub) so
// findAllComponents can locate it and inspect props.
const SelectStub = defineComponent({
  name: 'Select',
  props: ['modelValue', 'options', 'label', 'accent'],
  emits: ['update:modelValue'],
  template: '<div class="stub-select" />',
})

const ALL_VARIANT_VALUES = ITEM_VARIANTS.map(v => v.value)

function mkTemplate(variant: ItemVariant): ItemTemplate {
  return cloneTemplate(VARIANT_DEFAULTS[variant])
}

function mountEditor(opts: {
  parentUiType: string
  template: ItemTemplate
  source?: TemplateSource
  hasBindingOverride?: boolean
}) {
  return mount(LibraryItemTemplateEditor, {
    props: {
      parentUiType: opts.parentUiType,
      template: opts.template,
      source: opts.source ?? 'collection-template',
      hasBindingOverride: opts.hasBindingOverride ?? false,
      accent: '#6b8f71',
    },
    global: {
      stubs: {
        Select: SelectStub,
        // The rest can be auto-stubbed — we don't inspect them here.
        Switch: true,
        Button: true,
        TextInput: true,
        Icon: true,
      },
    },
  })
}

// The Variant picker is the only Select whose options are item variants.
function variantSelect(wrapper: ReturnType<typeof mountEditor>) {
  const selects = wrapper.findAllComponents(SelectStub)
  const match = selects.find((s) => {
    const options = s.props('options') as Array<{ value: string }> | undefined
    return Array.isArray(options) && options.some(o => o.value === 'tile')
  })
  if (!match) throw new Error('variant Select not found')
  return match
}

describe('LibraryItemTemplateEditor', () => {
  describe('container-constrained (known container type)', () => {
    it('offers only the container-compatible variants for a grid', () => {
      const w = mountEditor({ parentUiType: 'grid', template: mkTemplate('tile') })
      const options = variantSelect(w).props('options') as Array<{ value: string }>
      expect(options.map(o => o.value)).toEqual(['tile', 'card'])
    })

    it('shows a blocked compat callout when the variant is incompatible with the container', () => {
      // detail-row is not compatible with a grid
      const w = mountEditor({ parentUiType: 'grid', template: mkTemplate('detail-row') })
      expect(w.find('.lit-compat--blocked').exists()).toBe(true)
    })

    it('ignores a variant change to an incompatible variant', () => {
      const w = mountEditor({ parentUiType: 'grid', template: mkTemplate('tile') })
      // 'hero-card' isn't compatible with a grid — the guard should drop it.
      variantSelect(w).vm.$emit('update:modelValue', 'hero-card')
      expect(w.emitted('update-template')).toBeUndefined()
    })
  })

  describe('container-agnostic (editing a collection’s own template)', () => {
    // parentUiType has no compatible-variant list — e.g. a navigable
    // sub-collection that isn't itself a container layout.
    it('offers every variant when the parent has no container type', () => {
      const w = mountEditor({ parentUiType: 'unknown', template: mkTemplate('tile') })
      const options = variantSelect(w).props('options') as Array<{ value: string }>
      expect(options.map(o => o.value).sort()).toEqual([...ALL_VARIANT_VALUES].sort())
    })

    it('suppresses the compat callout regardless of the chosen variant', () => {
      const w = mountEditor({ parentUiType: 'unknown', template: mkTemplate('detail-row') })
      expect(w.find('.lit-compat').exists()).toBe(false)
    })

    it('accepts any variant change and emits the new template at collection scope', () => {
      const w = mountEditor({ parentUiType: 'unknown', template: mkTemplate('tile') })
      variantSelect(w).vm.$emit('update:modelValue', 'detail-row')
      const events = w.emitted('update-template')
      expect(events).toHaveLength(1)
      const [template, scope] = events![0] as [ItemTemplate, string]
      expect(template.variant).toBe('detail-row')
      expect(scope).toBe('collection')
    })
  })
})
