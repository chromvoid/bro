<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { refDebounced } from '@vueuse/core'
import {
  Camera,
  CookingPot,
  History,
  PackageSearch,
  Plus,
  ScanText,
  Search,
  Star,
} from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { api } from '@/core/client'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import {
  Compositions,
  FoodResults,
  Memory,
  foodName,
  unitLabel,
  defaultMealKind,
  type Composition,
  type Food,
  type Remembered,
  shownName,
} from './api'
import { useFood } from './useFood'

/**
 * One sheet to add food: search on top, a photo or a label next to it, and while nothing is
 * typed the foods eaten before, favorites and saved dishes. «+» repeats the last portion (or one
 * serving of a dish) at once, with an undo; a tap on the row opens the portion to adjust it.
 */
const props = defineProps<{ day: string }>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{
  pick: [food: Food]
  remember: [food: Remembered]
  dish: [composition: Composition]
  label: [name: string]
  photo: []
  assistant: []
}>()
const { t } = useI18n()
const nutrition = useFood()
const session = useSession()
const text = ref('')
const tab = ref<'recent' | 'favorites' | 'dishes'>('recent')
const busy = ref<string | null>(null)
watch(open, (value) => {
  if (value) text.value = ''
})
const query = refDebounced(
  computed(() => text.value.trim()),
  250,
)

const results = useQuery({
  queryKey: computed(() => ['nutrition', 'search', query.value]),
  queryFn: async ({ signal }) =>
    FoodResults.parse(
      await api.get(`/v1/nutrition/foods/search?q=${encodeURIComponent(query.value)}`, signal),
    ),
  enabled: computed(() => open.value && !!query.value),
})
const foods = computed(() => results.data.value?.foods ?? [])

const memory = useQuery({
  queryKey: computed(() => ['nutrition', 'memory', props.day]),
  queryFn: async ({ signal }) =>
    Memory.parse(
      await api.get(`/v1/nutrition/memory?date=${encodeURIComponent(props.day)}`, signal),
    ),
  enabled: open,
})
// «Недавнее» is what was eaten last; «Избранное» keeps the server's order.
const remembered = computed(() => {
  const all = memory.data.value?.foods ?? []
  if (tab.value === 'favorites') return all.filter((food) => food.favorite)
  return [...all]
    .filter((food) => food.last_eaten)
    .sort((a, b) => (b.last_eaten ?? '').localeCompare(a.last_eaten ?? ''))
})

// Saved dishes: the «Блюда» tab lists them, and a search finds them next to products (the
// server returns at most 30, so the search reaches the rest).
const compositions = useQuery({
  queryKey: computed(() => ['nutrition', 'compositions', query.value]),
  queryFn: async ({ signal }) =>
    Compositions.parse(
      await api.get(`/v1/nutrition/compositions?q=${encodeURIComponent(query.value)}`, signal),
    ),
  enabled: computed(() => open.value && (!!query.value || tab.value === 'dishes')),
})
const dishes = computed(() => compositions.data.value?.compositions ?? [])
const showDishes = computed(() => (query.value ? dishes.value.length > 0 : tab.value === 'dishes'))

const tabs = computed(() => [
  { value: 'recent', label: t('food.recent') },
  { value: 'favorites', label: t('food.favorites') },
  { value: 'dishes', label: t('food.dishesTab') },
])

/** The last portion and its calories, from the source it was logged with. */
function lastTime(food: Remembered): string | null {
  if (!food.last_quantity || !food.last_unit) return null
  const amount = `${formatAmount(food.last_quantity)} ${unitLabel(food.last_unit)}`
  const kcal =
    food.energy_kcal && food.basis_quantity && food.last_unit === food.basis_unit
      ? (Number(food.energy_kcal) * Number(food.last_quantity)) / Number(food.basis_quantity)
      : null
  return kcal === null
    ? t('food.lastTime', { value: amount })
    : t('food.lastTimeKcal', { value: amount, kcal: formatAmount(kcal, 0) })
}

