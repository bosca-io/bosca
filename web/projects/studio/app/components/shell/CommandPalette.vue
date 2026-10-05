<script setup lang="ts">
import { SUBSYSTEMS } from '~/composables/useSubsystems'
import type { OmniSearchHit, OmniSearchKind } from '~/composables/useOmniSearch'

const { visibleSubsystems, visibleSubsystemIds } = usePersonas()
const {
  content: omniContent,
  people: omniPeople,
  workops: omniWorkops,
  searching,
  failedSources,
  search: omniSearch,
  clear: clearOmni,
} = useOmniSearch()

const props = defineProps<{
  open: boolean
  subsystem: string
}>()

const emit = defineEmits<{
  close: []
  jump: [sub: string, view: string]
  navigate: [path: string]
}>()

const q = ref('')
const cursor = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

interface PaletteRow {
  kind: string
  icon?: string
  label: string
  hint?: string
  sub?: string
  view?: string
  path?: string
  accent?: string
  color?: string
}

// Quick actions deep-link into the owning page: `?new=1` pages open their
// create dialog via useCreateFromQuery; the rest have dedicated /new routes.
const QUICK_ACTIONS = [
  { sub: 'cms',         icon: 'file',      label: 'New document',   path: '/cms/documents?new=1' },
  { sub: 'cms',         icon: 'book',      label: 'New guide',      path: '/cms/guides?new=1' },
  { sub: 'cms',         icon: 'database',  label: 'New data',       path: '/cms/data?new=1' },
  { sub: 'cms',         icon: 'boxes',     label: 'New collection', path: '/cms/collections?new=1' },
  { sub: 'workops',     icon: 'check',     label: 'New task',       path: '/workops/tasks?new=1' },
  { sub: 'workops',     icon: 'file-text', label: 'New spec',       path: '/workops/specs?new=1' },
  { sub: 'workops',     icon: 'kanban',    label: 'New project',    path: '/workops/projects?new=1' },
  { sub: 'calendar',    icon: 'calendar',  label: 'New event',      path: '/calendar?new=1' },
  { sub: 'forms',       icon: 'form',      label: 'New form',       path: '/forms/builder/new' },
  { sub: 'experiments', icon: 'flask',     label: 'New experiment', path: '/experiments/exp?new=1' },
  { sub: 'experiments', icon: 'flag',      label: 'New flag',       path: '/experiments/flags?new=1' },
  { sub: 'audience',    icon: 'segment',   label: 'New segment',    path: '/audience/segments?new=1' },
  { sub: 'communications', icon: 'megaphone', label: 'New campaign', path: '/communications/campaigns/new' },
  { sub: 'analytics',   icon: 'dashboard', label: 'New dashboard',  path: '/analytics/dashboards?new=1' },
  { sub: 'analytics',   icon: 'database',  label: 'New query',      path: '/analytics/queries/new' },
  { sub: 'git',         icon: 'merge',     label: 'New repository', path: '/git/repositories?new=1' },
  { sub: 'communications',   icon: 'message',   label: 'New channel',    path: '/communications/channels?new=1' },
  { sub: 'ai',          icon: 'ai',        label: 'New agent',      path: '/ai/agents/new' },
  { sub: 'pipelines',   icon: 'workflow',  label: 'New pipeline',   path: '/pipelines/new' },
  { sub: 'scripts',     icon: 'braces',    label: 'New script',     path: '/scripts/new' },
]

// Build full flat item list once
const items = computed(() => {
  const out: PaletteRow[] = []

  // Quick actions — scoped to the user's personas like the nav items below
  for (const a of QUICK_ACTIONS) {
    if (canAccess(a.sub)) {
      out.push({
        kind: 'action',
        icon: a.icon,
        label: a.label,
        hint: SUBSYSTEMS.find(s => s.id === a.sub)?.label ?? a.sub,
        path: a.path,
        accent: subAccent(a.sub),
      })
    }
  }

  // The subsystems the user can actually see — persona/admin scoped and with
  // feature-disabled modules (e.g. commerce when ecommerce is off) removed.
  // Mirrors the sidebar logic in layouts/default.vue.
  const subs = visibleSubsystems.value

  // Navigation — every nav item across every subsystem
  for (const s of subs) {
    for (const g of s.nav) {
      for (const it of g.items) {
        out.push({ kind: 'nav', icon: it.icon, label: it.label, hint: `${s.label} · ${g.group}`, sub: s.id, view: it.id, accent: s.accent })
      }
    }
  }

  return out
})

