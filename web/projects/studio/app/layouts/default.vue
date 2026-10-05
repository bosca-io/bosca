<script setup lang="ts">
import { SUBSYSTEMS } from '~/composables/useSubsystems'
import { useAuth } from '@bosca/auth-client-browser'
import type { ContextMenuGroup } from '~/composables/useContextMenu'

const route = useRoute()
const { tweaks, setTweak } = useTweaks()
const { defaultPage } = useSubsystems()
// Persona/admin-scoped subsystems with feature-disabled modules (e.g. commerce
// when ecommerce is off) removed — see usePersonas().visibleSubsystems.
const { visibleSubsystems } = usePersonas()
const { id: currentSub, accent } = useCurrentSubsystem()
const contextMenu = useContextMenu()
const lastSubsystem = import.meta.client ? useLastSubsystem() : null
const authState = import.meta.client ? useAuth() : null
const profile = computed(() => authState?.profile.value ?? null)
const isDark = computed(() => tweaks.value.theme === 'dark')
useFavicon(currentSub)

const NAV_ALIASES: Record<string, string> = {
  editor: 'documents',
}

const currentPage = computed(() => {
  const segments = route.path.split('/').filter(Boolean)
  const page = segments.slice(1).join('/') || defaultPage(currentSub.value)
  const cleaned = page.replace(/\/[0-9a-f-]{36}$/, '').replace(/\/new$/, '')
  const base = NAV_ALIASES[cleaned] || cleaned
  if (base === 'dashboard' && route.query.key) {
    return `dashboard?key=${route.query.key}`
  }
  return base
})

if (import.meta.client) {
  watch([currentSub, currentPage], ([sub, page]) => {
    lastSubsystem?.save(sub, page)
  }, { immediate: true })
}

const paletteOpen = ref(false)
const navModalOpen = ref(false)

function onKey(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = true
  } else if (e.key === 'Escape') {
    paletteOpen.value = false
  }
}

const toast = useToast()
const { takeFlash } = useFlash()

onMounted(() => {
  window.addEventListener('keydown', onKey)
  // Surface any one-shot flash carried across an auth→app hard reload (e.g. the
  // account-link confirmation) as a toast, now that we're inside the app.
  const flash = takeFlash()
  if (flash) toast.success(flash)
})
onUnmounted(() => window.removeEventListener('keydown', onKey))

function onSubChange(id: string) {
  navigateTo(`/${id}/${defaultPage(id)}`)
}

function onPageChange(id: string) {
  navigateTo(`/${currentSub.value}/${id}`)
}

function onPaletteJump(sub: string, view?: string) {
  navigateTo(`/${sub}/${view || defaultPage(sub)}`)
  paletteOpen.value = false
}

function onPaletteNavigate(path: string) {
  navigateTo(path)
  paletteOpen.value = false
}

// Dynamic dashboard nav items for analytics subsystem
const { dashboardNavGroups } = useDashboardNav()

const initials = computed(() => {
  const name = profile.value?.name
  if (!name) return '??'
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
})

const contextMenuGroups = computed<ContextMenuGroup[]>(() => {
  const name = profile.value?.name ?? 'User'
  const currentSys = SUBSYSTEMS.find(s => s.id === currentSub.value)
  const slug = profile.value?.slug
  const settingsGroup = currentSys?.nav.find(g => g.group === 'Settings' || g.group === 'Configure' || g.admin)
  return [
    {
      id: profile.value?.id ? 'profile:view' : undefined,
      label: name,
      subtitle: slug ? `@${slug}` : undefined,
      avatar: initials.value,
      items: [],
    },
    {
      items: [
        {
          id: 'subsystems',
          label: 'Subsystems',
          icon: currentSys?.icon ?? 'dashboard',
          iconColor: currentSys?.accent,
          children: visibleSubsystems.value.map(s => ({
            id: `sub:${s.id}`,
            label: s.label,
            icon: s.icon,
            iconColor: s.accent,
          })),
        },
        ...(settingsGroup ? [{
          id: 'settings',
          label: 'Settings',
          icon: 'gear',
          children: settingsGroup.items.map(item => ({
            id: `settings:${item.id}`,
            label: item.label,
            icon: item.icon,
          })),
        }] : []),
      ],
    },
    {
      items: [
        {
          id: 'view:collab',
          label: tweaks.value.showCollab ? 'Hide Collaboration' : 'Show Collaboration',
          icon: 'message',
        },
      ],
    },
    {
      items: [
        { id: 'profile:theme', label: isDark.value ? 'Light mode' : 'Dark mode', icon: isDark.value ? 'sun' : 'moon' },
        { id: 'profile:signout', label: 'Sign out', icon: 'log-out', danger: true },
      ],
    },
  ]
})

