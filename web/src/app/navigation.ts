import {
  AudioLines,
  Dumbbell,
  House,
  ListTodo,
  Route,
  MessageCircle,
  Settings,
  ShieldCheck,
  Timer,
  User,
  Users,
  Utensils,
} from '@lucide/vue'
import type { Component } from 'vue'

export interface NavItem {
  /** Route name. */
  name: string
  /** i18n key of the label. */
  label: string
  icon: Component
  /** Hidden without this scope; the server still enforces access. */
  scope?: string
  admin?: boolean
  /** Folded under «Ещё» in the sidebar: a section opened rarely. */
  more?: boolean
}

interface Viewer {
  scopes: string[]
  system_admin: boolean
}

/** The phone tab bar and the first sidebar group. */
export const PRIMARY: NavItem[] = [
  { name: 'today', label: 'nav.today', icon: House },
  { name: 'assistant', label: 'nav.assistant', icon: MessageCircle },
  { name: 'food', label: 'nav.food', icon: Utensils, scope: 'nutrition.read' },
  { name: 'tasks', label: 'nav.tasks', icon: ListTodo, scope: 'tasks.read' },
]

/** The profile menu on phones and the second sidebar group. */
export const SECONDARY: NavItem[] = [
  { name: 'timers', label: 'nav.timers', icon: Timer, scope: 'timers.read' },
  { name: 'recordings', label: 'nav.recordings', icon: AudioLines, scope: 'session.control' },
  { name: 'circle', label: 'nav.circle', icon: Users },
  { name: 'activity', label: 'nav.activity', icon: Dumbbell, scope: 'activity.read' },
  { name: 'personal', label: 'nav.personal', icon: User, scope: 'personal.read' },
  { name: 'places', label: 'nav.places', icon: Route, scope: 'places.read', more: true },
  { name: 'settings', label: 'nav.settings', icon: Settings },
  { name: 'admin', label: 'nav.admin', icon: ShieldCheck, admin: true, more: true },
]

/** The microphone in the middle of the phone tab bar (not a route: it dictates). */
export const VOICE_TAB = 'voice'

/**
 * The phone tab bar: Сегодня · Питание · [микрофон] · Дела · Ассистент. The microphone stays in
 * the middle when a section is not available to this person.
 */
export function tabBar(viewer: Viewer | null): Array<NavItem | typeof VOICE_TAB> {
  if (!viewer) return []
  const order = ['today', 'food', 'tasks', 'assistant']
  const tabs: Array<NavItem | typeof VOICE_TAB> = visibleItems(PRIMARY, viewer).sort(
    (a, b) => order.indexOf(a.name) - order.indexOf(b.name),
  )
  tabs.splice(Math.ceil(tabs.length / 2), 0, VOICE_TAB)
  return tabs
}

export function visibleItems(items: NavItem[], viewer: Viewer | null): NavItem[] {
  if (!viewer) return []
  return items.filter(
    (item) =>
      (!item.scope || viewer.scopes.includes(item.scope)) && (!item.admin || viewer.system_admin),
  )
}

/** A plugin screen in the navigation, named by the installation's own title. */
export interface PluginLink {
  id: string
  title: string
  icon: Component
}
