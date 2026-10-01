import { describe, it, expect } from 'vitest'
import {
  BLOCK_DEFAULTS,
  BLOCK_TYPES,
  DEFAULT_COLLECTION_LAYOUT,
  ITEM_VARIANTS,
  RENDER_AS_ITEM,
  VARIANT_DEFAULTS,
  aspectRatioNumber,
  blockBindsToContent,
  blockCanDrill,
  blockTypeIcon,
  blockTypeLabel,
  buildBlockFromElement,
  buildSyntheticParentPreviewBlock,
  childRenderAsOptions,
  childRenderAsValue,
  cloneTemplate,
  collectionLayoutFromUi,
  collectionLayoutOptions,
  compatibleVariantsForContainer,
  computeBlockHealth,
  containerDefaultTemplate,
  isContainerType,
  isVariantCompatible,
  parseItemTemplate,
  previewContainerType,
  resolveBlockEffectiveTemplate,
  resolveEffectiveTemplate,
  resolveItemRenderPlan,
  isContentCollection,
  mergeUiKey,
  UNKNOWN_UI_TYPE,
  type ItemTemplate,
  type ItemVariant,
} from './library-utils'

describe('library-utils — BLOCK_TYPES invariants', () => {
  it('every type string is unique', () => {
    const seen = new Set<string>()
    for (const b of BLOCK_TYPES) {
      expect(seen.has(b.type), `duplicate block type "${b.type}"`).toBe(false)
      seen.add(b.type)
    }
  })

  it('every icon is unique across block types — duplicates confuse users in the palette', () => {
    const seen = new Map<string, string>()
    for (const b of BLOCK_TYPES) {
      const prior = seen.get(b.icon)
      expect(prior, `icon "${b.icon}" used by both "${prior}" and "${b.type}"`).toBeUndefined()
      seen.set(b.icon, b.type)
    }
  })

  it('every block type has a matching BLOCK_DEFAULTS entry', () => {
    for (const b of BLOCK_TYPES) {
      expect(BLOCK_DEFAULTS[b.type], `missing defaults for "${b.type}"`).toBeDefined()
      expect(BLOCK_DEFAULTS[b.type]!.type).toBe(b.type)
    }
  })

  it('every container is in CONTAINER_COMPAT', () => {
    for (const b of BLOCK_TYPES.filter(b => b.isContainer)) {
      expect(compatibleVariantsForContainer(b.type).length).toBeGreaterThan(0)
    }
  })

  it('non-container types have no compatible variants', () => {
    for (const b of BLOCK_TYPES.filter(b => !b.isContainer)) {
      expect(compatibleVariantsForContainer(b.type)).toEqual([])
    }
  })
})

describe('library-utils — accessors', () => {
  it('isContainerType matches BLOCK_TYPES', () => {
    expect(isContainerType('grid')).toBe(true)
    expect(isContainerType('carousel')).toBe(true)
    expect(isContainerType('stack')).toBe(true)
    expect(isContainerType('featured')).toBe(false)
    expect(isContainerType('banner')).toBe(false)
    expect(isContainerType('section-header')).toBe(false)
    expect(isContainerType('status-row')).toBe(false)
  })

  it('isContainerType is false for unknown types (defensive)', () => {
    expect(isContainerType('not-a-real-type')).toBe(false)
    expect(isContainerType('')).toBe(false)
  })

  it('blockBindsToContent matches BLOCK_TYPES', () => {
    expect(blockBindsToContent('featured')).toBe(true)
    expect(blockBindsToContent('grid')).toBe(true)
    expect(blockBindsToContent('banner')).toBe(false)
    expect(blockBindsToContent('section-header')).toBe(false)
    expect(blockBindsToContent('status-row')).toBe(false)
  })

  it('blockBindsToContent treats unknown as structural (no content picker)', () => {
    expect(blockBindsToContent('unknown')).toBe(false)
  })

  it('blockCanDrill allows drilling into any Collection except structural placeholders', () => {
    // Container collections are drillable.
    expect(blockCanDrill('grid', true)).toBe(true)
    expect(blockCanDrill('carousel', true)).toBe(true)
    // Non-container collections are ALSO drillable — a navigable group/sub-
    // collection must be openable to manage how its own items render, even
    // when it appears as a plain tile inside its parent.
    expect(blockCanDrill('featured', true)).toBe(true)
    expect(blockCanDrill('unknown', true)).toBe(true)
    // Metadata leaves have no children to open into.
    expect(blockCanDrill('grid', false)).toBe(false)
    expect(blockCanDrill('unknown', false)).toBe(false)
    // Structural placeholder blocks are collections only as an implementation
    // detail and have nothing to open into.
    expect(blockCanDrill('banner', true)).toBe(false)
    expect(blockCanDrill('section-header', true)).toBe(false)
    expect(blockCanDrill('status-row', true)).toBe(false)
  })

  it('blockTypeLabel returns label or falls back to type string', () => {
    expect(blockTypeLabel('grid')).toBe('Grid')
    expect(blockTypeLabel('stack')).toBe('Stack')
    expect(blockTypeLabel('hero-rail')).toBe('Hero + Rail')
    expect(blockTypeLabel('unknown-thing')).toBe('unknown-thing')
  })

  it('blockTypeIcon returns icon or falls back to "boxes"', () => {
    expect(blockTypeIcon('grid')).toBe('grid9')
    expect(blockTypeIcon('stack')).toBe('layers')
    expect(blockTypeIcon('unknown')).toBe('boxes')
  })
})

