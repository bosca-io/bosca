<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'
import { groupSections, type DocSection } from '~/composables/useSections'

const { sections, currentSection, hueShift } = useSections()
const { isDark, toggle: toggleTheme } = useTheme()
const { isAuthenticated, auth } = useAuth()

// SSR renders with empty auth state (skipInitOnServer), so gate the button
// behind mount to avoid a hydration mismatch.
const mounted = ref(false)
onMounted(() => {
  mounted.value = true
})
const showSignOut = computed(() => mounted.value && isAuthenticated.value)

async function signOut() {
  await auth.signOut()
  await navigateTo('/', { external: true })
}
const navOpen = inject<Ref<boolean>>('navOpen')!
const sidebarOpen = inject<Ref<boolean>>('sidebarOpen')!
const hoveredSection = ref<string | null>(null)

const query = ref('')
const cursor = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

function matchesQuery(s: DocSection): boolean {
  const q = query.value.trim().toLowerCase()
  if (!q) return true
  return s.label.toLowerCase().includes(q) || s.sub.toLowerCase().includes(q)
}

interface Block {
  id: string
  label: string
  items: DocSection[]
}

const blocks = computed<Block[]>(() => {
  const out: Block[] = []
  for (const g of groupSections(sections)) {
    const items = g.items.filter(matchesQuery)
    if (items.length > 0) out.push({ id: g.id, label: g.label, items })
  }
  return out
})

// Flat, display-ordered list used for keyboard navigation.
const flatSections = computed<DocSection[]>(() => blocks.value.flatMap(b => b.items))

function blockStartIndex(blockIndex: number): number {
  let n = 0
  for (let i = 0; i < blockIndex; i++) n += blocks.value[i]!.items.length
  return n
}

function navigateToSection(id: string) {
  navOpen.value = false
  const section = sections.find(s => s.id === id)
  const firstLink = section?.groups[0]?.links[0]?.to
  if (firstLink) navigateTo(firstLink)
}

function hoverCard(id: string, index: number) {
  hoveredSection.value = id
  cursor.value = index
}

