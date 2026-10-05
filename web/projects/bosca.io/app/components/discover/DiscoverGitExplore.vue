<script setup lang="ts">
// The Git marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers only
// its siblings.
const PAGES = [
  {
    to: '/discover/git',
    icon: 'git-branch',
    title: 'Git overview',
    sub: 'Self-hosted Git, woven into the rest of the platform.'
  },
  {
    to: '/discover/git/repositories',
    icon: 'folder-git',
    title: 'Repositories',
    sub: 'Host, browse, search, and compare code — with forks and archiving.'
  },
  {
    to: '/discover/git/pull-requests',
    icon: 'git-pull-request',
    title: 'Pull requests',
    sub: 'Propose, review, and merge — gated by branch protection.'
  },
  {
    to: '/discover/git/ci',
    icon: 'rocket',
    title: 'CI/CD',
    sub: 'Pipelines in YAML, run on your own agents, reported as statuses.'
  },
  {
    to: '/discover/git/integration',
    icon: 'share-2',
    title: 'Platform integration',
    sub: 'Task links, sourced scripts and queries, events, and webhooks.'
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
      <h2>More on <em>Git</em></h2>
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
