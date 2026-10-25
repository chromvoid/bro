<script setup lang="ts">
import { refDebounced } from '@vueuse/core'
import { useQuery } from '@tanstack/vue-query'
import { PackageSearch, Search } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { api } from '@/core/client'
import { formatAmount } from '@/core/numbers'
import { FoodResults, foodName, unitLabel, type Food, shownName } from './api'

const props = defineProps<{ initial?: string }>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ pick: [food: Food]; label: [name: string]; assistant: [] }>()
const { t } = useI18n()
const text = ref('')
watch(open, (value) => {
  if (value) text.value = props.initial ?? ''
})
const query = refDebounced(
  computed(() => text.value.trim()),
  250,
)
// An empty query lists the user's own products.
const results = useQuery({
  queryKey: computed(() => ['nutrition', 'search', query.value]),
  queryFn: async ({ signal }) =>
    FoodResults.parse(
      await api.get(`/v1/nutrition/foods/search?q=${encodeURIComponent(query.value)}`, signal),
    ),
  enabled: open,
})
const foods = computed(() => results.data.value?.foods ?? [])
</script>

<template>
  <Panel v-model:open="open" :title="t('food.searchTitle')">
    <div class="relative">
      <Search class="text-muted-foreground absolute top-3 left-3.5 size-5" />
      <Input
        v-model="text"
        type="search"
        maxlength="200"
        class="pl-11"
        :placeholder="t('food.searchPlaceholder')"
        :aria-label="t('food.searchPlaceholder')"
      />
    </div>
    <RefreshFailed :query="results" />
    <LoadingState v-if="results.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="results.isLoadingError.value"
      :error="results.error.value"
      @retry="results.refetch()"
    />
    <EmptyState
      v-else-if="!foods.length && query"
      :icon="PackageSearch"
      :title="t('food.searchNothing')"
      :description="t('food.searchNothingText')"
    >
      <div class="flex flex-wrap justify-center gap-2">
        <Button variant="outline" @click="emit('label', query)">{{ t('food.addLabel') }}</Button>
        <Button variant="ghost" @click="emit('assistant')">{{ t('food.askAssistant') }}</Button>
      </div>
    </EmptyState>
    <template v-else>
      <p class="text-muted-foreground text-sm font-medium">
        {{ query ? t('food.searchResults') : t('food.searchPersonal') }}
      </p>
      <ul class="divide-y rounded-2xl border" data-testid="food-results">
        <li v-for="food in foods" :key="food.snapshot_id">
          <button
            type="button"
            class="hover:bg-accent w-full px-4 py-3 text-left"
            @click="emit('pick', food)"
          >
            <span class="flex items-center gap-2">
              <span class="font-medium">{{ shownName(foodName(food)) }}</span>
              <Badge v-if="food.personal" variant="secondary">{{ t('food.personal') }}</Badge>
            </span>
            <span class="text-muted-foreground block text-sm">
              <template v-if="food.brand">{{ food.brand }} · </template>
              {{
                t('food.per', {
                  kcal: formatAmount(food.energy_kcal, 0),
                  basis: formatAmount(food.basis_quantity),
                  unit: unitLabel(food.basis_unit),
                })
              }}
            </span>
          </button>
        </li>
      </ul>
    </template>
  </Panel>
</template>
