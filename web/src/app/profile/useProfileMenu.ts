import { Monitor, Moon, Sun } from '@lucide/vue'
import { computed } from 'vue'
import { usePlugins } from '@/plugins/store'
import { useCircleAttention } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import type { ThemePreference } from '@/core/theme'
import { SECONDARY, visibleItems } from '../navigation'
import { useLogout } from '../useLogout'

export const THEMES: Array<{ value: ThemePreference; icon: typeof Sun; label: string }> = [
  { value: 'system', icon: Monitor, label: 'theme.system' },
  { value: 'light', icon: Sun, label: 'theme.light' },
  { value: 'dark', icon: Moon, label: 'theme.dark' },
]

/** What the profile offers: the person, the sections beyond the tab bar, sign-out. */
export function useProfileMenu() {
  const session = useSession()
  const plugins = usePlugins()
  const { askLogout } = useLogout()
  const circle = useCircleAttention()
  const initials = computed(() =>
    (session.me?.name ?? '?')
      .split(/\s+/)
      .map((part) => part[0] ?? '')
      .join('')
      .slice(0, 2)
      .toUpperCase(),
  )
  const links = computed(() => visibleItems(SECONDARY, session.me))
  return { session, plugins, circle, initials, links, askLogout }
}
