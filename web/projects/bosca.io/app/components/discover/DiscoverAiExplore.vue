<script setup lang="ts">
// The AI marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers only
// its siblings.
const PAGES = [
  {
    to: '/discover/ai',
    icon: 'sparkles',
    title: 'AI overview',
    sub: 'Kit, the assistant, and the parts AI is built from.'
  },
  {
    to: '/discover/ai/kit',
    icon: 'message-circle',
    title: 'Kit',
    sub: 'The built-in assistant — write, generate, run, and query.'
  },
  {
    to: '/discover/ai/agents',
    icon: 'workflow',
    title: 'Agents',
    sub: 'Compose a model, a prompt, and tools into an agent.'
  },
  {
    to: '/discover/ai/prompts',
    icon: 'quote',
    title: 'Prompts',
    sub: 'System and user prompts, versioned in Git.'
  },
  {
    to: '/discover/ai/models',
    icon: 'boxes',
    title: 'Models',
    sub: 'Bring your model — OpenAI or Google.'
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
      <h2>More on <em>AI</em></h2>
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