// Mirrors the persona scoping applied to nav items above: hide search groups
// for subsystems the user can't enter (persona-scoped, feature-disabled removed).
// The backend enforces real permissions.
function canAccess(sub: string) {
  return visibleSubsystemIds.value.has(sub)
}

const KIND_ICONS: Record<OmniSearchKind, string> = {
  metadata: 'file',
  collection: 'boxes',
  profile: 'users',
  task: 'check',
  spec: 'file-text',
}

function subAccent(id: string) {
  return SUBSYSTEMS.find(s => s.id === id)?.accent
}

function toContentRow(hit: OmniSearchHit): PaletteRow {
  return {
    kind: 'content',
    icon: KIND_ICONS[hit.kind],
    label: hit.label,
    hint: hit.hint,
    path: hit.path,
    accent: subAccent('cms'),
  }
}

function toPersonRow(hit: OmniSearchHit): PaletteRow {
  return {
    kind: 'person',
    label: hit.label,
    hint: hit.hint,
    path: hit.path,
    color: subAccent('audience'),
  }
}

function toWorkOpsRow(hit: OmniSearchHit): PaletteRow {
  return {
    kind: 'workops',
    icon: KIND_ICONS[hit.kind],
    label: hit.label,
    hint: hit.hint,
    path: hit.path,
    accent: subAccent('workops'),
  }
}

interface PaletteGroup {
  group: string
  rows: PaletteRow[]
  /** Render rows as a tile grid (the empty-query quick actions). */
  grid?: boolean
}

const filtered = computed<PaletteGroup[]>(() => {
  if (!q.value.trim()) {
    const sysItems = items.value.filter(i => i.kind === 'nav' && i.sub === props.subsystem)
    const actions  = items.value.filter(i => i.kind === 'action')
    const otherNav = items.value.filter(i => i.kind === 'nav' && i.sub !== props.subsystem).slice(0, 6)
    return [
      { group: 'Quick actions',        rows: actions, grid: true },
      { group: 'Go to · this subsystem', rows: sysItems },
      { group: 'Go to · other',        rows: otherNav },
    ].filter(g => g.rows.length > 0)
  }

  const needle = q.value.toLowerCase()
  const matches = items.value.filter(i =>
    i.label.toLowerCase().includes(needle) ||
    (i.hint ?? '').toLowerCase().includes(needle),
  )
  // Remote rows are already relevance-ranked by the backend — don't re-filter.
  return [
    { group: 'Actions',  rows: matches.filter(m => m.kind === 'action') },
    { group: 'Navigate', rows: matches.filter(m => m.kind === 'nav') },
    { group: 'Content',  rows: canAccess('cms') ? omniContent.value.map(toContentRow) : [] },
    { group: 'People',   rows: canAccess('audience') ? omniPeople.value.map(toPersonRow) : [] },
    { group: 'Work Ops', rows: canAccess('workops') ? omniWorkops.value.map(toWorkOpsRow) : [] },
  ].filter(g => g.rows.length > 0)
})

const flat = computed(() => filtered.value.flatMap(g => g.rows))

// Reset + focus when opened
watch(() => props.open, (val) => {
  if (val) {
    q.value = ''
    cursor.value = 0
    setTimeout(() => inputRef.value?.focus(), 30)
  }
})

const MIN_QUERY_LENGTH = 2
const SEARCH_DEBOUNCE_MS = 250
let searchTimeout: ReturnType<typeof setTimeout> | null = null

