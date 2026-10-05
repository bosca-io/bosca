<script setup lang="ts">
const props = defineProps<{
  code: string
  lang: string
  title?: string
}>()

const { highlight } = useHighlight()
const html = ref('')
const copied = ref(false)

onMounted(async () => {
  html.value = await highlight(props.code, props.lang)
})

function copy() {
  navigator.clipboard.writeText(props.code.trim())
  copied.value = true
  setTimeout(() => {
    copied.value = false
  }, 1500)
}
</script>

<template>
  <div class="code-block">
    <div class="code-block-header">
      <span class="code-block-lang">{{ title || lang }}</span>
      <button
        class="code-block-copy"
        @click="copy"
      >
        <Icon
          :name="copied ? 'checkmark' : 'copy'"
          :size="14"
        />
      </button>
    </div>
    <div v-html="html" />
  </div>
</template>
