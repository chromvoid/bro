<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { isIsoDay } from '@/core/dates'
import { formatAmount, parseAmount } from '@/core/numbers'
import { NUTRIENTS, entryName, sameAmount, unitLabel, type Entry, shownName } from './api'
import { estimationDetails } from './estimation'
import { useFood } from './useFood'

const props = defineProps<{
  entry: Entry | null
  /** A column beside the day on a wide page (Panel's `docked`). */
  docked?: boolean
}>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const nutrition = useFood()
const quantity = ref('')
const date = ref('')
const busy = ref(false)
const confirming = ref(false)
// The form keeps what the user typed when a conflict reloads the entry underneath it.
watch(
  () => [open.value, props.entry?.id] as const,
  ([visible]) => {
    if (!visible || !props.entry) return
    quantity.value = props.entry.quantity
    date.value = props.entry.local_date
    confirming.value = false
  },
  { immediate: true },
)
const parsed = computed(() => parseAmount(quantity.value))
const partOfDish = computed(() => !!props.entry?.dish_id)
const estimate = computed(() => estimationDetails(props.entry?.estimation_evidence))
const changed = computed(
  () =>
    props.entry &&
    (!sameAmount(parsed.value, props.entry.quantity) || date.value !== props.entry.local_date),
)

async function save() {
  const entry = props.entry
  if (!entry || !parsed.value || !isIsoDay(date.value) || busy.value) return
  busy.value = true
  try {
    const payload: Record<string, unknown> = { entry_id: entry.id }
    if (!sameAmount(parsed.value, entry.quantity)) payload.quantity = parsed.value
    if (date.value !== entry.local_date) payload.date = date.value
    const receipt = await nutrition.operate('entry.update', payload, { [entry.id]: entry.revision })
    nutrition.done(receipt, t('food.saved'))
    open.value = false
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = false
  }
}

async function remove() {
  const entry = props.entry
  if (!entry || busy.value) return
  busy.value = true
  try {
    const receipt = await nutrition.operate(
      'entry.delete',
      { entry_id: entry.id },
      { [entry.id]: entry.revision },
    )
    nutrition.done(receipt, t('food.deleted'))
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
    :title="entry ? shownName(entryName(entry)) : t('food.entryTitle')"
    :docked="docked"
  >
    <template v-if="entry">
      <div class="flex flex-wrap gap-2">
        <Badge v-if="entry.estimated" variant="warning">{{ t('food.estimated') }}</Badge>
        <Badge v-if="entry.source_provider === 'user'" variant="secondary">{{
          t('food.personal')
        }}</Badge>
      </div>
      <p v-if="estimate.note" class="text-muted-foreground text-sm">
        {{ estimate.note }}
      </p>
      <p v-if="estimate.sourceEstimated" class="text-muted-foreground text-sm">
        {{ t('food.recipeEstimate') }}
      </p>
      <dl
        class="bg-muted grid grid-cols-[repeat(auto-fill,minmax(5.5rem,1fr))] gap-x-3 gap-y-2 rounded-2xl p-4 text-sm"
      >
        <div>
          <dt class="text-muted-foreground">{{ t('food.kcal') }}</dt>
          <dd class="font-semibold tabular-nums">
            {{ formatAmount(entry.nutrients?.energy_kcal, 0) }}
          </dd>
        </div>
        <div v-for="nutrient in NUTRIENTS" :key="nutrient">
          <dt class="text-muted-foreground">{{ t(`food.macros.${nutrient}`) }}</dt>
          <dd class="tabular-nums">{{ formatAmount(entry.nutrients?.[nutrient]) }}</dd>
        </div>
      </dl>
      <p v-if="partOfDish" class="text-muted-foreground text-sm">{{ t('food.ingredientHint') }}</p>
      <template v-else>
        <div class="grid grid-cols-2 gap-3">
          <div class="space-y-2">
            <Label for="entry-quantity"
              >{{ t('food.quantity') }}, {{ unitLabel(entry.unit) }}</Label
            >
            <Input
              id="entry-quantity"
              v-model="quantity"
              inputmode="decimal"
              :aria-invalid="!parsed || undefined"
            />
            <FieldError v-if="!parsed">{{ t('food.invalidQuantity') }}</FieldError>
          </div>
          <div class="space-y-2">
            <Label for="entry-date">{{ t('food.date') }}</Label>
            <Input id="entry-date" v-model="date" type="date" />
          </div>
        </div>
        <Button size="lg" class="w-full" :disabled="!changed || !parsed || busy" @click="save">{{
          t('food.save')
        }}</Button>
        <div
          v-if="confirming"
          class="border-destructive/30 space-y-3 rounded-2xl border p-4"
          role="alert"
        >
          <p>{{ t('food.deleteConfirm', { name: shownName(entryName(entry)) }) }}</p>
          <div class="flex gap-2">
            <Button variant="destructive" :disabled="busy" @click="remove">{{
              t('food.delete')
            }}</Button>
            <Button variant="ghost" @click="confirming = false">{{ t('common.cancel') }}</Button>
          </div>
        </div>
        <Button v-else variant="ghost" class="text-destructive w-full" @click="confirming = true">{{
          t('food.delete')
        }}</Button>
      </template>
    </template>
  </Panel>
</template>
