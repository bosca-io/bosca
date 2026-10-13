<script setup lang="ts">
import type { BibleAttribution } from '~/types/graphql'

defineProps<{ attribution: BibleAttribution }>()
</script>

<template>
  <div class="bible-attribution">
    <p v-if="attribution.copyright">{{ attribution.copyright }}</p>
    <p v-if="attribution.citation">{{ attribution.citation }}</p>
    <a
      v-if="attribution.informationUrl"
      :href="attribution.informationUrl"
      target="_blank"
      rel="noopener noreferrer"
    >Translation information</a>
  </div>
</template>

<style scoped>
.bible-attribution {
  color: var(--fg-2);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-line;
}

.bible-attribution p {
  margin: 4px 0;
}

.bible-attribution a {
  color: var(--fg-1);
  text-decoration: underline;
}
</style>
