<script setup lang="ts" generic="T extends Record<string, any> = Record<string, any>">
import { computed } from 'vue'
import Icon from './Icon.vue'
import OverflowMenu from './OverflowMenu.vue'
/**
 * Reusable data table with translucent glass styling and CSS Grid layout.
 * Columns are defined declaratively; cell content is controlled via scoped slots.
 */

export interface GlassTableColumn {
  /** Unique key used to look up values on each row and to name per-column slots */
  key: string
  /** Header label (omit for invisible columns like a trailing arrow) */
  label?: string
  /** CSS grid column size — any valid grid track value */
  width?: string
  /** Apply the muted (secondary) text style */
  muted?: boolean
  /** Right-align the cell content */
  align?: 'left' | 'right' | 'center'
}

export interface OverflowMenuItem {
  id: string
  label: string
  icon?: string
  danger?: boolean
  disabled?: boolean
  separator?: boolean
}

const props = withDefaults(defineProps<{
  columns: GlassTableColumn[]
  rows: T[]
  /** Unique key field on each row object */
  rowKey?: string
  /** Show the trailing chevron arrow per row (ignored when #actions slot or rowActions is provided) */
  arrow?: boolean
  /** Width of the trailing actions/arrow column */
  actionsWidth?: string
  /** Display a loading spinner/message instead of rows */
  loading?: boolean
  /** Loading-state message */
  loadingText?: string
  /** Empty-state message when rows is empty and not loading */
  emptyText?: string
  /** Inner padding for the glass content container (any CSS padding value, e.g. '16px'). Default: none. */
  padding?: string
  /** Per-row overflow menu items. Can be an array or a function that receives the row. */
  rowActions?: OverflowMenuItem[] | ((row: T) => OverflowMenuItem[])
}>(), {
  rowKey: 'id',
  arrow: false,
  actionsWidth: '28px',
  loading: false,
  loadingText: 'Loading…',
  emptyText: 'No items.',
})

const emit = defineEmits<{
  (e: 'row-click', row: T): void
  (e: 'row-action', payload: { action: string; row: T }): void
}>()

function getRowMenuItems(row: T): OverflowMenuItem[] {
  if (!props.rowActions) return []
  if (typeof props.rowActions === 'function') return props.rowActions(row)
  return props.rowActions
}

function onMenuSelect(id: string, row: T) {
  emit('row-action', { action: id, row })
}

const slots = defineSlots<{
  [key: `col-${string}`]: (props: { row: T; value: any }) => any
  [key: `header-${string}`]: (props: { column: GlassTableColumn }) => any
  actions?: (props: { row: T }) => any
}>()

const hasActions = computed(() => !!slots.actions)
const hasRowActions = computed(() => !!props.rowActions)
const showTrailing = computed(() => hasActions.value || hasRowActions.value || props.arrow)

const gridColumns = computed(() => {
  const cols = props.columns.map(c => c.width ?? '1fr')
  if (showTrailing.value) cols.push(props.actionsWidth)
  return cols.join(' ')
})

function cellValue(row: T, key: string): any {
  return row[key]
}

// Header cells are flex containers, where text-align has no effect on the
// label (a flex item) — alignment must go through justify-content.
const JUSTIFY: Record<NonNullable<GlassTableColumn['align']>, string> = {
  left: 'flex-start',
  center: 'center',
  right: 'flex-end',
}

function headerJustify(align?: GlassTableColumn['align']): string | undefined {
  return align ? JUSTIFY[align] : undefined
}
</script>

<template>
  <div class="glass-table" :style="padding ? { padding } : undefined">
    <div class="gt-header" :style="{ gridTemplateColumns: gridColumns }">
      <span
        v-for="col in columns"
        :key="col.key"
        class="gt-header-cell"
        :style="{ justifyContent: headerJustify(col.align) }"
      >
        <slot :name="`header-${col.key}`" :column="col">{{ col.label }}</slot>
      </span>
      <span v-if="showTrailing" class="gt-header-cell" />
    </div>

    <div v-if="loading" class="gt-state">{{ loadingText }}</div>

    <template v-else-if="rows.length > 0">
      <div
        v-for="row in rows"
        :key="String(row[rowKey || ''])"
        class="gt-row"
        :style="{ gridTemplateColumns: gridColumns }"
        @click="emit('row-click', row)"
      >
        <span
          v-for="col in columns"
          :key="col.key"
          class="gt-cell"
          :class="{ 'gt-muted': col.muted }"
          :style="{ textAlign: col.align }"
        >
          <slot :name="`col-${col.key}`" :row="row" :value="cellValue(row, col.key)">
            {{ cellValue(row, col.key) ?? '—' }}
          </slot>
        </span>
        <span v-if="hasActions" class="gt-actions">
          <slot name="actions" :row="row" />
        </span>
        <span v-else-if="hasRowActions" class="gt-actions">
          <OverflowMenu
            :items="getRowMenuItems(row)"
            @select="(id: string) => onMenuSelect(id, row)"
          >
            <template #default="{ toggle }">
              <button class="gt-action-btn" @click.stop="toggle">
                <Icon name="more" :size="16" color="var(--fg-3)" />
              </button>
            </template>
          </OverflowMenu>
        </span>
        <span v-else-if="arrow" class="gt-arrow">
          <Icon name="chevron" :size="12" color="var(--fg-3)" />
        </span>
      </div>
    </template>

    <div v-else class="gt-state">{{ emptyText }}</div>
  </div>
</template>

<style scoped>
.glass-table {
  display: flex;
  flex-direction: column;
  padding-bottom: 6px;
}

.gt-header,
.gt-row {
  display: grid;
  align-items: center;
  gap: 4px;
  padding: 0 16px;
}

.gt-header {
  padding-top: 10px;
  padding-bottom: 10px;
  font-size: 10.5px;
  color: var(--fg-3);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent);
}

.gt-header-cell {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  align-items: center;
}

.gt-row {
  padding-top: 11px;
  padding-bottom: 11px;
  cursor: pointer;
  border-radius: var(--r-xs);
  margin: 1px 6px;
  padding-left: 10px;
  padding-right: 10px;
  transition: background 0.15s ease, box-shadow 0.15s ease;
}

.gt-row:not(:last-of-type) {
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
}

.gt-row:hover {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in oklch, var(--brand-2) 12%, transparent);
  border-bottom-color: transparent;
}

.gt-row:hover + .gt-row {
  border-top-color: transparent;
}

.gt-cell {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.gt-muted {
  color: var(--fg-2);
  font-size: 12px;
}

.gt-arrow,
.gt-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.gt-arrow {
  opacity: 0.4;
}

.gt-row:hover .gt-arrow,
.gt-row:hover .gt-actions {
  opacity: 1;
}

.gt-action-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  transition: background 0.15s;
}

.gt-action-btn:hover {
  background: var(--bg-3);
}

.gt-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}
</style>
