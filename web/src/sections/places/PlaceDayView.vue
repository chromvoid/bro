<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Footprints, MapPin } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import locationPlacesMessages from '@/locales/location-places-ru.json'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { fullDayLabel, timeIn } from '@/core/dates'
import { readDay, timeline } from './api'
import PlaceMap from './PlaceMap.vue'

/** One day of movements: stays with their addresses and the moves between them, by time. */
const { t } = useI18n({ useScope: 'local', messages: { ru: locationPlacesMessages } })
const route = useRoute()
const date = computed(() => String(route.params.date ?? ''))
const day = useQuery({
  queryKey: computed(() => ['places', 'day', date.value]),
  queryFn: ({ signal }) => readDay(date.value, signal),
})
const entries = computed(() => (day.data.value ? timeline(day.data.value) : []))
const zone = computed(() => day.data.value?.timezone ?? 'UTC')
const span = (from: string, to: string) => `${timeIn(zone.value, from)}–${timeIn(zone.value, to)}`
const duration = (minutes: number) =>
  minutes < 60
    ? t('places.minutes', { minutes })
    : minutes % 60
      ? t('places.hours', { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
      : t('places.hoursOnly', { hours: minutes / 60 })
const distance = (meters: number) =>
  meters >= 1000
    ? t('places.km', { km: (meters / 1000).toFixed(1).replace('.', ',') })
    : t('places.meters', { meters })
</script>

<template>
  <PageHeader :title="date ? fullDayLabel(date) : t('places.title')" :back="{ name: 'places' }" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="day" />
    <LoadingState v-if="day.isPending.value" :rows="4" />
    <ErrorState
      v-else-if="day.isLoadingError.value"
      :error="day.error.value"
      @retry="day.refetch()"
    />
    <EmptyState v-else-if="!entries.length" :icon="MapPin" :title="t('places.dayEmptyTitle')" />
    <PlaceMap v-if="day.data.value?.track.length" :day="day.data.value" />
    <ol
      v-if="entries.length"
      class="bg-card divide-y rounded-3xl border"
      data-testid="places-timeline"
    >
      <li
        v-for="(entry, index) in entries"
        :key="index"
        class="flex items-start gap-3 px-5 py-4"
        :data-kind="entry.kind"
      >
        <template v-if="entry.kind === 'stay'">
          <MapPin class="text-primary mt-0.5 size-5 shrink-0" />
          <div class="min-w-0">
            <p class="font-medium break-words">
              {{ entry.stay.place ?? t('places.unnamed') }}
            </p>
            <p class="text-muted-foreground text-sm">
              {{ span(entry.stay.from, entry.stay.to) }} · {{ duration(entry.stay.minutes) }}
            </p>
          </div>
        </template>
        <template v-else>
          <Footprints class="text-muted-foreground mt-0.5 size-5 shrink-0" />
          <p class="text-muted-foreground text-sm">
            {{ span(entry.move.from, entry.move.to) }} · {{ distance(entry.move.distance_m) }}
          </p>
        </template>
      </li>
    </ol>
    <p class="text-muted-foreground text-xs">
      <a
        class="underline"
        href="https://www.openstreetmap.org/copyright"
        target="_blank"
        rel="noopener noreferrer"
        >{{ t('places.attribution') }}</a
      >
    </p>
  </div>
</template>