describe('library-utils — buildBlockFromElement', () => {
  it('returns type when data-block-type attribute is set', () => {
    const el = document.createElement('div')
    el.setAttribute('data-block-type', 'grid')
    expect(buildBlockFromElement(el)).toEqual({ type: 'grid' })
  })

  it('returns null when attribute is missing', () => {
    const el = document.createElement('div')
    expect(buildBlockFromElement(el)).toBeNull()
  })
})

describe('library-utils — computeBlockHealth', () => {
  it('warns when uiType is unknown', () => {
    const issues = computeBlockHealth({
      uiType: 'unknown', childCount: 0,
      featuredImageUrl: null, squareImageUrl: null,
    })
    expect(issues).toHaveLength(1)
    expect(issues[0]?.message).toMatch(/no UI configuration/i)
  })

  it('warns when featured has no featured image', () => {
    const issues = computeBlockHealth({
      uiType: 'featured', childCount: 0,
      featuredImageUrl: null, squareImageUrl: null,
    })
    expect(issues.some(i => /Missing featured image/i.test(i.message))).toBe(true)
  })

  it('warns when container has no children', () => {
    for (const containerType of ['grid', 'carousel', 'compact-list', 'detail-list', 'stack', 'masonry', 'hero-rail']) {
      const issues = computeBlockHealth({
        uiType: containerType, childCount: 0,
        featuredImageUrl: null, squareImageUrl: null,
      })
      expect(
        issues.some(i => /Empty container/i.test(i.message)),
        `expected empty-container warning for "${containerType}"`,
      ).toBe(true)
    }
  })

  it('no warnings on healthy featured block', () => {
    const issues = computeBlockHealth({
      uiType: 'featured', childCount: 0,
      featuredImageUrl: '/img.jpg', squareImageUrl: null,
    })
    expect(issues).toEqual([])
  })
})

describe('library-utils — aspectRatioNumber', () => {
  it('maps each aspect token to the correct ratio', () => {
    expect(aspectRatioNumber('1:1')).toBe(1)
    expect(aspectRatioNumber('3:4')).toBe(0.75)
    expect(aspectRatioNumber('4:3')).toBeCloseTo(4 / 3)
    expect(aspectRatioNumber('16:9')).toBeCloseTo(16 / 9)
  })
})

describe('library-utils — ITEM_VARIANTS + VARIANT_DEFAULTS', () => {
  it('every ItemVariant has an ITEM_VARIANTS entry', () => {
    const vs: ItemVariant[] = ['tile', 'card', 'row', 'detail-row', 'hero-card']
    for (const v of vs) {
      expect(ITEM_VARIANTS.find(x => x.value === v), `missing ITEM_VARIANTS entry for "${v}"`).toBeDefined()
    }
  })

  it('every variant has a default template, and its variant field matches its key', () => {
    for (const v of ITEM_VARIANTS) {
      const def = VARIANT_DEFAULTS[v.value]
      expect(def, `missing VARIANT_DEFAULTS for "${v.value}"`).toBeDefined()
      expect(def!.variant).toBe(v.value)
    }
  })

  it('every default has fully populated fields (no partial defaults)', () => {
    for (const [key, def] of Object.entries(VARIANT_DEFAULTS)) {
      expect(def.image, `${key}.image`).toBeDefined()
      expect(def.title, `${key}.title`).toBeDefined()
      expect(def.subtitle, `${key}.subtitle`).toBeDefined()
      expect(def.badges, `${key}.badges`).toBeDefined()
      expect(def.progress, `${key}.progress`).toBeDefined()
      expect(def.action, `${key}.action`).toBeDefined()
    }
  })

  it('no default ships with variantCompatOverride set (override is a per-binding decision)', () => {
    for (const def of Object.values(VARIANT_DEFAULTS)) {
      expect(def.variantCompatOverride).toBeUndefined()
    }
  })

  it('every variant defaults to action.show=false — visible label is opt-in', () => {
    // Implicit "Open →" labels were appearing on every brand-new template
    // because the kind defaulted to 'navigate'. With the show/kind split,
    // kind is always one of two real behaviours and the *label* is the
    // opt-in piece. show must default off.
    for (const [key, def] of Object.entries(VARIANT_DEFAULTS)) {
      expect(def.action.show, `${key} should default to action.show=false`).toBe(false)
    }
  })

  it('every variant defaults action.kind to a real click behavior (no "none")', () => {
    for (const [key, def] of Object.entries(VARIANT_DEFAULTS)) {
      expect(['navigate', 'play'], `${key} action.kind`).toContain(def.action.kind)
    }
  })
})

