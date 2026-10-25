<script setup lang="ts">
import { Plus, Scale } from '@lucide/vue'
import { computed, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { dayIn, dayLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { signedKilograms } from '@/sections/personal/api'
import { useOverview, useWeightChange } from '@/sections/personal/usePersonal'
import { useSession } from '@/stores/session'

const props = defineProps<{ day: string }>()
const { t } = useI18n()
const session = useSession()
// The same query as the personal screen: one cache entry must have one parser.
const overview = useOverview(toRef(props, 'day'))
const weight = computed(() => overview.data.value?.metrics.weight)
const weightChange = useWeightChange(toRef(props, 'day'))
// No weighing yet: offer to record one instead of linking to an empty screen.
const empty = computed(
  () => !!overview.data.value && !(weight.value?.status === 'available' && weight.value.value),
)
const measured = computed(() => {
  const at = weight.value?.records?.at(-1)?.start_at
  const zone = session.me?.timezone ?? 'UTC'
  return at ? dayLabel(dayIn(zone, new Date(at)), props.day).toLowerCase() : null
})
</script>

<template>
  <section
    v-if="empty"
    class="bg-card space-y-3 rounded-3xl border p-5"
    data-testid="today-personal"
  >
    <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
      <Scale class="size-4" />{{ t('today.personal') }}
    </h2>
    <p class="text-muted-foreground">{{ t('today.noWeight') }}</p>
    <Button v-if="session.can('personal.write')" variant="outline" as-child>
      <RouterLink :to="{ name: 'personal', query: { weight: '1' } }"
        ><Plus />{{ t('today.startWeight') }}</RouterLink
      >
    </Button>
  </section>
  <RouterLink
    v-else
    :to="{ name: 'personal' }"
    class="bg-card hover:bg-accent/40 block space-y-2 rounded-3xl border p-5"
    data-testid="today-personal"
  >
    <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
      <Scale class="size-4" />{{ t('today.personal') }}
    </h2>
    <Skeleton v-if="overview.isPending.value" class="h-8 w-32" />
    <p v-else-if="overview.isLoadingError.value" class="text-muted-foreground text-sm">
      {{ t('today.cardError') }}
    </p>
    <p v-else-if="weight?.value" class="text-2xl font-semibold tabular-nums">
      {{ t('today.weightValue', { value: formatAmount(weight.value) }) }}
      <span v-if="measured" class="text-muted-foreground text-sm font-normal">{{
        t('today.measured', { when: measured })
      }}</span>
    </p>
    <p v-if="weightChange.change.value !== null" class="text-muted-foreground text-sm">
      {{
        t('personal.weightChange', {
          change: signedKilograms(weightChange.change.value, t('personal.kg')),
        })
      }}
    </p>
  </RouterLink>
</template>
