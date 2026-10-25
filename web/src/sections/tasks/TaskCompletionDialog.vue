<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { Task } from './api'
import type { TaskCompletion } from './useTaskCompletion'

const props = defineProps<{ completion: TaskCompletion }>()
const emit = defineEmits<{ inspect: [task: Task] }>()
const { t } = useI18n()
const loading = ref(false)
async function inspect() {
  const refusal = props.completion.pending.value
  if (!refusal || loading.value) return
  const id =
    refusal.reason === 'parent_closed'
      ? (refusal.detail.parent_id ?? refusal.detail.task_id)
      : refusal.detail.task_id
  loading.value = true
  try {
    const task = Task.parse(await api.get(`/v1/tasks/items/${encodeURIComponent(id)}`))
    if (props.completion.pending.value !== refusal) return
    props.completion.cancel()
    emit('inspect', task)
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog :open="!!completion.pending.value" @update:open="!$event && completion.cancel()">
    <DialogContent v-if="completion.pending.value" :show-close-button="false">
      <DialogHeader>
        <DialogTitle>{{ t('tasks.completion.title') }}</DialogTitle>
        <DialogDescription>
          {{
            t(`tasks.completion.${completion.pending.value.reason}`, {
              count:
                completion.pending.value.reason === 'open_subtasks'
                  ? completion.pending.value.detail.open_subtasks
                  : completion.pending.value.detail.incomplete_checklist,
            })
          }}
        </DialogDescription>
      </DialogHeader>
      <p
        v-if="
          completion.pending.value.reason === 'incomplete_checklist' &&
          !completion.pending.value.canConfirm
        "
        class="text-muted-foreground text-sm"
      >
        {{ t('tasks.completion.changed') }}
      </p>
      <DialogFooter>
        <Button variant="outline" @click="completion.cancel()">
          {{ t('tasks.completion.return') }}
        </Button>
        <Button
          v-if="completion.pending.value.canConfirm"
          :disabled="loading"
          @click="completion.confirm()"
        >
          {{ t('tasks.completion.confirm') }}
        </Button>
        <Button v-else :disabled="loading" @click="inspect">
          {{ t('tasks.completion.inspect') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