function onKeydown(e: KeyboardEvent) {
  if (!navOpen.value) return
  if (e.key === 'Escape') {
    navOpen.value = false
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    cursor.value = Math.min(flatSections.value.length - 1, cursor.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    cursor.value = Math.max(0, cursor.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const s = flatSections.value[cursor.value]
    if (s) navigateToSection(s.id)
  }
}

// Reset + focus the filter whenever the modal opens.
watch(navOpen, (open) => {
  if (open) {
    query.value = ''
    cursor.value = 0
    setTimeout(() => inputRef.value?.focus(), 30)
  }
})

// Reset the highlight whenever the result set changes.
watch(query, () => {
  cursor.value = 0
})

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <header class="top-nav">
    <button
      v-if="currentSection"
      class="nav-menu-btn"
      :aria-expanded="sidebarOpen"
      aria-label="Toggle section navigation"
      @click="sidebarOpen = !sidebarOpen"
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
      >
        <path
          d="M2 4H14M2 8H14M2 12H14"
          stroke="currentColor"
          stroke-width="1.4"
          stroke-linecap="round"
        />
      </svg>
    </button>
    <NuxtLink
      class="logo"
      to="/"
    >
      <span :style="{ display: 'flex', filter: `hue-rotate(${hueShift}deg)`, transition: 'filter 0.3s ease' }">
        <svg
          width="22"
          height="22"
          viewBox="0 0 512 512"
          fill="none"
        >
          <path
            d="M256 262L491 138L256 21L21 138L256 262Z"
            fill="#00c16a"
          />
          <path
            d="M491 138L256 262V498L491 372V138Z"
            fill="#00dc82"
          />
          <path
            d="M21 138L256 262V498L21 372V138Z"
            fill="#00a155"
          />
        </svg>
      </span>
      Bosca
    </NuxtLink>

    <div class="nav-actions">
      <button
        v-if="showSignOut"
        class="nav-pill nav-pill-btn"
        @click="signOut"
      >
        Sign out
      </button>
      <button
        class="nav-icon-btn"
        :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
        @click="toggleTheme"
      >
        <Icon
          :name="isDark ? 'sun' : 'moon'"
          :size="16"
        />
      </button>
    </div>

    <Teleport to="body">
      <template v-if="navOpen">
        <Transition
          name="nav-scrim"
          appear
        >
          <div
            class="nav-modal-scrim"
            @click="navOpen = false"
            @keydown="onKeydown"
          />
        </Transition>
        <div class="nav-modal-anchor">
          <div
            class="nav-modal-box"
            @click.stop
          >
            <div class="nav-modal-header">
              <div class="nav-modal-brand">
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 512 512"
                  fill="none"
                >
                  <path
                    d="M256 262L491 138L256 21L21 138L256 262Z"
                    fill="#00c16a"
                  />
                  <path
                    d="M491 138L256 262V498L491 372V138Z"
                    fill="#00dc82"
                  />
                  <path
                    d="M21 138L256 262V498L21 372V138Z"
                    fill="#00a155"
                  />
                </svg>
                <span class="nav-modal-brand-text">
                  <span class="nav-modal-brand-name">Bosca</span>
                  <span class="nav-modal-brand-sep" />
                  <span class="nav-modal-brand-app">Docs</span>
                </span>
              </div>
              <button
                class="nav-modal-close"
                @click="navOpen = false"
              >
                <Icon
                  name="x"
                  :size="14"
                  color="var(--fg-3)"
                />
              </button>
            </div>

            <div class="nav-modal-search">
              <Icon
                name="search"
                :size="15"
                color="var(--fg-3)"
              />
              <input
                ref="inputRef"
                v-model="query"
                class="nav-modal-search-input"
                placeholder="Filter sections…"
                aria-label="Filter sections"
              >
              <span class="nav-modal-esc">esc</span>
            </div>

            <div class="nav-modal-body">
              <div
                v-for="(block, bi) in blocks"
                :key="block.id"
                class="nav-block"
              >
                <div class="nav-block-label">
                  {{ block.label }}
                </div>
                <div class="nav-modal-grid">
                  <button
                    v-for="(section, ri) in block.items"
                    :key="`${block.id}-${section.id}`"
                    class="nav-section-card"
                    :class="{
                      active: currentSection?.id === section.id,
                      hovered: hoveredSection === section.id,
                      cursor: (blockStartIndex(bi) + ri) === cursor
                    }"
                    :style="{ '--section-accent': section.accent }"
                    @mouseenter="hoverCard(section.id, blockStartIndex(bi) + ri)"
                    @mouseleave="hoveredSection = null"
                    @click="navigateToSection(section.id)"
                  >
                    <span
                      class="nav-section-icon"
                      :style="{
                        background: `color-mix(in oklch, ${section.accent} 14%, transparent)`,
                        border: `1px solid color-mix(in oklch, ${section.accent} 24%, transparent)`
                      }"
                    >
                      <Icon
                        :name="section.icon"
                        :size="14"
                        :color="section.accent"
                      />
                    </span>
                    <span class="nav-section-text">
                      <span class="nav-section-label">{{ section.label }}</span>
                      <span class="nav-section-sub">{{ section.sub }}</span>
                    </span>
                  </button>
                </div>
              </div>

              <div
                v-if="flatSections.length === 0"
                class="nav-modal-empty"
              >
                No sections match <span class="nav-modal-empty-query">"{{ query }}"</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </Teleport>
  </header>
</template>

<style scoped>
/* Hamburger for the mobile sidebar drawer — hidden wherever the sidebar
   is visible alongside the content (>900px). */
.nav-menu-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  margin-right: 4px;
  background: none;
  border: none;
  border-radius: var(--r-sm);
  color: var(--fg-1);
  cursor: pointer;
  transition: background 0.15s ease;
}

.nav-menu-btn:hover {
  background: var(--bg-2);
}

@media (max-width: 900px) {
  .nav-menu-btn {
    display: flex;
  }
}

.nav-pill {
  font-size: 12px;
  color: var(--fg-3);
  text-decoration: none;
  padding: 5px 10px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
  transition: all 0.15s ease;
}

