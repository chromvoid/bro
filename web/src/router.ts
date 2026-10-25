import { shallowRef, type ShallowRef } from 'vue'
import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw,
  type Router,
} from 'vue-router'
import { reloadOnStaleChunks } from './core/update'
import { useSession } from './stores/session'
import { schedulePersonalScroll } from './sections/personal/personalNavigation'
import { scheduleActivityScroll } from './sections/activity/activityScroll'
import { dayIn } from './core/dates'

declare module 'vue-router' {
  interface RouteMeta {
    /** i18n key of the screen title. */
    title?: string
    /** Reachable without a session. */
    public?: boolean
    admin?: boolean
    scope?: string
    /** Navigation item that stays highlighted on nested screens. */
    section?: string
    /** Takes the full height; the screen scrolls its own content (conversations). */
    immersive?: boolean
  }
}

export const sections: RouteRecordRaw[] = [
  // Dedicated-origin installations still accept /; shared hosts keep their own root dashboard.
  { path: '', redirect: { name: 'today' } },
  {
    path: 'today',
    name: 'today',
    component: () => import('./sections/today/TodayView.vue'),
    meta: { title: 'nav.today' },
  },
  {
    path: 'recordings',
    name: 'recordings',
    component: () => import('./sections/recordings/RecordingsView.vue'),
    meta: { title: 'nav.recordings', scope: 'session.control' },
  },
  {
    path: 'assistant',
    name: 'assistant',
    component: () => import('./sections/assistant/AssistantView.vue'),
    meta: { title: 'nav.assistant', immersive: true },
  },
  {
    path: 'food',
    name: 'food',
    component: () => import('./sections/food/FoodView.vue'),
    meta: { title: 'nav.food', scope: 'nutrition.read' },
  },
  {
    path: 'food/stats',
    name: 'food-stats',
    component: () => import('./sections/food/StatsView.vue'),
    meta: { title: 'stats.title', section: 'food', scope: 'nutrition.read' },
  },
  {
    path: 'tasks',
    name: 'tasks',
    component: () => import('./sections/tasks/TasksView.vue'),
    meta: { title: 'nav.tasks', scope: 'tasks.read' },
  },
  {
    path: 'tasks/:task',
    name: 'task-detail',
    component: () => import('./sections/tasks/TasksView.vue'),
    meta: { title: 'nav.tasks', scope: 'tasks.read', section: 'tasks' },
  },
  {
    path: 'timers',
    name: 'timers',
    component: () => import('./sections/tasks/TimersView.vue'),
    meta: { title: 'tasks.timersTitle', scope: 'timers.read' },
  },
  {
    path: 'notifications',
    name: 'notifications',
    component: () => import('./sections/notifications/NotificationsView.vue'),
    meta: { title: 'notifications.title' },
  },
  {
    path: 'circle',
    name: 'circle',
    component: () => import('./sections/circle/CircleView.vue'),
    meta: { title: 'nav.circle' },
  },
  {
    path: 'circle/friends',
    name: 'circle-friends',
    component: () => import('./sections/circle/FriendsView.vue'),
    meta: { title: 'circle.friends', section: 'circle' },
  },
  {
    path: 'circle/shared',
    name: 'circle-shared',
    component: () => import('./sections/circle/SharedView.vue'),
    meta: { title: 'shared.title', section: 'circle' },
  },
  {
    path: 'circle/chat/:channel',
    name: 'circle-chat',
    component: () => import('./sections/circle/ChatView.vue'),
    meta: { title: 'circle.chat', section: 'circle', immersive: true },
  },
  {
    path: 'circle/spaces/:space',
    name: 'circle-space',
    component: () => import('./sections/circle/SpaceView.vue'),
    meta: { title: 'circle.space', section: 'circle' },
  },
  {
    path: 'circle/spaces/:space/members',
    name: 'circle-members',
    component: () => import('./sections/circle/MembersView.vue'),
    meta: { title: 'circle.members', section: 'circle' },
  },
  {
    path: 'circle/spaces/:space/challenges',
    name: 'circle-challenges',
    component: () => import('./sections/circle/ChallengesView.vue'),
    meta: { title: 'circle.challenges', section: 'circle', scope: 'challenges.read' },
  },
  {
    path: 'circle/lists/:list',
    name: 'circle-list',
    component: () => import('./sections/circle/ListView.vue'),
    meta: { title: 'circle.list', section: 'circle', scope: 'tasks.read' },
  },
  {
    path: 'circle/challenges/:challenge',
    name: 'circle-challenge',
    component: () => import('./sections/circle/ChallengeView.vue'),
    meta: { title: 'circle.challenge', section: 'circle', scope: 'challenges.read' },
  },
  {
    path: 'activity',
    name: 'activity',
    component: () => import('./sections/activity/ActivityView.vue'),
    meta: { title: 'nav.activity', scope: 'activity.read' },
  },
  {
    path: 'activity/:session',
    name: 'activity-session',
    component: () => import('./sections/activity/SessionView.vue'),
    meta: { title: 'nav.activity', scope: 'activity.read', section: 'activity' },
  },
  {
    path: 'places',
    name: 'places',
    component: () => import('./sections/places/PlacesView.vue'),
    meta: { title: 'nav.places', scope: 'places.read' },
  },
  {
    path: 'places/:date',
    name: 'places-day',
    component: () => import('./sections/places/PlaceDayView.vue'),
    meta: { title: 'nav.places', scope: 'places.read', section: 'places' },
  },
  {
    path: 'personal',
    name: 'personal',
    component: () => import('./sections/personal/PersonalView.vue'),
    meta: { title: 'nav.personal', scope: 'personal.read' },
  },
  {
    path: 'personal/profile',
    name: 'personal-profile',
    component: () => import('./sections/personal/ProfileView.vue'),
    meta: { title: 'personal.bodyProfileTitle', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'personal/history',
    name: 'personal-history',
    component: () => import('./sections/personal/HistoryView.vue'),
    meta: { title: 'personal.historyTitle', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'personal/sources',
    name: 'personal-sources',
    component: () => import('./sections/personal/SourcesView.vue'),
    meta: { title: 'sources.title', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'personal/sources/:source',
    name: 'personal-source',
    component: () => import('./sections/personal/SourceView.vue'),
    meta: { title: 'sources.title', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'personal/sources/:source/:row',
    name: 'personal-source-data',
    component: () => import('./sections/personal/SourceDataView.vue'),
    meta: { title: 'sources.title', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'personal/goals',
    name: 'personal-goals',
    component: () => import('./sections/personal/GoalsView.vue'),
    meta: { title: 'sources.goals', scope: 'personal.read', section: 'personal' },
  },
  {
    path: 'settings',
    name: 'settings',
    component: () => import('./sections/settings/SettingsView.vue'),
    meta: { title: 'nav.settings' },
  },
  {
    path: 'settings/speech',
    name: 'settings-speech',
    component: () => import('./sections/settings/SpeechView.vue'),
    meta: { title: 'speechSettings.title', section: 'settings' },
  },
  {
    path: 'settings/sharing',
    name: 'settings-sharing',
    component: () => import('./sections/settings/SharingView.vue'),
    meta: { title: 'sharing.title', section: 'settings' },
  },
  // My data's access is part of «Доступ и обмен»; what others opened to me lives in Circle.
  // A notification links to one message of the chat (a path: notification links carry no query).
  {
    path: 'assistant/m/:message',
    redirect: (to) => ({ name: 'assistant', query: { message: String(to.params.message) } }),
  },
  { path: 'settings/access', redirect: (to) => ({ name: 'settings-sharing', query: to.query }) },
  { path: 'settings/shared', redirect: (to) => ({ name: 'circle-shared', query: to.query }) },
  {
    path: 'settings/devices',
    name: 'settings-devices',
    component: () => import('./sections/devices/DevicesView.vue'),
    meta: { title: 'devices.title', section: 'settings' },
  },
  {
    path: 'settings/devices/:device',
    name: 'settings-display',
    component: () => import('./sections/devices/DisplayView.vue'),
    meta: { title: 'display.title', section: 'settings' },
  },
  {
    path: 'settings/notifications',
    name: 'settings-notifications',
    component: () => import('./sections/notifications/NotificationSettingsView.vue'),
    meta: { title: 'notificationSettings.title', section: 'settings' },
  },
  {
    path: 'settings/location',
    name: 'settings-location',
    component: () => import('./sections/settings/LocationSettingsView.vue'),
    meta: { title: 'locationSettings.title', section: 'settings' },
  },
  {
    path: 'settings/capabilities',
    name: 'settings-capabilities',
    component: () => import('./sections/settings/CapabilitiesView.vue'),
    meta: { title: 'capabilities.title', section: 'settings' },
  },
  {
    path: 'admin',
    name: 'admin',
    component: () => import('./sections/admin/AdminView.vue'),
    meta: { title: 'nav.admin', admin: true },
  },
  {
    path: 'admin/moderation',
    name: 'admin-moderation',
    component: () => import('./sections/admin/ModerationView.vue'),
    meta: { title: 'moderation.title', admin: true, section: 'admin' },
  },
  {
    path: 'admin/speech',
    name: 'admin-speech',
    component: () => import('./sections/admin/SpeechView.vue'),
    meta: { title: 'speechSettings.adminTitle', admin: true, section: 'admin' },
  },
  {
    path: 'admin/chat',
    name: 'admin-chat',
    component: () => import('./sections/admin/ChatSettingsView.vue'),
    meta: { title: 'chatSettings.title', admin: true, section: 'admin' },
  },
  {
    path: 'admin/users',
    name: 'admin-users',
    component: () => import('./sections/admin/UsersView.vue'),
    meta: { title: 'admin.users', admin: true, section: 'admin' },
  },
  {
    path: 'admin/research-sources',
    name: 'admin-research-sources',
    component: () => import('./sections/admin/ResearchSourcesView.vue'),
    meta: { title: 'researchSources.title', admin: true, section: 'admin' },
  },
  {
    path: 'admin/catalog',
    name: 'admin-food-catalog',
    component: () => import('./sections/admin/FoodCatalogView.vue'),
    meta: { title: 'foodCatalog.title', admin: true, section: 'admin' },
  },
  {
    path: 'accounts',
    name: 'accounts',
    component: () => import('./sections/admin/AccountsView.vue'),
    meta: { title: 'nav.accounts', admin: true, section: 'admin' },
  },
  {
    path: 'workers',
    name: 'workers',
    component: () => import('./sections/admin/WorkersView.vue'),
    meta: { title: 'nav.workers', admin: true, section: 'admin' },
  },
  {
    path: 'workers/runs/:run',
    name: 'workers-run',
    component: () => import('./sections/admin/RunView.vue'),
    meta: { title: 'admin.run', admin: true, section: 'admin' },
  },
  {
    path: 'plugins/:installation/:screen?',
    name: 'plugin',
    component: () => import('./plugins/PluginView.vue'),
    meta: { title: 'plugins.title', scope: 'session.control' },
  },
  { path: 'welcome', name: 'welcome', component: () => import('./sections/auth/WelcomeView.vue') },
  {
    path: 'no-access',
    name: 'no-access',
    component: () => import('./sections/system/NoAccessView.vue'),
  },
  // Addresses of the old pages lead to their new screens.
  { path: 'chat', redirect: { name: 'assistant' } },
  { path: 'community', redirect: { name: 'circle' } },
  { path: 'modules', redirect: { name: 'settings-capabilities' } },
  { path: ':rest(.*)*', name: 'not-found', component: () => import('./views/NotFoundView.vue') },
]

/** Opening, changing or closing a card leaves its task workspace at the same scroll position. */
function sameTaskWorkspace(to: RouteLocationNormalized, from: RouteLocationNormalized) {
  if (to.name !== 'tasks' || from.name !== 'tasks' || to.path !== from.path) return false
  const workspace = (route: RouteLocationNormalized) =>
    JSON.stringify(
      Object.entries(route.query)
        .filter(([key]) => key !== 'task' && key !== 'task_tab')
        .sort(([left], [right]) => left.localeCompare(right)),
    )
  return workspace(to) === workspace(from)
}

export const router = createRouter({
  history: createWebHistory('/'),
  scrollBehavior: (to, from, saved) =>
    scheduleActivityScroll(
      to,
      from,
      to.name === 'activity' ? dayIn(useSession().me?.timezone ?? 'UTC') : undefined,
    ) ||
    schedulePersonalScroll(
      to,
      from,
      to.name === 'personal' ? dayIn(useSession().me?.timezone ?? 'UTC') : undefined,
      !!saved,
    )
      ? false
      : (saved ??
        (to.hash && to.name !== 'join'
          ? { el: to.hash }
          : sameTaskWorkspace(to, from)
            ? false
            : { top: 0 })),
  routes: [
    {
      path: '/account-deletion',
      name: 'account-deletion',
      component: () => import('./sections/settings/AccountDeletionView.vue'),
      meta: { public: true, title: 'accountDeletion.title' },
    },
    {
      path: '/join',
      name: 'join',
      component: () => import('./sections/auth/JoinView.vue'),
      meta: { public: true, title: 'auth.joinTitle' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('./sections/auth/LoginView.vue'),
      meta: { public: true, title: 'auth.title' },
    },
    {
      path: '/_ui',
      name: 'gallery',
      component: () => import('./sections/gallery/GalleryView.vue'),
      meta: { public: true },
    },
    { path: '/', component: () => import('./app/AppShell.vue'), children: sections },
  ],
})

/**
 * The screen being opened while its guards run and its code loads; null once it is shown or the
 * navigation is given up. On a slow connection its code can take seconds to arrive.
 */
export function trackOpening(router: Router): Readonly<ShallowRef<RouteLocationNormalized | null>> {
  const opening = shallowRef<RouteLocationNormalized | null>(null)
  const settle = (to: RouteLocationNormalized) => {
    if (opening.value === to) opening.value = null
  }
  router.beforeEach((to) => {
    opening.value = to
  })
  router.afterEach(settle)
  router.onError((error, to) => {
    settle(to)
    // A handler takes over the router's own report of the error.
    console.error(error)
  })
  return opening
}

export const opening = trackOpening(router)

/** A screen whose code an older build no longer finds opens with a full load of the new one. */
export const chunks = reloadOnStaleChunks(window, () => {
  const target = opening.value
  return target && router.resolve(target).href
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const session = useSession()
  await session.ready()
  if (session.state === 'signed-out') return { name: 'login', query: { next: to.fullPath } }
  if (session.state !== 'ready') return true
  if (to.meta.admin && !session.me?.system_admin) return { name: 'no-access' }
  if (to.meta.scope && !session.can(to.meta.scope)) return { name: 'no-access' }
  return true
})
