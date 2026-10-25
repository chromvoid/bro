<script setup lang="ts">
import { ListTodo, Plus } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import BrandMark from '@/components/BrandMark.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import { dayLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import { type Task } from '@/sections/tasks/api'
import { useTaskActions } from '@/sections/tasks/useTaskActions'
import TaskCompletionDialog from '@/sections/tasks/TaskCompletionDialog.vue'
import { useSession } from '@/stores/session'
import { useTodayTasks } from './useTodayTasks'

const { t } = useI18n()
const session = useSession()
const router = useRouter()
const actions = useTaskActions()
const today = useTodayTasks()
const tasks = computed(() => today.data.value?.items ?? [])
defineExpose({ empty: computed(() => !!today.data.value && !tasks.value.length) })

async function complete(task: Task) {
  try {
    await actions.transition(task, 'task.complete', t('tasks.completed'))
  } catch (error) {
    toast.error(errorText(error, t))
  }
}
function inspect(task: Task) {
  void router.push({
    name: 'tasks',
    query: { task: task.id, ...(task.list_id ? { list: task.list_id } : { scope: 'all' }) },
  })
}
</script>

<template>
  <section class="bg-card space-y-3 rounded-3xl border p-5" data-testid="today-tasks">
    <div class="flex flex-wrap items-center justify-between">
      <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
        <ListTodo class="size-4" />{{ t('today.tasks') }}
      </h2>
      <RouterLink
        :to="{ name: 'tasks', query: { scope: 'all' } }"
        class="text-primary text-sm font-medium"
        >{{ t('today.allTasks') }}</RouterLink
      >
    </div>
    <div v-if="today.isPending.value" class="space-y-2">
      <Skeleton v-for="row in 2" :key="row" class="h-6 w-full" />
    </div>
    <p v-else-if="today.isLoadingError.value" class="text-muted-foreground text-sm">
      {{ t('today.cardError') }}
    </p>
    <template v-else-if="!tasks.length">
      <p class="text-muted-foreground flex items-center gap-3">
        <BrandMark mood="ok" class="size-8 shrink-0" />{{ t('today.tasksEmpty') }}
      </p>
      <Button v-if="session.can('tasks.write')" variant="outline" as-child>
        <RouterLink :to="{ name: 'tasks', query: { scope: 'all', add: '1' } }"
          ><Plus />{{ t('today.startTask') }}</RouterLink
        >
      </Button>
    </template>
    <ul v-else class="space-y-2">
      <li v-for="task in tasks" :key="task.id" class="flex items-center gap-3">
        <Checkbox
          :model-value="false"
          :aria-label="t('tasks.complete')"
          class="size-6"
          @update:model-value="complete(task)"
        />
        <span class="min-w-0 flex-1">
          <span class="line-clamp-2 break-words">{{ task.title }}</span>
          <span v-if="task.overdue && task.due_on" class="text-destructive block text-xs">
            {{ t('tasks.overdue', { date: dayLabel(task.due_on, today.data.value!.today) }) }}
          </span>
        </span>
      </li>
    </ul>
    <TaskCompletionDialog :completion="actions.completion" @inspect="inspect" />
  </section>
</template>
