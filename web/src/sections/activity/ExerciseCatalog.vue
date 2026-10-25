<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import type { ExerciseDefinition } from './api'

const props = defineProps<{
  catalog: ExerciseDefinition[]
  canWrite: boolean
  busy: boolean
}>()
const emit = defineEmits<{
  operation: [
    kind: string,
    payload: Record<string, unknown>,
    expected: Record<string, number>,
    onSaved?: () => void,
  ]
  progress: [exerciseId: string]
}>()
const { t } = useI18n()
const loadBases = ['total', 'per_implement', 'additional', 'assistance', 'bodyweight'] as const
const formOpen = ref(false)
const editing = ref<ExerciseDefinition | null>(null)
const deleting = ref<string | null>(null)
const form = reactive({ name: '', equipment: '', variant: '', load_basis: 'total' })
function openForm(exercise: ExerciseDefinition | null = null) {
  editing.value = exercise ? { ...exercise } : null
  Object.assign(form, {
    name: exercise?.name ?? '',
    equipment: exercise?.equipment ?? '',
    variant: exercise?.variant ?? '',
    load_basis: exercise?.load_basis ?? 'total',
  })
  formOpen.value = true
}

function save() {
  if (props.busy || !props.canWrite || !form.name.trim()) return
  const values = {
    name: form.name.trim(),
    equipment: form.equipment.trim(),
    variant: form.variant.trim(),
    load_basis: form.load_basis,
  }
  const onSaved = () => {
    formOpen.value = false
    editing.value = null
  }
  if (editing.value)
    emit(
      'operation',
      'exercise.update',
      { exercise_id: editing.value.id, ...values },
      { [editing.value.id]: editing.value.revision },
      onSaved,
    )
  else emit('operation', 'exercise.add', values, {}, onSaved)
}

function remove(exercise: ExerciseDefinition) {
  if (props.busy || !props.canWrite) return
  emit(
    'operation',
    'exercise.delete',
    { exercise_id: exercise.id },
    { [exercise.id]: exercise.revision },
    () => {
      deleting.value = null
    },
  )
}
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-lg font-semibold">{{ t('activity.exerciseCatalog') }}</h2>
      <Button v-if="canWrite" variant="outline" :disabled="busy" @click="openForm()">
        {{ t('activity.newExercise') }}
      </Button>
    </div>
    <form
      v-if="formOpen && canWrite"
      class="space-y-4 rounded-xl border p-4"
      @submit.prevent="save"
    >
      <h3 class="font-medium">
        {{ t(editing ? 'activity.editExercise' : 'activity.newExercise') }}
      </h3>
      <div class="space-y-2">
        <Label for="activity-exercise-name">{{ t('activity.exerciseName') }}</Label>
        <Input
          id="activity-exercise-name"
          v-model="form.name"
          maxlength="200"
          required
          :disabled="busy"
        />
      </div>
      <div class="grid gap-4 sm:grid-cols-2">
        <div class="space-y-2">
          <Label for="activity-exercise-equipment">{{ t('activity.equipment') }}</Label>
          <Input
            id="activity-exercise-equipment"
            v-model="form.equipment"
            maxlength="200"
            :disabled="busy"
          />
        </div>
        <div class="space-y-2">
          <Label for="activity-exercise-variant">{{ t('activity.variant') }}</Label>
          <Input
            id="activity-exercise-variant"
            v-model="form.variant"
            maxlength="200"
            :disabled="busy"
          />
        </div>
      </div>
      <div class="space-y-2">
        <Label for="activity-exercise-load-basis">{{ t('activity.loadBasis') }}</Label>
        <NativeSelect id="activity-exercise-load-basis" v-model="form.load_basis" :disabled="busy">
          <option v-for="basis in loadBases" :key="basis" :value="basis">
            {{ t(`activity.loadBases.${basis}`) }}
          </option>
        </NativeSelect>
      </div>
      <div class="flex flex-wrap gap-2">
        <Button type="submit" :disabled="busy || !form.name.trim()">{{ t('common.save') }}</Button>
        <Button type="button" variant="ghost" :disabled="busy" @click="formOpen = false">
          {{ t('common.cancel') }}
        </Button>
      </div>
    </form>
    <p v-if="!catalog.length" class="text-sm text-muted-foreground">
      {{ t('activity.noExercises') }}
    </p>
    <ul v-else class="divide-y rounded-xl border px-4">
      <li v-for="exercise in catalog" :key="exercise.id" class="space-y-3 py-4">
        <div class="flex flex-wrap items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="break-words font-medium">{{ exercise.name }}</h3>
            <p class="text-sm text-muted-foreground">
              {{ [exercise.equipment, exercise.variant].filter(Boolean).join(' · ') }}
            </p>
            <p class="text-sm text-muted-foreground">
              {{ t(`activity.loadBases.${exercise.load_basis}`) }}
            </p>
          </div>
          <div class="flex flex-wrap gap-1">
            <Button size="sm" variant="ghost" @click="emit('progress', exercise.id)">
              {{ t('activity.exerciseHistory') }}
            </Button>
            <template v-if="canWrite">
              <Button size="sm" variant="ghost" :disabled="busy" @click="openForm(exercise)">
                {{ t('common.edit') }}
              </Button>
              <Button
                size="sm"
                variant="destructive"
                :disabled="busy"
                @click="deleting = exercise.id"
              >
                {{ t('common.delete') }}
              </Button>
            </template>
          </div>
        </div>
        <div v-if="deleting === exercise.id && canWrite" class="space-y-2" role="alert">
          <p class="text-sm">{{ t('activity.confirmDeleteExercise') }}</p>
          <div class="flex gap-2">
            <Button size="sm" variant="destructive" :disabled="busy" @click="remove(exercise)">
              {{ t('common.delete') }}
            </Button>
            <Button size="sm" variant="ghost" :disabled="busy" @click="deleting = null">
              {{ t('common.cancel') }}
            </Button>
          </div>
        </div>
      </li>
    </ul>
  </section>
</template>
