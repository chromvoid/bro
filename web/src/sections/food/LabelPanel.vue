<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldError from '@/components/FieldError.vue'
import Panel from '@/components/Panel.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { parseAmount } from '@/core/numbers'
import type { Food } from './api'
import { useFood } from './useFood'

const props = defineProps<{ initial?: string }>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ created: [food: Food] }>()
const { t } = useI18n()
const nutrition = useFood()
const fields = [
  ['energy_kcal', 'food.energy'],
  ['protein_g', 'food.protein'],
  ['fat_g', 'food.fat'],
  ['carbohydrate_g', 'food.carbs'],
  ['fiber_g', 'food.fiber'],
] as const
const form = reactive({
  name: '',
  brand: '',
  basis_unit: 'g',
  basis_quantity: '100',
  values: {} as Record<string, string>,
})
const touched = ref(false)
const busy = ref(false)
watch(open, (visible) => {
  if (!visible) return
  Object.assign(form, {
    name: props.initial ?? '',
    brand: '',
    basis_unit: 'g',
    basis_quantity: '100',
    values: {},
  })
  touched.value = false
})

const invalid = (value: string | undefined) => !!value?.trim() && !parseAmount(value)
const notNumbers = computed(() =>
  fields.filter(([key]) => invalid(form.values[key])).map(([, label]) => t(label)),
)

async function create() {
  touched.value = true
  const basis = parseAmount(form.basis_quantity)
  if (
    !form.name.trim() ||
    !basis ||
    fields.some(([key]) => invalid(form.values[key])) ||
    busy.value
  )
    return
  busy.value = true
  try {
    // An empty field is unknown, so it is left out rather than sent as zero.
    const payload: Record<string, unknown> = {
      name: form.name.trim(),
      basis_unit: form.basis_unit,
      basis_quantity: basis,
    }
    if (form.brand.trim()) payload.brand = form.brand.trim()
    for (const [key] of fields) {
      const value = parseAmount(form.values[key] ?? '')
      if (value) payload[key] = value
    }
    const receipt = await nutrition.operate('food.create', payload)
    emit('created', {
      food_id: receipt.food_id!,
      snapshot_id: receipt.snapshot_id!,
      name: form.name.trim(),
      brand: form.brand.trim() || null,
      personal: true,
      basis_unit: form.basis_unit,
      basis_quantity: basis,
      energy_kcal: (payload.energy_kcal as string) ?? null,
    })
    open.value = false
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('food.labelTitle')" :description="t('food.labelHint')">
    <form class="space-y-4" novalidate @submit.prevent="create">
      <div class="space-y-2">
        <Label for="label-name">{{ t('food.name') }}</Label>
        <Input
          id="label-name"
          v-model="form.name"
          maxlength="200"
          :aria-invalid="(touched && !form.name.trim()) || undefined"
        />
        <FieldError v-if="touched && !form.name.trim()">{{ t('food.nameRequired') }}</FieldError>
      </div>
      <div class="space-y-2">
        <Label for="label-brand">{{ t('food.brand') }}</Label>
        <Input id="label-brand" v-model="form.brand" maxlength="200" />
      </div>
      <div class="space-y-2">
        <Label for="label-basis">{{ t('food.basis') }}</Label>
        <div class="flex gap-2">
          <Input
            id="label-basis"
            v-model="form.basis_quantity"
            inputmode="decimal"
            class="w-28"
            :aria-invalid="(touched && !parseAmount(form.basis_quantity)) || undefined"
          />
          <SegmentedControl
            v-model="form.basis_unit"
            :label="t('food.basis')"
            :segments="[
              { value: 'g', label: 'г' },
              { value: 'ml', label: 'мл' },
            ]"
          />
        </div>
        <FieldError v-if="touched && !parseAmount(form.basis_quantity)">{{
          t('food.invalidQuantity')
        }}</FieldError>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div v-for="[key, label] in fields" :key="key" class="space-y-2">
          <Label :for="`label-${key}`">{{ t(label) }}</Label>
          <Input
            :id="`label-${key}`"
            v-model="form.values[key]"
            inputmode="decimal"
            :aria-invalid="invalid(form.values[key]) || undefined"
          />
        </div>
      </div>
      <FieldError v-if="notNumbers.length">{{
        t('food.notNumbers', { fields: notNumbers.join(', ') })
      }}</FieldError>
      <Button type="submit" size="lg" class="w-full" :disabled="busy">{{
        t('food.createProduct')
      }}</Button>
    </form>
  </Panel>
</template>
