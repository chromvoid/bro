<script setup lang="ts">
import { refDebounced } from '@vueuse/core'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { useSession } from '@/stores/session'
import { formatAmount, parseAmount } from '@/core/numbers'
import {
  Estimate,
  NUTRIENTS,
  foodName,
  unitLabel,
  draftMealKind,
  type Draft,
  type Food,
  type MealKind,
  type Receipt,
  shownName,
} from './api'
import AtLeast from './AtLeast.vue'
import MealKindPicker from './MealKindPicker.vue'
import { useFood } from './useFood'

const props = defineProps<{
  food: Food | null
  day: string
  draft?: Draft | null
  quantity?: string | null
}>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ recorded: [receipt: Receipt] }>()
const { t } = useI18n()
const nutrition = useFood()

type Choice =
  | { kind: 'amount' }
  | { kind: 'reference' | 'saved'; id: string; name: string; quantity: string; unit: string }
const amount = ref('')
const choice = ref<Choice>({ kind: 'amount' })
const count = ref('1')
const busy = ref(false)
const session = useSession()
const mealKind = ref<MealKind | ''>('')
watch(
  () => [open.value, props.food] as const,
  ([visible, current]) => {
    if (!visible || !current) return
    amount.value = props.quantity ?? current.basis_quantity
    choice.value = { kind: 'amount' }
    count.value = '1'
    // Completing a draft keeps its meal unless the person picks another kind (that moves it).
    mealKind.value = draftMealKind(props.draft, props.day, session.me?.timezone ?? 'UTC') ?? ''
  },
  { immediate: true },
)

/** The item exactly as it will be logged; null while the input is not a valid amount. */
const item = computed(() => {
  const current = props.food
  if (!current) return null
  const label = foodName(current)
  if (choice.value.kind === 'amount') {
    const quantity = parseAmount(amount.value)
    return quantity && Number(quantity) > 0
      ? { label, snapshot_id: current.snapshot_id, quantity, unit: current.basis_unit }
      : null
  }
  const portions = parseAmount(count.value)
  if (!portions || Number(portions) <= 0) return null
  return choice.value.kind === 'reference'
    ? {
        label,
        snapshot_id: current.snapshot_id,
        reference_portion_id: choice.value.id,
        portion_count: portions,
      }
    : {
        label,
        snapshot_id: current.snapshot_id,
        portion_id: choice.value.id,
        portion_count: portions,
      }
})

// A preview that writes nothing; saved portions are previewed as their amount.
const previewItem = refDebounced(
  computed(() => {
    const current = item.value
    if (!current || choice.value.kind !== 'saved') return current
    const quantity = String(Number(choice.value.quantity) * Number(count.value))
    return {
      label: current.label,
      snapshot_id: current.snapshot_id,
      quantity,
      unit: choice.value.unit,
    }
  }),
  300,
)
const estimate = useQuery({
  queryKey: computed(() => ['nutrition', 'estimate', previewItem.value]),
  queryFn: async () =>
    Estimate.parse((await api.post('/v1/nutrition/estimate', { items: [previewItem.value] })).data),
  enabled: computed(() => open.value && !!previewItem.value),
})
const totals = computed(() => estimate.data.value?.totals)
const portions = computed(() => [
  ...(props.food?.saved_portions ?? []).map((portion) => ({ ...portion, kind: 'saved' as const })),
  ...(props.food?.portions ?? []).map((portion) => ({ ...portion, kind: 'reference' as const })),
])

async function record() {
  const current = item.value
  if (!current || busy.value) return
  busy.value = true
  try {
    const draft = props.draft
    const meal = mealKind.value ? { meal_kind: mealKind.value } : {}
    const receipt = draft
      ? await nutrition.operate(
          'entry.add_many',
          // On the item: only an item's own kind moves a draft out of the meal it belongs to.
          {
            plan_id: draft.plan_id,
            date: props.day,
            items: [{ ...current, item_id: draft.id, ...meal }],
          },
          { [draft.id]: draft.revision },
        )
      : await nutrition.operate('entry.add_many', { date: props.day, items: [current], ...meal })
    nutrition.done(receipt, nutrition.loggedMessage(receipt, current.label))
    emit('recorded', receipt)
    open.value = false
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel
    v-model:open="open"
    :title="food ? shownName(foodName(food)) : t('food.portionTitle')"
    :description="t('food.portionTitle')"
  >
    <template v-if="food">
      <div class="space-y-2">
        <Label for="portion-amount"
          >{{ t('food.quantity') }}, {{ unitLabel(food.basis_unit) }}</Label
        >
        <Input
          id="portion-amount"
          v-model="amount"
          inputmode="decimal"
          :aria-invalid="choice.kind === 'amount' && !item ? true : undefined"
          @focus="choice = { kind: 'amount' }"
        />
        <p v-if="choice.kind === 'amount' && !item" class="text-destructive text-sm">
          {{ t('food.invalidQuantity') }}
        </p>
      </div>
      <div v-if="portions.length" class="space-y-2">
        <p class="text-sm font-medium">{{ t('food.portions') }}</p>
        <div class="flex flex-wrap gap-2">
          <Button
            v-for="portion in portions"
            :key="portion.id"
            size="sm"
            :variant="choice.kind !== 'amount' && choice.id === portion.id ? 'default' : 'outline'"
            @click="
              choice = {
                kind: portion.kind,
                id: portion.id,
                name: portion.name,
                quantity: portion.quantity,
                unit: portion.unit,
              }
            "
          >
            {{ portion.name }} · {{ formatAmount(portion.quantity) }} {{ unitLabel(portion.unit) }}
          </Button>
        </div>
        <div v-if="choice.kind !== 'amount'" class="flex items-center gap-3">
          <Label for="portion-count" class="shrink-0">{{ t('food.servings') }}</Label>
          <Input id="portion-count" v-model="count" inputmode="decimal" class="w-24" />
        </div>
      </div>

      <div class="bg-muted space-y-2 rounded-2xl p-4" aria-live="polite" data-testid="estimate">
        <p class="text-muted-foreground text-sm">{{ t('food.estimate') }}</p>
        <p class="text-3xl font-semibold tabular-nums">
          <AtLeast
            v-if="totals"
            :value="totals.energy_kcal?.known"
            :complete="totals.energy_kcal?.complete ?? false"
            :fraction="0"
          /><template v-else>…</template>
          <span class="text-muted-foreground text-base font-normal">{{ t('food.kcal') }}</span>
        </p>
        <dl v-if="totals" class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-4">
          <div v-for="nutrient in NUTRIENTS" :key="nutrient">
            <dt class="text-muted-foreground">{{ t(`food.macros.${nutrient}`) }}</dt>
            <dd class="tabular-nums">
              {{ totals[nutrient]?.complete ? formatAmount(totals[nutrient]?.known) : '—' }}
            </dd>
          </div>
        </dl>
        <Badge v-if="choice.kind === 'reference'" variant="warning">{{
          t('food.estimated')
        }}</Badge>
      </div>
      <MealKindPicker v-model="mealKind" />
      <Button size="lg" class="w-full" :disabled="!item || busy" @click="record">
        {{ busy ? t('food.recording') : t('food.record') }}
      </Button>
    </template>
  </Panel>
</template>