describe('library-utils — containerDefaultTemplate', () => {
  it('returns a valid template for every container type', () => {
    for (const b of BLOCK_TYPES.filter(b => b.isContainer)) {
      const t = containerDefaultTemplate(b.type)
      expect(t.variant).toBeDefined()
      expect(isVariantCompatible(b.type, t.variant)).toBe(true)
    }
  })

  it('grid defaults to tile', () => {
    expect(containerDefaultTemplate('grid').variant).toBe('tile')
  })

  it('carousel defaults to card', () => {
    expect(containerDefaultTemplate('carousel').variant).toBe('card')
  })

  it('compact-list defaults to row', () => {
    expect(containerDefaultTemplate('compact-list').variant).toBe('row')
  })

  it('detail-list defaults to detail-row', () => {
    expect(containerDefaultTemplate('detail-list').variant).toBe('detail-row')
  })

  it('stack defaults to hero-card', () => {
    expect(containerDefaultTemplate('stack').variant).toBe('hero-card')
  })

  it('unknown container falls back to tile', () => {
    expect(containerDefaultTemplate('not-a-container').variant).toBe('tile')
  })

  it('returns a fresh clone, not a shared reference (mutation safety)', () => {
    const a = containerDefaultTemplate('grid')
    const b = containerDefaultTemplate('grid')
    expect(a).not.toBe(b)
    expect(a.image).not.toBe(b.image)
    a.image.aspect = '16:9'
    expect(b.image.aspect).toBe('1:1')
  })
})

describe('library-utils — cloneTemplate', () => {
  const sample: ItemTemplate = {
    variant: 'card',
    image: { placement: 'top', aspect: '16:9' },
    title: { show: true, placement: 'below', lines: 2 },
    subtitle: { show: true, source: 'subtitle' },
    badges: { show: true, source: 'category' },
    progress: { show: true, source: 'guide-progress' },
    action: { show: true, kind: 'navigate', label: 'Read' },
  }

  it('produces a structurally equal copy', () => {
    const copy = cloneTemplate(sample)
    expect(copy).toEqual(sample)
  })

  it('mutation of clone does NOT affect original', () => {
    const copy = cloneTemplate(sample)
    copy.variant = 'tile'
    copy.image.aspect = '1:1'
    copy.title.show = false
    copy.action.label = 'Open'
    expect(sample.variant).toBe('card')
    expect(sample.image.aspect).toBe('16:9')
    expect(sample.title.show).toBe(true)
    expect(sample.action.label).toBe('Read')
  })

  it('clones every nested object — no shared references', () => {
    const copy = cloneTemplate(sample)
    expect(copy.image).not.toBe(sample.image)
    expect(copy.title).not.toBe(sample.title)
    expect(copy.subtitle).not.toBe(sample.subtitle)
    expect(copy.badges).not.toBe(sample.badges)
    expect(copy.progress).not.toBe(sample.progress)
    expect(copy.action).not.toBe(sample.action)
  })

  it('preserves variantCompatOverride when present', () => {
    const overridden: ItemTemplate = { ...sample, variantCompatOverride: true }
    expect(cloneTemplate(overridden).variantCompatOverride).toBe(true)
  })

  it('omits variantCompatOverride when not present (no extra property)', () => {
    const copy = cloneTemplate(sample)
    expect('variantCompatOverride' in copy).toBe(false)
  })
})

