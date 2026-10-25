<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button } from '@/components/ui/button'
import type { PluginError } from '@/plugins/api'
import type { CorePosition } from '../contracts'
import { errorText, methodLabel, t } from '../labels'

/** A Russian keyboard types a decimal comma; the service takes a point. */
const decimal = (text: string) => text.trim().replace(',', '.')

/** How many of a coin's coins, held at the start of a day, are the position held for long. */
const props = defineProps<{
  coin: string
  held: string
  method: string
  core: CorePosition | null | undefined
  busy: boolean
  error: PluginError | null
}>()
const emit = defineEmits<{ save: [qty: string, since: string]; cancel: [] }>()
/** The local calendar day of an instant, as an <input type="date"> value. */
const day = (instant: Date) =>
  `${instant.getFullYear()}-${String(instant.getMonth() + 1).padStart(2, '0')}-${String(instant.getDate()).padStart(2, '0')}`
const qty = ref(props.core?.asked ?? props.held)
const since = ref(day(props.core ? new Date(props.core.since) : new Date()))
const start = computed(() => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(since.value) || since.value > day(new Date())) return null
  const instant = new Date(`${since.value}T00:00:00`)
  return Number.isFinite(instant.getTime()) && day(instant) === since.value ? instant : null
})
const valid = computed(
  () =>
    /^\d+(\.\d+)?$/.test(decimal(qty.value)) &&
    Number(decimal(qty.value)) > 0 &&
    start.value !== null,
)
const save = () => {
  if (valid.value && start.value) emit('save', decimal(qty.value), start.value.toISOString())
}
</script>

<template>
  <form class="bg-muted/40 space-y-3 rounded-2xl p-3" @submit.prevent="save">
    <label class="block space-y-1 text-sm">
      <span>{{ t('core_qty', coin) }}</span>
      <input
        v-model="qty"
        inputmode="decimal"
        :aria-invalid="!valid"
        class="bg-background w-full rounded-lg border px-3 py-2 tabular-nums"
      />
    </label>
    <label class="block space-y-1 text-sm">
      <span>{{ t('core_since') }}</span>
      <input
        v-model="since"
        type="date"
        :max="day(new Date())"
        class="bg-background w-full rounded-lg border px-3 py-2 tabular-nums"
      />
    </label>
    <p class="text-muted-foreground text-xs">{{ t('core_editor_hint', methodLabel(method)) }}</p>
    <p v-if="error" class="text-destructive text-sm" role="alert">{{ errorText(error) }}</p>
    <div class="flex gap-2">
      <Button type="submit" :disabled="busy || !valid">{{ t('mark_save') }}</Button>
      <Button type="button" variant="ghost" :disabled="busy" @click="emit('cancel')">
        {{ t('cancel') }}
      </Button>
    </div>
  </form>
</template>
