<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { RefreshCw } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { timeIn } from '@/core/dates'
import { useDeveloperMode } from '@/core/developer'
import { z } from '@/core/zod'
import { useSession } from '@/stores/session'
import ScreenDiagnostics from './ScreenDiagnostics.vue'

/**
 * S09: what this installation offers, in terms of the user's tasks first. A provider that was
 * only connected at start is never called «working»: its live health is not checked here.
 */
const { t } = useI18n()
const session = useSession()
// The installation inventory names models and providers: for developer mode only.
const developer = useDeveloperMode()
const Item = z.looseObject({
  id: z.string(),
  name: z.string(),
  description: z.string().nullish(),
  status: z.string(),
  detail: z.string().nullish(),
  note: z.string().nullish(),
  version: z.string().nullish(),
  provider: z.looseObject({ name: z.string(), model: z.string().nullish() }).nullish(),
})
type Item = z.infer<typeof Item>
const Inventory = z.looseObject({
  modules: z.array(Item),
  plugins: z.array(Item),
  checked_at: z.string(),
})
const inventory = useQuery({
  queryKey: ['me', 'capabilities'],
  enabled: computed(() => developer.value && session.can('session.control')),
  queryFn: async ({ signal }) => Inventory.parse(await api.get('/v1/conversation/modules', signal)),
})

/** The user's own features, from the scopes of this session. */
const features = computed(() =>
  (
    [
      ['assistant', 'session.control'],
      ['nutrition', 'nutrition.read'],
      ['tasks', 'tasks.read'],
      ['timers', 'timers.read'],
      ['personal', 'personal.read'],
      ['challenges', 'challenges.read'],
    ] as const
  ).map(([key, scope]) => ({ key, available: session.can(scope) })),
)
const tone = (item: Item) =>
  item.status === 'connected' ? 'success' : item.status === 'disabled' ? 'secondary' : 'warning'
const describeItem = (item: Item) =>
  [
    item.provider
      ? `${item.provider.name}${item.provider.model ? ` · ${item.provider.model}` : ''}`
      : '',
    item.detail ?? '',
    item.note ?? '',
  ]
    .filter(Boolean)
    .join(' · ')
</script>

<template>
  <PageHeader :title="t('capabilities.title')" :back="{ name: 'settings' }" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <section class="space-y-2" aria-labelledby="features-title">
      <h2 id="features-title" class="font-semibold">{{ t('capabilities.yours') }}</h2>
      <ul class="bg-card divide-y rounded-3xl border" data-testid="features">
        <li
          v-for="feature in features"
          :key="feature.key"
          class="flex flex-wrap items-center justify-between gap-3 px-4 py-3"
        >
          <span>
            <span class="block font-medium">{{ t(`capabilities.feature.${feature.key}`) }}</span>
            <span class="text-muted-foreground block text-sm">{{
              t(`capabilities.featureText.${feature.key}`)
            }}</span>
          </span>
          <Badge :variant="feature.available ? 'success' : 'secondary'">{{
            feature.available ? t('capabilities.available') : t('capabilities.notAvailable')
          }}</Badge>
        </li>
      </ul>
      <Alert>
        <AlertTitle>{{ t('capabilities.managed') }}</AlertTitle>
        <AlertDescription>{{ t('capabilities.managedText') }}</AlertDescription>
      </Alert>
    </section>

    <section
      v-if="developer && session.can('session.control')"
      class="space-y-3"
      aria-labelledby="installation-title"
    >
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 id="installation-title" class="font-semibold">
            {{ t('capabilities.installation') }}
          </h2>
          <p v-if="inventory.data.value" class="text-muted-foreground text-sm">
            {{
              t('capabilities.checked', {
                time: timeIn(session.me?.timezone ?? 'UTC', inventory.data.value.checked_at),
              })
            }}
          </p>
        </div>
        <Button
          variant="ghost"
          size="icon"
          :aria-label="t('capabilities.refresh')"
          :disabled="inventory.isFetching.value"
          @click="inventory.refetch()"
        >
          <RefreshCw :class="['size-4', inventory.isFetching.value && 'animate-spin']" />
        </Button>
      </div>
      <RefreshFailed :query="inventory" />
      <LoadingState v-if="inventory.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="inventory.isLoadingError.value"
        :error="inventory.error.value"
        @retry="inventory.refetch()"
      />
      <template v-else-if="inventory.data.value">
        <div
          v-for="group in ['modules', 'plugins'] as const"
          :key="group"
          class="space-y-2"
          :data-testid="`inventory-${group}`"
        >
          <h3 class="text-muted-foreground text-sm font-medium">
            {{ t(`capabilities.${group}`) }}
          </h3>
          <ul v-if="inventory.data.value[group].length" class="bg-card divide-y rounded-3xl border">
            <li
              v-for="item in inventory.data.value[group]"
              :key="item.id"
              class="flex items-start gap-3 px-4 py-3"
            >
              <div class="min-w-0 flex-1">
                <p class="font-medium">
                  {{ item.name }}
                  <span v-if="item.version" class="text-muted-foreground text-sm font-normal">
                    {{ t('capabilities.version', { version: item.version }) }}</span
                  >
                </p>
                <p v-if="item.description" class="text-muted-foreground text-sm">
                  {{ item.description }}
                </p>
                <p v-if="describeItem(item)" class="text-muted-foreground mt-1 text-xs">
                  {{ describeItem(item) }}
                </p>
              </div>
              <Badge :variant="tone(item)" class="shrink-0">{{
                t(`capabilities.status.${item.status}`)
              }}</Badge>
            </li>
          </ul>
          <p v-else class="text-muted-foreground text-sm">{{ t('capabilities.noPlugins') }}</p>
        </div>
      </template>
    </section>
    <ScreenDiagnostics v-if="developer" />
  </div>
</template>
