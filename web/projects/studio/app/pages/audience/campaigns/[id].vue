<script setup lang="ts">
const route = useRoute()
await navigateTo(`/communications/campaigns/${route.params.id as string}`, { redirectCode: 301, replace: true })
</script>
