<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { dayIn, isIsoDay, zonedInput, zonedInstant } from '@/core/dates'
import { parseAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { usePersonal } from './usePersonal'

/** P02: a weighing belongs to the moment it was measured, in the account's time zone. */
const props = defineProps<{ day?: string }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const form = reactive({ weight: '', measured: '', busy: false, error: '' })
watch(
  open,
  (visible) => {
    if (!visible) return
    const now = zonedInput(new Date(), zone.value)
    const selected =
      isIsoDay(props.day) && props.day <= dayIn(zone.value) ? props.day : now.slice(0, 10)
    Object.assign(form, { weight: '', measured: `${selected}T${now.slice(11)}`, error: '' })
  },
  { immediate: true },
)

function problem(): string {
  const weight = parseAmount(form.weight)
  if (!weight || Number(weight) < 20 || Number(weight) > 500) return t('personal.weightRange')
  const instant = zonedInstant(form.measured, zone.value)
  if (!instant) return t('personal.timeRequired')
  if (Date.parse(instant) > Date.now() + 60_000) return t('personal.futureTime')
  return ''
}

async function save() {
  form.error = problem()
  if (form.error || form.busy) return
  form.busy = true
  try {
    await personal.operate('weight.add', {
      weight_kg: parseAmount(form.weight),
      measured_at: zonedInstant(form.measured, zone.value),
    })
    toast.success(t('personal.weightSaved'))
    open.value = false
  } catch (error) {
    personal.failed(error)
  } finally {
    form.busy = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('personal.addWeight')">
    <form class="space-y-4" novalidate @submit.prevent="save">
      <div class="space-y-2">
        <Label for="weight-value">{{ t('personal.weightKg') }}</Label>
        <Input
          id="weight-value"
          v-model="form.weight"
          inputmode="decimal"
          autocomplete="off"
          :aria-invalid="!!form.error"
          aria-describedby="weight-error"
        />
      </div>
      <div class="space-y-2">
        <Label for="weight-measured">{{ t('personal.measuredAt') }}</Label>
        <Input id="weight-measured" v-model="form.measured" type="datetime-local" />
        <p class="text-muted-foreground text-sm">
          {{ t('personal.zoneHint', { zone }) }}
        </p>
      </div>
      <p v-if="form.error" id="weight-error" class="text-destructive text-sm" role="alert">
        {{ form.error }}
      </p>
      <Button type="submit" size="lg" class="w-full" :disabled="form.busy">
        {{ t('personal.saveWeight') }}
      </Button>
    </form>
  </Panel>
</template>
