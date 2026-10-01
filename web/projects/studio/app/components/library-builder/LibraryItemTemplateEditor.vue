<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  ITEM_VARIANTS,
  VARIANT_DEFAULTS,
  blockTypeLabel,
  cloneTemplate,
  compatibleVariantsForContainer,
  isVariantCompatible,
  type ItemImageAspect,
  type ItemImagePlacement,
  type ItemTemplate,
  type ItemVariant,
  type TemplateSource,
} from './library-utils'
import type { SelectOption } from '@bosca/ui'

const props = defineProps<{
  parentUiType: string
  template: ItemTemplate
  source: TemplateSource
  hasBindingOverride: boolean
  accent: string
  // When set, the editor always writes to this scope and the Scope picker is
  // hidden. 'collection' — editing a collection's OWN presentation (content
  // view), where there's no single parent binding to override. 'binding' —
  // overriding how ONE item renders within its parent (per-item override). When
  // omitted the user chooses (the drilled / selected-block flows).
  lockedScope?: 'collection' | 'binding'
}>()

const emit = defineEmits<{
  // The editor never mutates `props.template` directly — it emits the full
  // new template. The page owner decides whether to write it to the
  // collection or to the binding override based on `scope`.
  'update-template': [template: ItemTemplate, scope: 'collection' | 'binding']
  'clear-binding-override': []
  'lift-binding-to-collection': [template: ItemTemplate]
}>()

const advancedExpanded = ref(false)

// Editing scope: where the next mutation writes. Default tracks the existing
// state — if a binding override exists, edits go there; otherwise collection.
// The user can flip explicitly when they need to. When scopeLocked, edits
// always target the collection (no binding to override).
const editScope = ref<'collection' | 'binding'>(
  props.lockedScope ?? (props.hasBindingOverride ? 'binding' : 'collection'),
)

const scopeOptions: SelectOption[] = [
  { label: 'This collection', value: 'collection' },
  { label: 'This binding only', value: 'binding' },
]

const sourceLabel = computed(() => {
  switch (props.source) {
    case 'binding-override':    return 'Binding override'
    case 'collection-template': return 'From collection'
    case 'container-default':   return 'Default'
    default:                    return 'Default'
  }
})

const compatibleVariants = computed(() => compatibleVariantsForContainer(props.parentUiType))

// When the parent isn't a known container layout (e.g. editing a
// collection's OWN template directly — its items aren't bound to one
// specific container), there's nothing for the variant to be "compatible"
// with. Every variant is valid and the compat callout is suppressed. We
// detect this as "no compatible variants are defined for this parent type",
// which is exactly the case for non-container / unknown block types.
const isContainerAgnostic = computed(() => compatibleVariants.value.length === 0)

const compatIssue = computed(() => {
  if (isContainerAgnostic.value) return null
  const v = props.template.variant
  if (isVariantCompatible(props.parentUiType, v)) return null
  if (props.template.variantCompatOverride) {
    return {
      kind: 'overridden' as const,
      message: `"${variantLabel(v)}" isn't designed for ${blockTypeLabel(props.parentUiType)} — override active.`,
    }
  }
  return {
    kind: 'blocked' as const,
    message: `"${variantLabel(v)}" isn't compatible with ${blockTypeLabel(props.parentUiType)}.`,
  }
})

function variantLabel(v: ItemVariant): string {
  return ITEM_VARIANTS.find(x => x.value === v)?.label ?? v
}

// Variant picker shows only compatible variants. The "override" path is
// surfaced as a separate button in the compat callout — keeping the
// happy-path dropdown clean. When container-agnostic (editing a collection's
// own template with no specific container to constrain against) every variant
// is offered directly.
const variantOptions = computed<SelectOption[]>(() =>
  ITEM_VARIANTS
    .filter(v => isContainerAgnostic.value || isVariantCompatible(props.parentUiType, v.value))
    .map(v => ({ label: v.label, value: v.value })),
)

