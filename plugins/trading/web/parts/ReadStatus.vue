<script setup lang="ts">
import { CircleAlert } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { PluginHost } from '@/plugins/api'
import { errorText, t } from '../labels'
import type { ReadModel } from '../model'

/**
 * Everything but the data: a placeholder of its shape while loading, not connected, denied, unavailable, and a
 * failed refresh over data kept (the screen's freshness line tells how old it is).
 */
withDefaults(
  defineProps<{ model: ReadModel<unknown>; host: PluginHost; shape?: 'card' | 'list' | 'chart' }>(),
  {
    shape: 'card',
  },
)
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="model.kind === 'loading'" class="space-y-2" role="status" :aria-label="t('loading')">
    <Skeleton v-if="shape === 'chart'" class="h-48 w-full rounded-3xl" />
    <template v-else-if="shape === 'list'">
      <Skeleton v-for="row in 4" :key="row" class="h-14 w-full rounded-2xl" />
    </template>
    <Skeleton v-else class="h-32 w-full rounded-3xl" />
  </div>
  <div
    v-else-if="model.kind === 'not_connected'"
    class="bg-card space-y-3 rounded-2xl border p-4"
    data-testid="trading-not-connected"
  >
    <p>{{ t('not_connected') }}</p>
    <Button
      v-if="host.capabilities.value.configurationWritable"
      size="sm"
      @click="host.navigate({ name: 'connect', ids: {} })"
    >
      {{ t('connect_action') }}
    </Button>
  </div>
  <p v-else-if="model.kind === 'denied'" class="text-destructive text-sm" role="alert">
    {{ t('denied') }}
  </p>
  <div v-else-if="model.kind === 'unavailable'" class="space-y-2" role="alert">
    <p class="text-destructive flex items-start gap-2 text-sm">
      <CircleAlert class="mt-0.5 size-4 shrink-0" />{{ errorText(model.error) }}
    </p>
    <Button size="sm" variant="outline" @click="emit('retry')">{{ t('refresh') }}</Button>
  </div>
  <template v-else-if="model.kind === 'ready'">
    <p v-if="model.problem" class="text-destructive text-sm" role="alert">
      {{ errorText(model.problem) }}
    </p>
  </template>
</template>
