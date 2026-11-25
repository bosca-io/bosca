<script setup lang="ts">
import JsonEditorVue from 'json-editor-vue'

defineProps<{ data: unknown; dataType?: string; sdl?: string }>()
</script>

<template>
  <div class="graphql-output">
    <details class="graphql-result" open>
      <summary>GraphQL result<span v-if="dataType"> · {{ dataType }}</span></summary>
      <ClientOnly>
        <JsonEditorVue
          :model-value="data"
          :read-only="true"
          :main-menu-bar="false"
          :navigation-bar="false"
          class="jse-theme-dark" />
      </ClientOnly>
    </details>
    <details v-if="sdl" class="graphql-schema">
      <summary>Schema</summary>
      <pre>{{ sdl }}</pre>
    </details>
  </div>
</template>

<style scoped>
.graphql-output { min-width: 0; }
.graphql-result { max-height: 400px; overflow: auto; }
.graphql-schema pre { max-height: 300px; overflow: auto; white-space: pre-wrap; font-size: 12px; }
</style>
