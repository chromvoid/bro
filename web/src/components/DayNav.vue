<script setup lang="ts">
import { CalendarDays, ChevronLeft, ChevronRight } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { dayLabel, fullDayLabel, isIsoDay, shiftDay, shortDateLabel } from '@/core/dates'

/**
 * Moves between calendar days of the account's zone; later than today is not offered. One look on
 * every screen: «‹ Сегодня · 12 окт. ›», and an older day is just its date. Large text wraps, and
 * the arrows stay 44 px so the date keeps its room.
 */
defineProps<{ day: string; today: string }>()
const emit = defineEmits<{ go: [day: string] }>()
const { t } = useI18n()
const picking = ref(false)

function pick(value: unknown) {
  if (!isIsoDay(value)) return
  emit('go', value)
  picking.value = false
}
</script>

<template>
  <nav class="flex items-center gap-2" :aria-label="t('day.pick')">
    <Button
      variant="ghost"
      size="icon"
      class="size-[44px]"
      :aria-label="t('day.previous')"
      @click="emit('go', shiftDay(day, -1))"
    >
      <ChevronLeft class="size-5" />
    </Button>
    <Popover v-model:open="picking">
      <PopoverTrigger as-child>
        <Button
          variant="ghost"
          class="h-auto min-h-11 min-w-0 flex-1 flex-wrap gap-x-1.5 px-1 whitespace-normal"
          :aria-label="fullDayLabel(day)"
          data-testid="day-label"
        >
          <span class="text-base font-semibold">{{ dayLabel(day, today) }}</span>
          <span v-if="day >= shiftDay(today, -1)" class="text-muted-foreground text-sm"
            >· {{ shortDateLabel(day, today) }}</span
          >
        </Button>
      </PopoverTrigger>
      <PopoverContent class="w-72 space-y-3">
        <label class="space-y-2 text-sm font-medium">
          <span class="flex items-center gap-2"
            ><CalendarDays class="size-4" />{{ t('day.pick') }}</span
          >
          <Input type="date" :model-value="day" :max="today" @update:model-value="pick" />
        </label>
        <Button v-if="day !== today" variant="outline" class="w-full" @click="pick(today)">
          {{ t('day.today') }}
        </Button>
      </PopoverContent>
    </Popover>
    <Button
      variant="ghost"
      size="icon"
      class="size-[44px]"
      :aria-label="t('day.next')"
      :disabled="day >= today"
      @click="emit('go', shiftDay(day, 1))"
    >
      <ChevronRight class="size-5" />
    </Button>
  </nav>
</template>
