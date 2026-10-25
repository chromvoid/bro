<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight, Route } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { dayIn, dayLabel } from '@/core/dates'
import { readDays } from './api'

/** «Перемещения»: the days the phones recorded, newest first (docs/location-weather-spec.md §5.3). */
const { t } = useI18n()
const days = useQuery({ queryKey: ['places', 'days'], queryFn: ({ signal }) => readDays(signal) })
const today = computed(() => dayIn(days.data.value?.timezone ?? 'UTC'))
</script>

<template>
  <PageHeader :title="t('places.title')" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="days" />
    <LoadingState v-if="days.isPending.value" :rows="4" />
    <ErrorState
      v-else-if="days.isLoadingError.value"
      :error="days.error.value"
      @retry="days.refetch()"
    />
    <EmptyState
      v-else-if="!days.data.value?.days.length"
      :icon="Route"
      :title="t('places.emptyTitle')"
      :description="t('places.emptyText')"
    />
    <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="places-days">
      <li v-for="day in days.data.value.days" :key="day.date">
        <RouterLink
          :to="{ name: 'places-day', params: { date: day.date } }"
          class="hover:bg-accent/60 flex items-center justify-between gap-3 px-5 py-4"
        >
          <span class="font-medium">{{ dayLabel(day.date, today) }}</span>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
      </li>
    </ul>
  </div>
</template>
