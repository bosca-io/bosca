<script setup lang="ts">
// The Work Ops marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/workops',
    icon: 'kanban',
    title: 'Work Ops overview',
    sub: 'Plan, track, and ship work next to your content and code.'
  },
  {
    to: '/discover/workops/specs',
    icon: 'file-text',
    title: 'Specs & Requirements',
    sub: 'Write rich specs, break them into requirements, track each as a task.'
  },
  {
    to: '/discover/workops/releases',
    icon: 'tag',
    title: 'Releases & Milestones',
    sub: 'Track what ships in each version and hit dated milestones.'
  },
  {
    to: '/discover/workops/tasks',
    icon: 'list-checks',
    title: 'Tasks & Workflows',
    sub: 'Work items with your own types, statuses, and workflows.'
  },
  {
    to: '/discover/workops/boards',
    icon: 'columns',
    title: 'Boards & Sprints',
    sub: 'Kanban boards and sprints to run the work.'
  },
  {
    to: '/discover/workops/notifications',
    icon: 'bell',
    title: 'Notifications',
    sub: 'A personal inbox, per-event channels, digests, and focus controls.'
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
      <h2>More on <em>Work Ops</em></h2>
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
