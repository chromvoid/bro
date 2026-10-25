<script setup lang="ts">
import { Blocks, ChevronDown, Ellipsis, Mic } from '@lucide/vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useElementSize } from '@vueuse/core'
import {
  computed,
  onMounted,
  onScopeDispose,
  provide,
  ref,
  useTemplateRef,
  watch,
  watchEffect,
} from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useCalls } from '@/calls/useCalls'
import BrandMark from '@/components/BrandMark.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { usePlugins } from '@/plugins/store'
import { chunks, opening } from '@/router'
import { useCircleAttention } from '@/sections/circle/useCircle'
import TaskPlaces from '@/sections/tasks/TaskPlaces.vue'
import { useNotificationToasts } from '@/sections/notifications/useNotificationToasts'
import { useSession } from '@/stores/session'
import CallOverlay from './CallOverlay.vue'
import { tabBarHidden, watchKeyboard } from './composing'
import LogoutDialog from './LogoutDialog.vue'
import {
  PRIMARY,
  SECONDARY,
  VOICE_TAB,
  tabBar,
  visibleItems,
  type NavItem,
  type PluginLink,
} from './navigation'
import { prefetchSections } from './prefetch'
import ProfileMenu from './ProfileMenu.vue'
import StatusLine from './status/StatusLine.vue'
import StatusSheet from './status/StatusSheet.vue'
import { useShellStatus } from './status/useShellStatus'
import { useLocationReporter } from '@/location/useLocationReporter'
import { VOICE, composerDictation } from './voice'
import VoiceSheet from './VoiceSheet.vue'

const { t } = useI18n()
const route = useRoute()
// A tapped section lights up at once, while its screen may still be loading.
const shown = computed(() => opening.value ?? route)
const session = useSession()
// What the assistant says on its own pops up wherever the person is in the app.
useNotificationToasts()
// A call rings, runs and ends over every screen; moving around the app never drops it.
const call = useCalls()
// Where this device is, while the app is on screen and the permission is granted.
useLocationReporter()
// A call elsewhere, background recording, the connection, an app update, speech: one line.
const status = useShellStatus(call)
const primary = computed(() => visibleItems(PRIMARY, session.me))
const secondary = computed(() => visibleItems(SECONDARY, session.me))
// The sidebar keeps rare sections under «Ещё», so plugins stay in view on a laptop screen; the
// section on screen stays visible, so «Ещё» opens on its own when it holds it.
const sidebar = computed(() => [...primary.value, ...secondary.value.filter((item) => !item.more)])
const sidebarMore = computed(() => secondary.value.filter((item) => item.more))
const moreOpen = ref(false)
watchEffect(() => {
  if (sidebarMore.value.some(active)) moreOpen.value = true
})
const sidebarLink = (on: boolean) =>
  cn(
    'hover:bg-sidebar-accent flex h-11 items-center gap-3 rounded-xl px-3 text-[0.9375rem] transition-colors',
    on && 'bg-sidebar-accent text-sidebar-accent-foreground font-medium',
  )
const tabs = computed(() => tabBar(session.me))
// Unread messages and invitations in Circle: a count by its name, a dot on the avatar.
const circle = useCircleAttention()
const columns = computed(
  () =>
    ({ 2: 'grid-cols-2', 3: 'grid-cols-3', 4: 'grid-cols-4', 5: 'grid-cols-5' })[
      tabs.value.length
    ] ?? 'grid-cols-5',
)

// One tap dictates from anywhere: the assistant's composer records in place while it is open
// (a composer still loading its draft ignores the tap: a sheet writing the draft behind its
// back would be overwritten); every other screen uses the voice sheet, which hands the text to
// the composer's saved draft.
const voiceSheet = useTemplateRef<InstanceType<typeof VoiceSheet>>('voiceSheet')
function dictate() {
  if (route.name === 'assistant') return void composerDictation()?.()
  voiceSheet.value?.dictate()
}
provide(VOICE, { dictate })

// The tab bar steps aside while the keyboard is up over the composer (composing.ts); an
// immersive screen takes its measured height, so large text or a hidden bar leaves no gap.
watchKeyboard()
const tabBarElement = useTemplateRef<HTMLElement>('tabBarElement')
const { height: tabBarHeight } = useElementSize(tabBarElement, undefined, { box: 'border-box' })

