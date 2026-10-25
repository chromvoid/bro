<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { api } from '@/core/client'
import { z } from '@/core/zod'
import { Execution } from './model'
import UsageDetails from './UsageDetails.vue'
import { UsageField } from './usage'

const props = defineProps<{ turnId: string }>()
const open = defineModel<boolean>('open', { required: true })
const { t, te } = useI18n()

const Details = z.looseObject({
  state: z.string(),
  usage: UsageField,
  result: z
    .looseObject({
      execution: Execution.nullish(),
      services: z
        .array(
          z.looseObject({
            service: z.string().nullish(),
            status: z.string().nullish(),
            receipt_text: z.string().nullish(),
            message: z.string().nullish(),
            question: z.string().nullish(),
          }),
        )
        .nullish(),
    })
    .nullish(),
})
const SERVICES: Record<string, string> = {
  nutrition: 'nav.food',
  tasks: 'nav.tasks',
  personal: 'nav.personal',
  accounts: 'nav.accounts',
  workers: 'nav.workers',
  timers: 'services.timers',
  research: 'services.research',
  general: 'services.general',
}

// Loading details of a turn with an unknown outcome also settles it on the server.
const details = useQuery({
  queryKey: computed(() => ['conversation', 'turn', props.turnId]),
  queryFn: async ({ signal }) =>
    Details.parse(
      await api.get(`/v1/conversation/turns/${encodeURIComponent(props.turnId)}`, signal),
    ),
  enabled: open,
})
const services = computed(() => details.data.value?.result?.services ?? [])
const stateLabel = (state: string | null | undefined) =>
  state && te(`assistant.state.${state}`) ? t(`assistant.state.${state}`) : (state ?? '')
</script>

<template>
  <Sheet v-model:open="open">
    <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
      <SheetHeader>
        <SheetTitle>{{ t('assistant.detailsTitle') }}</SheetTitle>
        <SheetDescription>{{ t('assistant.detailsServices') }}</SheetDescription>
      </SheetHeader>
      <div class="px-4 pb-6">
        <RefreshFailed :query="details" />
        <LoadingState v-if="details.isPending.value" :rows="2" />
        <ErrorState
          v-else-if="details.isLoadingError.value"
          :error="details.error.value"
          @retry="details.refetch()"
        />
        <p v-else-if="!services.length" class="text-muted-foreground">
          {{
            t(
              details.data.value?.result?.execution?.effects === 'none'
                ? 'assistant.noChanges'
                : 'assistant.detailsEmpty',
            )
          }}
        </p>
        <ul v-else class="space-y-3">
          <li
            v-for="(service, index) in services"
            :key="index"
            class="space-y-1.5 rounded-xl border p-3"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-medium">{{
                service.service && SERVICES[service.service]
                  ? t(SERVICES[service.service]!)
                  : service.service
              }}</span>
              <Badge
                :variant="
                  service.status === 'completed'
                    ? 'success'
                    : service.status === 'rejected'
                      ? 'destructive'
                      : 'warning'
                "
                >{{ stateLabel(service.status) }}</Badge
              >
            </div>
            <p v-if="service.receipt_text" class="text-sm">{{ service.receipt_text }}</p>
            <p v-if="service.message" class="text-muted-foreground text-sm">
              {{ service.message }}
            </p>
            <p v-if="service.question" class="text-sm">{{ service.question }}</p>
          </li>
        </ul>
        <UsageDetails v-if="details.data.value" :usage="details.data.value?.usage" class="mt-5" />
        <!-- The turn id finds this reply's trace: tools/traces.py recent --turn ID (docs/telemetry.md). -->
        <p class="text-muted-foreground mt-6 text-xs">
          {{ t('assistant.detailsDiagnostic') }}
          <span class="font-mono break-all select-all" data-testid="turn-diagnostic">{{
            turnId
          }}</span>
        </p>
      </div>
    </SheetContent>
  </Sheet>
</template>