describe('library-utils — parseItemTemplate', () => {
  it('returns null for non-objects', () => {
    expect(parseItemTemplate(null)).toBeNull()
    expect(parseItemTemplate(undefined)).toBeNull()
    expect(parseItemTemplate('string')).toBeNull()
    expect(parseItemTemplate(42)).toBeNull()
    expect(parseItemTemplate(true)).toBeNull()
    expect(parseItemTemplate([])).toBeNull()
  })

  it('returns null when variant is missing or invalid', () => {
    expect(parseItemTemplate({})).toBeNull()
    expect(parseItemTemplate({ variant: 'not-a-variant' })).toBeNull()
    expect(parseItemTemplate({ variant: '' })).toBeNull()
  })

  it('parses a full template round-trip', () => {
    const input = {
      variant: 'card',
      image: { placement: 'top', aspect: '16:9' },
      title: { show: true, placement: 'below', lines: 2 },
      subtitle: { show: true, source: 'subtitle' },
      badges: { show: false, source: 'category' },
      progress: { show: false, source: 'none' },
      action: { kind: 'navigate', label: 'Read' },
    }
    const parsed = parseItemTemplate(input)
    expect(parsed).not.toBeNull()
    expect(parsed!.variant).toBe('card')
    expect(parsed!.image.placement).toBe('top')
    expect(parsed!.image.aspect).toBe('16:9')
    expect(parsed!.action.label).toBe('Read')
  })

  it('substitutes variant defaults for invalid per-field values', () => {
    const input = {
      variant: 'card',
      image: { placement: 'banana', aspect: 'whatever' },
      title: { show: 'yes', placement: '???', lines: 99 },
    }
    const parsed = parseItemTemplate(input)
    expect(parsed).not.toBeNull()
    const defaults = VARIANT_DEFAULTS['card']
    expect(parsed!.image.placement).toBe(defaults.image.placement)
    expect(parsed!.image.aspect).toBe(defaults.image.aspect)
    expect(parsed!.title.show).toBe(defaults.title.show)
    expect(parsed!.title.placement).toBe(defaults.title.placement)
    expect(parsed!.title.lines).toBe(defaults.title.lines)
  })

  it('does not propagate variantCompatOverride unless explicitly true', () => {
    const t = parseItemTemplate({ variant: 'tile' })
    expect(t!.variantCompatOverride).toBeUndefined()

    const t2 = parseItemTemplate({ variant: 'tile', variantCompatOverride: false })
    expect(t2!.variantCompatOverride).toBeUndefined()

    const t3 = parseItemTemplate({ variant: 'tile', variantCompatOverride: 'truthy' })
    expect(t3!.variantCompatOverride).toBeUndefined()

    const t4 = parseItemTemplate({ variant: 'tile', variantCompatOverride: true })
    expect(t4!.variantCompatOverride).toBe(true)
  })

  it('handles every variant', () => {
    const variants: ItemVariant[] = ['tile', 'card', 'row', 'detail-row', 'hero-card']
    for (const v of variants) {
      const parsed = parseItemTemplate({ variant: v })
      expect(parsed, `parse of variant "${v}"`).not.toBeNull()
      expect(parsed!.variant).toBe(v)
    }
  })

  it('preserves action.label when present, omits when missing', () => {
    const a = parseItemTemplate({ variant: 'card', action: { show: true, kind: 'navigate', label: 'Open' } })
    expect(a!.action.label).toBe('Open')

    const b = parseItemTemplate({ variant: 'card', action: { show: true, kind: 'navigate' } })
    expect(b!.action.label).toBeUndefined()
  })

  describe('legacy action migration (old kind="none" semantics)', () => {
    // Pre-refactor schema used action.kind: 'none' | 'navigate' | 'play'.
    // 'none' meant both "no click" and "no label". The new schema splits
    // these; legacy records on disk must read back as the equivalent new
    // shape so existing libraries keep working.

    it('legacy { kind: "none" } → { show: false, kind: "navigate" }', () => {
      const t = parseItemTemplate({ variant: 'card', action: { kind: 'none' } })
      expect(t!.action.show).toBe(false)
      expect(t!.action.kind).toBe('navigate')
    })

    it('legacy { kind: "navigate", label } → { show: true, kind: "navigate", label }', () => {
      const t = parseItemTemplate({ variant: 'card', action: { kind: 'navigate', label: 'Read' } })
      expect(t!.action.show).toBe(true)
      expect(t!.action.kind).toBe('navigate')
      expect(t!.action.label).toBe('Read')
    })

    it('legacy { kind: "play" } → { show: true, kind: "play" }', () => {
      const t = parseItemTemplate({ variant: 'card', action: { kind: 'play' } })
      expect(t!.action.show).toBe(true)
      expect(t!.action.kind).toBe('play')
    })

    it('explicit show: false overrides the legacy kind-derived inference', () => {
      const t = parseItemTemplate({
        variant: 'card',
        action: { show: false, kind: 'navigate', label: 'Read' },
      })
      expect(t!.action.show).toBe(false)
      expect(t!.action.kind).toBe('navigate')
      expect(t!.action.label).toBe('Read')
    })

    it('explicit show: true overrides default false', () => {
      const t = parseItemTemplate({ variant: 'card', action: { show: true, kind: 'play' } })
      expect(t!.action.show).toBe(true)
      expect(t!.action.kind).toBe('play')
    })

    it('invalid kind falls back to variant default kind', () => {
      const t = parseItemTemplate({ variant: 'card', action: { kind: 'banana' } })
      expect(t!.action.kind).toBe('navigate') // card default
    })
  })
})

