<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { Progress } from '@/components/ui/progress'
import type { TaskList } from '@/sections/circle/api'
import { useMembers } from '@/sections/circle/useCircle'
import { progressOf } from './places'

/**
 * The chosen list or project above its tasks: the name, the outcome it is for and, for a project,
 * how far it is, who shares it and whether it is shown as the board or the list. On a phone the
 * chip above already names it and the layout switch and «…» stand beside the chips: the name stays
 * for screen readers, the board shows the progress under «Готово», the list in a row of its own.
 */
const props = defineProps<{ list: TaskList; board: boolean }>()
const emit = defineEmits<{ layout: [layout: 'board' | 'list'] }>()
const { t } = useI18n()
const project = computed(() => props.list.list_kind === 'project')
const progress = computed(() => progressOf(props.list))
const members = useMembers(computed(() => props.list.space_id ?? null))
const people = computed(() =>
  (members.data.value?.members ?? []).slice(0, 4).map((member) => ({
    id: member.user_id,
    name: member.name,
    initials: member.name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((word) => word[0]!.toUpperCase())
      .join(''),
  })),
)
const layouts = computed(() => [
  { value: 'board', label: t('tasks.boardView') },
  { value: 'list', label: t('tasks.listView') },
])
function choose(value: string) {
  if (value === 'board' || value === 'list') emit('layout', value)
}
</script>

<template>
  <div class="space-y-1 md:space-y-2" data-testid="project-header">
    <div class="flex items-center gap-3 md:items-start">
      <h2
        class="min-w-0 flex-1 self-center text-xl leading-tight font-semibold break-words max-md:sr-only"
      >
        {{ list.name }}
      </h2>
      <div
        v-if="project && progress.total && !board"
        class="text-muted-foreground flex min-w-0 flex-1 items-center gap-3 text-sm tabular-nums md:hidden"
      >
        <Progress
          :model-value="Math.round((progress.done / progress.total) * 100)"
          class="h-1.5 flex-1"
          :aria-label="t('tasks.projectProgress', progress)"
        />
        <span class="shrink-0" aria-hidden="true">{{
          t('tasks.projectProgressShort', progress)
        }}</span>
      </div>
      <ul v-if="people.length" class="hidden gap-1 pt-1 md:flex" :aria-label="t('tasks.members')">
        <li
          v-for="person in people"
          :key="person.id"
          class="bg-accent text-accent-foreground grid size-7 place-items-center rounded-full text-[0.6875rem] font-semibold"
          :title="person.name"
        >
          <span aria-hidden="true">{{ person.initials }}</span
          ><span class="sr-only">{{ person.name }}</span>
        </li>
      </ul>
      <div v-if="$slots.menu" class="ms-auto flex shrink-0 items-center">
        <slot name="menu" />
      </div>
    </div>
    <p v-if="list.outcome" class="text-muted-foreground">{{ list.outcome }}</p>
    <div v-if="project" class="hidden flex-wrap items-center gap-x-4 gap-y-2 md:flex">
      <div
        v-if="progress.total"
        class="text-muted-foreground flex min-w-0 flex-[1_1_10rem] items-center gap-3 text-sm tabular-nums"
      >
        <Progress
          :model-value="Math.round((progress.done / progress.total) * 100)"
          class="h-1.5 max-w-60 flex-1"
          :aria-label="t('tasks.projectProgress', progress)"
        />
        <span class="shrink-0">{{ t('tasks.projectProgress', progress) }}</span>
      </div>
      <SegmentedControl
        :segments="layouts"
        :label="t('tasks.layoutLabel')"
        :model-value="board ? 'board' : 'list'"
        class="ms-auto"
        @update:model-value="choose"
      />
    </div>
  </div>
</template>
