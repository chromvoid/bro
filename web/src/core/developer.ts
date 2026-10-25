/**
 * Technical details (model usage, delivery traces, the installation inventory, agent tokens) stay
 * hidden from the family. An administrator can show them on one device; the choice is per device
 * like the theme, and it means nothing without administrator rights.
 */
import { computed, ref, type ComputedRef } from 'vue'
import { useSession } from '@/stores/session'

const KEY = 'assistant.web.developer'

function stored(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** What this device asked for, before administrator rights are taken into account. */
export const developerPreference = ref(stored())

export function setDeveloperMode(enabled: boolean): void {
  developerPreference.value = enabled
  try {
    if (enabled) localStorage.setItem(KEY, '1')
    else localStorage.removeItem(KEY)
  } catch {
    // Private windows may refuse storage; the choice still applies to this page.
  }
}

/** True when technical details are shown: an administrator who turned them on here. */
export function useDeveloperMode(): ComputedRef<boolean> {
  const session = useSession()
  return computed(() => !!session.me?.system_admin && developerPreference.value)
}