const imagePlacementOptions: SelectOption[] = [
  { label: 'Background', value: 'background' },
  { label: 'Top', value: 'top' },
  { label: 'Left', value: 'left' },
  { label: 'None', value: 'none' },
]
const imageAspectOptions: SelectOption[] = [
  { label: 'Square (1:1)', value: '1:1' },
  { label: 'Portrait (3:4)', value: '3:4' },
  { label: 'Landscape (4:3)', value: '4:3' },
  { label: 'Wide (16:9)', value: '16:9' },
]
const titlePlacementOptions: SelectOption[] = [
  { label: 'Overlay', value: 'overlay' },
  { label: 'Below', value: 'below' },
  { label: 'Beside', value: 'beside' },
]
const titleLinesOptions: SelectOption[] = [
  { label: '1 line', value: '1' },
  { label: '2 lines', value: '2' },
  { label: '3 lines', value: '3' },
]
const subtitleSourceOptions: SelectOption[] = [
  { label: 'Subtitle', value: 'subtitle' },
  { label: 'Description', value: 'description' },
  { label: 'Category', value: 'category' },
]
const badgeSourceOptions: SelectOption[] = [
  { label: 'Category', value: 'category' },
  { label: 'Duration', value: 'duration' },
  { label: 'Status', value: 'status' },
]
const progressSourceOptions: SelectOption[] = [
  { label: 'Guide progress', value: 'guide-progress' },
  { label: 'Reading', value: 'reading' },
]
// Kind controls click BEHAVIOR. The visibility of the action label is a
// separate concern (action.show). Items are clickable regardless of
// label visibility — kind always picks one of the two real actions.
const actionKindOptions: SelectOption[] = [
  { label: 'Navigate', value: 'navigate' },
  { label: 'Play', value: 'play' },
]

const allVariantOptions = computed<SelectOption[]>(() =>
  ITEM_VARIANTS.map(v => ({ label: v.label, value: v.value })),
)

// A guarded mutation entry-point: clone, mutate, emit. We don't mutate
// `props.template` because Vue's reactivity bridges template props from the
// parent; in-place mutation would couple the editor to the parent's storage
// (collection vs binding) without going through the page's scope-aware emit.
function patch(mutator: (t: ItemTemplate) => void) {
  const next = cloneTemplate(props.template)
  mutator(next)
  emit('update-template', next, editScope.value)
}

function onScopeChange(v: string | string[] | null | undefined) {
  if (v === 'collection' || v === 'binding') editScope.value = v
}

function onVariantChange(value: string | string[] | null | undefined) {
  if (typeof value !== 'string') return
  const v = value as ItemVariant
  // In container-agnostic mode any variant is valid; otherwise the dropdown
  // only offers compatible variants and we guard against a stale value.
  if (!isContainerAgnostic.value && !isVariantCompatible(props.parentUiType, v)) return
  const next = cloneTemplate(VARIANT_DEFAULTS[v])
  delete next.variantCompatOverride
  emit('update-template', next, editScope.value)
}

// Explicit "I accept the risk" flow for incompatible variants. The user
// picks the variant from a confirm panel; the override flag locks in.
const showRiskPicker = ref(false)
const riskVariant = ref<ItemVariant | null>(null)
function openRiskPicker() {
  riskVariant.value = props.template.variant
  showRiskPicker.value = true
}
function acceptRiskVariant() {
  const v = riskVariant.value
  if (!v) return
  const next = cloneTemplate(VARIANT_DEFAULTS[v])
  next.variantCompatOverride = true
  showRiskPicker.value = false
  riskVariant.value = null
  editScope.value = 'binding'
  emit('update-template', next, 'binding')
}
function cancelRiskPicker() {
  showRiskPicker.value = false
  riskVariant.value = null
}

