<script setup lang="ts">
import {
  BarChart3,
  BookmarkCheck,
  CircleAlert,
  CircleCheck,
  Download,
  EllipsisVertical,
  Plus,
  Target,
  Utensils,
} from '@lucide/vue'
import { computed, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import BrandMark from '@/components/BrandMark.vue'
import DataNotes from '@/components/DataNotes.vue'
import DayNav from '@/components/DayNav.vue'
import Panel from '@/components/Panel.vue'
import { useIntersectionObserver, useMediaQuery } from '@vueuse/core'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Progress } from '@/components/ui/progress'
import { api } from '@/core/client'
import { dayIn, dayLabel, isIsoDay, timeIn } from '@/core/dates'
import { normalizePhoto, photoProblemKey } from '@/core/photo'
import { useQueryIntent } from '@/core/intent'
import { useRoomFor } from '@/core/layout'
import { formatAmount, UNKNOWN } from '@/core/numbers'
import { operationKind } from '@/core/operations'
import { useScrollDirection } from '@/core/useScrollDirection'
import { useUndo } from '@/core/undo'
import { draftHasPhoto, handOff } from '@/sections/assistant/useComposerDraft'
import { useSession } from '@/stores/session'
import {
  FoodResults,
  MEAL_KINDS,
  MealKindReceipt,
  NUTRIENTS,
  calorieBalance,
  entryName,
  isDish,
  nutrientProgress,
  shownName,
  unitLabel,
  type Dish,
  type Draft,
  type Entry,
  type Composition,
  type Food,
  type Remembered,
} from './api'
import AddFoodPanel from './AddFoodPanel.vue'
import AtLeast from './AtLeast.vue'
import DishesPanel from './DishesPanel.vue'
import { byKind, type KindSection, type Meal } from './meals'
import WeekStrip from './WeekStrip.vue'
import DishPanel from './DishPanel.vue'
import EntryPanel from './EntryPanel.vue'
import GoalPanel from './GoalPanel.vue'
import LabelPanel from './LabelPanel.vue'
import PortionPanel from './PortionPanel.vue'
import SearchPanel from './SearchPanel.vue'
import { useFood, useFoodDay } from './useFood'
import { targetItem, useFoodItemTarget } from './itemTarget'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const nutrition = useFood()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const targetId = computed(() => (typeof route.query.item === 'string' ? route.query.item : ''))
const targetKind = computed(() => (typeof route.query.kind === 'string' ? route.query.kind : ''))
const linkedItem = useFoodItemTarget(targetKind, targetId)
// The day lives in the address, so it survives reloads and can be shared as a link.
const day = computed(() =>
  linkedItem.data.value?.status === 'active' && targetId.value
    ? linkedItem.data.value.date
    : isIsoDay(route.query.day)
      ? route.query.day
      : today.value,
)
const diary = useFoodDay(day)
const canWrite = computed(() => session.can('nutrition.write'))

type Sheet =
  'add' | 'search' | 'portion' | 'entry' | 'dish' | 'dishes' | 'label' | 'goal' | 'export'
const sheet = ref<Sheet | null>(null)
const open = (name: Sheet) => (sheet.value = name)
// «Записать еду» on an empty Today card lands here with the add sheet open.
useQueryIntent('add', () => canWrite.value && open('add'))
const model = (name: Sheet) =>
  computed({
    get: () => sheet.value === name,
    // Closing a panel must not close the one it just opened (label → portion).
    set: (value: boolean) => {
      if (value) sheet.value = name
      else if (sheet.value === name) {
        sheet.value = null
        if (targetId.value && (name === 'entry' || name === 'dish')) closeTarget()
      }
    },
  })
const addOpen = model('add')
const searchOpen = model('search')
const portionOpen = model('portion')
const entryOpen = model('entry')
const dishOpen = model('dish')
const dishesOpen = model('dishes')
const labelOpen = model('label')
const goalOpen = model('goal')
const exportOpen = model('export')
// With room, an opened entry or dish sits beside the day instead of covering it; adding food
// keeps its own panel over the page. Wider screens add from the header, phones from the floating
// button above the tab bar.
const area = ref<HTMLElement | null>(null)
const roomy = useRoomFor(area, 56)
const split = computed(() => roomy.value && (entryOpen.value || dishOpen.value))
const wide = useMediaQuery('(min-width: 768px)')

