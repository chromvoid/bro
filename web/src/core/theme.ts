/** A per-device preference; without one the theme follows the system (pure CSS, no flash). */
import { ref, watchEffect } from 'vue'
import { useShell } from './shell'

export type ThemePreference = 'system' | 'light' | 'dark'

const KEY = 'assistant.web.theme'

function stored(): ThemePreference {
  try {
    const value = localStorage.getItem(KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

export const theme = ref<ThemePreference>(stored())

export function applyTheme(preference: ThemePreference): void {
  theme.value = preference
  const root = document.documentElement
  if (preference === 'system') delete root.dataset.theme
  else root.dataset.theme = preference
  try {
    if (preference === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, preference)
  } catch {
    // Private windows may refuse storage; the choice still applies to this page.
  }
}

/** Native bars and screens follow this page, including a saved system override. */
export function syncShellTheme(
  shell: Pick<ReturnType<typeof useShell>, 'capabilities' | 'request'> = useShell(),
  media: MediaQueryList = window.matchMedia('(prefers-color-scheme: dark)'),
): () => void {
  const systemDark = ref(media.matches)
  const changed = (event: MediaQueryListEvent) => (systemDark.value = event.matches)
  media.addEventListener('change', changed)
  const stop = watchEffect(() => {
    if (!shell.capabilities.value?.system_bars) return
    const dark = theme.value === 'dark' || (theme.value === 'system' && systemDark.value)
    // The choice also applies to the iOS shell's native recording controls.
    void shell.request('theme.set', { dark, preference: theme.value }).catch(() => undefined)
  })
  return () => {
    stop()
    media.removeEventListener('change', changed)
  }
}