function onImagePlacement(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.image.placement = v as ItemImagePlacement })
}
function onImageAspect(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.image.aspect = v as ItemImageAspect })
}
function onTitleShow(show: boolean) {
  patch(t => { t.title.show = show })
}
function onTitlePlacement(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.title.placement = v as ItemTemplate['title']['placement'] })
}
function onTitleLines(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  const n = parseInt(v, 10)
  if (n !== 1 && n !== 2 && n !== 3) return
  patch(t => { t.title.lines = n })
}
function onSubtitleShow(show: boolean) {
  patch(t => { t.subtitle.show = show })
}
function onSubtitleSource(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.subtitle.source = v as ItemTemplate['subtitle']['source'] })
}
function onBadgeShow(show: boolean) {
  patch(t => { t.badges.show = show })
}
function onBadgeSource(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.badges.source = v as ItemTemplate['badges']['source'] })
}
function onProgressShow(show: boolean) {
  if (show) {
    // Showing progress requires a non-'none' source; pick a sane default.
    patch(t => {
      t.progress.show = true
      if (t.progress.source === 'none') t.progress.source = 'guide-progress'
    })
  } else {
    patch(t => { t.progress.show = false })
  }
}
function onProgressSource(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.progress.source = v as ItemTemplate['progress']['source'] })
}
function onActionKind(v: string | string[] | null | undefined) {
  if (typeof v !== 'string') return
  patch(t => { t.action.kind = v as ItemTemplate['action']['kind'] })
}
function onActionLabel(v: string) {
  patch(t => { t.action.label = v && v.trim().length > 0 ? v : undefined })
}
// Toggle action LABEL visibility. The kind (click behavior) stays put —
// the item is clickable either way. Flipping off and back on doesn't make
// the user re-type the label.
function onActionShow(show: boolean) {
  patch(t => { t.action.show = show })
}

function clearBindingOverride() {
  emit('clear-binding-override')
}
function liftToCollection() {
  emit('lift-binding-to-collection', cloneTemplate(props.template))
}

</script>

