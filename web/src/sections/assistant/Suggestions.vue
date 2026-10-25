<script setup lang="ts">
import { ClipboardCheck, ListTodo, Utensils } from '@lucide/vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { dayIn, hourIn } from '@/core/dates'
import { useMinuteNow } from '@/core/useCalendarDay'
import { suggestedMealKind } from '@/sections/food/api'
import { useFoodDay } from '@/sections/food/useFood'
import { useTodayTasks } from '@/sections/today/useTodayTasks'
import { useSession } from '@/stores/session'
import { daySuggestions, foodRecorded, mealRecorded, type SuggestionKey } from './daySuggestions'

const props = defineProps<{
  /** Examples of what to write, for a conversation that has none yet; each begins a message. */
  examples: string[]
}>()
const emit = defineEmits<{
  /** Send this question as it reads. */
  ask: [text: string]
  /** Put this beginning in the field for the person to finish. */
  start: [text: string]
}>()

const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const now = useMinuteNow()
const today = computed(() => dayIn(zone.value, now.value))
const meal = computed(() => suggestedMealKind(now.value, zone.value))
const readsFood = computed(() => session.can('nutrition.read'))
const readsTasks = computed(() => session.can('tasks.read'))
// The same reads as «Сегодня»: a meal the assistant records refreshes them through change events.
const diary = useFoodDay(today, readsFood)
const tasks = useTodayTasks(readsTasks)
const ICONS: Record<SuggestionKey, Component> = {
  breakfast: Utensils,
  lunch: Utensils,
  dinner: Utensils,
  dayAhead: ListTodo,
  dayLeft: ListTodo,
  summary: ClipboardCheck,
}

interface Item {
  key: string
  label: string
  text: string
  mode: 'ask' | 'start'
  icon?: Component
}

// The row appears once, when what it depends on has loaded; a failed read only drops its own.
const settled = computed(
  () =>
    (!readsFood.value || !diary.isPending.value) && (!readsTasks.value || !tasks.isPending.value),
)
const items = computed<Item[]>(() => {
  if (!settled.value) return []
  const day = diary.data.value
  const fromDay: Item[] = daySuggestions({
    hour: hourIn(zone.value, now.value),
    meal: meal.value,
    mealRecorded: day && session.can('nutrition.write') ? mealRecorded(day, meal.value) : null,
    foodRecorded: day ? foodRecorded(day) : null,
    tasksToday: tasks.data.value ? tasks.data.value.items.length > 0 : null,
  }).map(({ key, mode }) => ({
    key,
    mode,
    label: t(`assistant.suggestions.${key}`),
    // A beginning continues after a space, typed or dictated.
    text:
      mode === 'start'
        ? `${t(`assistant.suggestions.${key}Start`)} `
        : t(`assistant.suggestions.${key}`),
    icon: ICONS[key],
  }))
  const asked = new Set(fromDay.map((item) => item.text))
  const examples: Item[] = props.examples
    .filter((example) => !asked.has(example))
    .map((example) => ({ key: example, mode: 'start', label: example, text: example }))
  return [...fromDay, ...examples]
})
</script>

<template>
  <!-- On a phone one row that scrolls sideways to the screen's edges; wider, they wrap. -->
  <div
    v-if="items.length"
    role="group"
    :aria-label="t('assistant.suggestions.label')"
    class="flex gap-2 max-md:-mx-3 max-md:overflow-x-auto max-md:px-3 max-md:py-1 max-md:[scrollbar-width:none] md:flex-wrap [&::-webkit-scrollbar]:hidden"
    data-testid="suggestions"
  >
    <Button
      v-for="item in items"
      :key="item.key"
      type="button"
      variant="outline"
      size="sm"
      class="bg-card shrink-0 rounded-full"
      @click="item.mode === 'ask' ? emit('ask', item.text) : emit('start', item.text)"
    >
      <component :is="item.icon" v-if="item.icon" class="text-primary" />{{ item.label }}
    </Button>
  </div>
</template>
