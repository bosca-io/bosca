<script setup lang="ts">
// The System marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers only
// its siblings.
const PAGES = [
  {
    to: '/discover/system',
    icon: 'gear',
    title: 'System overview',
    sub: 'Operate, secure, and configure the platform.'
  },
  {
    to: '/discover/system/jobs',
    icon: 'clock',
    title: 'Jobs & Scheduling',
    sub: 'Durable jobs, a cron scheduler, and run history.'
  },
  {
    to: '/discover/system/security',
    icon: 'shield-check',
    title: 'Security & Identity',
    sub: 'Principals, groups, passkeys, and scoped API tokens.'
  },
  {
    to: '/discover/system/storage',
    icon: 'database',
    title: 'Storage & Data',
    sub: 'Object storage, backups, and search.'
  },
  {
    to: '/discover/system/configuration',
    icon: 'settings-2',
    title: 'Configuration',
    sub: 'Settings and integrations.'
  }
]

withDefaults(defineProps<{
  title?: string
}>(), {
  title: 'Keep exploring'
})

const route = useRoute()
const siblings = computed(() => PAGES.filter(p => p.to !== route.path))
</script>

<template>
  <section class="section explore">
    <div class="section-head reveal">
      <p class="kicker">
        {{ title }}
      </p>
      <h2>More on <em>System</em></h2>
    </div>
    <div class="explore-grid reveal">
      <NuxtLink
        v-for="page in siblings"
        :key="page.to"
        :to="page.to"
        class="explore-card"
      >
        <span class="explore-icon">
          <Icon
            :name="page.icon"
            :size="16"
          />
        </span>
        <span class="explore-text">
          <h3>{{ page.title }}</h3>
          <p>{{ page.sub }}</p>
        </span>
        <Icon
          name="arrow-right"
          :size="15"
          class="explore-arrow"
        />
      </NuxtLink>
    </div>
  </section>
</template>
