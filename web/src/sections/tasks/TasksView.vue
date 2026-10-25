<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import {
  ArrowUp,
  CalendarClock,
  ChevronDown,
  Columns3,
  List,
  ListTodo,
  Plus,
  Search,
} from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import LinkedText from '@/components/LinkedText'
import { Checkbox } from '@/components/ui/checkbox'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { useQueryIntent } from '@/core/intent'
import { useMeasuredRoomFor } from '@/core/layout'
import { operationKind, useLedger } from '@/core/operations'
import { useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import { TASKS, previousSibling } from './api'
import { progressOf } from './places'
import ProjectHeader from './ProjectHeader.vue'
import ProjectPanel from './ProjectPanel.vue'
import TaskBoard from './TaskBoard.vue'
import TaskCover from './TaskCover.vue'
import TaskFilters from './TaskFilters.vue'
import TaskDiscussionBadge from './TaskDiscussionBadge.vue'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import TaskListMenu from './TaskListMenu.vue'
import TaskPanel from './TaskPanel.vue'
import TaskPlaces from './TaskPlaces.vue'
import { applyTaskFilterParams } from './taskFilters'
import { useTaskFilters } from './useTaskFilters'
import { useTaskListActions } from './useTaskListActions'
import { TONES, useTaskListPages } from './useTaskListPages'
import { otherViews, useTaskPlace } from './useTaskPlace'
import { useTaskSearch } from './useTaskSearch'
import { useTaskSelection } from './useTaskSelection'

const { t } = useI18n()
const router = useRouter()
const session = useSession()
const ledger = useLedger(TASKS)
const lists = useTaskLists()
const place = useTaskPlace(lists)
const {
  listId,
  currentList,
  listsPending,
  isProject,
  view,
  sort,
  board,
  viewTo,
  quickViews,
  otherView,
  showView,
  setLayout,
  order,
  sorts,
} = place
const filtering = useTaskFilters(() => (board.value ? 'board' : 'list'))
const filters = filtering.filters
const filterParams = computed(() => {
  const params = new URLSearchParams()
  applyTaskFilterParams(params, filters.value)
  return Object.fromEntries(params)
})
// Lists and projects are chips above the tasks on a phone; wide screens list them in the sidebar.
const wide = useMediaQuery('(min-width: 768px)')
// A docked board retains two comfortable columns, a gap and a 24rem card. The threshold is
// measured in rem against the available page area, including the page's horizontal padding.
const area = ref<HTMLElement | null>(null)
const { roomy, measured } = useMeasuredRoomFor(area, 56)
const { roomy: boardRoomy, measured: boardMeasured } = useMeasuredRoomFor(area, 68.25)
const docked = computed(() => (board.value ? boardRoomy.value : roomy.value))
// A retry after a first failure can become pending again without data. Keep the panel mounted.
const listsSettled = ref(false)
watch(
  lists.isPending,
  (pending) => {
    if (!pending) listsSettled.value = true
  },
  { immediate: true, flush: 'sync' },
)
// A deep link must wait for its list's layout and the first measured width before mounting.
const panelLayoutReady = computed(
  () =>
    (!listsPending.value || listsSettled.value) &&
    (board.value ? boardMeasured.value : measured.value),
)
const boardPanel = useTemplateRef<InstanceType<typeof TaskBoard>>('boardPanel')
const quickAddField = useTemplateRef<{ $el: HTMLInputElement }>('quickAddField')
const canWrite = computed(
  () =>
    session.can('tasks.write') &&
    (!listId.value || ['edit', 'manage'].includes(currentList.value?.effective ?? '')),
)
// New tasks go to the list in view; the board adds them in its columns.
const quickAdd = computed(
  () => canWrite.value && !board.value && view.value !== 'trash' && view.value !== 'done',
)
const { search, query, searchOpen, toggleSearch } = useTaskSearch()
const { boardResults, list, tasks, resultTotal, resultsPending, hidden, today, more, meta } =
  useTaskListPages(place, filtering, lists)
const {
  taskId,
  detail,
  panelOpen,
  taskTab,
  setTaskTab,
  selected,
  neighbours,
  selectedCanWrite,
  select,
  openDiscussion,
  taskDenied,
} = useTaskSelection({ board, boardPanel, list, tasks, lists })
const split = computed(() => docked.value && panelOpen.value && !!selected.value)
const width = computed(() => (board.value ? '7xl' : split.value ? '6xl' : '3xl'))
const WIDTHS = { '3xl': 'max-w-3xl', '6xl': 'max-w-6xl', '7xl': 'max-w-7xl' } as const
const { title, adding, add, actions, writable, toggle, moveUp } = useTaskListActions({
  quickAddField,
  ledger,
  place,
  filtering,
  lists,
  today,
})
const projectOpen = ref(false)
const newProject = ref(false)
function editProject(create: boolean) {
  newProject.value = create
  projectOpen.value = true
}
// «+ Проект» in the chips and in the sidebar.
useQueryIntent('project', () => editProject(true))
// Settings turn a list into a project; one's own «Личные задачи» stays a list.
const canManage = computed(
  () => currentList.value?.effective === 'manage' && !currentList.value.personal_user_id,
)
function createdProject(id: string) {
  void router.replace({ name: 'tasks', query: { list: id } })
}
const describe = (entry: { body: string }) =>
  t('tasks.unresolved', { kind: operationKind(entry as never) })
</script>

<template>
  <PageHeader :title="t('tasks.title')" :width="width">
    <template #actions>
      <TaskFilters
        :filters="filters"
        :shared="isProject && !!currentList?.space_id"
        @change="filtering.change"
        @reset="filtering.reset"
        @whole-history="filtering.wholeHistory"
      />
      <HeaderAction
        :icon="Search"
        :label="t('tasks.search')"
        :aria-expanded="searchOpen"
        aria-controls="task-search"
        @click="toggleSearch"
      />
    </template>
  </PageHeader>
  <div ref="area">
    <div :class="['mx-auto px-4 pb-8 md:px-8', WIDTHS[width]]">
      <div :class="split ? 'grid grid-cols-[minmax(0,1fr)_24rem] items-start gap-6' : undefined">
        <div :class="['min-w-0', board ? 'space-y-1 pb-14 md:space-y-4 md:pb-0' : 'space-y-4']">
          <div v-if="searchOpen" class="relative">
            <Search class="text-muted-foreground absolute top-3 left-3.5 size-5" /><Input
              id="task-search"
              v-model="search"
              type="search"
              maxlength="200"
              class="pl-11"
              :placeholder="t('tasks.search')"
              :aria-label="t('tasks.search')"
            />
          </div>
          <!-- On a phone the list's layout switch and «…» stand at the end of the chips' row. -->
          <div v-if="!wide" class="flex items-center">
            <TaskPlaces variant="chips" class="me-0 min-w-0 flex-1 pe-1" />
            <template v-if="currentList">
              <Button
                v-if="isProject"
                variant="ghost"
                size="icon"
                :aria-label="t(board ? 'tasks.listView' : 'tasks.boardView')"
                :title="t(board ? 'tasks.listView' : 'tasks.boardView')"
                @click="setLayout(board ? 'list' : 'board')"
                ><List v-if="board" class="size-5" /><Columns3 v-else class="size-5"
              /></Button>
              <TaskListMenu
                v-model:order="order"
                :sorts="sorts"
                :can-manage="canManage"
                @settings="editProject(false)"
              />
            </template>
          </div>
          <ProjectHeader v-if="currentList" :list="currentList" :board="board" @layout="setLayout">
            <template v-if="wide" #menu>
              <TaskListMenu
                v-model:order="order"
                :sorts="sorts"
                :can-manage="canManage"
                @settings="editProject(false)"
            /></template>
          </ProjectHeader>
          <div v-if="!board" class="flex flex-wrap items-center gap-2">
            <SegmentedControl
              :segments="quickViews"
              :label="t('tasks.viewLabel')"
              :model-value="view"
            />
            <DropdownMenu>
              <DropdownMenuTrigger as-child>
                <Button :variant="otherView ? 'secondary' : 'ghost'" class="h-11 rounded-xl"
                  >{{ otherView ? t(`tasks.views.${otherView}`) : t('tasks.moreViews')
                  }}<ChevronDown
                /></Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" class="min-w-56">
                <DropdownMenuRadioGroup :model-value="view" @update:model-value="showView">
                  <DropdownMenuRadioItem v-for="name in otherViews" :key="name" :value="name">{{
                    t(`tasks.views.${name}`)
                  }}</DropdownMenuRadioItem>
                </DropdownMenuRadioGroup>
                <!-- Across lists the order is here; a list keeps it in its own menu. -->
                <template v-if="!currentList">
                  <DropdownMenuSeparator />
                  <DropdownMenuLabel>{{ t('tasks.sort') }}</DropdownMenuLabel>
                  <DropdownMenuRadioGroup v-model="order">
                    <DropdownMenuRadioItem v-for="name in sorts" :key="name" :value="name">{{
                      t(`tasks.sorts.${name}`)
                    }}</DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                </template>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
          <ErrorState
            v-if="lists.isLoadingError.value"
            :error="lists.error.value"
            @retry="lists.refetch()"
          />
          <TaskFilters
            placement="summary"
            :filters="filters"
            :total="resultTotal"
            :pending="resultsPending"
            :project-name="currentList?.name"
            @change="filtering.change"
            @reset="filtering.reset"
            @whole-history="filtering.wholeHistory"
          />
          <UnresolvedWrites
            :entries="ledger.unresolved.value"
            :describe="describe"
            :check="ledger.check"
            :resend="ledger.resend"
            :abandon="ledger.abandon"
          />
          <LoadingState v-if="listsPending" />
          <TaskBoard
            v-else-if="board"
            ref="boardPanel"
            :list-id="listId"
            :sort="sort"
            :q="filters.q"
            :filters="filterParams"
            :today="today"
            :space-id="currentList?.space_id ?? null"
            :can-write="canWrite"
            :progress="currentList ? progressOf(currentList) : null"
            @select="select"
            @discussion="openDiscussion"
            @results="boardResults = $event"
          />
          <template v-else>
            <RefreshFailed :query="[list, lists]" />
            <ErrorState
              v-if="list.isLoadingError.value"
              :error="list.error.value"
              @retry="list.refetch()"
            />
            <template v-else>
              <!-- New tasks start at the top of the list itself, not in a form of their own. -->
              <div v-if="quickAdd || tasks.length" class="bg-card divide-y rounded-3xl border">
                <form
                  v-if="quickAdd"
                  class="focus-within:bg-accent/50 flex items-center gap-3 py-1 ps-4 pe-2 transition-colors first:rounded-t-3xl last:rounded-b-3xl"
                  @submit.prevent="add"
                >
                  <span class="text-muted-foreground grid size-6 shrink-0 place-items-center"
                    ><Plus class="size-5" aria-hidden="true"
                  /></span>
                  <Input
                    id="task-quick-add"
                    ref="quickAddField"
                    v-model="title"
                    maxlength="300"
                    class="rounded-none border-0 bg-transparent px-0 focus-visible:ring-0 dark:bg-transparent"
                    :placeholder="t('tasks.quickAdd')"
                    :aria-label="t('tasks.quickAdd')"
                  /><Button v-if="title.trim()" type="submit" size="sm" :disabled="adding">{{
                    t('tasks.add')
                  }}</Button>
                </form>
                <ul v-if="tasks.length" class="divide-y" data-testid="tasks">
                  <li v-for="task in tasks" :key="task.id" class="flex items-center gap-3 px-4">
                    <Checkbox
                      v-if="view !== 'trash' && writable(task)"
                      :model-value="task.status === 'done'"
                      :aria-label="task.status === 'done' ? t('tasks.reopen') : t('tasks.complete')"
                      class="size-6"
                      @update:model-value="toggle(task)"
                    />
                    <div class="relative min-w-0 flex-1 py-3 text-left">
                      <button
                        type="button"
                        class="focus-visible:ring-ring absolute inset-0 rounded focus-visible:ring-2 focus-visible:outline-none"
                        :aria-label="task.title"
                        @click="select(task)"
                      />
                      <LinkedText
                        :text="task.title"
                        :class="[
                          'pointer-events-none relative line-clamp-2 font-medium break-words',
                          task.status === 'done' && 'text-muted-foreground line-through',
                        ]"
                      /><span
                        v-if="meta(task).length"
                        class="text-muted-foreground pointer-events-none relative block text-sm"
                        ><template v-for="(part, index) in meta(task)" :key="index"
                          ><template v-if="index"> · </template
                          ><span :class="part.tone && TONES[part.tone]"
                            ><CalendarClock
                              v-if="part.tone === 'warning'"
                              class="me-0.5 inline size-3.5 align-[-0.125em]"
                              aria-hidden="true"
                            />{{ part.text }}</span
                          ></template
                        ></span
                      >
                    </div>
                    <TaskCover :task="task" />
                    <TaskDiscussionBadge
                      v-if="!task.deleted_at"
                      :discussion="task.discussion"
                      @open="openDiscussion(task)"
                    />
                    <Button
                      v-if="
                        listId &&
                        sort === 'manual' &&
                        !filtering.narrowed.value &&
                        previousSibling(tasks, task) &&
                        writable(task)
                      "
                      size="icon"
                      variant="ghost"
                      :aria-label="t('tasks.moveUp')"
                      @click="moveUp(task, previousSibling(tasks, task)!)"
                      ><ArrowUp
                    /></Button>
                  </li>
                </ul>
              </div>
              <LoadingState v-if="list.isPending.value" />
              <EmptyState
                v-else-if="!tasks.length && !filtering.count.value"
                :icon="ListTodo"
                :brand="!query && !hidden && view === 'today'"
                :title="query ? t('tasks.searchEmpty', { q: query }) : t(`tasks.empty.${view}`)"
                :description="
                  query ? undefined : hidden ? t('tasks.hiddenByView') : t('tasks.emptyText')
                "
              >
                <Button v-if="hidden" variant="outline" as-child
                  ><RouterLink :to="viewTo('open')">{{ t('tasks.showOpen') }}</RouterLink></Button
                >
              </EmptyState>
              <div v-if="list.hasNextPage.value" class="flex justify-center pb-6">
                <Button variant="outline" :disabled="list.isFetchingNextPage.value" @click="more">{{
                  t('tasks.more')
                }}</Button>
              </div>
            </template>
          </template>
          <ErrorState
            v-if="taskId && detail.isLoadingError.value"
            :error="detail.error.value"
            @retry="detail.refetch()"
          />
        </div>
        <TaskPanel
          v-if="selected && panelLayoutReady"
          v-model:open="panelOpen"
          :task="selected"
          :today="today"
          :can-write="selectedCanWrite"
          :docked="docked"
          :previous="neighbours.previous"
          :next="neighbours.next"
          :initial-tab="taskTab"
          data-testid="task-panel"
          @select="select"
          @tab="setTaskTab"
          @denied="taskDenied"
        />
      </div>
    </div>
  </div>
  <ProjectPanel
    v-model:open="projectOpen"
    :project="newProject ? null : currentList"
    @created="createdProject"
  />
  <TaskCompletionDialog :completion="actions.completion" @inspect="select" />
</template>
