<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Skeleton } from '@/components/ui/skeleton'

withDefaults(defineProps<{ rows?: number; label?: string }>(), { rows: 3, label: undefined })
const { t } = useI18n()
</script>

<template>
  <div
    class="space-y-3 px-1 py-2"
    role="status"
    data-state="loading"
    :aria-label="label ?? t('state.loading')"
  >
    <Skeleton v-for="row in rows" :key="row" class="h-16 w-full rounded-xl" />
    <span class="sr-only">{{ label ?? t('state.loading') }}</span>
  </div>
</template>