<template>
  <!--
    No outer card, no collapse toggle. In the drilled view this IS the
    primary purpose of the right panel — there's nothing to hide behind. The
    panel itself already has a "PROPERTIES" header; we just lay out our
    sections directly.
  -->
  <div class="lit">
    <div class="lit-head">
      <span class="lit-head-label">Item Presentation</span>
      <span class="lit-head-source" :title="sourceLabel">{{ sourceLabel }}</span>
    </div>

    <div class="lit-body">
      <!-- Compat callout — only when relevant, sits at top so it's not missed. -->
      <div v-if="compatIssue" class="lit-compat" :class="`lit-compat--${compatIssue.kind}`">
        <Icon name="alert" :size="13" color="var(--warn, #e3b341)" />
        <div class="lit-compat-body">
          <div>{{ compatIssue.message }}</div>
          <div v-if="compatIssue.kind === 'blocked'" class="lit-compat-actions">
            <span class="lit-compat-hint">
              Compatible:
              <template v-if="compatibleVariants.length">
                {{ compatibleVariants.map(v => variantLabel(v)).join(', ') }}
              </template>
              <template v-else>none</template>
            </span>
            <Button size="sm" @click="openRiskPicker">Override</Button>
          </div>
        </div>
      </div>

      <!-- Risk picker -->
      <div v-if="showRiskPicker" class="lit-risk-picker">
        <div class="lit-risk-title">Override compatibility</div>
        <p class="lit-risk-body">
          The override saves on this binding only, never on the collection.
        </p>
        <Select
          :model-value="riskVariant ?? ''"
          :options="allVariantOptions"
          label="Variant"
          :accent="accent"
          @update:model-value="(v) => { if (typeof v === 'string') riskVariant = v as ItemVariant }"
        />
        <div class="lit-risk-actions">
          <Button size="sm" @click="cancelRiskPicker">Cancel</Button>
          <Button size="sm" :accent="accent" @click="acceptRiskVariant">Accept</Button>
        </div>
      </div>

      <!--
        Every control is one full-width row: a small label on its own line
        then the input below it. No 2-col grid (the panel is too narrow for
        Select widths). No inline labels (Select's "Wide (16:9)" + chevron
        eats horizontal room and would clip).
      -->
      <div class="lit-field">
        <span class="lit-label">Variant</span>
        <Select
          :model-value="template.variant"
          :options="variantOptions"
          :accent="accent"
          @update:model-value="onVariantChange"
        />
      </div>

      <div class="lit-field">
        <span class="lit-label">Image placement</span>
        <Select
          :model-value="template.image.placement"
          :options="imagePlacementOptions"
          :accent="accent"
          @update:model-value="onImagePlacement"
        />
      </div>

      <div class="lit-field">
        <span class="lit-label">Aspect ratio</span>
        <Select
          :model-value="template.image.aspect"
          :options="imageAspectOptions"
          :accent="accent"
          @update:model-value="onImageAspect"
        />
      </div>

      <!-- Title: switch row + when on, placement & lines below -->
      <div class="lit-switch">
        <span class="lit-label">Title</span>
        <Switch
          :model-value="template.title.show"
          :accent="accent"
          @update:model-value="onTitleShow"
        />
      </div>
      <template v-if="template.title.show">
        <div class="lit-field">
          <span class="lit-label">Title placement</span>
          <Select
            :model-value="template.title.placement"
            :options="titlePlacementOptions"
            :accent="accent"
            @update:model-value="onTitlePlacement"
          />
        </div>
        <div class="lit-field">
          <span class="lit-label">Title lines</span>
          <Select
            :model-value="String(template.title.lines)"
            :options="titleLinesOptions"
            :accent="accent"
            @update:model-value="onTitleLines"
          />
        </div>
      </template>

      <!-- Subtitle: switch row + source below when on -->
      <div class="lit-switch">
        <span class="lit-label">Subtitle</span>
        <Switch
          :model-value="template.subtitle.show"
          :accent="accent"
          @update:model-value="onSubtitleShow"
        />
      </div>
      <div v-if="template.subtitle.show" class="lit-field">
        <span class="lit-label">Subtitle source</span>
        <Select
          :model-value="template.subtitle.source"
          :options="subtitleSourceOptions"
          :accent="accent"
          @update:model-value="onSubtitleSource"
        />
      </div>

      <!--
        Advanced — Badge / Progress / Action. Collapsed by default. The 80%
        case (Variant/Image/Title/Subtitle) sits above this; only power users
        come down here.
      -->
      <button
        class="lit-advanced-toggle"
        type="button"
        @click="advancedExpanded = !advancedExpanded"
      >
        <Icon
          :name="advancedExpanded ? 'chevron-down' : 'chevron-right'"
          :size="11"
          color="var(--fg-3)"
        />
        <span>Advanced</span>
      </button>

      <template v-if="advancedExpanded">
        <div class="lit-switch">
          <span class="lit-label">Badge</span>
          <Switch
            :model-value="template.badges.show"
            :accent="accent"
            @update:model-value="onBadgeShow"
          />
        </div>
        <div v-if="template.badges.show" class="lit-field">
          <span class="lit-label">Badge source</span>
          <Select
            :model-value="template.badges.source"
            :options="badgeSourceOptions"
            :accent="accent"
            @update:model-value="onBadgeSource"
          />
        </div>

        <div class="lit-switch">
          <span class="lit-label">Progress</span>
          <Switch
            :model-value="template.progress.show"
            :accent="accent"
            @update:model-value="onProgressShow"
          />
        </div>
        <div v-if="template.progress.show" class="lit-field">
          <span class="lit-label">Progress source</span>
          <Select
            :model-value="template.progress.source === 'none' ? 'guide-progress' : template.progress.source"
            :options="progressSourceOptions"
            :accent="accent"
            @update:model-value="onProgressSource"
          />
        </div>

        <!-- On click is ALWAYS visible — items are always clickable; this
             says what the click does. Label visibility is a separate
             optional choice below. -->
        <div class="lit-field">
          <span class="lit-label">On click</span>
          <Select
            :model-value="template.action.kind"
            :options="actionKindOptions"
            :accent="accent"
            @update:model-value="onActionKind"
          />
        </div>
        <div class="lit-switch">
          <span class="lit-label">Show label</span>
          <Switch
            :model-value="template.action.show"
            :accent="accent"
            @update:model-value="onActionShow"
          />
        </div>
        <div v-if="template.action.show" class="lit-field">
          <span class="lit-label">Label text</span>
          <TextInput
            :model-value="template.action.label ?? ''"
            placeholder="e.g. Read"
            @update:model-value="onActionLabel"
          />
        </div>
      </template>

      <!--
        Scope — sits at the bottom but flows inline with the rest. No
        bordered footer card; just a divider above and the controls.
        Override actions only appear when a binding-level override exists.
      -->
      <div v-if="!lockedScope || hasBindingOverride" class="lit-scope">
        <div v-if="!lockedScope" class="lit-field">
          <span class="lit-label">Scope</span>
          <Select
            :model-value="editScope"
            :options="scopeOptions"
            :accent="accent"
            @update:model-value="onScopeChange"
          />
        </div>
        <div v-if="hasBindingOverride" class="lit-scope-actions">
          <Button size="sm" @click="clearBindingOverride">Clear override</Button>
          <Button v-if="!lockedScope" size="sm" @click="liftToCollection">Lift to collection</Button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * No card wrapper, no collapse, no 2-col grid. Just a flat stack of
 * label+control fields. The right panel itself provides the framing; we
 * don't need a second border on top of it.
 *
 * One atom — .lit-field — handles every labelled control. One atom —
 * .lit-switch — handles every toggle row. That's it.
 */
