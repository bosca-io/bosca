<script setup lang="ts">
definePageMeta({ middleware: 'github-admin' })
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Settings', 'GitHub User Mappings')"
        title="GitHub User Mappings"
        subtitle="Map GitHub users to Bosca principals" />
    </template>
    <GitHubUserMappings />
  </PageShell>
</template>
