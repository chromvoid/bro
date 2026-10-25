<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatAmount, parseAmount } from '@/core/numbers'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import type { ActivitySession, ActivitySet, ExerciseDefinition, SessionExercise } from './api'

const props = defineProps<{
  session: ActivitySession
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
  edit: []
  delete: []
}>()
const { t } = useI18n()
const selectedExercise = ref('')
const noteEditor = ref<{ exerciseId: string; expected: Record<string, number> } | null>(null)
const exerciseNote = ref('')
const setEditor = ref<{
  exerciseId: string
  setId: string | null
  expected: Record<string, number>
} | null>(null)
const setForm = reactive({
  repetitions: '' as string | number,
  load_value: '' as string | number,
  load_unit: 'kg',
  implement_count: '1' as string | number,
  kind: 'working',
  note: '',
})
const deletingExercise = ref<string | null>(null)
const deletingSet = ref<string | null>(null)
const orderedExercises = computed(() =>
  [...props.session.exercises].sort((left, right) => left.position - right.position),
)
const editedExercise = computed(() =>
  props.session.exercises.find((exercise) => exercise.id === setEditor.value?.exerciseId),
)
const sessionTime = computed(() => {
  if (!props.session.started_at) return ''
  try {
    return new Intl.DateTimeFormat('ru', {
      hour: '2-digit',
      minute: '2-digit',
      timeZone: props.session.timezone,
    }).format(new Date(props.session.started_at))
  } catch {
    return t('activity.unknown')
  }
})
const validSet = computed(() => {
  const reps = String(setForm.repetitions).trim()
  const load = String(setForm.load_value).trim()
  const count = Number(setForm.implement_count)
  return (
    (!reps || /^\d+$/.test(reps)) &&
    (!load || parseAmount(load) !== null) &&
    Number.isInteger(count) &&
    count >= 1
  )
})

function expected(exercise?: SessionExercise, set?: ActivitySet) {
  return {
    [props.session.id]: props.session.revision,
    ...(exercise ? { [exercise.id]: exercise.revision } : {}),
    ...(set ? { [set.id]: set.revision } : {}),
  }
}

function operate(
  kind: string,
  payload: Record<string, unknown>,
  revisions: Record<string, number>,
  onSaved?: () => void,
) {
  if (props.busy || !props.canWrite) return
  emit('operation', kind, { session_id: props.session.id, ...payload }, revisions, onSaved)
}

function addExercise() {
  if (!selectedExercise.value) return
  operate('session_exercise.add', { exercise_id: selectedExercise.value }, expected(), () => {
    selectedExercise.value = ''
  })
}

function editExerciseNote(exercise: SessionExercise) {
  exerciseNote.value = exercise.note ?? ''
  noteEditor.value = { exerciseId: exercise.id, expected: expected(exercise) }
}

function saveExerciseNote() {
  const editor = noteEditor.value
  if (!editor) return
  operate(
    'session_exercise.update',
    { session_exercise_id: editor.exerciseId, note: exerciseNote.value.trim() },
    editor.expected,
    () => {
      noteEditor.value = null
    },
  )
}

function editSet(exercise: SessionExercise, set: ActivitySet | null = null) {
  setEditor.value = {
    exerciseId: exercise.id,
    setId: set?.id ?? null,
    expected: expected(exercise, set ?? undefined),
  }
  Object.assign(setForm, {
    repetitions: set?.repetitions ?? '',
    load_value: set?.load_value ?? '',
    load_unit: set?.load_unit ?? 'kg',
    implement_count: set?.implement_count ?? 1,
    kind: set?.kind ?? 'working',
    note: set?.note ?? '',
  })
}

function saveSet() {
  const editor = setEditor.value
  if (!editor || !validSet.value) return
  const reps = String(setForm.repetitions).trim()
  const load = String(setForm.load_value).trim()
  operate(
    editor.setId ? 'set.update' : 'set.add',
    {
      session_exercise_id: editor.exerciseId,
      ...(editor.setId ? { set_id: editor.setId } : {}),
      repetitions: reps ? Number(reps) : null,
      load_value:
        editedExercise.value?.snapshot.load_basis === 'bodyweight' ? null : parseAmount(load),
      load_unit: setForm.load_unit,
      implement_count:
        editedExercise.value?.snapshot.load_basis === 'per_implement'
          ? Number(setForm.implement_count)
          : 1,
      kind: setForm.kind,
      note: setForm.note.trim(),
    },
    editor.expected,
    () => {
      setEditor.value = null
    },
  )
}

