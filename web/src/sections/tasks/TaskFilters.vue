<script setup lang="ts">
import { SlidersHorizontal, X } from '@lucide/vue'
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { activeTaskFilterCount, hasTaskNarrowing, type TaskFilterState } from './taskFilters'

/**
 * The action lives in the existing header row. A separate summary instance takes room only
 * while conditions are active, so an untouched mobile board keeps its first card in view.
 */
const props = withDefaults(
  defineProps<{
    filters: TaskFilterState
    placement?: 'action' | 'summary'
    shared?: boolean
    total?: number | null
    pending?: boolean
    projectName?: string | null
  }>(),
  { placement: 'action', shared: false, total: null, pending: false, projectName: null },
)
const emit = defineEmits<{
  change: [patch: Partial<TaskFilterState>]
  reset: []
  wholeHistory: []
}>()
const { t } = useI18n()
const id = useId()
const open = ref(false)
const count = computed(() => activeTaskFilterCount(props.filters))
const narrowed = computed(() => hasTaskNarrowing(props.filters))
const assignees = ['any', 'me', 'unassigned'] as const

function chooseAssignee(value: unknown) {
  if (value === 'any') emit('change', { assignee: null })
  else if (value === 'me' || value === 'unassigned') emit('change', { assignee: value })
}

function wholeHistory() {
  open.value = false
  emit('wholeHistory')
}

function reset() {
  open.value = false
  emit('reset')
}

interface Chip {
  key: keyof TaskFilterState
  label: string
  clear: Partial<TaskFilterState>
}
const chips = computed(() => {
  const items: Chip[] = []
  const filters = props.filters
  if (filters.q)
    items.push({
      key: 'q',
      label: t('tasks.filters.query', { query: filters.q }),
      clear: { q: '' },
    })
  if (filters.important)
    items.push({
      key: 'important',
      label: t('tasks.filters.important'),
      clear: { important: false },
    })
  if (filters.overdue)
    items.push({ key: 'overdue', label: t('tasks.filters.overdue'), clear: { overdue: false } })
  if (filters.assignee)
    items.push({
      key: 'assignee',
      label: t(`tasks.filters.assignees.${filters.assignee}`),
      clear: { assignee: null },
    })
  if (filters.completed_scope === 'all')
    items.push({
      key: 'completed_scope',
      label: t('tasks.filters.allHistory'),
      clear: { completed_scope: 'recent' },
    })
  return items
})
const resultLabel = computed(() =>
  props.pending || props.total === null
    ? t('tasks.filters.updating')
    : t('tasks.filters.results', { count: props.total }),
)
</script>

<template>
  <Popover v-if="placement === 'action'" v-model:open="open">
    <PopoverTrigger as-child>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        class="relative"
        :aria-label="count ? t('tasks.filters.actionCount', { count }) : t('tasks.filters.action')"
        data-testid="task-filters-action"
        ><SlidersHorizontal class="size-5" aria-hidden="true" />
        <span
          v-if="count"
          class="bg-primary text-primary-foreground absolute end-0.5 top-0.5 grid min-w-4 place-items-center rounded-full px-1 text-[0.625rem] font-semibold tabular-nums"
          aria-hidden="true"
          >{{ count }}</span
        >
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="gap-3 p-3" :aria-label="t('tasks.filters.action')">
      <h2 class="font-medium">{{ t('tasks.filters.action') }}</h2>
      <p class="text-muted-foreground text-xs">{{ t('tasks.filters.combine') }}</p>
      <div>
        <div class="flex min-h-11 items-center gap-3">
          <Checkbox
            :id="`${id}-important`"
            :model-value="filters.important"
            @update:model-value="emit('change', { important: $event === true })"
          />
          <Label :for="`${id}-important`" class="flex-1">{{ t('tasks.filters.important') }}</Label>
        </div>
        <div class="flex min-h-11 items-center gap-3">
          <Checkbox
            :id="`${id}-overdue`"
            :model-value="filters.overdue"
            @update:model-value="emit('change', { overdue: $event === true })"
          />
          <Label :for="`${id}-overdue`" class="flex-1">{{ t('tasks.filters.overdue') }}</Label>
        </div>
      </div>
      <fieldset v-if="shared" class="border-t pt-3">
        <legend class="text-muted-foreground mb-1 px-1 text-xs">
          {{ t('tasks.filters.assignee') }}
        </legend>
        <RadioGroup
          :model-value="filters.assignee ?? 'any'"
          class="gap-0"
          :aria-label="t('tasks.filters.assignee')"
          @update:model-value="chooseAssignee"
        >
          <div v-for="person in assignees" :key="person" class="flex min-h-11 items-center gap-3">
            <RadioGroupItem :id="`${id}-${person}`" :value="person" />
            <Label :for="`${id}-${person}`" class="flex-1">{{
              t(`tasks.filters.assignees.${person}`)
            }}</Label>
          </div>
        </RadioGroup>
      </fieldset>
      <div class="space-y-1 border-t pt-3">
        <p class="text-muted-foreground text-xs">
          {{
            filters.completed_scope === 'all'
              ? t('tasks.filters.completedAll')
              : t('tasks.filters.completedRecent')
          }}
        </p>
        <Button
          v-if="filters.completed_scope !== 'all'"
          type="button"
          variant="ghost"
          size="sm"
          class="w-full justify-start text-left whitespace-normal"
          data-testid="task-filters-whole-history"
          @click="wholeHistory"
          >{{ t('tasks.filters.searchAllHistory') }}</Button
        >
        <Button
          v-else
          type="button"
          variant="ghost"
          size="sm"
          class="w-full justify-start text-left whitespace-normal"
          @click="emit('change', { completed_scope: 'recent' })"
          >{{ t('tasks.filters.recentOnly') }}</Button
        >
      </div>
      <Button v-if="count" type="button" variant="outline" size="sm" @click="reset">{{
        t('tasks.filters.reset')
      }}</Button>
    </PopoverContent>
  </Popover>
  <section
    v-else-if="count"
    class="min-w-0 space-y-1"
    :aria-label="t('tasks.filters.active')"
    data-testid="task-filters-summary"
  >
    <div class="flex flex-wrap items-center gap-1.5">
      <Button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        variant="secondary"
        size="xs"
        class="max-w-full rounded-full"
        :aria-label="t('tasks.filters.remove', { filter: chip.label })"
        :data-task-filter="chip.key"
        @click="emit('change', chip.clear)"
        ><span class="max-w-60 truncate">{{ chip.label }}</span
        ><X class="size-3" aria-hidden="true"
      /></Button>
      <Button type="button" variant="ghost" size="xs" @click="reset">{{
        t('tasks.filters.reset')
      }}</Button>
    </div>
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <p class="text-muted-foreground text-xs" role="status" aria-live="polite" aria-atomic="true">
        {{ resultLabel }}
        <span v-if="projectName">
          · {{ t('tasks.filters.projectScope', { project: projectName }) }}</span
        >
      </p>
      <Button
        v-if="narrowed && filters.completed_scope === 'recent'"
        type="button"
        variant="link"
        size="xs"
        class="px-0 text-left whitespace-normal"
        data-testid="task-filters-whole-history"
        @click="wholeHistory"
        >{{ t('tasks.filters.searchAllHistory') }}</Button
      >
    </div>
    <p v-if="!pending && total === 0" class="text-muted-foreground text-sm">
      {{ projectName ? t('tasks.filters.noneInProject') : t('tasks.filters.none') }}
    </p>
  </section>
</template>
