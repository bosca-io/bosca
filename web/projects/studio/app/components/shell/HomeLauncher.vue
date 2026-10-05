<script setup lang="ts">
import { groupSubsystems } from '~/composables/useSubsystems'
import { useAuth } from '@bosca/auth-client-browser'
import type { Subsystem } from '~~/shared/types'

// Persona/admin-scoped subsystems with feature-disabled modules removed — the
// same source the sidebar and navigation modal use, so the hub can never offer
// an area the user can't actually open. Resolved during SSR by the index page,
// so the category grid renders correctly on first paint (no client-only flash).
const { visibleSubsystems } = usePersonas()
const { defaultPage } = useSubsystems()

const docsUrl = useRuntimeConfig().public.docsUrl

// Auth is wired in a client-only plugin, so it is only available in the browser
// (mirrors the guard in welcome.vue). Name + sign-out therefore resolve after
// hydration; the greeting falls back to a neutral "Welcome" during SSR.
const authState = import.meta.client ? useAuth() : null
const profile = computed(() => authState?.profile.value ?? null)

const firstName = computed(() => {
  const name = profile.value?.name
  if (!name) return null
  return name.trim().split(/\s+/)[0]
})

// Time-of-day greeting resolved on the client only: the server and browser
// clocks can disagree, so computing this during SSR risks a hydration mismatch.
// Defaults to "Welcome" until mounted.
const greeting = ref('Welcome')
onMounted(() => {
  const hour = new Date().getHours()
  greeting.value = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
})

const heading = computed(() =>
  firstName.value ? `${greeting.value}, ${firstName.value}.` : `${greeting.value}.`,
)

// Recently visited subsystems (localStorage), filtered to what's still visible
// and de-duplicated by the recents list itself. Client-only, so the row is
// wrapped in <ClientOnly> below to avoid a hydration mismatch.
const recentIds = import.meta.client ? useLastSubsystem().recents() : []
const recent = computed<Subsystem[]>(() => {
  const byId = new Map(visibleSubsystems.value.map(s => [s.id, s]))
  return recentIds
    .map(id => byId.get(id))
    .filter((s): s is Subsystem => !!s)
    .slice(0, 4)
})

const categories = computed(() => groupSubsystems(visibleSubsystems.value))

function open(sub: Subsystem) {
  navigateTo(`/${sub.id}/${defaultPage(sub.id)}`)
}

async function handleSignOut() {
  await authState?.auth?.signOut()
  await navigateTo('/auth/login')
}
</script>

