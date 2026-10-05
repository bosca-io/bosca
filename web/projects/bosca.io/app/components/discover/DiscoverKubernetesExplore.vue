<script setup lang="ts">
// The Kubernetes marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/kubernetes',
    icon: 'kubernetes',
    title: 'Kubernetes overview',
    sub: 'A multi-cluster console, built into the platform.'
  },
  {
    to: '/discover/kubernetes/clusters',
    icon: 'server',
    title: 'Clusters & Fleet',
    sub: 'Register clusters, watch nodes, events, and namespaces.'
  },
  {
    to: '/discover/kubernetes/workloads',
    icon: 'boxes',
    title: 'Workloads',
    sub: 'Scale, restart, delete, and stream live pod logs.'
  },
  {
    to: '/discover/kubernetes/resources',
    icon: 'share-2',
    title: 'Resources',
    sub: 'Networking, config, storage, operators, and RBAC.'
  },
  {
    to: '/discover/kubernetes/helm',
    icon: 'package',
    title: 'Helm',
    sub: 'A live chart catalog, dry-run installs, and every release.'
  },
  {
    to: '/discover/kubernetes/controller',
    icon: 'shield-check',
    title: 'The Controller',
    sub: 'How it connects — live, secure, admin-gated.'
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
      <h2>More on <em>Kubernetes</em></h2>
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
