<script setup lang="ts">
import { nextTick, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import DateField from '@/components/DateField.vue'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { isIsoDay } from '@/core/dates'
import { parseAmount } from '@/core/numbers'
import type { Day } from './api'
import { useFood } from './useFood'

const props = defineProps<{
  day: string
  goal: Day['goal']
  /** The macro whose «Задать цель» opened the panel: its field takes the focus. */
  field?: string | null
}>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const nutrition = useFood()
const macros = [
  ['protein_g', 'food.protein'],
  ['fat_g', 'food.fat'],
  ['carbohydrate_g', 'food.carbs'],
] as const
const form = reactive({ calories: '', from: '', macros: {} as Record<string, string>, busy: false })
watch(open, (visible) => {
  if (!visible) return
  form.calories = props.goal?.calorie_target ?? ''
  form.from = props.day
  form.macros = Object.fromEntries(
    macros.map(([key]) => [key, props.goal?.macro_targets?.[key] ?? '']),
  )
  // After the panel's own first focus, which lands on the calories.
  if (props.field)
    void nextTick(() =>
      requestAnimationFrame(() => document.getElementById(`goal-${props.field}`)?.focus()),
    )
})

async function save() {
  const calories = parseAmount(form.calories)
  if (!calories || !isIsoDay(form.from) || form.busy) return
  form.busy = true
  try {
    const macroTargets = Object.fromEntries(
      macros.flatMap(([key]) => {
        const value = parseAmount(form.macros[key] ?? '')
        return value ? [[key, value]] : []
      }),
    )
    // Replacing the goal of the same start date names its revision; a new date adds a version.
    const goal = props.goal
    const expected =
      goal && goal.effective_local_date === form.from ? { [goal.id]: goal.revision } : {}
    await nutrition.operate(
      'goal.set',
      {
        date: form.from,
        calorie_target: calories,
        ...(Object.keys(macroTargets).length ? { macro_targets: macroTargets } : {}),
      },
      expected,
    )
    toast.success(t('food.goalSaved'))
    open.value = false
  } catch (error) {
    nutrition.failed(error)
  } finally {
    form.busy = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('food.goalTitle')" :description="t('food.goalHint')">
    <form class="space-y-4" novalidate @submit.prevent="save">
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-2">
          <Label for="goal-calories">{{ t('food.calories') }}</Label>
          <Input id="goal-calories" v-model="form.calories" inputmode="numeric" />
        </div>
        <div class="space-y-2">
          <Label for="goal-from">{{ t('food.from') }}</Label>
          <DateField id="goal-from" v-model="form.from" />
        </div>
      </div>
      <div class="grid grid-cols-3 gap-3">
        <div v-for="[key, label] in macros" :key="key" class="space-y-2">
          <Label :for="`goal-${key}`">{{ t(label) }}</Label>
          <Input :id="`goal-${key}`" v-model="form.macros[key]" inputmode="decimal" />
        </div>
      </div>
      <Button
        type="submit"
        size="lg"
        class="w-full"
        :disabled="!parseAmount(form.calories) || form.busy"
      >
        {{ t('food.save') }}
      </Button>
    </form>
  </Panel>
</template>