describe('library-utils — compatibility matrix', () => {
  it('grid is compatible with tile and card only', () => {
    expect(compatibleVariantsForContainer('grid').sort()).toEqual(['card', 'tile'])
    expect(isVariantCompatible('grid', 'tile')).toBe(true)
    expect(isVariantCompatible('grid', 'card')).toBe(true)
    expect(isVariantCompatible('grid', 'row')).toBe(false)
    expect(isVariantCompatible('grid', 'detail-row')).toBe(false)
    expect(isVariantCompatible('grid', 'hero-card')).toBe(false)
  })

  it('compact-list only accepts row', () => {
    expect(compatibleVariantsForContainer('compact-list')).toEqual(['row'])
    expect(isVariantCompatible('compact-list', 'row')).toBe(true)
    expect(isVariantCompatible('compact-list', 'tile')).toBe(false)
    expect(isVariantCompatible('compact-list', 'card')).toBe(false)
  })

  it('detail-list only accepts detail-row', () => {
    expect(compatibleVariantsForContainer('detail-list')).toEqual(['detail-row'])
    expect(isVariantCompatible('detail-list', 'detail-row')).toBe(true)
    expect(isVariantCompatible('detail-list', 'row')).toBe(false)
  })

  it('stack accepts card and hero-card', () => {
    expect(compatibleVariantsForContainer('stack').sort()).toEqual(['card', 'hero-card'])
  })

  it('hero-rail accepts tile, card, and hero-card', () => {
    expect(compatibleVariantsForContainer('hero-rail').sort()).toEqual(['card', 'hero-card', 'tile'])
  })

  it('unknown container returns empty', () => {
    expect(compatibleVariantsForContainer('not-a-container')).toEqual([])
    expect(isVariantCompatible('not-a-container', 'tile')).toBe(false)
  })
})

describe('library-utils — resolveEffectiveTemplate', () => {
  const mkTemplate = (label: string): ItemTemplate => ({
    variant: 'card',
    image: { placement: 'top', aspect: '16:9' },
    title: { show: true, placement: 'below', lines: 2 },
    subtitle: { show: false, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: true, kind: 'navigate', label },
  })

  it('binding override wins when both present', () => {
    const coll = mkTemplate('from-collection')
    const bind = mkTemplate('from-binding')
    const { template, source } = resolveEffectiveTemplate('grid', coll, bind)
    expect(template.action.label).toBe('from-binding')
    expect(source).toBe('binding-override')
  })

  it('collection template used when no binding override', () => {
    const coll = mkTemplate('from-collection')
    const { template, source } = resolveEffectiveTemplate('grid', coll, null)
    expect(template.action.label).toBe('from-collection')
    expect(source).toBe('collection-template')
  })

  it('container default used when neither set', () => {
    const { template, source } = resolveEffectiveTemplate('grid', null, null)
    expect(template.variant).toBe('tile') // grid's default
    expect(source).toBe('container-default')
  })

  it('null binding falls through to collection', () => {
    const coll = mkTemplate('coll')
    const { template, source } = resolveEffectiveTemplate('grid', coll, null)
    expect(template.action.label).toBe('coll')
    expect(source).toBe('collection-template')
  })

  it('returns a defensive clone for the default — mutation never affects another call', () => {
    const a = resolveEffectiveTemplate('grid', null, null).template
    const b = resolveEffectiveTemplate('grid', null, null).template
    a.image.aspect = '16:9'
    expect(b.image.aspect).toBe('1:1')
  })
})