// Reset cursor + kick off a debounced omni-search on query change
watch(q, (val) => {
  cursor.value = 0
  if (searchTimeout) clearTimeout(searchTimeout)
  const trimmed = val.trim()
  if (trimmed.length < MIN_QUERY_LENGTH) {
    clearOmni()
    return
  }
  searchTimeout = setTimeout(() => {
    void omniSearch(trimmed, {
      entities: canAccess('cms') || canAccess('audience'),
      workops: canAccess('workops'),
    })
  }, SEARCH_DEBOUNCE_MS)
})

function activate(it: typeof flat.value[number]) {
  if (it.path) {
    emit('navigate', it.path)
  } else if (it.sub) {
    emit('jump', it.sub, it.view ?? '')
  } else {
    emit('close')
  }
}

const GRID_COLS = 4

// The grid group is always first when present, so its rows occupy flat
// indices [0, gridCount). Inside it, Up/Down move by visual row and
// Left/Right by tile; past its last row, Down drops into the list below.
const gridCount = computed(() => filtered.value.find(g => g.grid)?.rows.length ?? 0)

function onKey(e: KeyboardEvent) {
  if (!props.open) return
  const inGrid = cursor.value < gridCount.value
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (inGrid) {
      const next = cursor.value + GRID_COLS
      cursor.value = next < gridCount.value
        ? next
        : Math.min(gridCount.value, flat.value.length - 1)
    } else {
      cursor.value = Math.min(flat.value.length - 1, cursor.value + 1)
    }
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (inGrid) {
      const next = cursor.value - GRID_COLS
      if (next >= 0) cursor.value = next
    } else {
      cursor.value = Math.max(0, cursor.value - 1)
    }
  } else if (e.key === 'ArrowRight' && inGrid) {
    e.preventDefault()
    cursor.value = Math.min(gridCount.value - 1, cursor.value + 1)
  } else if (e.key === 'ArrowLeft' && inGrid) {
    e.preventDefault()
    cursor.value = Math.max(0, cursor.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const it = flat.value[cursor.value]
    if (it) activate(it)
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  if (searchTimeout) clearTimeout(searchTimeout)
})

function personInitials(name: string) {
  return name.split(' ').map((s: string) => s[0]).slice(0, 2).join('')
}

// Accumulate starting flat index for each group
function groupStartIdx(gi: number) {
  return filtered.value.slice(0, gi).reduce((acc, g) => acc + g.rows.length, 0)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="palette-overlay" @click="emit('close')">
      <div class="palette-panel" @click.stop>
        <!-- Search input row -->
        <div class="palette-search-row">
          <Icon name="search" :size="16" color="var(--fg-3)" />
          <input
            ref="inputRef"
            v-model="q"
            placeholder="Search anything — content, people, pages, actions…"
            class="palette-search-input"
          >
          <span class="palette-esc-key mono">esc</span>
        </div>

        <!-- Results -->
        <div class="palette-results">
          <div v-if="filtered.length === 0 && searching" class="palette-no-results">
            Searching…
          </div>
          <div v-else-if="filtered.length === 0" class="palette-no-results">
            No results for <span class="palette-no-results-query">"{{ q }}"</span>
          </div>

          <div
            v-for="(g, gi) in filtered"
            :key="g.group"
            class="palette-group"
          >
            <div class="palette-group-label">{{ g.group }}</div>

            <div v-if="g.grid" class="palette-action-grid">
              <button
                v-for="(it, ri) in g.rows"
                :key="`${g.group}-${ri}`"
                class="palette-tile"
                :title="it.hint"
                :style="{
                  background: (groupStartIdx(gi) + ri) === cursor
                    ? 'color-mix(in oklch, var(--fg-2) 12%, transparent)'
                    : 'transparent',
                }"
                @mouseenter="cursor = groupStartIdx(gi) + ri"
                @click="activate(it)"
              >
                <span
                  class="palette-tile-icon"
                  :style="{
                    background: it.accent
                      ? `color-mix(in oklch, ${it.accent} 18%, transparent)`
                      : 'var(--bg-3)',
                  }"
                >
                  <Icon :name="it.icon ?? 'plus'" :size="15" :color="it.accent ?? 'var(--fg-2)'" />
                </span>
                <span class="palette-tile-label">{{ it.label }}</span>
              </button>
            </div>

            <button
              v-for="(it, ri) in g.grid ? [] : g.rows"
              :key="`${g.group}-${ri}`"
              class="palette-item"
              :style="{
                background: (groupStartIdx(gi) + ri) === cursor
                  ? 'color-mix(in oklch, var(--fg-2) 12%, transparent)'
                  : 'transparent',
              }"
              @mouseenter="cursor = groupStartIdx(gi) + ri"
              @click="activate(it)"
            >
              <!-- Person avatar -->
              <span
                v-if="it.kind === 'person'"
                class="palette-person-avatar"
                :style="{ background: it.color }"
              >{{ personInitials(it.label) }}</span>
              <!-- Icon badge -->
              <span
                v-else
                class="palette-icon-badge"
                :style="{
                  background: it.accent
                    ? `color-mix(in oklch, ${it.accent} 18%, transparent)`
                    : 'var(--bg-3)',
                }"
              >
                <Icon :name="it.icon ?? 'file'" :size="13" :color="it.accent ?? 'var(--fg-2)'" />
              </span>

              <span class="palette-item-label">{{ it.label }}</span>
              <span class="palette-item-hint">{{ it.hint }}</span>
              <span
                v-if="(groupStartIdx(gi) + ri) === cursor"
                class="palette-item-enter mono"
              >↵</span>
            </button>
          </div>
        </div>

        <!-- Footer -->
        <div class="palette-footer">
          <span class="palette-footer-action">
            <span class="palette-kbd mono">↑↓</span>
            navigate
          </span>
          <span class="palette-footer-action">
            <span class="palette-kbd mono">↵</span>
            open
          </span>
          <span class="palette-footer-action">
            <span class="palette-kbd mono">esc</span>
            close
          </span>
          <span
            v-if="failedSources.length > 0"
            class="palette-source-warning"
          >Some search sources are unavailable</span>
          <span class="spacer" />
          <span v-if="searching" class="mono">Searching…</span>
          <span v-else class="mono">{{ flat.length }} result{{ flat.length === 1 ? '' : 's' }}</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(8, 12, 14, 0.55);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 12vh;
}

