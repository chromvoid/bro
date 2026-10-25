<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button } from '@/components/ui/button'
import type { PluginError } from '@/plugins/api'
import { TREATMENTS, type FlowItem, type Treatment } from '../contracts'
import { errorText, t, treatmentLabel } from '../labels'

/** A Russian keyboard types a decimal comma; the service takes a point. */
const decimal = (text: string) => text.trim().replace(',', '.')

/** How the position book counts one deposit or withdrawal: automatically, own wallet, or money from outside. */
const props = defineProps<{ item: FlowItem; busy: boolean; error: PluginError | null }>()
const emit = defineEmits<{ save: [treatment: Treatment, unitCost: string | null]; cancel: [] }>()
const treatment = ref<Treatment>(
  (TREATMENTS as readonly string[]).includes(props.item.treatment)
    ? (props.item.treatment as Treatment)
    : 'auto',
)
const unitCost = ref(props.item.unit_cost_usd ?? '')
const incoming = props.item.direction === 'in'
const usesCost = computed(() => treatment.value === 'external' && incoming)
const valid = () =>
  !usesCost.value || !decimal(unitCost.value) || /^\d+(\.\d+)?$/.test(decimal(unitCost.value))
const save = () => {
  if (!valid()) return
  const cost = usesCost.value ? decimal(unitCost.value) || null : null
  emit('save', treatment.value, cost)
}
</script>

<template>
  <form class="bg-muted/40 mt-2 space-y-3 rounded-2xl p-3" @submit.prevent="save">
    <fieldset class="space-y-2">
      <legend class="text-sm font-medium">{{ t('mark_title') }}</legend>
      <label v-for="value in TREATMENTS" :key="value" class="flex items-start gap-2 text-sm">
        <input v-model="treatment" type="radio" :value="value" class="mt-1" />
        <span>
          {{ treatmentLabel(value, incoming) }}
          <span class="text-muted-foreground block text-xs">{{
            t(`mark_hint_${value}_${incoming ? 'in' : 'out'}` as const)
          }}</span>
        </span>
      </label>
    </fieldset>
    <label v-if="usesCost" class="block space-y-1 text-sm">
      <span>{{ t('mark_unit_cost', item.coin) }}</span>
      <input
        v-model="unitCost"
        inputmode="decimal"
        :aria-invalid="!valid()"
        class="bg-background w-full rounded-lg border px-3 py-2 tabular-nums"
      />
    </label>
    <p v-if="error" class="text-destructive text-sm" role="alert">{{ errorText(error) }}</p>
    <div class="flex gap-2">
      <Button type="submit" :disabled="busy || !valid()">{{ t('mark_save') }}</Button>
      <Button type="button" variant="ghost" :disabled="busy" @click="emit('cancel')">
        {{ t('cancel') }}
      </Button>
    </div>
  </form>
</template>
