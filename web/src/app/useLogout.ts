/**
 * Signing out removes drafts and the write journal from this device, so every way out asks first
 * when a write is still unsettled. The dialog is mounted once in the shell (LogoutDialog).
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Submission } from '@/core/journal'
import { useSession } from '@/stores/session'

const leaving = ref(false)
const pending = ref<Submission[]>([])

export function useLogout() {
  const session = useSession()
  const router = useRouter()

  async function logout() {
    leaving.value = false
    await session.logout()
    await router.replace({ name: 'login' })
  }

  async function askLogout() {
    pending.value = await session.unsettledWrites()
    if (pending.value.length) leaving.value = true
    else await logout()
  }

  return { leaving, pending, askLogout, logout }
}