// What the frame keeps over the page, for sticky content, docked panels, floating buttons and
// scroll padding (styles.css): the top region and the tab bar as measured. Before the first
// measurement, and while a part is hidden, the bare system bars stand in.
const topRegion = useTemplateRef<HTMLElement>('topRegion')
const { height: topHeight } = useElementSize(topRegion, undefined, { box: 'border-box' })
const root = document.documentElement.style
watchEffect(() => {
  if (topHeight.value > 0) root.setProperty('--app-top', `${topHeight.value}px`)
  else root.removeProperty('--app-top')
  if (tabBarHeight.value > 0) root.setProperty('--app-bottom', `${tabBarHeight.value}px`)
  else root.removeProperty('--app-bottom')
})
onScopeDispose(() => {
  root.removeProperty('--app-top')
  root.removeProperty('--app-bottom')
})

// Plugin screens the catalog offers this account follow the built-in sections.
const plugins = usePlugins()
watch(
  () => session.account,
  (account) => account && void plugins.load(),
  { immediate: true },
)
const pluginLinks = computed<PluginLink[]>(() =>
  plugins.entries.map((entry) => ({
    id: entry.installation.installation_id,
    title: entry.installation.title,
    icon: entry.ui?.icon ?? Blocks,
  })),
)
const pluginActive = (link: PluginLink) =>
  shown.value.name === 'plugin' && shown.value.params.installation === link.id

// When the session ends mid-use, sign in again and come back to the same screen.
const router = useRouter()
watch(
  () => session.state,
  (state) => {
    if (state === 'signed-out')
      void router.replace({ name: 'login', query: { next: route.fullPath } })
  },
)

// The tab bar's screens load while the first one is in view, so on a slow connection a tab
// opens without waiting for its code.
const queryClient = useQueryClient()
onMounted(() => {
  const names = primary.value.map((item) => item.name)
  void prefetchSections(router, names, chunks.preload, queryClient)
})

/** A section stays highlighted on its nested screens. */
function active(item: NavItem): boolean {
  return shown.value.matched.some(
    (record) => record.name === item.name || record.meta.section === item.name,
  )
}
</script>

