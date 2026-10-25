<script setup lang="ts">
import { Archive, Plus } from '@lucide/vue'
import { computed, nextTick, onMounted, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import ScrollStrip from '@/components/ScrollStrip.vue'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import { placesOf, type Place, type Scope } from './places'

/**
 * Lists and projects in view: a row of chips above the tasks on a phone, entries under «Дела»
 * in the sidebar on wide screens. «+ Проект» opens the new-project panel on the tasks screen.
 */
const props = defineProps<{ variant: 'chips' | 'sidebar' }>()
const { t } = useI18n()
const route = useRoute()
const session = useSession()
const lists = useTaskLists()
const chips = computed(() => props.variant === 'chips')
const grouped = computed(() =>
  placesOf(lists.data.value?.lists ?? [], (scope: Scope) =>
    t(chips.value ? `tasks.places.${scope}` : `tasks.scopes.${scope}`),
  ),
)
const current = computed(() => {
  if (route.name !== 'tasks' && route.name !== 'task-detail') return null
  if (typeof route.query.list === 'string') return route.query.list
  return ['assigned', 'personal'].includes(String(route.query.scope))
    ? String(route.query.scope)
    : 'all'
})
const to = (place: Place) => ({ name: 'tasks', query: place.query })
const create = computed(() => ({ name: 'tasks', query: { ...route.query, project: '1' } }))
const canCreate = computed(() => session.can('tasks.write'))
const projects = computed(() => grouped.value.places.filter((place) => place.kind === 'project'))
const others = computed(() => grouped.value.places.filter((place) => place.kind === 'list'))
const scopes = computed(() => grouped.value.places.filter((place) => place.kind === 'scope'))

// The chosen chip stays in view when the row scrolls, the page itself does not move.
const row = useTemplateRef<InstanceType<typeof ScrollStrip>>('row')
function reveal() {
  void nextTick(() => row.value?.reveal(row.value.element?.querySelector('[aria-current="page"]')))
}
onMounted(reveal)
watch([current, () => grouped.value.places.length], reveal)

const chip =
  'border-border bg-card text-foreground hover:bg-accent inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full border px-3.5 text-sm font-medium whitespace-nowrap transition-colors pointer-coarse:h-11 aria-[current=page]:border-primary/40 aria-[current=page]:bg-accent aria-[current=page]:text-accent-foreground'
const entry =
  'text-sidebar-foreground hover:bg-sidebar-accent flex min-h-9 items-center gap-2 rounded-lg px-3 py-1.5 text-sm transition-colors aria-[current=page]:bg-sidebar-accent aria-[current=page]:text-sidebar-accent-foreground aria-[current=page]:font-medium'
</script>

<template>
  <ScrollStrip
    v-if="chips"
    ref="row"
    as="nav"
    bleed
    :aria-label="t('tasks.places.label')"
    class="gap-2 pb-1"
    data-testid="task-places"
  >
    <RouterLink
      v-for="place in grouped.places"
      :key="place.key"
      :to="to(place)"
      :aria-current="current === place.key ? 'page' : undefined"
      :class="chip"
      >{{ place.name }}</RouterLink
    >
    <DropdownMenu v-if="grouped.finished.length">
      <DropdownMenuTrigger :class="chip"
        ><Archive class="size-4" />{{ t('tasks.places.finished') }}</DropdownMenuTrigger
      >
      <DropdownMenuContent align="end">
        <DropdownMenuItem v-for="place in grouped.finished" :key="place.key" as-child
          ><RouterLink :to="to(place)">{{ place.name }}</RouterLink></DropdownMenuItem
        >
      </DropdownMenuContent>
    </DropdownMenu>
    <RouterLink
      v-if="canCreate"
      :to="create"
      :class="cn(chip, 'text-primary border-dashed bg-transparent')"
      ><Plus class="size-4" />{{ t('tasks.places.newProject') }}</RouterLink
    >
  </ScrollStrip>
  <div
    v-else
    class="ms-5 flex flex-col gap-0.5 border-s ps-2"
    :aria-label="t('tasks.places.label')"
    role="group"
    data-testid="task-places"
  >
    <RouterLink
      v-for="place in scopes"
      :key="place.key"
      :to="to(place)"
      :aria-current="current === place.key ? 'page' : undefined"
      :class="entry"
      >{{ place.name }}</RouterLink
    >
    <p v-if="projects.length" class="text-muted-foreground px-3 pt-2 pb-0.5 text-xs font-medium">
      {{ t('tasks.places.projects') }}
    </p>
    <RouterLink
      v-for="place in projects"
      :key="place.key"
      :to="to(place)"
      :aria-current="current === place.key ? 'page' : undefined"
      :class="entry"
      ><span class="min-w-0 flex-1 truncate">{{ place.name }}</span
      ><template v-if="place.progress?.total"
        ><span class="text-muted-foreground text-xs tabular-nums" aria-hidden="true"
          >{{ place.progress.done }}/{{ place.progress.total }}</span
        ><span class="sr-only">{{ t('tasks.progress', place.progress) }}</span></template
      ></RouterLink
    >
    <p v-if="others.length" class="text-muted-foreground px-3 pt-2 pb-0.5 text-xs font-medium">
      {{ t('tasks.places.lists') }}
    </p>
    <RouterLink
      v-for="place in others"
      :key="place.key"
      :to="to(place)"
      :aria-current="current === place.key ? 'page' : undefined"
      :class="entry"
      ><span class="min-w-0 flex-1 truncate">{{ place.name }}</span></RouterLink
    >
    <details v-if="grouped.finished.length" class="group">
      <summary :class="cn(entry, 'text-muted-foreground cursor-pointer list-none')">
        <Archive class="size-4" />{{ t('tasks.places.finished') }} ·
        {{ grouped.finished.length }}
      </summary>
      <RouterLink
        v-for="place in grouped.finished"
        :key="place.key"
        :to="to(place)"
        :aria-current="current === place.key ? 'page' : undefined"
        :class="entry"
        ><span class="min-w-0 flex-1 truncate">{{ place.name }}</span></RouterLink
      >
    </details>
    <RouterLink v-if="canCreate" :to="create" :class="cn(entry, 'text-primary')"
      ><Plus class="size-4" />{{ t('tasks.newProject') }}</RouterLink
    >
  </div>
</template>
