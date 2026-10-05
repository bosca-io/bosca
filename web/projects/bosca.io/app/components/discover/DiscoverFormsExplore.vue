<script setup lang="ts">
// The Forms marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/forms',
    icon: 'form',
    title: 'Forms overview',
    sub: 'A form builder built into the platform.'
  },
  {
    to: '/discover/forms/builder',
    icon: 'edit',
    title: 'The builder',
    sub: 'Drag fields onto a canvas, preview the real form, publish.'
  },
  {
    to: '/discover/forms/fields',
    icon: 'sliders',
    title: 'Fields',
    sub: 'Fifteen field types, layout rows, and selects fed by live data.'
  },
  {
    to: '/discover/forms/submissions',
    icon: 'inbox',
    title: 'Submissions',
    sub: 'Review answers in one queue — and create profiles from them.'
  },
  {
    to: '/discover/forms/tasks',
    icon: 'kanban',
    title: 'Task intake',
    sub: 'A form in front, a Work Ops task behind it.'
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
      <h2>More on <em>Forms</em></h2>
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
