import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, type Ref } from 'vue'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { Limits } from '@/sections/settings/limits'
import { SpeechSnapshot } from '@/sections/settings/speech/api'
import { useSession } from '@/stores/session'
import {
  Access,
  AccountsSnapshot,
  Audit,
  Login,
  RunDetail,
  TERMINAL_LOGIN,
  Users,
  WorkersSnapshot,
} from './api'

/** A service this installation does not run answers 404 or 503: shown as absent, not broken. */
export const notInstalled = (error: unknown) =>
  error instanceof ApiError && (error.status === 404 || error.status === 503)

const once = (count: number, error: unknown) => !notInstalled(error) && count < 2

export function useUsers() {
  return useQuery({
    queryKey: ['accounts', 'users'],
    queryFn: async ({ signal }) => Users.parse(await api.get('/v1/admin/users', signal)),
  })
}

export function useUserLimits(user: Ref<string | null>) {
  return useQuery({
    queryKey: computed(() => ['accounts', 'limits', user.value]),
    enabled: computed(() => user.value !== null),
    queryFn: async ({ signal }) =>
      Limits.parse(
        await api.get(`/v1/admin/users/${encodeURIComponent(user.value!)}/limits`, signal),
      ),
  })
}

/** Who may use which subscription and shared speech connection, person by person. */
export function useAccess() {
  return useQuery({
    queryKey: ['accounts', 'access'],
    retry: once,
    queryFn: async ({ signal }) => Access.parse(await api.get('/v1/admin/access', signal)),
  })
}

/** The installation's shared speech connections, granted per person like subscriptions. */
export function useSharedSpeech() {
  const session = useSession()
  return useQuery({
    queryKey: computed(() => ['speech', session.me?.id, 'admin']),
    retry: once,
    queryFn: async ({ signal }) => SpeechSnapshot.parse(await api.get('/v1/admin/speech', signal)),
  })
}

export function useAudit() {
  return useQuery({
    queryKey: ['accounts', 'audit'],
    queryFn: async ({ signal }) => Audit.parse(await api.get('/v1/admin/audit', signal)),
  })
}

/** Provider observations change on their own: refreshed every 15 s while the page is visible. */
export function useAccounts() {
  return useQuery({
    queryKey: ['accounts', 'snapshot'],
    retry: once,
    refetchInterval: 15_000,
    queryFn: async ({ signal }) =>
      AccountsSnapshot.parse(await api.get('/v1/accounts/snapshot', signal)),
  })
}

/** A sign-in in progress is polled every 2 s until it ends. */
export function useLogin(login: Ref<string>) {
  const finished = ref(false)
  const client = useQueryClient()
  return useQuery({
    queryKey: computed(() => ['accounts', 'login', login.value]),
    refetchInterval: computed(() => (finished.value ? false : 2000)),
    queryFn: async ({ signal }) => {
      const data = Login.parse(
        await api.get(`/v1/accounts/logins/${encodeURIComponent(login.value)}`, signal),
      )
      if (!finished.value && TERMINAL_LOGIN.has(data.state))
        void client.invalidateQueries({ queryKey: ['accounts', 'snapshot'] })
      finished.value = TERMINAL_LOGIN.has(data.state)
      return data
    },
  })
}

export function useWorkers() {
  return useQuery({
    queryKey: ['workers', 'snapshot'],
    retry: once,
    refetchInterval: 15_000,
    queryFn: async ({ signal }) =>
      WorkersSnapshot.parse(await api.get('/v1/workers/snapshot', signal)),
  })
}

export function useRun(run: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['workers', 'run', run.value]),
    retry: once,
    refetchInterval: 15_000,
    queryFn: async ({ signal }) =>
      RunDetail.parse(await api.get(`/v1/workers/runs/${encodeURIComponent(run.value)}`, signal)),
  })
}
