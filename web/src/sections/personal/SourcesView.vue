<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight, Circle, Plug, Plus, Ruler, Scale, Watch } from '@lucide/vue'
import { computed, ref, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { api } from '@/core/client'
import { dateLabel, dayIn, momentPhrase } from '@/core/dates'
import { useDeveloperMode } from '@/core/developer'
import { useSession } from '@/stores/session'
import MetricSourcesPanel from './MetricSourcesPanel.vue'
import { personalReturnLink } from './personalNavigation'
import { Rings } from './sensors'
import {
  activeSource,
  contributors,
  deviceDetail,
  deviceTitle,
  pinOf,
  rowDaily,
  shortNames,
  SOURCE_GROUPS,
  sourcesOf,
  useSources,
  useSourcesDay,
  type DeviceKind,
  type SourceEntry,
  type SourceRowSpec,
} from './sources'

/**
 * Where the person's measurements come from: the devices and services, then each kind of
 * measurement with the source it takes today. A row opens its choice; a device opens its page.
 */
const { t } = useI18n()
const route = useRoute()
const session = useSession()
const developer = useDeveloperMode()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const back = computed(() => personalReturnLink(route.query, today.value))
const canWrite = computed(() => session.can('personal.write'))
const sources = useSources()
const day = useSourcesDay(today)
const rings = useQuery({
  queryKey: ['personal', 'rings'],
  queryFn: async ({ signal }) => Rings.parse(await api.get('/v1/personal/rings', signal)),
})

const ICONS: Record<DeviceKind, Component> = {
  ring: Circle,
  whoop: Watch,
  ddx: Scale,
  caliper: Ruler,
  custom: Plug,
  collector: Plug,
}
const ORDER: DeviceKind[] = ['ring', 'whoop', 'ddx', 'caliper', 'collector', 'custom']
const list = computed(() =>
  [...(sources.data.value?.sources ?? [])].sort(
    (a, b) =>
      ORDER.indexOf(a.device.kind) - ORDER.indexOf(b.device.kind) ||
      (b.created_at ?? '').localeCompare(a.created_at ?? ''),
  ),
)
const connected = computed(() => list.value.filter(activeSource))
const switchedOff = computed(() => list.value.filter((source) => !activeSource(source)))
const names = computed(() =>
  shortNames(t, list.value, (source) =>
    dateLabel(dayIn(zone.value, new Date(source.created_at!)), today.value),
  ),
)
const batteries = computed(
  () =>
    new Map(
      (rings.data.value?.rings ?? []).map((item) => [
        item.source_id,
        item.device_state?.battery_percent ?? null,
      ]),
    ),
)
function status(source: SourceEntry) {
  const battery = batteries.value.get(source.id)
  return [
    battery !== null && battery !== undefined ? t('sources.battery', { value: battery }) : null,
    source.last_received_at
      ? t('sources.dataAt', {
          when: momentPhrase(source.last_received_at, zone.value, today.value),
        })
      : t('sources.noData'),
  ]
    .filter(Boolean)
    .join(' · ')
}

const capital = (text: string) => text.charAt(0).toLocaleUpperCase('ru-RU') + text.slice(1)
const nameOf = (id: string | null) => (id && names.value.get(id)) ?? t('sources.device.custom')
const groups = computed(() =>
  SOURCE_GROUPS.map((group) => ({
    key: group.key,
    rows: group.rows.filter((row) => sourcesOf(row, list.value).length),
  })).filter((group) => group.rows.length),
)
/** «Всегда WHOOP» when pinned, else who supplied today's value, else who can. */
function summary(row: SourceRowSpec) {
  const pinned = pinOf(row, day.data.value?.policies ?? [], today.value)
  if (pinned && names.value.has(pinned)) return t('sources.always', { name: nameOf(pinned) })
  const used = contributors(rowDaily(row, day.data.value, today.value)).map((item) =>
    nameOf(item.source_id),
  )
  const who = used.length ? used : sourcesOf(row, list.value).map((source) => nameOf(source.id))
  return capital([...new Set(who)].join(', '))
}

const chosen = ref<SourceRowSpec | null>(null)
const choosing = computed({
  get: () => chosen.value !== null,
  set: (open) => {
    if (!open) chosen.value = null
  },
})

const connecting = ref(false)
const connectOptions = computed(() => [
  { kind: 'ring' as const, available: false },
  { kind: 'whoop' as const, available: false },
  { kind: 'ddx' as const, available: true },
  ...(developer.value ? [{ kind: 'custom' as const, available: true }] : []),
])
</script>

<template>
  <PageHeader :title="t('sources.title')" :back="back" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <RefreshFailed :query="sources" />
    <LoadingState v-if="sources.isPending.value" :rows="4" />
    <ErrorState
      v-else-if="sources.isLoadingError.value"
      :error="sources.error.value"
      @retry="sources.refetch()"
    />
    <template v-else>
      <section class="space-y-2" aria-labelledby="sources-devices">
        <h2 id="sources-devices" class="font-semibold">{{ t('sources.devices') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border" data-testid="source-devices">
          <li v-for="source in connected" :key="source.id">
            <RouterLink
              :to="{ name: 'personal-source', params: { source: source.id } }"
              class="flex min-h-14 items-center gap-3 px-4 py-3"
              :data-source-id="source.id"
            >
              <component
                :is="ICONS[source.device.kind]"
                class="text-muted-foreground size-5 shrink-0"
                aria-hidden="true"
              />
              <span class="min-w-0 flex-1">
                <span class="block font-medium">
                  {{ deviceTitle(t, source) }}
                  <span v-if="deviceDetail(source)" class="text-muted-foreground font-normal">
                    · {{ deviceDetail(source) }}</span
                  >
                </span>
                <span
                  class="block text-sm"
                  :class="
                    source.status === 'unavailable' ? 'text-warning' : 'text-muted-foreground'
                  "
                  >{{
                    source.status === 'unavailable'
                      ? t('personal.sourceState.unavailable')
                      : status(source)
                  }}</span
                >
              </span>
              <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
            </RouterLink>
          </li>
          <li v-if="canWrite">
            <button
              type="button"
              class="text-primary flex min-h-14 w-full items-center gap-3 px-4 py-3 text-left"
              data-testid="source-connect"
              @click="connecting = true"
            >
              <Plus class="size-5 shrink-0" aria-hidden="true" />
              <span class="font-medium">{{ t('sources.connect') }}</span>
            </button>
          </li>
        </ul>
        <details v-if="switchedOff.length" class="text-sm">
          <summary class="text-primary min-h-11 cursor-pointer py-2">
            {{ t('sources.disabledDevices', { count: switchedOff.length }) }}
          </summary>
          <ul class="bg-card divide-y rounded-3xl border">
            <li v-for="source in switchedOff" :key="source.id">
              <RouterLink
                :to="{ name: 'personal-source', params: { source: source.id } }"
                class="flex min-h-12 items-center gap-3 px-4 py-2"
              >
                <span class="min-w-0 flex-1">
                  {{ deviceTitle(t, source) }}
                  <span class="text-muted-foreground"> · {{ t('sources.off') }}</span>
                </span>
                <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
              </RouterLink>
            </li>
          </ul>
        </details>
      </section>

      <section v-if="groups.length" class="space-y-3" aria-labelledby="sources-rows">
        <h2 id="sources-rows" class="font-semibold">{{ t('sources.whereFrom') }}</h2>
        <div v-for="group in groups" :key="group.key" class="space-y-1">
          <h3 class="text-muted-foreground px-1 text-sm">{{ t(`sources.group.${group.key}`) }}</h3>
          <ul
            class="bg-card divide-y rounded-3xl border"
            :data-testid="`source-group-${group.key}`"
          >
            <li v-for="row in group.rows" :key="row.key">
              <button
                type="button"
                class="flex min-h-14 w-full items-center gap-3 px-4 py-3 text-left"
                :data-testid="`source-row-${row.key}`"
                @click="chosen = row"
              >
                <span class="min-w-0 flex-1">
                  <span class="block font-medium">{{ t(`sources.row.${row.key}`) }}</span>
                  <span class="text-muted-foreground block text-sm">{{ summary(row) }}</span>
                </span>
                <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
              </button>
            </li>
          </ul>
        </div>
      </section>
    </template>
  </div>

  <MetricSourcesPanel
    v-if="chosen"
    v-model:open="choosing"
    :row="chosen"
    :sources="list"
    :page="day.data.value"
    :today="today"
    :zone="zone"
    :names="names"
    :can-write="canWrite"
  />

  <Panel v-model:open="connecting" :title="t('sources.connectTitle')" :autofocus="false">
    <ul class="bg-card divide-y rounded-3xl border" data-testid="source-connect-options">
      <li v-for="option in connectOptions" :key="option.kind">
        <RouterLink
          v-if="option.available"
          :to="{ name: 'personal-source', params: { source: option.kind } }"
          class="flex min-h-14 items-center gap-3 px-4 py-3"
          @click="connecting = false"
        >
          <component
            :is="ICONS[option.kind]"
            class="text-muted-foreground size-5 shrink-0"
            aria-hidden="true"
          />
          <span class="min-w-0 flex-1 font-medium">{{ t(`sources.device.${option.kind}`) }}</span>
          <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
        </RouterLink>
        <div v-else class="flex min-h-14 items-center gap-3 px-4 py-3">
          <component
            :is="ICONS[option.kind]"
            class="text-muted-foreground size-5 shrink-0"
            aria-hidden="true"
          />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ t(`sources.device.${option.kind}`) }}</span>
            <span class="text-muted-foreground block text-sm">{{ t('sources.androidOnly') }}</span>
          </span>
        </div>
      </li>
    </ul>
  </Panel>
</template>
