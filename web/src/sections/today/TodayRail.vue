<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCalendarDay } from '@/core/useCalendarDay'
import { useSession } from '@/stores/session'
import FoodCard from './FoodCard.vue'
import TasksCard from './TasksCard.vue'
import TimersCard from './TimersCard.vue'

// The day beside the conversation on a wide screen: what a message has just changed (a meal, a
// timer, a task) shows here, while the answer itself stays the model's text. The cards are those
// of «Сегодня» and load on their own.
const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const day = useCalendarDay(zone)
// Without any of its cards the rail would be a heading alone: the conversation keeps the width.
const reads = computed(() => ({
  food: session.can('nutrition.read'),
  timers: session.can('timers.read'),
  tasks: session.can('tasks.read'),
}))
</script>

<template>
  <aside
    v-if="reads.food || reads.timers || reads.tasks"
    aria-labelledby="today-rail-title"
    class="w-72 shrink-0 space-y-4 overflow-y-auto border-l px-4 pt-8 pb-6"
    data-testid="today-rail"
  >
    <h2 id="today-rail-title" class="px-1 text-lg font-semibold">
      <RouterLink :to="{ name: 'today' }" class="hover:underline">{{
        t('today.title')
      }}</RouterLink>
    </h2>
    <FoodCard v-if="reads.food" :day="day" />
    <TimersCard v-if="reads.timers" />
    <TasksCard v-if="reads.tasks" />
  </aside>
</template>