describe('library-utils — resolveBlockEffectiveTemplate (block-shape wrapper)', () => {
  const mkBlockTemplate = (label: string, showTitle: boolean): ItemTemplate => ({
    variant: 'tile',
    image: { placement: 'background', aspect: '1:1' },
    title: { show: showTitle, placement: 'overlay', lines: 2 },
    subtitle: { show: false, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: false, kind: 'navigate' },
    ...(label ? {} : {}),
  })

  it('uses block.collectionItemTemplate when no binding override — toggling Title off must appear in preview', () => {
    // Regression: previewBlocks at root view once passed null instead of
    // block.collectionItemTemplate, so the right-panel Item Presentation
    // edits saved correctly but the preview kept reading container
    // defaults. This test fails if that bug returns.
    const titleOff = mkBlockTemplate('coll', false)
    const result = resolveBlockEffectiveTemplate({
      uiType: 'grid',
      collectionItemTemplate: titleOff,
      bindingItemTemplateOverride: null,
    })
    expect(result.template.title.show).toBe(false)
    expect(result.source).toBe('collection-template')
  })

  it('binding override wins over collection template', () => {
    const result = resolveBlockEffectiveTemplate({
      uiType: 'grid',
      collectionItemTemplate: mkBlockTemplate('coll', true),
      bindingItemTemplateOverride: mkBlockTemplate('binding', false),
    })
    expect(result.template.title.show).toBe(false)
    expect(result.source).toBe('binding-override')
  })

  it('falls back to container default when both null', () => {
    const result = resolveBlockEffectiveTemplate({
      uiType: 'grid',
      collectionItemTemplate: null,
      bindingItemTemplateOverride: null,
    })
    expect(result.source).toBe('container-default')
    expect(result.template.variant).toBe('tile') // grid's default
  })

  it('honors per-variant defaults for different container types', () => {
    expect(resolveBlockEffectiveTemplate({
      uiType: 'carousel', collectionItemTemplate: null, bindingItemTemplateOverride: null,
    }).template.variant).toBe('card')
    expect(resolveBlockEffectiveTemplate({
      uiType: 'compact-list', collectionItemTemplate: null, bindingItemTemplateOverride: null,
    }).template.variant).toBe('row')
    expect(resolveBlockEffectiveTemplate({
      uiType: 'stack', collectionItemTemplate: null, bindingItemTemplateOverride: null,
    }).template.variant).toBe('hero-card')
  })
})

describe('library-utils — previewContainerType', () => {
  it('honours a renderable container type as-is', () => {
    expect(previewContainerType('grid', 'tile')).toBe('grid')
    expect(previewContainerType('carousel', 'card')).toBe('carousel')
    expect(previewContainerType('detail-list', 'detail-row')).toBe('detail-list')
    expect(previewContainerType('stack', 'hero-card')).toBe('stack')
  })

  it('falls back to a variant-appropriate container for non-renderable types', () => {
    // A collection with no container layout of its own (uiType 'unknown')
    // must still render its items through their variant's natural layout.
    expect(previewContainerType('unknown', 'tile')).toBe('grid')
    expect(previewContainerType('unknown', 'card')).toBe('grid')
    expect(previewContainerType('unknown', 'row')).toBe('compact-list')
    expect(previewContainerType('unknown', 'detail-row')).toBe('detail-list')
    expect(previewContainerType('unknown', 'hero-card')).toBe('stack')
  })

  it('NEVER returns a non-container renderable type — it would drop the items', () => {
    // featured/banner/section-header/status-row are single-element / decoration
    // blocks. A content collection that carries one of these as a stray/legacy
    // `ui.type` must still render its items inside a REAL multi-item container,
    // or every item is silently dropped (the "one bar, no items" bug).
    for (const stray of ['featured', 'banner', 'section-header', 'status-row']) {
      expect(previewContainerType(stray, 'tile'), `${stray}+tile`).toBe('grid')
      expect(previewContainerType(stray, 'row'), `${stray}+row`).toBe('compact-list')
      expect(previewContainerType(stray, 'hero-card'), `${stray}+hero-card`).toBe('stack')
    }
  })

  it('every item variant maps to a renderable container', () => {
    for (const v of ITEM_VARIANTS) {
      const c = previewContainerType('unknown', v.value)
      // The result must itself be a real, renderable container type.
      expect(['grid', 'compact-list', 'detail-list', 'stack', 'carousel', 'masonry', 'hero-rail']).toContain(c)
    }
  })
})