function onContextMenu(e: MouseEvent) {
  contextMenu.open(e, contextMenuGroups.value, (id) => {
    if (id === 'profile:view') {
      const pid = profile.value?.id
      if (pid) navigateTo(`/audience/profiles/${pid}`)
    } else if (id.startsWith('sub:')) {
      onSubChange(id.slice(4))
    } else if (id.startsWith('settings:')) {
      navigateTo(`/${currentSub.value}/${id.slice(9)}`)
    } else if (id === 'view:collab') {
      setTweak('showCollab', !tweaks.value.showCollab)
    } else if (id === 'profile:theme') {
      setTweak('theme', isDark.value ? 'light' : 'dark')
    } else if (id === 'profile:signout') {
      void authState?.auth?.signOut().then(() => { navigateTo('/auth/login') })
    }
  })
}
</script>

<template>
  <div
    class="layout-root"
    data-screen-label="B · Sidebar Layout"
    @contextmenu="onContextMenu"
  >
    <!-- Ambient gradient background -->
    <div
      class="ambient-gradient"
      :style="{
        background: `
          radial-gradient(80% 55% at 12% -5%, color-mix(in oklch, ${accent} 28%, transparent), transparent 60%),
          radial-gradient(60% 45% at 95% 8%, color-mix(in oklch, var(--brand-accent) 18%, transparent), transparent 65%),
          radial-gradient(70% 50% at 100% 100%, color-mix(in oklch, ${accent} 12%, transparent), transparent 70%),
          linear-gradient(180deg, color-mix(in oklch, ${accent} 6%, var(--bg-0)) 0%, var(--bg-0) 40%)
        `,
      }"
    />

    <div class="layout-body">
      <ScopedNav
        :subsystem="currentSub"
        :active="currentPage"
        :accent="accent"
        variant="glass"
        :dynamic-groups="currentSub === 'analytics' ? dashboardNavGroups : undefined"
        @activate="onPageChange"
        @brand-click="navModalOpen = true"
        @home="navigateTo('/')"
        @search-click="paletteOpen = true" />
      <div class="layout-main">
        <slot />
      </div>
    </div>

    <ClientOnly>
      <CollabDock v-if="tweaks.showCollab" variant="glass" />
    </ClientOnly>

    <CommandPalette
      :open="paletteOpen"
      :subsystem="currentSub"
      @close="paletteOpen = false"
      @jump="onPaletteJump"
      @navigate="onPaletteNavigate"
    />

    <NavigationModal
      v-if="navModalOpen"
      :accent="accent"
      @close="navModalOpen = false"
      @navigate="(id) => { onSubChange(id); navModalOpen = false }"
    />

    <ContextMenu />
  </div>
</template>

<style scoped>
.layout-root {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
  color: var(--fg-0);
  overflow: hidden;
  position: relative;
}

.ambient-gradient {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.layout-body {
  flex: 1;
  display: flex;
  min-height: 0;
  position: relative;
  z-index: 1;
}

.layout-main {
  flex: 1;
  display: flex;
  min-width: 0;
}
</style>
