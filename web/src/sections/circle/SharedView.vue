<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight, Eye, Lock } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import DayNav from '@/components/DayNav.vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { api } from '@/core/client'
import { dayIn, isIsoDay } from '@/core/dates'
import { ApiError } from '@/core/http'
import { formatAmount } from '@/core/numbers'
import { z } from '@/core/zod'
import { useSession } from '@/stores/session'
import { Day, entryName, isDish, unitLabel, shownName } from '@/sections/food/api'
import { KINDS, amountText, type Kind } from '@/sections/personal/api'
import { Sharing, readScope } from '@/sections/settings/sharing'

/**
 * S06: sections other people opened to me, read through /v1/shared only. Nothing here is kept
 * after leaving the screen, and a revoked grant shows as such instead of stale data.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))
const sharing = useQuery({
  queryKey: ['shared', 'mine'],
  queryFn: async ({ signal }) => Sharing.parse(await api.get('/v1/me/sharing', signal)),
})
const opened = computed(() =>
  (sharing.data.value?.shared ?? []).filter((item) => session.can(readScope(item.name))),
)
const selectedId = computed(() => (typeof route.query.id === 'string' ? route.query.id : null))
const selected = computed(() => opened.value.find((item) => item.id === selectedId.value) ?? null)
// Without a day the server shows the owner's today.
const askedDay = computed(() => (isIsoDay(route.query.day) ? route.query.day : null))

const Metrics = z.record(
  z.string(),
  z.looseObject({ status: z.string(), value: z.union([z.string(), z.number()]).nullish() }),
)
const View = z.looseObject({
  section: z.string(),
  permission: z.string(),
  data: z.looseObject({ date: z.string().nullish() }),
})
const view = useQuery({
  queryKey: computed(() => ['shared', 'view', selectedId.value, askedDay.value]),
  enabled: computed(() => selected.value !== null),
  // Another person's data is not kept once the screen is left.
  gcTime: 0,
  retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  queryFn: async ({ signal }) => {
    const query = askedDay.value ? `?date=${askedDay.value}` : ''
    return View.parse(
      await api.get(`/v1/shared/${encodeURIComponent(selectedId.value!)}${query}`, signal),
    )
  },
})
const revoked = computed(
  () => view.error.value instanceof ApiError && view.error.value.status === 404,
)
const shownDay = computed(() => view.data.value?.data.date ?? askedDay.value ?? today.value)
const detail = computed(() => view.data.value?.permission !== 'summary')
const diary = computed(() => {
  const data = view.data.value
  if (data?.section !== 'nutrition' || !detail.value) return null
  const parsed = Day.safeParse(data.data)
  return parsed.success ? parsed.data : null
})
const nutritionSummary = computed(() => {
  const data = view.data.value
  if (data?.section !== 'nutrition' || detail.value) return null
  return data.data as { value?: string | null; target?: string | null; remaining?: string | null }
})
const metrics = computed(() => {
  const data = view.data.value
  if (data?.section !== 'personal') return []
  const parsed = Metrics.safeParse((data.data as { metrics?: unknown }).metrics)
  if (!parsed.success) return []
  return KINDS.map((kind) => ({ kind, metric: parsed.data[kind] })).filter((item) => item.metric)
})
const profile = computed(() => {
  const data = view.data.value
  if (data?.section !== 'personal' || !detail.value) return null
  return (data.data as { profile?: { height_cm?: string | null; birth_date?: string | null } })
    .profile
})

function open(id: string | null) {
  void router.push({ query: id ? { id } : {} })
}
function go(day: string) {
  void router.replace({ query: { ...route.query, day } })
}
const metricText = (kind: Kind, metric: { status: string; value?: string | number | null }) =>
  metric.status === 'conflict'
    ? t('personal.disagree')
    : (amountText(
        kind,
        metric.value === null || metric.value === undefined ? null : String(metric.value),
      ) ?? t('personal.noData'))
</script>

<template>
  <PageHeader
    :title="selected ? t(`access.section.${selected.name}`) : t('shared.title')"
    :description="selected ? t('shared.of', { name: selected.owner_name }) : undefined"
    :back="selected ? { name: 'circle-shared' } : { name: 'circle' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="sharing" />
    <LoadingState v-if="sharing.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="sharing.isLoadingError.value"
      :error="sharing.error.value"
      @retry="sharing.refetch()"
    />

    <template v-else-if="!selectedId">
      <EmptyState
        v-if="!opened.length"
        :icon="Eye"
        :title="t('shared.empty')"
        :description="t('shared.emptyText')"
      />
      <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="shared-list">
        <li v-for="item in opened" :key="item.id">
          <button
            type="button"
            class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
            @click="open(item.id)"
          >
            <span class="min-w-0 flex-1">
              <span class="block font-medium"
                >{{ item.owner_name }} · {{ t(`access.section.${item.name}`).toLowerCase() }}</span
              >
              <span class="text-muted-foreground block text-sm"
                ><template v-if="item.owner_login">@{{ item.owner_login }} · </template
                >{{ t(`shared.level.${item.permission}`) }}</span
              >
            </span>
            <ChevronRight class="text-muted-foreground size-4" />
          </button>
        </li>
      </ul>
    </template>

    <template v-else>
      <Alert v-if="revoked || !selected" variant="warning" data-testid="shared-revoked">
        <Lock />
        <AlertTitle>{{ t('shared.revoked') }}</AlertTitle>
        <AlertDescription>{{ t('shared.revokedText') }}</AlertDescription>
      </Alert>
      <template v-else>
        <DayNav :day="shownDay" :today="today" @go="go" />
        <RefreshFailed :query="view" />
        <LoadingState v-if="view.isPending.value" :rows="2" />
        <ErrorState
          v-else-if="view.isLoadingError.value"
          :error="view.error.value"
          @retry="view.refetch()"
        />
        <template v-else-if="view.data.value">
          <section
            v-if="nutritionSummary"
            class="bg-card space-y-2 rounded-3xl border p-5"
            data-testid="shared-view"
          >
            <p class="text-muted-foreground text-sm">{{ t('shared.eaten') }}</p>
            <p class="text-4xl font-semibold tabular-nums">
              {{ formatAmount(nutritionSummary.value, 0) }}
              <span class="text-muted-foreground text-base font-normal">{{ t('food.kcal') }}</span>
            </p>
            <p v-if="nutritionSummary.target" class="text-muted-foreground text-sm">
              {{ t('shared.goal', { value: formatAmount(nutritionSummary.target, 0) }) }}
            </p>
          </section>
          <template v-else-if="diary">
            <section class="bg-card space-y-2 rounded-3xl border p-5" data-testid="shared-view">
              <p class="text-muted-foreground text-sm">{{ t('shared.eaten') }}</p>
              <p class="text-4xl font-semibold tabular-nums">
                {{ formatAmount(diary.totals.energy_kcal?.known, 0) }}
                <span class="text-muted-foreground text-base font-normal">{{
                  diary.goal
                    ? t('food.of', { target: formatAmount(diary.goal.calorie_target, 0) })
                    : t('food.kcal')
                }}</span>
              </p>
            </section>
            <ul v-if="diary.items.length" class="bg-card divide-y rounded-3xl border">
              <li
                v-for="item in diary.items"
                :key="item.id"
                class="flex items-center gap-3 px-4 py-3"
              >
                <span class="min-w-0 flex-1">
                  <span class="block truncate font-medium">{{
                    shownName(isDish(item) ? item.name : entryName(item))
                  }}</span>
                  <span class="text-muted-foreground text-sm"
                    >{{ formatAmount(item.quantity) }} {{ unitLabel(item.unit) }}</span
                  >
                </span>
                <span class="tabular-nums">{{ formatAmount(item.nutrients?.energy_kcal, 0) }}</span>
              </li>
            </ul>
            <p v-else class="text-muted-foreground text-sm">{{ t('shared.noEntries') }}</p>
          </template>
          <template v-else-if="metrics.length">
            <ul class="grid gap-3 sm:grid-cols-2" data-testid="shared-view">
              <li
                v-for="item in metrics"
                :key="item.kind"
                class="bg-card space-y-1 rounded-3xl border p-5"
              >
                <p class="text-muted-foreground text-sm">{{ t(`personal.metric.${item.kind}`) }}</p>
                <p class="text-xl font-semibold tabular-nums">
                  {{ metricText(item.kind, item.metric!) }}
                </p>
              </li>
            </ul>
            <dl
              v-if="profile"
              class="bg-card grid grid-cols-2 gap-3 rounded-3xl border p-5 text-sm"
            >
              <div>
                <dt class="text-muted-foreground">{{ t('personal.height') }}</dt>
                <dd>{{ formatAmount(profile.height_cm ?? null) }}</dd>
              </div>
              <div>
                <dt class="text-muted-foreground">{{ t('personal.birthDate') }}</dt>
                <dd>{{ profile.birth_date ?? '—' }}</dd>
              </div>
            </dl>
          </template>
          <p class="text-muted-foreground flex items-center gap-2 text-sm">
            <Badge variant="secondary">{{ t(`shared.level.${view.data.value.permission}`) }}</Badge>
            {{ t('shared.readOnly') }}
          </p>
        </template>
      </template>
    </template>
  </div>
</template>