describe('library-utils — buildSyntheticParentPreviewBlock', () => {
  const mkChildren = () => [
    { id: 'a', name: 'A', imageUrl: null, isCollection: true },
    { id: 'b', name: 'B', imageUrl: '/b.jpg', isCollection: false },
  ]

  const template = containerDefaultTemplate('grid')

  it('produces a block carrying the parent uiType + collection name', () => {
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'grid-id',
      parentUiType: 'grid',
      parentUiConfig: { columns: 3 },
      collectionName: 'Featured',
      children: mkChildren(),
      effectiveTemplate: template,
    })
    expect(block.id).toBe('grid-id')
    expect(block.uiType).toBe('grid')
    expect(block.name).toBe('Featured')
    expect(block.children).toHaveLength(2)
  })

  it('remaps a non-container parent (unknown) to a variant-appropriate layout so items render', () => {
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'men-id',
      parentUiType: 'unknown',
      parentUiConfig: {},
      collectionName: 'Men',
      children: mkChildren(),
      effectiveTemplate: containerDefaultTemplate('detail-list'), // variant: detail-row
    })
    // 'unknown' isn't renderable; detail-row's natural layout is detail-list.
    expect(block.uiType).toBe('detail-list')
  })

  it('clones uiConfig — mutating the synthetic block does not mutate the snapshot', () => {
    const snapshot = { columns: 3, fullBleed: true }
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'x',
      parentUiType: 'stack',
      parentUiConfig: snapshot,
      collectionName: 'n',
      children: [],
      effectiveTemplate: template,
    })
    block.uiConfig.columns = 9
    expect(snapshot.columns).toBe(3)
  })

  it('passes the effective template through unchanged', () => {
    const t: ItemTemplate = cloneTemplate(VARIANT_DEFAULTS['card'])
    t.action.label = 'Read'
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'x',
      parentUiType: 'carousel',
      parentUiConfig: {},
      collectionName: 'n',
      children: [],
      effectiveTemplate: t,
    })
    expect(block.itemTemplate).toBe(t) // caller owns the template's lifetime
  })

  it('never sets featuredImageUrl on the synthetic block', () => {
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'x',
      parentUiType: 'grid',
      parentUiConfig: {},
      collectionName: 'n',
      children: [],
      effectiveTemplate: template,
    })
    expect(block.featuredImageUrl).toBeNull()
  })

  it('preserves child order', () => {
    const children = [
      { id: '1', name: 'First', imageUrl: null, isCollection: true },
      { id: '2', name: 'Second', imageUrl: null, isCollection: false },
      { id: '3', name: 'Third', imageUrl: null, isCollection: true },
    ]
    const block = buildSyntheticParentPreviewBlock({
      parentBindingBlockId: 'x',
      parentUiType: 'grid',
      parentUiConfig: {},
      collectionName: 'n',
      children,
      effectiveTemplate: template,
    })
    expect(block.children.map(c => c.id)).toEqual(['1', '2', '3'])
  })
})

describe('library-utils — resolveEffectiveTemplate clones on every path', () => {
  it('returns a fresh clone of a binding override (never the same reference)', () => {
    const override = cloneTemplate(VARIANT_DEFAULTS.card)
    const { template, source } = resolveEffectiveTemplate('grid', null, override)
    expect(source).toBe('binding-override')
    expect(template).not.toBe(override)
    expect(template).toEqual(override)
    // Mutating the result must not corrupt the input (which aliases reactive state).
    template.title.show = !template.title.show
    expect(override.title.show).not.toBe(template.title.show)
  })

  it('returns a fresh clone of a collection template (never the same reference)', () => {
    const coll = cloneTemplate(VARIANT_DEFAULTS.tile)
    const { template, source } = resolveEffectiveTemplate('grid', coll, null)
    expect(source).toBe('collection-template')
    expect(template).not.toBe(coll)
    expect(template).toEqual(coll)
  })

  it('binding override wins over collection template', () => {
    const coll = cloneTemplate(VARIANT_DEFAULTS.tile)
    const override = cloneTemplate(VARIANT_DEFAULTS.card)
    const { template, source } = resolveEffectiveTemplate('grid', coll, override)
    expect(source).toBe('binding-override')
    expect(template.variant).toBe('card')
  })
})

describe('library-utils — resolveItemRenderPlan', () => {
  it('pairs a non-container collection with a variant-appropriate container', () => {
    const plan = resolveItemRenderPlan(UNKNOWN_UI_TYPE, cloneTemplate(VARIANT_DEFAULTS['detail-row']), null)
    expect(plan.container).toBe('detail-list')
    expect(plan.template.variant).toBe('detail-row')
    expect(plan.source).toBe('collection-template')
  })

  it('honours a real container type as the container', () => {
    const plan = resolveItemRenderPlan('grid', null, null)
    expect(plan.container).toBe('grid')
    expect(plan.source).toBe('container-default')
  })

  it('applies the binding override and reflects it in the container choice', () => {
    const override = cloneTemplate(VARIANT_DEFAULTS.row)
    const plan = resolveItemRenderPlan(UNKNOWN_UI_TYPE, null, override)
    expect(plan.source).toBe('binding-override')
    expect(plan.template.variant).toBe('row')
    expect(plan.container).toBe('compact-list')
  })

  it('returns a cloned template (mutation-safe)', () => {
    const override = cloneTemplate(VARIANT_DEFAULTS.card)
    const plan = resolveItemRenderPlan('grid', null, override)
    expect(plan.template).not.toBe(override)
  })
})

