<script setup lang="ts">
import { useInfiniteQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import LinkedText from '@/components/LinkedText'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import ErrorState from '@/components/state/ErrorState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { TASKS, TaskPage, type Task } from './api'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import { useTaskCompletion } from './useTaskCompletion'
const props = defineProps<{ task: Task; canWrite: boolean }>()
const emit = defineEmits<{ select: [task: Task] }>()
const { t } = useI18n()
const ledger = useLedger(TASKS)
const completion = useTaskCompletion(ledger.operate)
watch(() => props.task.id, completion.cancel)
const section = ref<HTMLElement | null>(null)
defineExpose({
  focus() {
    section.value?.scrollIntoView({ block: 'start', behavior: 'instant' })
    section.value?.focus({ preventScroll: true })
  },
})
const busy = ref(false)
const step = ref('')
const child = ref('')
const children = useInfiniteQuery({
  queryKey: computed(() => ['tasks', 'children', props.task.id]),
  enabled: computed(() => !props.task.parent_id),
  initialPageParam: null as string | null,
  queryFn: async ({ signal, pageParam }) => {
    const params = new URLSearchParams({
      parent_id: props.task.id,
      scope: 'all',
      view: 'all',
      completed_scope: 'all',
      limit: '100',
    })
    if (pageParam) params.set('cursor', pageParam)
    return TaskPage.parse(await api.get(`/v1/tasks/items?${params}`, signal))
  },
  getNextPageParam: (page) => page.next_cursor,
})
const subtasks = computed(() => children.data.value?.pages.flatMap((page) => page.items) ?? [])
async function moreSubtasks() {
  const result = await children.fetchNextPage()
  if (result.isError) toast.error(errorText(result.error, t))
}
async function change(checklist: NonNullable<Task['checklist']>) {
  if (busy.value || !props.canWrite) return
  busy.value = true
  try {
    await ledger.operate({
      kind: 'task.update',
      payload: { task_id: props.task.id, changes: { checklist } },
      expected_revisions: { [props.task.id]: props.task.revision },
    })
    step.value = ''
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
function toggle(id: string, done: boolean) {
  void change(
    (props.task.checklist ?? []).map((item) => (item.id === id ? { ...item, done } : item)),
  )
}
function addStep() {
  if (step.value.trim())
    void change([
      ...(props.task.checklist ?? []),
      { id: crypto.randomUUID(), text: step.value.trim(), done: false },
    ])
}
async function addChild() {
  if (!child.value.trim() || busy.value || !props.canWrite) return
  busy.value = true
  try {
    const response = await completion.operate({
      kind: 'task.create',
      ...(props.task.list_id ? { target_resource_id: props.task.list_id } : {}),
      payload: { title: child.value.trim(), parent_id: props.task.id },
      expected_revisions: { [props.task.id]: props.task.revision },
    })
    if (response === null) return
    child.value = ''
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section
    ref="section"
    tabindex="-1"
    class="focus-visible:ring-ring space-y-3 border-t pt-4 outline-none focus-visible:ring-2"
    :aria-label="t('tasks.checklist')"
  >
    <h3 class="font-medium">{{ t('tasks.checklist') }}</h3>
    <ul class="space-y-2">
      <li v-for="item in task.checklist ?? []" :key="item.id" class="flex items-center gap-2">
        <Checkbox
          :model-value="item.done"
          :disabled="busy || !canWrite"
          :aria-label="item.text"
          @update:model-value="toggle(item.id, $event === true)"
        /><LinkedText
          :text="item.text"
          :class="[
            'min-w-0 flex-1 break-words text-sm',
            item.done && 'text-muted-foreground line-through',
          ]"
        /><Button
          v-if="canWrite"
          variant="ghost"
          size="sm"
          :disabled="busy"
          @click="change((task.checklist ?? []).filter((row) => row.id !== item.id))"
          >{{ t('tasks.removeStep') }}</Button
        >
      </li>
    </ul>
    <form v-if="canWrite" class="flex gap-2" @submit.prevent="addStep">
      <Input
        v-model="step"
        maxlength="1000"
        :aria-label="t('tasks.newStep')"
        :placeholder="t('tasks.newStep')"
      /><Button type="submit" variant="outline" :disabled="busy || !step.trim()">{{
        t('tasks.add')
      }}</Button>
    </form>
    <template v-if="!task.parent_id"
      ><h3 class="pt-3 font-medium">{{ t('tasks.subtasks') }}</h3>
      <RefreshFailed :query="children" />
      <ErrorState
        v-if="children.isLoadingError.value"
        :error="children.error.value"
        @retry="children.refetch()"
      />
      <ul class="divide-y">
        <li v-for="item in subtasks" :key="item.id" class="relative py-2 text-sm">
          <button
            type="button"
            class="focus-visible:ring-ring absolute inset-0 rounded focus-visible:ring-2 focus-visible:outline-none"
            :aria-label="item.title"
            @click="emit('select', item)"
          />
          <LinkedText
            :text="item.title"
            :class="[
              'pointer-events-none relative block break-words',
              item.status === 'done' && 'text-muted-foreground line-through',
            ]"
          />
        </li>
      </ul>
      <Button
        v-if="children.hasNextPage.value"
        size="sm"
        variant="ghost"
        :disabled="children.isFetchingNextPage.value"
        @click="moreSubtasks"
        >{{ t('tasks.more') }}</Button
      >
      <p v-if="canWrite && task.status === 'done'" class="text-muted-foreground text-sm">
        {{ t('tasks.reopenBeforeSubtask') }}
      </p>
      <form v-else-if="canWrite" class="flex gap-2" @submit.prevent="addChild">
        <Input
          v-model="child"
          maxlength="300"
          :aria-label="t('tasks.newSubtask')"
          :placeholder="t('tasks.newSubtask')"
        /><Button type="submit" variant="outline" :disabled="busy || !child.trim()">{{
          t('tasks.add')
        }}</Button>
      </form></template
    >
  </section>
  <TaskCompletionDialog :completion="completion" @inspect="emit('select', $event)" />
</template>
