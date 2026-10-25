<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatAmount, parseAmount } from '@/core/numbers'
import { entryName, sameAmount, unitLabel, type Dish, shownName } from './api'
import { useFood } from './useFood'

const props = defineProps<{
  dish: Dish | null
  /** A column beside the day on a wide page (Panel's `docked`). */
  docked?: boolean
}>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const nutrition = useFood()
const mode = ref<'view' | 'components' | 'remember' | 'delete'>('view')
const total = ref('')
const amounts = reactive<Record<string, string>>({})
const name = ref('')
const busy = ref(false)
watch(
  () => [open.value, props.dish?.id] as const,
  ([visible]) => {
    const dish = props.dish
    if (!visible || !dish) return
    mode.value = 'view'
    total.value = dish.quantity
    name.value = dish.name
    for (const component of dish.components) amounts[component.id] = component.quantity
  },
  { immediate: true },
)
const scaled = computed(() => parseAmount(total.value))

async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await task()
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = false
  }
}

/** F25: a new total scales every ingredient in proportion. */
const scale = () =>
  run(async () => {
    const dish = props.dish!
    const receipt = await nutrition.operate(
      'dish.update',
      { dish_id: dish.id, quantity: scaled.value },
      { [dish.id]: dish.revision },
    )
    nutrition.done(receipt, t('food.saved'))
    open.value = false
  })

/** F24: change the amounts of the existing ingredients. */
const saveComponents = () =>
  run(async () => {
    const dish = props.dish!
    const components = dish.components
      .map((component) => ({
        entry_id: component.id,
        quantity: parseAmount(amounts[component.id] ?? ''),
      }))
      .filter(
        (component, index) =>
          component.quantity && !sameAmount(component.quantity, dish.components[index]!.quantity),
      )
    if (!components.length) return
    const receipt = await nutrition.operate(
      'dish.update',
      { dish_id: dish.id, components },
      { [dish.id]: dish.revision },
    )
    nutrition.done(receipt, t('food.saved'))
    open.value = false
  })

/** F22: keep the composition to log it again later, without adding food now. */
const remember = () =>
  run(async () => {
    const dish = props.dish!
    const expected = Object.fromEntries(
      dish.components.map((component) => [component.id, component.revision]),
    )
    await nutrition.operate(
      'composition.save',
      { name: name.value.trim(), entry_ids: dish.components.map((c) => c.id) },
      expected,
    )
    toast.success(t('food.remembered', { name: name.value.trim() }))
    mode.value = 'view'
  })

const remove = () =>
  run(async () => {
    const dish = props.dish!
    const receipt = await nutrition.operate(
      'dish.delete',
      { dish_id: dish.id },
      { [dish.id]: dish.revision },
    )
    nutrition.done(receipt, t('food.deleted'))
    open.value = false
  })
</script>

<template>
  <Panel v-model:open="open" :title="dish?.name ?? t('food.dishTitle')" :docked="docked">
    <template v-if="dish">
      <p class="text-3xl font-semibold tabular-nums">
        {{ formatAmount(dish.nutrients?.energy_kcal, 0) }}
        <span class="text-muted-foreground text-base font-normal">{{ t('food.kcal') }}</span>
      </p>
      <section class="space-y-2">
        <h3 class="font-medium">{{ t('food.components') }}</h3>
        <ul class="divide-y rounded-2xl border">
          <li
            v-for="component in dish.components"
            :key="component.id"
            class="flex items-center gap-3 px-4 py-2.5"
          >
            <span class="min-w-0 flex-1 truncate">{{ shownName(entryName(component)) }}</span>
            <template v-if="mode === 'components'">
              <Input
                v-model="amounts[component.id]"
                inputmode="decimal"
                class="w-24"
                :aria-label="`${shownName(entryName(component))}, ${unitLabel(component.unit)}`"
              />
              <span class="text-muted-foreground w-8 text-sm">{{ unitLabel(component.unit) }}</span>
            </template>
            <span v-else class="text-muted-foreground text-sm tabular-nums">
              {{ formatAmount(component.quantity) }} {{ unitLabel(component.unit) }} ·
              {{ formatAmount(component.nutrients?.energy_kcal, 0) }} {{ t('food.kcal') }}
            </span>
          </li>
        </ul>
      </section>

      <template v-if="mode === 'view'">
        <div class="space-y-2">
          <Label for="dish-total">{{ t('food.dishQuantity') }}, {{ unitLabel(dish.unit) }}</Label>
          <div class="flex gap-2">
            <Input id="dish-total" v-model="total" inputmode="decimal" />
            <Button
              variant="outline"
              :disabled="!scaled || sameAmount(scaled, dish.quantity) || busy"
              @click="scale"
              >{{ t('food.save') }}</Button
            >
          </div>
          <p class="text-muted-foreground text-sm">{{ t('food.scaleHint') }}</p>
        </div>
        <div class="grid grid-cols-1 gap-2">
          <Button variant="outline" @click="mode = 'components'">{{
            t('food.editComponents')
          }}</Button>
          <Button variant="outline" @click="mode = 'remember'">{{ t('food.remember') }}</Button>
          <Button variant="ghost" class="text-destructive" @click="mode = 'delete'">{{
            t('food.delete')
          }}</Button>
        </div>
      </template>
      <div v-else-if="mode === 'components'" class="flex gap-2">
        <Button class="flex-1" :disabled="busy" @click="saveComponents">{{
          t('food.saveComponents')
        }}</Button>
        <Button variant="ghost" @click="mode = 'view'">{{ t('common.cancel') }}</Button>
      </div>
      <div v-else-if="mode === 'remember'" class="space-y-2">
        <Label for="dish-name">{{ t('food.rememberName') }}</Label>
        <Input id="dish-name" v-model="name" maxlength="100" />
        <div class="flex gap-2">
          <Button class="flex-1" :disabled="!name.trim() || busy" @click="remember">{{
            t('food.remember')
          }}</Button>
          <Button variant="ghost" @click="mode = 'view'">{{ t('common.cancel') }}</Button>
        </div>
      </div>
      <div v-else class="border-destructive/30 space-y-3 rounded-2xl border p-4" role="alert">
        <p>{{ t('food.deleteConfirm', { name: shownName(dish.name) }) }}</p>
        <div class="flex gap-2">
          <Button variant="destructive" :disabled="busy" @click="remove">{{
            t('food.delete')
          }}</Button>
          <Button variant="ghost" @click="mode = 'view'">{{ t('common.cancel') }}</Button>
        </div>
      </div>
    </template>
  </Panel>
</template>