.palette-panel {
  width: min(640px, 94vw);
  background: var(--bg-1);
  border: 1px solid color-mix(in oklch, var(--line) 80%, transparent);
  border-radius: 14px;
  box-shadow: 0 30px 80px -20px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.02) inset;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 70vh;
}

.palette-search-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 55%, transparent);
}

.palette-search-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  font-size: 15px;
  color: var(--fg-0);
  font-family: inherit;
  letter-spacing: -0.005em;
}

.palette-esc-key {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--fg-3);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.palette-results {
  flex: 1;
  overflow: auto;
  padding: 6px 0;
}

.palette-no-results {
  padding: 40px 20px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.palette-no-results-query {
  color: var(--fg-1);
  font-weight: 500;
}

.palette-group {
  padding: 6px 0;
}

.palette-group-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: .12em;
  font-weight: 700;
  padding: 6px 16px 4px;
}

.palette-action-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  padding: 0 12px;
}

.palette-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 8px 10px;
  border-radius: 10px;
  border: 1px solid color-mix(in oklch, var(--line) 45%, transparent);
  color: var(--fg-1);
  font-size: 11.5px;
  text-align: center;
  min-width: 0;
}

.palette-tile-icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.palette-tile-label {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-item {
  width: calc(100% - 12px);
  margin: 0 6px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  color: var(--fg-1);
  font-size: 13px;
  text-align: left;
}

.palette-person-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  color: #fff;
  flex: 0 0 22px;
}

.palette-icon-badge {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 22px;
}

.palette-item-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-item-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  white-space: nowrap;
}

.palette-item-enter {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--fg-2);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.palette-footer {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 14px;
  border-top: 1px solid color-mix(in oklch, var(--line) 55%, transparent);
  font-size: 11px;
  color: var(--fg-3);
}

.palette-footer-action {
  display: flex;
  align-items: center;
  gap: 5px;
}

.palette-kbd {
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.palette-source-warning {
  color: #ffb547;
}

.spacer {
  flex: 1;
}
</style>
