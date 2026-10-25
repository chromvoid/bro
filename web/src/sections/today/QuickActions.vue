<script setup lang="ts">
import { ListTodo, Scale, Timer, Utensils } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dayIn } from '@/core/dates'
import { cn } from '@/lib/utils'
import WeightPanel from '@/sections/personal/WeightPanel.vue'
import QuickTaskPanel from '@/sections/tasks/QuickTaskPanel.vue'
import { useSession } from '@/stores/session'
import QuickTimerPanel from './QuickTimerPanel.vue'

/** «Сегодня» as a starting point: the four things people record most, one tap each. */
const { t } = useI18n()
const session = useSession()
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))
const task = ref(false)
const weight = ref(false)
const timer = ref(false)
const actions = computed(() =>
  [
    { key: 'food', icon: Utensils, label: 'today.quickFood', scope: 'nutrition.write' },
    { key: 'task', icon: ListTodo, label: 'today.quickTask', scope: 'tasks.write' },
    { key: 'weight', icon: Scale, label: 'today.quickWeight', scope: 'personal.write' },
    { key: 'timer', icon: Timer, label: 'today.quickTimer', scope: 'timers.write' },
  ].filter((action) => session.can(action.scope)),
)
const columns = computed(
  () =>
    ({ 1: 'grid-cols-1', 2: 'grid-cols-2', 3: 'grid-cols-3' })[actions.value.length] ??
    'grid-cols-4',
)
const item =
  'bg-card hover:bg-accent/60 flex min-h-16 flex-col items-center justify-center gap-1 rounded-2xl border px-2 py-3 text-sm font-medium transition-colors'

function act(key: string) {
  if (key === 'task') task.value = true
  if (key === 'weight') weight.value = true
  if (key === 'timer') timer.value = true
}
</script>

<template>
  <nav
    v-if="actions.length"
    :class="cn('grid gap-2', columns)"
    :aria-label="t('today.quick')"
    data-testid="today-quick"
  >
    <template v-for="action in actions" :key="action.key">
      <RouterLink
        v-if="action.key === 'food'"
        :to="{ name: 'food', query: { add: '1' } }"
        :class="item"
      >
        <component :is="action.icon" class="text-primary size-5" />{{ t(action.label) }}
      </RouterLink>
      <button v-else type="button" :class="item" @click="act(action.key)">
        <component :is="action.icon" class="text-primary size-5" />{{ t(action.label) }}
      </button>
    </template>
  </nav>
  <QuickTaskPanel v-model:open="task" :today="today" planned-for="today" />
  <WeightPanel v-model:open="weight" />
  <QuickTimerPanel v-model:open="timer" />
</template>