const food = shallowRef<Food | null>(null)
const portionQuantity = ref<string | null>(null)
const draft = shallowRef<Draft | null>(null)
const searchText = ref('')
const labelText = ref('')
const entry = shallowRef<Entry | null>(null)
const dish = shallowRef<Dish | null>(null)
const completing = ref(false)

const data = computed(() => diary.data.value)
const energy = computed(() => data.value?.totals.energy_kcal)
const target = computed(() => data.value?.goal?.calorie_target ?? null)
const progress = computed(() =>
  target.value && energy.value
    ? Math.min(100, (Number(energy.value.known) / Number(target.value)) * 100)
    : 0,
)
const balance = computed(() => (data.value ? calorieBalance(data.value) : null))
// Entries read as meals: what was logged together, with its time and calories.
// Meals by the kind chosen when logging; a day without kinds reads by time, as it was logged.
const sections = computed(() => (data.value ? byKind(data.value.items) : []))
const typed = computed(() => sections.value.some((section) => section.kind !== null))
const kindLabel = (section: KindSection) =>
  section.kind ? t(`food.mealKinds.${section.kind}`) : t('food.noMealKind')
// A meal's time is when it was logged, not eaten: a recap of the day logged in the evening puts
// its breakfast at 23:04, so under a kind the time says it is the logging's.
const mealTime = (section: KindSection, meal: Meal) => {
  if (!meal.at) return t('food.noTime')
  const time = timeIn(zone.value, meal.at)
  return section.kind ? t('food.mealLoggedAt', { time }) : time
}

const undoable = useUndo()
async function setKind(meal: Meal, value: unknown) {
  const kind = MEAL_KINDS.find((item) => item === value) ?? null
  if (!meal.mealIds.length) return
  try {
    const receipt = MealKindReceipt.parse(
      await nutrition.operate('meal.set_kind', { meal_ids: meal.mealIds, kind }),
    )
    const message = kind
      ? t('food.mealKindSet', { kind: t(`food.mealKinds.${kind}`) })
      : t('food.mealKindCleared')
    // Undo gives each meal back its own kind: one change per kind it had.
    undoable.confirm(message, async () => {
      for (const previous of new Set(receipt.meals.map((item) => item.previous_kind))) {
        const ids = receipt.meals.filter((item) => item.previous_kind === previous)
        await nutrition.operate('meal.set_kind', {
          meal_ids: ids.map((item) => item.meal_id),
          kind: previous,
        })
      }
    })
  } catch (error) {
    nutrition.failed(error)
  }
}
// Grams of each nutrient, against its goal when the goal names one.
const macros = computed(() => {
  const day = data.value
  if (!day) return []
  return NUTRIENTS.map((key) => {
    const total = day.totals[key]
    return {
      key,
      total,
      goal: nutrientProgress(day, key),
      // Nothing known yet (no item states it) reads as no data, not «≥ 0 г».
      blank: !total || (!total.complete && !Number(total.known)),
    }
  })
})
// A macro without a goal offers to set one, once the day has a calorie goal to go with it.
const GOAL_MACROS: readonly string[] = ['protein_g', 'fat_g', 'carbohydrate_g']
const goalField = ref<string | null>(null)
function setGoal(field: string | null = null) {
  goalField.value = field
  open('goal')
}
// A meal's calories only where its kind's heading does not already say them.
const mealKcal = (section: KindSection) => !typed.value || section.meals.length > 1
const estimated = computed(() => data.value?.items.some((item) => item.estimated) ?? false)

// The floating «Добавить еду» steps aside while the day is read down and comes back on the way
// up or at the end; once the summary is out of view it is a round button, so it covers less.
const scrolling = useScrollDirection()
const fabHidden = computed(
  () => scrolling.direction.value === 'down' && !scrolling.atEnd.value && !sheet.value,
)
const summary = useTemplateRef<HTMLElement>('summary')
const summaryInView = ref(true)
useIntersectionObserver(summary, ([entry]) => (summaryInView.value = entry?.isIntersecting ?? true))
const remaining = computed(() =>
  balance.value
    ? t(balance.value.over ? 'food.over' : 'food.remaining', {
        value: formatAmount(balance.value.kcal, 0),
      })
    : null,
)
const blocker = computed(() => {
  const current = data.value
  if (!current) return null
  if (!current.entries.length) return 'empty'
  if (current.drafts.length) return 'drafts'
  if (!current.totals.energy_kcal?.complete) return 'incomplete'
  return null
})