// One tap logs into the meal the clock suggests for today; another day gets no kind.
function kindFor() {
  const kind = defaultMealKind(props.day, session.me?.timezone ?? 'UTC')
  return kind ? { meal_kind: kind } : {}
}

const repeatable = (food: Remembered) =>
  !!(food.snapshot_id && food.last_quantity && food.last_unit)

async function repeat(food: Remembered) {
  if (!repeatable(food) || busy.value) return
  busy.value = food.food_id
  try {
    const receipt = await nutrition.operate('entry.add_many', {
      date: props.day,
      ...kindFor(),
      items: [
        {
          label: food.name,
          snapshot_id: food.snapshot_id,
          quantity: food.last_quantity,
          unit: food.last_unit,
        },
      ],
    })
    nutrition.done(receipt, nutrition.loggedMessage(receipt, food.name))
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = null
  }
}

async function logDish(composition: Composition) {
  if (busy.value) return
  busy.value = composition.id
  try {
    const receipt = await nutrition.operate('composition.log', {
      composition_id: composition.id,
      count: '1',
      date: props.day,
      ...kindFor(),
    })
    nutrition.done(receipt, nutrition.loggedMessage(receipt, composition.name))
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = null
  }
}

async function toggleFavorite(food: Remembered) {
  if (busy.value) return
  busy.value = food.food_id
  try {
    await nutrition.operate(
      'food.favorite',
      { food_id: food.food_id, favorite: !food.favorite },
      { [food.food_id]: food.preference_revision },
    )
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <!-- No autofocus: on a phone the keyboard would cover the foods eaten before. -->
  <Panel v-model:open="open" :title="t('food.addTitle')" :autofocus="false">
    <div class="relative">
      <Search class="text-muted-foreground absolute top-3 left-3.5 size-5" />
      <Input
        v-model="text"
        type="search"
        maxlength="200"
        class="pl-11"
        :placeholder="t('food.searchPlaceholder')"
        :aria-label="t('food.searchPlaceholder')"
        data-testid="add-search"
      />
    </div>
    <div class="grid grid-cols-2 gap-2">
      <Button variant="outline" data-testid="add-photo" @click="emit('photo')">
        <Camera />{{ t('food.addPhoto') }}
      </Button>
      <Button variant="outline" @click="emit('label', query)">
        <ScanText />{{ t('food.labelShort') }}
      </Button>
    </div>

    <SegmentedControl
      v-if="!query"
      v-model="tab"
      :segments="tabs"
      :label="t('food.addTitle')"
      stretch
    />

    <section v-if="showDishes" class="space-y-2">
      <p v-if="query" class="text-muted-foreground text-sm font-medium">
        {{ t('food.dishesTab') }}
      </p>
      <template v-else>
        <RefreshFailed :query="compositions" />
        <LoadingState v-if="compositions.isPending.value" :rows="3" />
        <ErrorState
          v-else-if="compositions.isLoadingError.value"
          :error="compositions.error.value"
          @retry="compositions.refetch()"
        />
        <EmptyState v-else-if="!dishes.length" :icon="CookingPot" :title="t('food.dishesEmpty')" />
      </template>
      <ul v-if="dishes.length" class="divide-y rounded-2xl border" data-testid="add-dishes">
        <li
          v-for="composition in dishes"
          :key="composition.id"
          class="flex items-center gap-1 pr-2"
        >
          <button
            type="button"
            class="hover:bg-accent min-w-0 flex-1 px-4 py-3 text-left"
            @click="emit('dish', composition)"
          >
            <span class="block truncate font-medium">{{ composition.name }}</span>
            <span class="text-muted-foreground block truncate text-sm">
              {{
                composition.components
                  .map(
                    (part) =>
                      `${part.label} ${formatAmount(part.quantity)} ${unitLabel(part.unit)}`,
                  )
                  .join(', ')
              }}
            </span>
          </button>
          <Button
            variant="ghost"
            size="icon"
            :aria-label="t('food.logOneServing', { name: composition.name })"
            :disabled="busy === composition.id"
            @click="logDish(composition)"
          >
            <Plus class="size-5" />
          </Button>
        </li>
      </ul>
    </section>

    <section v-if="query" class="space-y-2">
      <RefreshFailed :query="results" />
      <LoadingState v-if="results.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="results.isLoadingError.value"
        :error="results.error.value"
        @retry="results.refetch()"
      />
      <EmptyState
        v-else-if="!foods.length && !dishes.length"
        :icon="PackageSearch"
        :title="t('food.searchNothing')"
        :description="t('food.searchNothingText')"
      >
        <div class="flex flex-wrap justify-center gap-2">
          <Button variant="outline" @click="emit('label', query)">{{ t('food.addLabel') }}</Button>
          <Button variant="ghost" @click="emit('assistant')">{{ t('food.askAssistant') }}</Button>
        </div>
      </EmptyState>
      <template v-else-if="foods.length">
        <p v-if="dishes.length" class="text-muted-foreground text-sm font-medium">
          {{ t('food.searchResults') }}
        </p>
        <ul class="divide-y rounded-2xl border" data-testid="food-results">
          <li v-for="item in foods" :key="item.snapshot_id">
            <button
              type="button"
              class="hover:bg-accent w-full px-4 py-3 text-left"
              @click="emit('pick', item)"
            >
              <span class="flex items-center gap-2">
                <span class="font-medium">{{ shownName(foodName(item)) }}</span>
                <Badge v-if="item.personal" variant="secondary">{{ t('food.personal') }}</Badge>
              </span>
              <span class="text-muted-foreground block text-sm">
                <template v-if="item.brand">{{ item.brand }} · </template>
                {{
                  t('food.per', {
                    kcal: formatAmount(item.energy_kcal, 0),
                    basis: formatAmount(item.basis_quantity),
                    unit: unitLabel(item.basis_unit),
                  })
                }}
              </span>
            </button>
          </li>
        </ul>
      </template>
    </section>

    <section v-else-if="tab !== 'dishes'" class="space-y-2">
      <RefreshFailed :query="memory" />
      <LoadingState v-if="memory.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="memory.isLoadingError.value"
        :error="memory.error.value"
        @retry="memory.refetch()"
      />
      <EmptyState
        v-else-if="!remembered.length"
        :icon="tab === 'favorites' ? Star : History"
        :title="tab === 'favorites' ? t('food.favoritesEmpty') : t('food.memoryEmpty')"
      />
      <ul v-else class="divide-y rounded-2xl border" data-testid="add-memory">
        <li v-for="item in remembered" :key="item.food_id" class="flex items-center gap-1 pr-2">
          <button
            type="button"
            class="hover:bg-accent min-w-0 flex-1 px-4 py-3 text-left"
            @click="emit('remember', item)"
          >
            <span class="block truncate font-medium">{{ shownName(item.name) }}</span>
            <span v-if="lastTime(item)" class="text-muted-foreground block text-sm">
              {{ lastTime(item) }}
            </span>
          </button>
          <Button
            variant="ghost"
            size="icon"
            :aria-label="item.favorite ? t('food.unfavorite') : t('food.favorite')"
            :aria-pressed="item.favorite"
            :disabled="busy === item.food_id"
            @click="toggleFavorite(item)"
          >
            <Star :class="['size-5', item.favorite && 'text-warning-fill fill-current']" />
          </Button>
          <Button
            v-if="repeatable(item)"
            variant="ghost"
            size="icon"
            :aria-label="t('food.repeatLast', { name: shownName(item.name) })"
            :disabled="busy === item.food_id"
            data-testid="repeat-last"
            @click="repeat(item)"
          >
            <Plus class="size-5" />
          </Button>
        </li>
      </ul>
    </section>
  </Panel>
</template>