describe('library-utils — isContentCollection', () => {
  const item = (uiType = UNKNOWN_UI_TYPE, isCollection = true, childCount = 3) => ({ uiType, isCollection, childCount })

  it('is true when every child is a content item', () => {
    expect(isContentCollection([item(), item()])).toBe(true)
  })
  it('treats an empty collection as content (an empty item list)', () => {
    expect(isContentCollection([])).toBe(true)
  })
  it('treats a child overridden to a container as content, not a page', () => {
    // A child rendered as a grid/carousel is a content item expanded inline; it
    // does NOT turn the collection into a designed page.
    expect(isContentCollection([item(), item('grid'), item('carousel')])).toBe(true)
  })
  it('is a page when a structural placeholder block is present', () => {
    expect(isContentCollection([item(), { uiType: 'banner', isCollection: true, childCount: 0 }])).toBe(false)
    expect(isContentCollection([{ uiType: 'section-header', isCollection: true, childCount: 0 }])).toBe(false)
    expect(isContentCollection([{ uiType: 'status-row', isCollection: true, childCount: 0 }])).toBe(false)
  })
  it('ignores a stray structural type on a real collection-with-children', () => {
    // A mis-set 'section-header' on a collection that actually has its own items
    // is still content — classify by what the child IS, not the stray value.
    expect(isContentCollection([item('section-header', true, 26), item()])).toBe(true)
  })
  it('treats featured as a content item, not a page block', () => {
    expect(isContentCollection([item('featured', false, 0)])).toBe(true)
  })
})

describe('library-utils — collection layout + render-as', () => {
  it('DEFAULT_COLLECTION_LAYOUT is a real container (a vertical list)', () => {
    expect(DEFAULT_COLLECTION_LAYOUT).toBe('compact-list')
    expect(isContainerType(DEFAULT_COLLECTION_LAYOUT)).toBe(true)
  })

  it('collectionLayoutFromUi reads a valid container type', () => {
    expect(collectionLayoutFromUi({ type: 'grid' })).toBe('grid')
    expect(collectionLayoutFromUi({ type: 'carousel', itemTemplate: {} })).toBe('carousel')
  })

  it('collectionLayoutFromUi falls back to the default for missing/invalid/non-container', () => {
    expect(collectionLayoutFromUi(null)).toBe(DEFAULT_COLLECTION_LAYOUT)
    expect(collectionLayoutFromUi(undefined)).toBe(DEFAULT_COLLECTION_LAYOUT)
    expect(collectionLayoutFromUi({})).toBe(DEFAULT_COLLECTION_LAYOUT)
    expect(collectionLayoutFromUi({ type: 'featured' })).toBe(DEFAULT_COLLECTION_LAYOUT)
    expect(collectionLayoutFromUi({ type: 'nonsense' })).toBe(DEFAULT_COLLECTION_LAYOUT)
  })

  it('collectionLayoutOptions are exactly the container block types', () => {
    const containers = BLOCK_TYPES.filter((b) => b.isContainer).map((b) => b.type).sort()
    expect(collectionLayoutOptions().map((o) => o.value).sort()).toEqual(containers)
  })

  it('childRenderAsValue maps containers to themselves, everything else to "item"', () => {
    expect(childRenderAsValue('carousel')).toBe('carousel')
    expect(childRenderAsValue('grid')).toBe('grid')
    expect(childRenderAsValue(UNKNOWN_UI_TYPE)).toBe(RENDER_AS_ITEM)
    expect(childRenderAsValue('section-header')).toBe(RENDER_AS_ITEM)
    expect(childRenderAsValue('featured')).toBe(RENDER_AS_ITEM)
  })

  it('childRenderAsOptions lead with Item, then the container layouts', () => {
    const opts = childRenderAsOptions()
    expect(opts[0]).toEqual({ value: RENDER_AS_ITEM, label: 'Item' })
    expect(opts.slice(1).map((o) => o.value).sort()).toEqual(collectionLayoutOptions().map((o) => o.value).sort())
  })
})

describe('library-utils — mergeUiKey (full-ui read-modify-write)', () => {
  it('sets a key while preserving all sibling keys', () => {
    const ui = { type: 'grid', columns: 4, label: 'Top' }
    const next = mergeUiKey(ui, 'itemTemplateOverride', { variant: 'card' })
    expect(next).toEqual({ type: 'grid', columns: 4, label: 'Top', itemTemplateOverride: { variant: 'card' } })
  })
  it('removes the key when value is null (clears it), preserving siblings', () => {
    const ui = { type: 'grid', columns: 4, itemTemplateOverride: { variant: 'card' } }
    const next = mergeUiKey(ui, 'itemTemplateOverride', null)
    expect(next).toEqual({ type: 'grid', columns: 4 })
    expect('itemTemplateOverride' in next).toBe(false)
  })
  it('does not mutate the input and tolerates null/undefined ui', () => {
    const ui = { a: 1 }
    const next = mergeUiKey(ui, 'b', 2)
    expect(ui).toEqual({ a: 1 })
    expect(next).toEqual({ a: 1, b: 2 })
    expect(mergeUiKey(null, 'x', 1)).toEqual({ x: 1 })
    expect(mergeUiKey(undefined, 'x', null)).toEqual({})
  })
})
