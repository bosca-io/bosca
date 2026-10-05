<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'

const authState = import.meta.client ? useAuth() : null
const profile = computed(() => authState?.profile.value ?? null)
const auth = computed(() => authState?.auth ?? null)
const { tweaks, setTweak } = useTweaks()

const open = ref(false)
const menuRef = ref<HTMLElement | null>(null)

const initials = computed(() => {
  const name = profile.value?.name
  if (!name) return '??'
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase()
})

const isDark = computed(() => tweaks.value.theme === 'dark')

function toggleTheme() {
  setTweak('theme', isDark.value ? 'light' : 'dark')
}

async function handleSignOut() {
  open.value = false
  await auth.value?.signOut()
  navigateTo('/auth/login')
}

function onClickOutside(e: MouseEvent) {
  if (menuRef.value && !menuRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onClickOutside, true))
onUnmounted(() => document.removeEventListener('click', onClickOutside, true))
</script>

<template>
  <div ref="menuRef" class="profile-menu-wrap">
    <button class="profile-trigger" :title="profile?.name ?? 'Profile'" @click="open = !open">
      <span class="profile-avatar">{{ initials }}</span>
      <span class="profile-trigger-name">{{ profile?.name ?? 'User' }}</span>
    </button>

    <Transition name="menu-fade">
      <div v-if="open" class="profile-dropdown">
        <div class="profile-header">
          <div class="profile-avatar-lg">{{ initials }}</div>
          <div class="profile-info">
            <span class="profile-name">{{ profile?.name ?? 'User' }}</span>
            <span v-if="profile?.slug" class="profile-slug">@{{ profile.slug }}</span>
          </div>
        </div>

        <div class="menu-divider" />

        <button class="menu-item" @click="navigateTo('/system/config'); open = false">
          <Icon name="gear" :size="14" color="var(--fg-2)" />
          Settings
        </button>

        <button class="menu-item" @click="toggleTheme">
          <Icon :name="isDark ? 'sun' : 'moon'" :size="14" color="var(--fg-2)" />
          {{ isDark ? 'Light mode' : 'Dark mode' }}
        </button>

        <div class="menu-divider" />

        <button class="menu-item menu-item--danger" @click="handleSignOut">
          <Icon name="log-out" :size="14" color="currentColor" />
          Sign out
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.profile-menu-wrap {
  position: relative;
}

.profile-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  background: transparent;
  border-radius: 8px;
  padding: 0;
  transition: opacity 0.15s ease;
}

.profile-trigger:hover {
  opacity: 0.85;
}

.profile-trigger-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.profile-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.profile-dropdown {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: 220px;
  background: color-mix(in oklch, var(--bg-1) 80%, transparent);
  backdrop-filter: blur(20px) saturate(1.2);
  -webkit-backdrop-filter: blur(20px) saturate(1.2);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  border-radius: 12px;
  padding: 6px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
  z-index: 100;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
}

.profile-avatar-lg {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.profile-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.profile-name {
  font-size: 13px;
  font-weight: 550;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.profile-slug {
  font-size: 11px;
  color: var(--fg-3);
}

.menu-divider {
  height: 1px;
  background: var(--line);
  margin: 4px 0;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--fg-1);
  background: transparent;
  text-align: left;
  transition: background 0.1s ease;
}

.menu-item:hover {
  background: color-mix(in oklch, var(--fg-0) 6%, transparent);
}

.menu-item--danger {
  color: #ef4444;
}

.menu-item--danger:hover {
  background: color-mix(in oklch, #ef4444 10%, transparent);
}

.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