.nav-pill:hover {
  border-color: var(--line-2);
  color: var(--fg-1);
}

.nav-pill-btn {
  background: none;
  font-family: inherit;
  cursor: pointer;
}

.nav-icon-btn {
  background: none;
  border: none;
  color: var(--fg-2);
  padding: 6px;
  cursor: pointer;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  transition: color 0.15s ease;
}

.nav-icon-btn:hover {
  color: var(--fg-0);
}

.logo-app-name {
  color: var(--fg-1);
  font-weight: 500;
  font-size: 14px;
}

/* ── Modal ─────────────────────────── */

.nav-modal-scrim {
  position: fixed;
  inset: 0;
  z-index: 9998;
}

.nav-modal-anchor {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 80px 20px 20px;
  pointer-events: none;
}

.nav-modal-box {
  width: min(860px, 100%);
  /* Anchor padding is 80px top + 20px bottom — never extend past the
     bottom edge on short/mobile viewports. */
  max-height: calc(100vh - 100px);
  max-height: calc(100dvh - 100px);
  background: color-mix(in oklch, var(--bg-0) 25%, transparent);
  backdrop-filter: blur(40px) saturate(1.6);
  -webkit-backdrop-filter: blur(40px) saturate(1.6);
  border: 1px solid color-mix(in oklch, var(--fg-3) 18%, transparent);
  border-radius: var(--r-lg);
  box-shadow:
    0 24px 80px -20px rgba(0, 0, 0, 0.4),
    inset 0 0 0 1px color-mix(in oklch, var(--fg-4) 8%, transparent),
    inset 0 1px 0 0 color-mix(in oklch, #fff 7%, transparent);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
}

.nav-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
}

.nav-modal-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.nav-modal-brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.nav-modal-brand-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.nav-modal-brand-sep {
  width: 1px;
  height: 16px;
  background: var(--fg-3);
  align-self: center;
}

.nav-modal-brand-app {
  font-size: 13px;
  color: var(--fg-1);
  font-weight: 500;
}

.nav-modal-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  transition: background 0.15s;
}

.nav-modal-close:hover {
  background: color-mix(in oklch, var(--fg-3) 12%, transparent);
}

.nav-modal-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
}

.nav-modal-search-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  font-size: 14px;
  color: var(--fg-0);
  font-family: inherit;
  letter-spacing: -0.005em;
}

.nav-modal-search-input::placeholder {
  color: var(--fg-3);
}

.nav-modal-esc {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--fg-3);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.nav-modal-body {
  padding: 8px;
  overflow: auto;
}

.nav-block + .nav-block {
  margin-top: 4px;
}

.nav-block-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 700;
  padding: 8px 16px 4px;
}

.nav-modal-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
}

@media (max-width: 820px) {
  .nav-modal-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .nav-modal-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .nav-modal-anchor {
    padding: 64px 12px 12px;
  }

  .nav-modal-box {
    max-height: calc(100vh - 76px);
    max-height: calc(100dvh - 76px);
  }
}

.nav-modal-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.nav-modal-empty-query {
  color: var(--fg-1);
  font-weight: 500;
}

.nav-section-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  background: none;
  border: none;
  cursor: pointer;
  text-align: left;
  width: 100%;
  transition: background 0.15s;
}

.nav-section-card.hovered,
.nav-section-card.cursor {
  background: color-mix(in oklch, var(--section-accent) 10%, transparent);
}

.nav-section-card.active {
  background: color-mix(in oklch, var(--section-accent) 12%, transparent);
}

.nav-section-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  margin-top: 1px;
}

.nav-section-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.nav-section-label {
  font-size: 13.5px;
  font-weight: 550;
  color: var(--fg-0);
  letter-spacing: -0.005em;
}

.nav-section-sub {
  font-size: 11.5px;
  color: var(--fg-3);
  line-height: 1.35;
}

/* Scrim fade — separate from box so backdrop-filter never sees opacity changes */
.nav-scrim-enter-active,
.nav-scrim-leave-active {
  transition: opacity 0.15s ease;
}

.nav-scrim-enter-from,
.nav-scrim-leave-to {
  opacity: 0;
}
</style>
