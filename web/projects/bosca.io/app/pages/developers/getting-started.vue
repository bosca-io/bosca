<script setup lang="ts">
import { SECTIONS } from '~/composables/useSections'

definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Developer Documentation',
  description: 'Set up Bosca locally and use the platform reference for services, APIs, identity, and runtime behavior.'
})

const groups = (SECTIONS.find(section => section.id === 'developers')?.groups ?? [])
  .map(group => ({
    ...group,
    links: group.links.filter(link => link.to !== '/developers/getting-started')
  }))
</script>

<template>
  <div class="doc-content article">
    <h1>Developer Documentation</h1>
    <p class="subtitle">
      Set up Bosca, work with its APIs, and build features using the platform's existing patterns.
    </p>
    <p>
      Start with <NuxtLink to="/developers/run-locally">Run Bosca Locally</NuxtLink> to launch
      the platform with Docker Compose and sign in to Studio. The reference below covers
      models, services, APIs, identity, and runtime behavior.
    </p>
    <p>
      For an introduction to the platform, visit the
      <NuxtLink to="/discover/developers">developers overview</NuxtLink>.
    </p>
    <h2 id="choose-path">
      Choose a starting point
    </h2>
    <ol>
      <li><strong>Explore Bosca:</strong> start the local Compose stack and sign in to Studio.</li>
      <li><strong>Build an integration:</strong> install the CLI, learn authentication, and call the GraphQL API.</li>
      <li><strong>Change the platform:</strong> run the source-development dependencies, then read the architecture and domain implementation guides.</li>
    </ol>
    <p>
      These pages describe the current workspace. For a published release, check its CLI help
      and GraphQL schema for the available commands and fields.
    </p>
    <section
      v-for="group in groups"
      :key="group.title"
    >
      <h2>{{ group.title }}</h2>
      <ul>
        <li
          v-for="link in group.links"
          :key="link.to"
        >
          <NuxtLink :to="link.to">{{ link.label }}</NuxtLink>
        </li>
      </ul>
    </section>
  </div>
</template>