<template>
  <div class="home">
    <header class="home-header">
      <NuxtLink to="/" class="home-brand" aria-label="Bosca Studio home">
        <BoscaMark :size="26" />
        <span class="home-brand-text">
          <span class="home-brand-name">Bosca</span>
          <span class="home-brand-sep" />
          <span class="home-brand-label">Studio</span>
        </span>
      </NuxtLink>

      <div class="home-actions">
        <a
          class="home-action"
          :href="docsUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <Icon name="book-open" :size="15" color="var(--fg-3)" />
          <span>Documentation</span>
          <Icon name="external-link" :size="13" color="var(--fg-4)" />
        </a>
        <ClientOnly>
          <button class="home-action" @click="handleSignOut">
            <Icon name="log-out" :size="15" color="var(--fg-3)" />
            <span>Sign out</span>
          </button>
        </ClientOnly>
      </div>
    </header>

    <div class="home-hero">
      <ClientOnly>
        <h1 class="home-title">{{ heading }}</h1>
        <template #fallback>
          <h1 class="home-title">Welcome.</h1>
        </template>
      </ClientOnly>
      <p class="home-subtitle">Where would you like to go?</p>
    </div>

    <ClientOnly>
      <section v-if="recent.length" class="home-recent">
        <div class="home-section-label">Recent</div>
        <div class="home-recent-row">
          <button
            v-for="s in recent"
            :key="`recent-${s.id}`"
            class="home-chip"
            :style="{ '--item-accent': s.accent }"
            @click="open(s)"
          >
            <span
              class="home-chip-icon"
              :style="{
                background: `color-mix(in oklch, ${s.accent} 14%, transparent)`,
                border: `1px solid color-mix(in oklch, ${s.accent} 24%, transparent)`,
              }"
            >
              <Icon :name="s.icon" :size="13" :color="s.accent" />
            </span>
            <span class="home-chip-label">{{ s.label }}</span>
          </button>
        </div>
      </section>
    </ClientOnly>

    <section
      v-for="category in categories"
      :key="category.id"
      class="home-category"
    >
      <div class="home-section-label">{{ category.label }}</div>
      <div class="home-grid">
        <button
          v-for="s in category.items"
          :key="s.id"
          class="home-card"
          :style="{ '--item-accent': s.accent }"
          @click="open(s)"
        >
          <span
            class="home-card-icon"
            :style="{
              background: `color-mix(in oklch, ${s.accent} 14%, transparent)`,
              border: `1px solid color-mix(in oklch, ${s.accent} 24%, transparent)`,
            }"
          >
            <Icon :name="s.icon" :size="16" :color="s.accent" />
          </span>
          <span class="home-card-text">
            <span class="home-card-label">{{ s.label }}</span>
            <span class="home-card-desc">{{ s.sub }}</span>
          </span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  width: 100%;
  max-width: 1080px;
  margin-inline: auto;
  padding: 32px 40px 64px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.home-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  transition: opacity 0.15s ease;
}

.home-brand:hover {
  opacity: 0.7;
}

.home-brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.home-brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.home-brand-sep {
  width: 1px;
  height: 16px;
  background: var(--fg-3);
  align-self: center;
}

.home-brand-label {
  font-size: 14px;
  color: var(--fg-2);
  font-weight: 500;
}

.home-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.home-action {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-2);
  background: transparent;
  border: 1px solid color-mix(in oklch, var(--fg-3) 16%, transparent);
  border-radius: 9px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.home-action:hover {
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  color: var(--fg-0);
  border-color: color-mix(in oklch, var(--fg-3) 26%, transparent);
}

.home-hero {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.home-title {
  margin: 0;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #f3f5fb;
  line-height: 1.1;
}

.home-subtitle {
  margin: 0;
  font-size: 14.5px;
  color: var(--fg-2);
}

.home-section-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 700;
  margin-bottom: 10px;
}

/* Recent row — compact chips */
.home-recent-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.home-chip {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 8px 14px 8px 8px;
  border-radius: 10px;
  background: color-mix(in oklch, var(--fg-3) 6%, transparent);
  border: 1px solid color-mix(in oklch, var(--fg-3) 12%, transparent);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.home-chip:hover {
  background: color-mix(in oklch, var(--item-accent) 12%, transparent);
  border-color: color-mix(in oklch, var(--item-accent) 30%, transparent);
}

.home-chip-icon {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 26px;
}

.home-chip-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

/* Category card grid */
.home-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 8px;
}

.home-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 12px;
  background: color-mix(in oklch, var(--fg-3) 5%, transparent);
  border: 1px solid color-mix(in oklch, var(--fg-3) 11%, transparent);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, transform 0.15s;
}

.home-card:hover {
  background: color-mix(in oklch, var(--item-accent) 10%, transparent);
  border-color: color-mix(in oklch, var(--item-accent) 32%, transparent);
  transform: translateY(-1px);
}

.home-card-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 34px;
  margin-top: 1px;
}

.home-card-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.home-card-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.home-card-desc {
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.4;
}

@media (max-width: 640px) {
  .home {
    padding: 24px 20px 48px;
  }

  .home-title {
    font-size: 25px;
  }

  .home-grid {
    grid-template-columns: 1fr;
  }
}
</style>
