<script setup lang="ts">
const { confirmCancel, dismissCancel, confirmOpen, pendingJob } = useJobCancel()
</script>

<template>
  <ConfirmModal
    v-if="confirmOpen && pendingJob"
    :title="`Cancel job '${pendingJob.displayName}'?`"
    subtitle="The job will be stopped. This may leave the item in an intermediate state."
    confirm-label="Cancel Job"
    @close="dismissCancel"
    @confirm="confirmCancel"
  />
</template>