function removeExercise(exercise: SessionExercise) {
  operate(
    'session_exercise.delete',
    { session_exercise_id: exercise.id },
    expected(exercise),
    () => {
      deletingExercise.value = null
    },
  )
}

function removeSet(exercise: SessionExercise, set: ActivitySet) {
  operate(
    'set.delete',
    { session_exercise_id: exercise.id, set_id: set.id },
    expected(exercise, set),
    () => {
      deletingSet.value = null
    },
  )
}

function orderedSets(exercise: SessionExercise) {
  return [...exercise.sets].sort((left, right) => left.position - right.position)
}

function setLoad(exercise: SessionExercise, set: ActivitySet) {
  if (exercise.snapshot.load_basis === 'bodyweight') return t('activity.loadBases.bodyweight')
  if (set.load_value === null) return t('activity.unknown')
  return `${formatAmount(set.load_value, 2)} ${t(`activity.loadUnits.${set.load_unit}`)}${exercise.snapshot.load_basis === 'per_implement' ? ` × ${set.implement_count}` : ''}`
}

watch(
  () => props.session.id,
  () => {
    setEditor.value = null
    noteEditor.value = null
    deletingExercise.value = null
    deletingSet.value = null
    selectedExercise.value = ''
  },
)
</script>

<template>
  <section class="space-y-5">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 class="text-lg font-semibold">
          {{ session.title || t(`activity.kinds.${session.kind}`) }}
        </h2>
        <p class="text-sm text-muted-foreground">
          {{ session.date }} · {{ t(`activity.kinds.${session.kind}`) }}
        </p>
      </div>
      <div v-if="canWrite" class="flex flex-wrap gap-2">
        <Button variant="outline" :disabled="busy" @click="emit('edit')">{{
          t('common.edit')
        }}</Button>
        <Button variant="destructive" :disabled="busy" @click="emit('delete')">{{
          t('common.delete')
        }}</Button>
      </div>
    </div>
    <dl class="grid gap-3 rounded-xl border p-4 text-sm sm:grid-cols-2">
      <div>
        <dt class="text-muted-foreground">{{ t('activity.duration') }}</dt>
        <dd>
          {{
            session.duration_minutes === null
              ? t('activity.unknown')
              : `${session.duration_minutes} ${t('activity.minutes')}`
          }}
        </dd>
      </div>
      <div>
        <dt class="text-muted-foreground">{{ t('activity.distance') }}</dt>
        <dd>
          {{
            session.distance_km === null
              ? t('activity.unknown')
              : `${session.distance_km} ${t('activity.kilometers')}`
          }}
        </dd>
      </div>
      <div v-if="session.started_at">
        <dt class="text-muted-foreground">{{ t('activity.sessionTime') }}</dt>
        <dd>{{ sessionTime }}</dd>
      </div>
    </dl>
    <p v-if="session.note" class="whitespace-pre-wrap break-words text-sm">{{ session.note }}</p>

    <template v-if="session.kind === 'strength'">
      <form v-if="canWrite" class="flex flex-wrap items-end gap-2" @submit.prevent="addExercise">
        <div class="min-w-48 flex-1 space-y-2">
          <Label for="activity-session-exercise">{{ t('activity.chooseExercise') }}</Label>
          <NativeSelect
            id="activity-session-exercise"
            v-model="selectedExercise"
            :disabled="busy || !catalog.length"
          >
            <option value="">{{ t('activity.chooseExercise') }}</option>
            <option v-for="exercise in catalog" :key="exercise.id" :value="exercise.id">
              {{
                [exercise.name, exercise.equipment, exercise.variant].filter(Boolean).join(' · ')
              }}
            </option>
          </NativeSelect>
        </div>
        <Button type="submit" variant="outline" :disabled="busy || !selectedExercise">{{
          t('activity.addExercise')
        }}</Button>
      </form>
      <p v-if="!catalog.length && canWrite" class="text-sm text-muted-foreground">
        {{ t('activity.noExercises') }}
      </p>
      <p v-if="!orderedExercises.length" class="text-sm text-muted-foreground">
        {{ t('activity.noSessionExercises') }}
      </p>
      <article
        v-for="exercise in orderedExercises"
        :key="exercise.id"
        class="space-y-4 rounded-xl border p-4"
      >
        <div class="flex flex-wrap items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="break-words font-semibold">{{ exercise.snapshot.name }}</h3>
            <p class="text-sm text-muted-foreground">
              {{
                [exercise.snapshot.equipment, exercise.snapshot.variant].filter(Boolean).join(' · ')
              }}
            </p>
            <p class="text-sm text-muted-foreground">
              {{ t(`activity.loadBases.${exercise.snapshot.load_basis}`) }}
            </p>
          </div>
          <div class="flex flex-wrap gap-1">
            <Button size="sm" variant="ghost" @click="emit('progress', exercise.exercise_id)">{{
              t('activity.exerciseHistory')
            }}</Button>
            <template v-if="canWrite">
              <Button
                size="sm"
                variant="ghost"
                :disabled="busy"
                @click="editExerciseNote(exercise)"
                >{{ t('common.edit') }}</Button
              >
              <Button
                size="sm"
                variant="destructive"
                :disabled="busy"
                @click="deletingExercise = exercise.id"
                >{{ t('common.delete') }}</Button
              >
            </template>
          </div>
        </div>
        <p v-if="exercise.note" class="whitespace-pre-wrap break-words text-sm">
          {{ exercise.note }}
        </p>
        <form
          v-if="canWrite && noteEditor?.exerciseId === exercise.id"
          class="space-y-2"
          @submit.prevent="saveExerciseNote"
        >
          <Label :for="`activity-exercise-note-${exercise.id}`">{{
            t('activity.exerciseNote')
          }}</Label>
          <Textarea
            :id="`activity-exercise-note-${exercise.id}`"
            v-model="exerciseNote"
            maxlength="4000"
            :disabled="busy"
          />
          <div class="flex gap-2">
            <Button type="submit" size="sm" :disabled="busy">{{ t('common.save') }}</Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              :disabled="busy"
              @click="noteEditor = null"
              >{{ t('common.cancel') }}</Button
            >
          </div>
        </form>
        <div v-if="canWrite && deletingExercise === exercise.id" class="space-y-2" role="alert">
          <p class="text-sm">{{ t('activity.confirmDeleteSessionExercise') }}</p>
          <div class="flex gap-2">
            <Button
              size="sm"
              variant="destructive"
              :disabled="busy"
              @click="removeExercise(exercise)"
              >{{ t('common.delete') }}</Button
            >
            <Button size="sm" variant="ghost" :disabled="busy" @click="deletingExercise = null">{{
              t('common.cancel')
            }}</Button>
          </div>
        </div>
        <h4 class="text-sm font-medium">{{ t('activity.sets') }}</h4>
        <p v-if="!exercise.sets.length" class="text-sm text-muted-foreground">
          {{ t('activity.noSets') }}
        </p>
        <ol class="space-y-2">
          <li
            v-for="(set, index) in orderedSets(exercise)"
            :key="set.id"
            class="rounded-lg bg-muted/40 p-3"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="text-sm">
                <span class="mr-2 font-medium">{{ index + 1 }}.</span>
                <span
                  >{{ t(`activity.setKinds.${set.kind}`) }} ·
                  {{ set.repetitions ?? t('activity.unknown') }}
                  {{ t('activity.repetitionsShort') }} · {{ setLoad(exercise, set) }}</span
                >
              </div>
              <div v-if="canWrite" class="flex gap-1">
                <Button
                  size="sm"
                  variant="ghost"
                  :disabled="busy"
                  :aria-label="t('activity.editSet')"
                  @click="editSet(exercise, set)"
                  >{{ t('common.edit') }}</Button
                >
                <Button
                  size="sm"
                  variant="destructive"
                  :disabled="busy"
                  @click="deletingSet = set.id"
                  >{{ t('common.delete') }}</Button
                >
              </div>
            </div>
            <p
              v-if="set.note"
              class="mt-1 whitespace-pre-wrap break-words text-sm text-muted-foreground"
            >
              {{ set.note }}
            </p>
            <div v-if="canWrite && deletingSet === set.id" class="mt-3 space-y-2" role="alert">
              <p class="text-sm">{{ t('activity.confirmDeleteSet') }}</p>
              <div class="flex gap-2">
                <Button
                  size="sm"
                  variant="destructive"
                  :disabled="busy"
                  @click="removeSet(exercise, set)"
                  >{{ t('common.delete') }}</Button
                >
                <Button size="sm" variant="ghost" :disabled="busy" @click="deletingSet = null">{{
                  t('common.cancel')
                }}</Button>
              </div>
            </div>
          </li>
        </ol>
        <form
          v-if="canWrite && setEditor?.exerciseId === exercise.id"
          class="space-y-4 rounded-xl border p-4"
          @submit.prevent="saveSet"
        >
          <h4 class="font-medium">
            {{ t(setEditor.setId ? 'activity.editSet' : 'activity.addSet') }}
          </h4>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="space-y-2">
              <Label :for="`activity-set-repetitions-${exercise.id}`">{{
                t('activity.repetitions')
              }}</Label>
              <Input
                :id="`activity-set-repetitions-${exercise.id}`"
                v-model="setForm.repetitions"
                type="number"
                inputmode="numeric"
                min="0"
                step="1"
                :placeholder="t('activity.unknown')"
                :disabled="busy"
              />
            </div>
            <div class="space-y-2">
              <Label :for="`activity-set-kind-${exercise.id}`">{{ t('activity.setKind') }}</Label>
              <NativeSelect
                :id="`activity-set-kind-${exercise.id}`"
                v-model="setForm.kind"
                :disabled="busy"
              >
                <option v-for="kind in ['working', 'warmup']" :key="kind" :value="kind">
                  {{ t(`activity.setKinds.${kind}`) }}
                </option>
              </NativeSelect>
            </div>
            <template v-if="exercise.snapshot.load_basis !== 'bodyweight'">
              <div class="space-y-2">
                <Label :for="`activity-set-load-${exercise.id}`">{{
                  t(`activity.loadBases.${exercise.snapshot.load_basis}`)
                }}</Label>
                <Input
                  :id="`activity-set-load-${exercise.id}`"
                  v-model="setForm.load_value"
                  inputmode="decimal"
                  :placeholder="t('activity.unknown')"
                  :disabled="busy"
                />
              </div>
              <div class="space-y-2">
                <Label :for="`activity-set-unit-${exercise.id}`">{{
                  t('activity.loadUnit')
                }}</Label>
                <NativeSelect
                  :id="`activity-set-unit-${exercise.id}`"
                  v-model="setForm.load_unit"
                  :disabled="busy"
                >
                  <option v-for="unit in ['kg', 'lb']" :key="unit" :value="unit">
                    {{ t(`activity.loadUnits.${unit}`) }}
                  </option>
                </NativeSelect>
              </div>
            </template>
            <div v-if="exercise.snapshot.load_basis === 'per_implement'" class="space-y-2">
              <Label :for="`activity-set-count-${exercise.id}`">{{
                t('activity.implementCount')
              }}</Label>
              <Input
                :id="`activity-set-count-${exercise.id}`"
                v-model="setForm.implement_count"
                type="number"
                min="1"
                step="1"
                required
                :disabled="busy"
              />
            </div>
          </div>
          <div class="space-y-2">
            <Label :for="`activity-set-note-${exercise.id}`">{{ t('activity.setNote') }}</Label>
            <Textarea
              :id="`activity-set-note-${exercise.id}`"
              v-model="setForm.note"
              maxlength="4000"
              :disabled="busy"
            />
          </div>
          <div class="flex flex-wrap gap-2">
            <Button type="submit" :disabled="busy || !validSet">{{ t('common.save') }}</Button>
            <Button type="button" variant="ghost" :disabled="busy" @click="setEditor = null">{{
              t('common.cancel')
            }}</Button>
          </div>
        </form>
        <Button
          v-if="canWrite && setEditor?.exerciseId !== exercise.id"
          size="sm"
          variant="outline"
          :disabled="busy"
          @click="editSet(exercise)"
          >{{ t('activity.addSet') }}</Button
        >
      </article>
    </template>
  </section>
</template>