function go(next: string) {
  if (targetId.value) sheet.value = null
  void router.replace({
    query: {
      ...route.query,
      day: next === today.value ? undefined : next,
      item: undefined,
      kind: undefined,
    },
  })
}

function pickFood(picked: Food) {
  food.value = picked
  portionQuantity.value = null
  open('portion')
}

function startSearch(text = '', forDraft: Draft | null = null) {
  draft.value = forDraft
  searchText.value = text
  open('search')
}

/** Remembered foods carry no snapshot; find the current one by name and match the food. */
async function pickRemembered(remembered: Remembered) {
  try {
    const results = FoodResults.parse(
      await api.get(`/v1/nutrition/foods/search?q=${encodeURIComponent(remembered.name)}`),
    )
    const match = results.foods.find((candidate) => candidate.food_id === remembered.food_id)
    if (!match) return startSearch(remembered.name)
    draft.value = null
    food.value = match
    portionQuantity.value =
      remembered.last_unit === match.basis_unit ? (remembered.last_quantity ?? null) : null
    open('portion')
  } catch (error) {
    nutrition.failed(error)
  }
}

function openItem(item: Entry | Dish) {
  if (isDish(item)) {
    dish.value = item
    open('dish')
  } else {
    entry.value = item
    open('entry')
  }
}

function closeTarget() {
  void router.replace({
    query: {
      ...route.query,
      day: day.value === today.value ? undefined : day.value,
      item: undefined,
      kind: undefined,
    },
  })
}
const targetUnavailable = computed(
  () =>
    !!targetId.value &&
    (!session.can('nutrition.read') ||
      !['entry', 'dish'].includes(targetKind.value) ||
      linkedItem.isLoadingError.value ||
      linkedItem.data.value?.status === 'deleted' ||
      (!!linkedItem.data.value &&
        !!data.value &&
        data.value.date === day.value &&
        !targetItem(linkedItem.data.value, data.value))),
)
watch(
  [targetId, targetKind, () => session.me?.id, () => session.can('nutrition.read')],
  () => {
    sheet.value = null
    entry.value = null
    dish.value = null
  },
  { flush: 'sync' },
)
watch(
  [targetId, targetKind, linkedItem.data, data],
  () => {
    if (!session.can('nutrition.read') || !targetId.value || !linkedItem.data.value || !data.value)
      return
    const item = targetItem(linkedItem.data.value, data.value)
    if (item) openItem(item)
    else if (sheet.value === 'entry' || sheet.value === 'dish') sheet.value = null
  },
  { immediate: true },
)
watch(targetUnavailable, (unavailable) => {
  if (unavailable && (sheet.value === 'entry' || sheet.value === 'dish')) sheet.value = null
})

async function complete() {
  const current = data.value
  if (!current) return
  try {
    await nutrition.operate('day.complete', { date: current.date, revision: current.day_revision })
    toast.success(t('food.completed'))
    completing.value = false
  } catch (error) {
    nutrition.failed(error)
  }
}

const describe = (write: { body: string }) =>
  t('food.unresolved', { kind: operationKind(write as never) })
const missing = (fields: string[]) =>
  fields
    .map((field) =>
      t(`food.missing.${field}`) !== `food.missing.${field}` ? t(`food.missing.${field}`) : field,
    )
    .join(', ')

// «Фото еды»: the photo goes to the assistant's draft with a request to log it; the person
// reviews and sends it there. Recognizing food from a photo is the assistant's job.
const camera = useTemplateRef<HTMLInputElement>('camera')
const replacing = shallowRef<Blob | null>(null)
async function photoPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const account = session.account
  if (!file || !account) return
  let photo: Blob
  try {
    photo = await normalizePhoto(file)
  } catch (error) {
    return void toast.error(t(photoProblemKey(error)))
  }
  if (account !== session.account) return
  let occupied: boolean
  try {
    occupied = await draftHasPhoto(account.store)
  } catch {
    return void toast.error(t('food.photoHandoffFailed'))
  }
  if (occupied) replacing.value = photo
  else await sendPhoto(photo, false)
}
async function sendPhoto(photo: Blob, replacePhoto: boolean) {
  replacing.value = null
  const account = session.account
  if (!account) return
  const text =
    day.value === today.value
      ? t('food.photoPrompt')
      : t('food.photoPromptDay', { day: dayLabel(day.value, today.value).toLowerCase() })
  try {
    await handOff(account.store, account.journal, { mode: 'prefill', text, photo, replacePhoto })
  } catch {
    return void toast.error(t('food.photoHandoffFailed'))
  }
  if (account === session.account) await router.push({ name: 'assistant' })
}