<template>
  <a
    href="#main"
    class="bg-primary text-primary-foreground sr-only z-50 rounded-lg px-4 py-2 focus:not-sr-only focus:fixed focus:top-[calc(var(--inset-top)+0.5rem)] focus:left-2"
    >{{ t('app.skip') }}</a
  >
  <!-- In the native shells the page runs edge to edge: the system bars lie over it. -->
  <div class="min-h-dvh md:grid md:grid-cols-[16rem_minmax(0,1fr)]">
    <aside
      class="bg-sidebar text-sidebar-foreground sticky top-0 hidden h-dvh flex-col gap-6 border-r px-3 pt-[calc(var(--inset-top)+1.25rem)] pb-5 md:flex"
    >
      <RouterLink :to="{ name: 'today' }" class="flex items-center gap-2.5 px-2">
        <BrandMark class="size-8" />
        <span class="text-base font-semibold">{{ t('app.title') }}</span>
      </RouterLink>
      <Button
        variant="outline"
        class="justify-start gap-3"
        data-testid="voice-sidebar"
        @click="dictate"
      >
        <Mic class="size-5" />{{ t('dictation.start') }}
      </Button>
      <nav
        :aria-label="t('nav.main')"
        class="-mx-1 flex min-h-0 flex-col gap-1 overflow-y-auto px-1 [scrollbar-width:thin] [&>*]:shrink-0"
      >
        <template v-for="item in sidebar" :key="item.name">
          <RouterLink
            :to="{ name: item.name }"
            :aria-current="active(item) ? 'page' : undefined"
            :class="sidebarLink(active(item))"
          >
            <component :is="item.icon" class="size-5" />
            {{ t(item.label) }}
            <span
              v-if="item.name === 'circle' && circle"
              class="bg-primary text-primary-foreground ms-auto min-w-6 rounded-full px-2 text-center text-xs font-semibold tabular-nums"
              data-testid="circle-count"
              ><span class="sr-only">{{ t('circle.waiting') }}: </span>{{ circle }}</span
            >
          </RouterLink>
          <!-- Inside «Дела» its lists and projects follow it. -->
          <TaskPlaces v-if="item.name === 'tasks' && active(item)" variant="sidebar" />
        </template>
        <template v-if="sidebarMore.length">
          <button
            type="button"
            :class="sidebarLink(false)"
            :aria-label="t('nav.moreSections')"
            :aria-expanded="moreOpen"
            aria-controls="sidebar-more"
            data-testid="sidebar-more"
            @click="moreOpen = !moreOpen"
          >
            <Ellipsis class="size-5" />{{ t('nav.more') }}
            <ChevronDown
              class="text-muted-foreground ms-auto size-4 transition-transform"
              :class="moreOpen && 'rotate-180'"
              aria-hidden="true"
            />
          </button>
          <div v-show="moreOpen" id="sidebar-more" class="flex flex-col gap-1">
            <RouterLink
              v-for="item in sidebarMore"
              :key="item.name"
              :to="{ name: item.name }"
              :aria-current="active(item) ? 'page' : undefined"
              :class="sidebarLink(active(item))"
            >
              <component :is="item.icon" class="size-5" />
              {{ t(item.label) }}
            </RouterLink>
          </div>
        </template>
        <template v-if="pluginLinks.length">
          <p class="text-muted-foreground mt-3 px-3 text-xs font-medium">
            {{ t('plugins.title') }}
          </p>
          <RouterLink
            v-for="link in pluginLinks"
            :key="link.id"
            :to="{ name: 'plugin', params: { installation: link.id } }"
            :aria-current="pluginActive(link) ? 'page' : undefined"
            :class="sidebarLink(pluginActive(link))"
          >
            <component :is="link.icon" class="size-5" />
            {{ link.title }}
          </RouterLink>
        </template>
      </nav>
      <div class="mt-auto">
        <ProfileMenu wide />
      </div>
    </aside>

    <!-- An immersive screen ends right above the tab bar, whatever its height; none on md+. -->
    <div
      class="flex min-w-0 flex-col"
      :style="route.meta.immersive ? { height: `calc(100dvh - ${tabBarHeight}px)` } : undefined"
    >
      <!-- Opaque under the status bar, so nothing scrolls beneath the clock. The status line is in
           the content flow, so it moves the header down instead of covering it; it stays on screen
           only during a call or a recording. -->
      <div
        ref="topRegion"
        class="bg-background sticky top-0 z-20 shrink-0 pt-(--inset-top)"
        data-testid="app-top"
      >
        <StatusLine v-if="status.sticky.value" :status="status" />
      </div>
      <StatusLine v-if="!status.sticky.value" :status="status" />
      <main
        id="main"
        :class="
          cn(
            // The parent's height includes the bars above so they cannot cover the composer.
            route.meta.immersive
              ? 'flex min-h-0 flex-1 flex-col'
              : 'flex-1 pb-[calc(var(--app-bottom)+1rem)] md:pb-8',
          )
        "
      >
        <ErrorState
          v-if="session.state === 'unavailable'"
          :title="t('connection.unavailableTitle')"
          @retry="session.load()"
        />
        <RouterView v-else />
      </main>
    </div>
  </div>

  <nav
    ref="tabBarElement"
    :aria-label="t('nav.main')"
    :class="
      cn(
        'bg-background/95 fixed inset-x-0 bottom-0 z-30 border-t pb-(--inset-bottom) backdrop-blur md:hidden',
        tabBarHidden && 'hidden',
      )
    "
    data-testid="tab-bar"
  >
    <ul :class="cn('grid', columns)">
      <li v-for="item in tabs" :key="item === VOICE_TAB ? item : item.name">
        <button
          v-if="item === VOICE_TAB"
          type="button"
          class="flex min-h-14 w-full items-center justify-center"
          :aria-label="t('dictation.start')"
          data-testid="voice-tab"
          @click="dictate"
        >
          <!-- Shrinks with its cell at large text sizes instead of overflowing the bar. -->
          <span
            class="bg-primary text-primary-foreground ring-background grid aspect-square w-12 max-w-[85%] place-items-center rounded-full shadow-md ring-4"
          >
            <Mic class="size-6" />
          </span>
        </button>
        <RouterLink
          v-else
          :to="{ name: item.name }"
          :aria-current="active(item) ? 'page' : undefined"
          :class="
            cn(
              'text-muted-foreground flex min-h-14 flex-col items-center justify-center gap-0.5 text-xs font-medium',
              active(item) && 'text-primary',
            )
          "
        >
          <span
            :class="
              cn(
                'grid h-7 w-12 place-items-center rounded-full transition-colors',
                active(item) && 'bg-accent',
              )
            "
          >
            <component :is="item.icon" class="size-5" />
          </span>
          <span class="max-w-full truncate px-1">{{ t(item.label) }}</span>
        </RouterLink>
      </li>
    </ul>
  </nav>
  <VoiceSheet ref="voiceSheet" />
  <StatusSheet :status="status" />
  <LogoutDialog />
  <CallOverlay :model="call" />
</template>
