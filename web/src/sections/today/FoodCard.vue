<script setup lang="ts">
import { Plus, Utensils } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { formatAmount } from '@/core/numbers'
import { calorieBalance } from '@/sections/food/api'
import AtLeast from '@/sections/food/AtLeast.vue'
import { useFoodDay } from '@/sections/food/useFood'
import { useSession } from '@/stores/session'

const props = defineProps<{ day: string }>()
const { t } = useI18n()
const session = useSession()
const diary = useFoodDay(computed(() => props.day))
const data = computed(() => diary.data.value)
const energy = computed(() => data.value?.totals.energy_kcal)
const target = computed(() => data.value?.goal?.calorie_target ?? null)
const balance = computed(() => (data.value ? calorieBalance(data.value) : null))
// Nothing recorded yet: offer to start instead of linking to an empty diary.
const empty = computed(() => !!data.value && !data.value.items.length && !data.value.drafts.length)
</script>

<template>
  <section v-if="empty" class="bg-card space-y-3 rounded-3xl border p-5" data-testid="today-food">
    <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
      <Utensils class="size-4" />{{ t('today.food') }}
    </h2>
    <p class="text-muted-foreground">{{ t('today.foodEmpty') }}</p>
    <Button v-if="session.can('nutrition.write')" variant="outline" as-child>
      <RouterLink :to="{ name: 'food', query: { add: '1' } }"
        ><Plus />{{ t('today.startFood') }}</RouterLink
      >
    </Button>
  </section>
  <RouterLink
    v-else
    :to="{ name: 'food' }"
    class="bg-card hover:bg-accent/40 block space-y-3 rounded-3xl border p-5 transition-colors"
    data-testid="today-food"
  >
    <h2 class="text-muted-foreground flex items-center gap-2 text-sm font-medium">
      <Utensils class="size-4" />{{ t('today.food') }}
    </h2>
    <Skeleton v-if="diary.isPending.value" class="h-10 w-40" />
    <template v-else-if="data">
      <p class="text-3xl font-semibold tabular-nums">
        <AtLeast :value="energy?.known" :complete="energy?.complete ?? true" :fraction="0" />
        <span class="text-muted-foreground ml-1 text-base font-normal">
          {{ target ? t('food.of', { target: formatAmount(target, 0) }) : t('food.kcal') }}
        </span>
      </p>
      <Progress
        v-if="target && energy"
        :model-value="Math.min(100, (Number(energy.known) / Number(target)) * 100)"
        :tone="balance?.over ? 'warning' : 'default'"
        :aria-label="t('food.eaten')"
      />
      <p
        v-if="balance"
        :class="['text-sm', balance.over ? 'text-warning font-medium' : 'text-muted-foreground']"
      >
        {{
          t(balance.over ? 'food.over' : 'food.remaining', {
            value: formatAmount(balance.kcal, 0),
          })
        }}
      </p>
    </template>
  </RouterLink>
</template>