/** The camera opens inside the tap on «Фото еды»; a picker opened later would be blocked. */
function takePhoto() {
  camera.value?.click()
  sheet.value = null
}
const composition = shallowRef<Composition | null>(null)
function pickDish(chosen: Composition) {
  composition.value = chosen
  open('dishes')
}
function startLabel(name: string) {
  labelText.value = name
  open('label')
}
</script>

<template>
  <PageHeader :title="t('food.title')" :width="split ? '6xl' : '3xl'">
    <template #actions>
      <Button v-if="canWrite && wide" data-testid="add-food" @click="open('add')"
        ><Plus />{{ t('food.add') }}</Button
      >
    </template>
    <template #menu>
      <DropdownMenuItem @select="router.push({ name: 'food-stats' })"
        ><BarChart3 />{{ t('stats.title') }}</DropdownMenuItem
      >
      <DropdownMenuItem v-if="canWrite" @select="setGoal()"
        ><Target />{{ t('food.goalTitle') }}</DropdownMenuItem
      >
      <DropdownMenuItem @select="open('export')"
        ><Download />{{ t('food.exportTitle') }}</DropdownMenuItem
      >
    </template>
  </PageHeader>

  <div ref="area">
    <div :class="['mx-auto px-4 md:px-8', split ? 'max-w-6xl' : 'max-w-3xl']">
      <div :class="split ? 'grid grid-cols-[minmax(0,1fr)_24rem] items-start gap-6' : undefined">
        <div class="min-w-0 space-y-5">
          <DayNav :day="day" :today="today" @go="go" />
          <WeekStrip :day="day" :today="today" @go="go" />

          <UnresolvedWrites
            :entries="nutrition.unresolved.value"
            :describe="describe"
            :check="nutrition.check"
            :resend="nutrition.resend"
            :abandon="nutrition.abandon"
          />

          <RefreshFailed :query="diary" />
          <RefreshFailed v-if="targetId" :query="linkedItem" />
          <div
            v-if="targetUnavailable"
            role="status"
            class="flex flex-wrap items-center gap-2 text-sm"
            data-testid="food-target-unavailable"
          >
            <p>{{ t('assistant.resultActions.unavailable') }}</p>
            <Button size="sm" variant="ghost" @click="closeTarget">{{ t('common.close') }}</Button>
          </div>
          <p
            v-else-if="targetId && linkedItem.isPending.value"
            role="status"
            class="text-muted-foreground text-sm"
          >
            {{ t('assistant.resultActions.opening') }}
          </p>
          <LoadingState v-if="diary.isPending.value" :rows="4" />
          <ErrorState
            v-else-if="diary.isLoadingError.value"
            :error="diary.error.value"
            @retry="diary.refetch()"
          />
          <template v-else-if="data">
            <section
              ref="summary"
              class="bg-card space-y-4 rounded-3xl border p-5"
              data-testid="day-summary"
            >
              <div class="flex flex-wrap items-end justify-between gap-3">
                <div>
                  <p class="text-muted-foreground text-sm">{{ t('food.eaten') }}</p>
                  <p class="text-4xl font-semibold tracking-tight tabular-nums">
                    <AtLeast
                      :value="energy?.known"
                      :complete="energy?.complete ?? true"
                      :fraction="0"
                    />
                    <span class="text-muted-foreground ml-1.5 text-base font-normal">
                      {{
                        target ? t('food.of', { target: formatAmount(target, 0) }) : t('food.kcal')
                      }}
                    </span>
                  </p>
                </div>
                <Button v-if="!target && canWrite" variant="outline" size="sm" @click="setGoal()">
                  <Target />{{ t('food.setLimit') }}
                </Button>
              </div>
              <template v-if="target">
                <Progress
                  :model-value="progress"
                  :tone="balance?.over ? 'warning' : 'default'"
                  :aria-label="t('food.eaten')"
                />
                <p
                  :class="['text-sm font-medium', balance?.over && 'text-warning']"
                  data-testid="calorie-balance"
                >
                  {{ remaining }}
                </p>
              </template>
              <p
                v-if="energy && !energy.complete"
                class="text-muted-foreground flex items-start gap-2 text-sm"
              >
                <CircleAlert class="text-warning mt-0.5 size-4 shrink-0" />{{
                  t('food.incomplete')
                }}
              </p>
              <dl class="grid grid-cols-2 gap-x-4 gap-y-3 border-t pt-4 text-sm sm:grid-cols-4">
                <div
                  v-for="macro in macros"
                  :key="macro.key"
                  class="space-y-1"
                  :data-testid="`macro-${macro.key}`"
                >
                  <dt class="text-muted-foreground">{{ t(`food.macros.${macro.key}`) }}</dt>
                  <dd v-if="macro.blank" class="font-medium">
                    <span aria-hidden="true">{{ UNKNOWN }}</span
                    ><span class="sr-only">{{ t('food.noData') }}</span>
                  </dd>
                  <dd v-else class="font-medium tabular-nums">
                    <template v-if="macro.total && !macro.total.complete"
                      ><span class="text-warning" aria-hidden="true">≥&nbsp;</span
                      ><span class="sr-only">{{ t('food.atLeast') }}&nbsp;</span></template
                    >{{
                      macro.goal
                        ? t('food.gramsOf', {
                            value: formatAmount(macro.total?.known),
                            target: formatAmount(macro.goal.target, 0),
                          })
                        : t('food.grams', { value: formatAmount(macro.total?.known) })
                    }}<template v-if="macro.goal?.reached"
                      ><CircleCheck
                        class="text-success ms-1.5 inline size-4 align-[-0.125em]"
                        aria-hidden="true"
                      /><span class="sr-only">{{ t('food.goalReached') }}</span></template
                    ><template v-else-if="macro.goal?.over"
                      ><CircleAlert
                        class="text-warning ms-1.5 inline size-4 align-[-0.125em]"
                        aria-hidden="true"
                      /><span class="sr-only">{{ t('food.goalPassed') }}</span></template
                    >
                  </dd>
                  <dd v-if="macro.goal">
                    <Progress
                      :model-value="macro.goal.share"
                      :tone="
                        macro.goal.over ? 'warning' : macro.goal.reached ? 'success' : 'default'
                      "
                      :aria-label="t(`food.macros.${macro.key}`)"
                    />
                  </dd>
                  <dd v-else-if="target && canWrite && GOAL_MACROS.includes(macro.key)">
                    <!-- The small link keeps the grid compact; its pseudo-element makes the
                         touch target 44 px tall. -->
                    <button
                      type="button"
                      class="text-primary relative text-xs font-medium underline-offset-4 before:absolute before:-inset-x-2 before:-inset-y-3.5 hover:underline"
                      :aria-label="t('food.setGoalFor', { name: t(`food.macros.${macro.key}`) })"
                      @click="setGoal(macro.key)"
                    >
                      {{ t('food.setGoal') }}
                    </button>
                  </dd>
                </div>
              </dl>
            </section>

            <section v-if="data.drafts.length" class="space-y-2" aria-labelledby="drafts-title">
              <h2 id="drafts-title" class="font-semibold">{{ t('food.drafts') }}</h2>
              <ul class="space-y-2">
                <li
                  v-for="item in data.drafts"
                  :key="item.id"
                  class="bg-warning-surface flex items-center gap-3 rounded-2xl px-4 py-3"
                >
                  <div class="min-w-0 flex-1">
                    <p class="font-medium">{{ item.label }}</p>
                    <p class="text-muted-foreground text-sm">
                      {{ t('food.draftText', { fields: missing(item.missing_fields) }) }}
                    </p>
                  </div>
                  <Button
                    v-if="canWrite"
                    size="sm"
                    variant="outline"
                    @click="startSearch(item.label, item)"
                    >{{ t('food.resolve') }}</Button
                  >
                </li>
              </ul>
            </section>

            <EmptyState
              v-if="!data.items.length && !data.drafts.length"
              :icon="Utensils"
              :title="t('food.emptyTitle')"
              :description="t('food.emptyText')"
            />
            <section
              v-else-if="data.items.length"
              class="space-y-2"
              aria-labelledby="entries-title"
            >
              <h2 id="entries-title" class="font-semibold">{{ t('food.entries') }}</h2>
              <div class="space-y-4" data-testid="entries">
                <section
                  v-for="section in sections"
                  :key="section.kind ?? 'none'"
                  class="space-y-2"
                  :aria-label="typed ? kindLabel(section) : undefined"
                  :data-testid="`meal-kind-${section.kind ?? 'none'}`"
                >
                  <h3
                    v-if="typed"
                    class="flex items-baseline justify-between gap-3 px-1 font-medium"
                  >
                    <span>{{ kindLabel(section) }}</span>
                    <span class="text-muted-foreground text-sm font-normal"
                      ><AtLeast :value="section.kcal" :complete="section.complete" :fraction="0" />
                      {{ t('food.kcal') }}</span
                    >
                  </h3>
                  <section
                    v-for="meal in section.meals"
                    :key="meal.key"
                    class="bg-card overflow-hidden rounded-3xl border"
                    :aria-label="
                      meal.at
                        ? t('food.loggedAt', { time: timeIn(zone, meal.at) })
                        : t('food.noTime')
                    "
                  >
                    <div
                      class="text-muted-foreground flex flex-wrap items-center justify-between gap-x-3 border-b py-1 pr-1 pl-4 text-sm"
                      data-testid="meal-header"
                    >
                      <span>{{ mealTime(section, meal) }}</span>
                      <!-- With large text the calories and the menu move under the time together. -->
                      <div class="ms-auto flex items-center gap-3">
                        <span v-if="mealKcal(section)"
                          ><AtLeast :value="meal.kcal" :complete="meal.complete" :fraction="0" />
                          {{ t('food.kcal') }}</span
                        >
                        <DropdownMenu v-if="canWrite && meal.mealIds.length">
                          <DropdownMenuTrigger as-child>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              :aria-label="t('food.changeMealKind')"
                              ><EllipsisVertical
                            /></Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuLabel>{{ t('food.changeMealKind') }}</DropdownMenuLabel>
                            <DropdownMenuRadioGroup
                              :model-value="section.kind ?? 'none'"
                              @update:model-value="setKind(meal, $event)"
                            >
                              <DropdownMenuRadioItem
                                v-for="kind in MEAL_KINDS"
                                :key="kind"
                                :value="kind"
                                >{{ t(`food.mealKinds.${kind}`) }}</DropdownMenuRadioItem
                              >
                              <DropdownMenuRadioItem value="none">{{
                                t('food.noMealKind')
                              }}</DropdownMenuRadioItem>
                            </DropdownMenuRadioGroup>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    </div>
                    <ul class="divide-y">
                      <li v-for="item in meal.items" :key="item.id">
                        <button
                          type="button"
                          class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3 text-left"
                          @click="openItem(item)"
                        >
                          <span class="min-w-0 flex-1">
                            <span class="line-clamp-2 font-medium break-words">{{
                              shownName(isDish(item) ? item.name : entryName(item))
                            }}</span>
                            <span class="text-muted-foreground block text-sm">
                              {{ formatAmount(item.quantity) }} {{ unitLabel(item.unit) }}
                              <template v-if="isDish(item)">
                                ·
                                {{
                                  t(
                                    'food.dishOf',
                                    { count: item.components.length },
                                    item.components.length,
                                  )
                                }}</template
                              >
                            </span>
                          </span>
                          <span class="shrink-0 font-medium tabular-nums">
                            <template v-if="item.estimated"
                              ><span class="text-muted-foreground" aria-hidden="true">≈</span
                              ><span class="sr-only">{{ t('food.estimated') }}</span></template
                            >{{ formatAmount(item.nutrients?.energy_kcal, 0) }}
                            <span class="text-muted-foreground text-sm font-normal">{{
                              t('food.kcal')
                            }}</span>
                          </span>
                        </button>
                      </li>
                    </ul>
                  </section>
                </section>
              </div>
              <DataNotes
                class="px-1"
                :notes="[estimated && t('food.estimatedLegend')]"
                data-testid="estimated-legend"
              />
            </section>

            <section class="pb-24 md:pb-8">
              <div
                v-if="data.completed"
                class="bg-success-surface flex items-center gap-3 rounded-2xl p-4"
                role="status"
              >
                <BrandMark mood="ok" class="size-10 shrink-0" />
                <div>
                  <p class="flex items-center gap-1.5 font-medium">
                    <BookmarkCheck class="text-success size-4 shrink-0" />{{ t('food.completed') }}
                  </p>
                </div>
              </div>
              <div v-else-if="canWrite" class="space-y-1.5">
                <Button
                  variant="outline"
                  class="w-full"
                  :disabled="!!blocker"
                  @click="completing = true"
                >
                  <BookmarkCheck />{{ t('food.complete') }}
                </Button>
                <p
                  v-if="blocker && blocker !== 'empty'"
                  class="text-muted-foreground text-center text-sm"
                >
                  {{ t(`food.cannotComplete.${blocker}`) }}
                </p>
              </div>
            </section>
          </template>
        </div>
        <EntryPanel v-model:open="entryOpen" :entry="entry" :docked="roomy" />
        <DishPanel v-model:open="dishOpen" :dish="dish" :docked="roomy" />
      </div>
    </div>
  </div>

  <div
    v-if="canWrite && !wide"
    :class="[
      'pointer-events-none fixed inset-x-0 bottom-[calc(var(--app-bottom)+1rem)] z-20 flex justify-end px-4 motion-safe:transition-transform motion-safe:duration-200',
      fabHidden && 'translate-y-[calc(100%+var(--app-bottom)+1rem)]',
    ]"
    :inert="fabHidden"
    data-testid="add-food-dock"
    :data-hidden="fabHidden || undefined"
  >
    <Button
      size="lg"
      :class="[
        'pointer-events-auto rounded-full shadow-lg',
        summaryInView ? 'px-6' : 'size-14 px-0 [&_svg]:size-6',
      ]"
      :aria-label="summaryInView ? undefined : t('food.add')"
      data-testid="add-food"
      @click="open('add')"
    >
      <Plus /><template v-if="summaryInView">{{ t('food.add') }}</template>
    </Button>
  </div>

  <!-- Outside the panel: the panel's content is gone once it closes, the picker must stay. -->
  <input
    ref="camera"
    type="file"
    accept="image/*"
    capture="environment"
    class="hidden"
    data-testid="food-photo-input"
    @change="photoPicked"
  />
  <AddFoodPanel
    v-model:open="addOpen"
    :day="day"
    @pick="pickFood"
    @remember="pickRemembered"
    @dish="pickDish"
    @label="startLabel"
    @photo="takePhoto"
    @assistant="router.push({ name: 'assistant' })"
  />
  <SearchPanel
    v-model:open="searchOpen"
    :initial="searchText"
    @pick="pickFood"
    @label="startLabel"
    @assistant="router.push({ name: 'assistant' })"
  />
  <PortionPanel
    v-model:open="portionOpen"
    :food="food"
    :day="day"
    :draft="draft"
    :quantity="portionQuantity"
  />
  <DishesPanel v-model:open="dishesOpen" :day="day" :initial="composition" />
  <LabelPanel v-model:open="labelOpen" :initial="labelText" @created="pickFood" />
  <GoalPanel v-model:open="goalOpen" :day="day" :goal="data?.goal ?? null" :field="goalField" />
  <Panel
    v-model:open="exportOpen"
    :title="t('food.exportTitle')"
    :description="t('food.exportHint')"
  >
    <div class="grid grid-cols-1 gap-2">
      <Button as-child variant="outline" size="lg">
        <a href="/v1/nutrition/export?format=csv" download><Download />{{ t('food.exportCsv') }}</a>
      </Button>
      <Button as-child variant="outline" size="lg">
        <a href="/v1/nutrition/export?format=json" download
          ><Download />{{ t('food.exportJson') }}</a
        >
      </Button>
    </div>
  </Panel>
  <Dialog :open="!!replacing" @update:open="(value) => !value && (replacing = null)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('food.photoReplaceTitle') }}</DialogTitle>
        <DialogDescription>{{ t('food.photoReplaceText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="replacing = null">{{ t('common.cancel') }}</Button>
        <Button data-testid="food-photo-replace" @click="sendPhoto(replacing!, true)">{{
          t('food.photoReplace')
        }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
  <Dialog v-model:open="completing">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('food.completeTitle') }}</DialogTitle>
        <DialogDescription>{{ t('food.completeText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="completing = false">{{ t('common.cancel') }}</Button>
        <Button @click="complete">{{ t('food.completeConfirm') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
