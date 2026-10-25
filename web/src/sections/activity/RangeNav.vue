<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DateField from '@/components/DateField.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { isIsoDay, rangeLabel, shiftDay } from '@/core/dates'
import { rangeDigits } from './charts'

/**
 * The period on view, like DayNav for a day: «‹ 1–7 окт. ›» steps a whole period back or
 * forward; the label opens the period's length (7, 30, 90 days) and its last day.
 */
const props = defineProps<{ anchor: string; period: number; today: string }>()
const emit = defineEmits<{ go: [day: string]; period: [days: number] }>()
const { t } = useI18n()
const picking = ref(false)
const from = computed(() => shiftDay(props.anchor, 1 - props.period))
const periods = computed(() =>
  [7, 30, 90].map((days) => ({ value: String(days), label: t('activity.period', { days }) })),
)
const later = computed(() => {
  const next = shiftDay(props.anchor, props.period)
  return next > props.today ? props.today : next
})
function toToday() {
  emit('go', props.today)
  picking.value = false
}
function pick(value: unknown) {
  if (!isIsoDay(value) || value > props.today) return
  emit('go', value)
}
</script>

<template>
  <!-- Narrow (the kind filter shares the row on a phone) the label turns to digits. -->
  <nav class="@container flex min-w-0 items-center gap-1" :aria-label="t('activity.periodLabel')">
    <Button
      variant="ghost"
      size="icon"
      :aria-label="t('activity.previousPeriod')"
      @click="emit('go', shiftDay(anchor, -period))"
      ><ChevronLeft class="size-5"
    /></Button>
    <Popover v-model:open="picking">
      <PopoverTrigger as-child>
        <Button
          variant="ghost"
          class="h-auto min-h-11 min-w-0 flex-1 px-2 text-base font-semibold @max-[15rem]:px-1 @max-[15rem]:text-sm"
          data-testid="activity-range"
        >
          <span class="truncate @max-[15rem]:sr-only">{{ rangeLabel(from, anchor) }}</span>
          <span class="hidden tabular-nums @max-[15rem]:inline" aria-hidden="true">{{
            rangeDigits(from, anchor)
          }}</span>
          <span class="sr-only">{{ t('activity.changePeriod') }}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent class="w-72 space-y-4">
        <SegmentedControl
          stretch
          :segments="periods"
          :label="t('activity.periodLabel')"
          :model-value="String(period)"
          @update:model-value="emit('period', Number($event))"
        />
        <div class="space-y-1.5">
          <Label for="activity-until">{{ t('activity.until') }}</Label>
          <DateField
            id="activity-until"
            :max="today"
            :model-value="anchor"
            @update:model-value="pick"
          />
        </div>
        <Button v-if="anchor !== today" variant="outline" class="w-full" @click="toToday">
          {{ t('activity.toToday') }}
        </Button>
      </PopoverContent>
    </Popover>
    <Button
      variant="ghost"
      size="icon"
      :disabled="anchor >= today"
      :aria-label="t('activity.nextPeriod')"
      @click="emit('go', later)"
      ><ChevronRight class="size-5"
    /></Button>
  </nav>
</template>