.lit {
  display: flex;
  flex-direction: column;
  gap: 12px;
  /* min-width: 0 propagates so Select children can shrink below their
   * intrinsic width and stop overflowing the 300px panel. */
  min-width: 0;
}

.lit-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.lit-head-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}
.lit-head-source {
  font-size: 11px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.lit-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* Field: label above + control below. min-width: 0 so the child Select
 * can clip text instead of pushing the panel wider. */
.lit-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.lit-label {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fg-2);
}

/* Switch row: label on the left, switch on the right. */
.lit-switch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

/* Compat callout */
.lit-compat {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--warn, #e3b341) 8%, transparent);
  border: 1px solid color-mix(in oklch, var(--warn, #e3b341) 25%, transparent);
}
.lit-compat--overridden {
  background: color-mix(in oklch, var(--brand-2, #6b8f71) 6%, transparent);
  border-color: color-mix(in oklch, var(--brand-2, #6b8f71) 25%, transparent);
}
.lit-compat-body { font-size: 12px; color: var(--fg-1); flex: 1; min-width: 0; line-height: 1.4; }
.lit-compat-actions { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.lit-compat-hint { font-size: 11.5px; color: var(--fg-3); flex: 1; min-width: 0; }

.lit-risk-picker {
  padding: 10px;
  border-radius: var(--r-sm);
  background: var(--bg-2);
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lit-risk-title { font-size: 12px; font-weight: 600; color: var(--fg-1); }
.lit-risk-body { font-size: 11.5px; color: var(--fg-2); margin: 0; line-height: 1.4; }
.lit-risk-actions { display: flex; gap: 6px; justify-content: flex-end; }

/* Advanced disclosure — a quiet button between the main fields and the
 * Scope section. Border-top draws the divider without needing a separate
 * element. */
.lit-advanced-toggle {
  all: unset;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0 0;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fg-3);
  border-top: 1px solid var(--line);
}
.lit-advanced-toggle:hover { color: var(--fg-1); }

/* Scope — divider above; flows inline with the rest. */
.lit-scope {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.lit-scope-actions {
  display: flex;
  gap: 6px;
}
</style>
