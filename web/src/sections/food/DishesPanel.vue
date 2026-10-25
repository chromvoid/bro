<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { refDebounced } from '@vueuse/core'
import { CookingPot, Search } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { formatAmount, parseAmount } from '@/core/numbers'
import { Compositions, unitLabel, type Composition } from './api'
import { useFood } from './useFood'

/** `initial`: a dish picked in the add sheet, ready for its number of servings. */
const props = defineProps<{ day: string; initial?: Composition | null }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const nutrition = useFood()
const chosen = ref<Composition | null>(null)
const count = ref('1')
const busy = ref(false)
const search = ref('')
watch(open, () => {
  chosen.value = props.initial ?? null
  count.value = '1'
  search.value = ''
})
// The server returns at most 30 dishes, so a search reaches the rest.
const query = refDebounced(
  computed(() => search.value.trim()),
  250,
)
const compositions = useQuery({
  queryKey: computed(() => ['nutrition', 'compositions', query.value]),
  queryFn: async ({ signal }) =>
    Compositions.parse(
      await api.get(`/v1/nutrition/compositions?q=${encodeURIComponent(query.value)}`, signal),
    ),
  enabled: open,
})
const servings = computed(() => parseAmount(count.value))

/** F26: the saved composition becomes one dish entry for the day. */
async function log() {
  const composition = chosen.value
  if (!composition || !servings.value || busy.value) return
  busy.value = true
  try {
    const receipt = await nutrition.operate('composition.log', {
      composition_id: composition.id,
      count: servings.value,
      date: props.day,
    })
    nutrition.done(receipt, nutrition.loggedMessage(receipt, composition.name))
    open.value = false
  } catch (error) {
    nutrition.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('food.dishesTitle')">
    <div class="relative">
      <Search class="text-muted-foreground absolute top-3 left-3.5 size-5" />
      <Input
        v-model="search"
        type="search"
        maxlength="200"
        class="pl-11"
        :placeholder="t('food.dishesSearch')"
        :aria-label="t('food.dishesSearch')"
      />
    </div>
    <RefreshFailed :query="compositions" />
    <LoadingState v-if="compositions.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="compositions.isLoadingError.value"
      :error="compositions.error.value"
      @retry="compositions.refetch()"
    />
    <EmptyState
      v-else-if="!compositions.data.value?.compositions.length"
      :icon="CookingPot"
      :title="t('food.dishesEmpty')"
    />
    <template v-else>
      <ul class="divide-y rounded-2xl border">
        <li v-for="composition in compositions.data.value.compositions" :key="composition.id">
          <button
            type="button"
            :class="[
              'hover:bg-accent w-full px-4 py-3 text-left',
              chosen?.id === composition.id && 'bg-accent',
            ]"
            :aria-pressed="chosen?.id === composition.id"
            @click="chosen = composition"
          >
            <span class="block font-medium">{{ composition.name }}</span>
            <span class="text-muted-foreground block text-sm">
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
        </li>
      </ul>
      <div v-if="chosen" class="flex items-end gap-3">
        <div class="w-28 space-y-2">
          <Label for="dish-count">{{ t('food.servings') }}</Label>
          <Input id="dish-count" v-model="count" inputmode="decimal" />
        </div>
        <Button class="flex-1" size="lg" :disabled="!servings || busy" @click="log">{{
          t('food.logDish')
        }}</Button>
      </div>
    </template>
  </Panel>
</template>
