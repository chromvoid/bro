<script setup lang="ts">
import RawReportHistory from './RawReportHistory.vue'
defineProps<{ from: string; to: string; timezone: string; today: string }>()
</script>

<template>
  <RawReportHistory
    metric="temperature"
    :from="from"
    :to="to"
    :timezone="timezone"
    :today="today"
  />
</template>
