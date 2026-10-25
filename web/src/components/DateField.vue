<script setup lang="ts">
import { CalendarDays, X } from '@lucide/vue'
import { computed, useTemplateRef, type HTMLAttributes } from 'vue'
import { useI18n } from 'vue-i18n'
import { dayIn, dayLabel, isIsoDay } from '@/core/dates'
import { cn } from '@/lib/utils'
import { useSession } from '@/stores/session'

/**
 * A date as people say it («Сегодня», «8 октября»), not «дд.мм.гггг». The platform's own date
 * picker lies over the field, invisible: a tap opens it, a mouse click asks for it. Attributes
 * (id, min, max, required, aria-*) reach that input, so a Label still names it.
 */
defineOptions({ inheritAttrs: false })
const props = defineProps<{
  /** Shows a button that empties an optional date. */
  clearable?: boolean
  /** What an empty field says; «Не выбрана» by default. */
  placeholder?: string
  class?: HTMLAttributes['class']
}>()
const model = defineModel<string>({ default: '' })
const { t } = useI18n()
const session = useSession()
const today = computed(() =>
  dayIn(session.me?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone),
)
const label = computed(() =>
  isIsoDay(model.value)
    ? dayLabel(model.value, today.value)
    : (props.placeholder ?? t('common.dateNotSet')),
)
const input = useTemplateRef<HTMLInputElement>('input')
function pick() {
  // Touch opens the picker by itself; a mouse click on a transparent input may not.
  if (!window.matchMedia?.('(pointer: fine)').matches) return
  try {
    input.value?.showPicker()
  } catch {
    // Outside a user gesture or unsupported: the input still takes typing.
  }
}
</script>

<template>
  <div
    :class="
      cn(
        'has-[input:focus-visible]:border-ring has-[input:focus-visible]:ring-ring/50 dark:bg-input/30 border-input relative flex h-11 w-full min-w-0 items-center gap-2 rounded-xl border bg-transparent px-3.5 text-base transition-colors has-[input:disabled]:opacity-50 has-[input:focus-visible]:ring-3 md:pointer-fine:text-sm',
        props.class,
      )
    "
    data-slot="date-field"
  >
    <CalendarDays class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
    <span
      :class="cn('min-w-0 flex-1 truncate', !isIsoDay(model) && 'text-muted-foreground')"
      aria-hidden="true"
      data-testid="date-field-label"
      >{{ label }}</span
    >
    <input
      ref="input"
      v-model="model"
      type="date"
      v-bind="$attrs"
      class="absolute inset-0 h-full w-full cursor-pointer opacity-0 [&::-webkit-calendar-picker-indicator]:absolute [&::-webkit-calendar-picker-indicator]:inset-0 [&::-webkit-calendar-picker-indicator]:size-full [&::-webkit-calendar-picker-indicator]:cursor-pointer"
      @click="pick"
    />
    <button
      v-if="clearable && model"
      type="button"
      class="text-muted-foreground hover:bg-accent focus-visible:ring-ring/50 relative -me-2 grid size-9 shrink-0 place-items-center rounded-lg outline-none focus-visible:ring-3"
      :aria-label="t('common.clearDate')"
      @click="model = ''"
    >
      <X class="size-4" aria-hidden="true" />
    </button>
  </div>
</template>
