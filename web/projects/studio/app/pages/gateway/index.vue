<script setup lang="ts">
// Default landing for /gateway: send the user to the gateways list.
// This mirrors how other subsystems land on their first nav item when
// no explicit page is requested.
const router = useRouter()
onMounted(() => {
  router.replace('/gateway/gateways')
})
</script>

<template>
  <div />
</template>
