import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { operationKind, useLedger } from '@/core/operations'
import type { Submission } from '@/core/journal'
import {
  ACTIVITY,
  Exercises,
  Progress,
  SessionReply,
  Sessions,
  Summary,
  type ActivityKind,
} from './api'

export interface ActivityRange {
  from: string
  to: string
  kind: ActivityKind | null
}
export function rangeQuery(range: ActivityRange) {
  return new URLSearchParams({
    from: range.from,
    to: range.to,
    ...(range.kind ? { kind: range.kind } : {}),
  })
}
export function useActivitySessions(range: Ref<ActivityRange>, enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: computed(() => ['activity', 'sessions', range.value]),
    queryFn: async ({ signal }) =>
      Sessions.parse(await api.get(`/v1/activity/sessions?${rangeQuery(range.value)}`, signal)),
  })
}
export function useActivitySummary(range: Ref<ActivityRange>, enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: computed(() => ['activity', 'summary', range.value]),
    queryFn: async ({ signal }) =>
      Summary.parse(await api.get(`/v1/activity/summary?${rangeQuery(range.value)}`, signal)),
  })
}
export function useActivitySession(id: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['activity', 'session', id.value]),
    queryFn: async ({ signal }) =>
      SessionReply.parse(
        await api.get(`/v1/activity/sessions/${encodeURIComponent(id.value)}`, signal),
      ),
  })
}
export function useExercises(enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: ['activity', 'exercises'],
    queryFn: async ({ signal }) => Exercises.parse(await api.get('/v1/activity/exercises', signal)),
  })
}
export function useExerciseProgress(id: Ref<string>, range: Ref<ActivityRange>) {
  return useQuery({
    queryKey: computed(() => ['activity', 'progress', id.value, range.value.from, range.value.to]),
    enabled: computed(() => !!id.value),
    queryFn: async ({ signal }) =>
      Progress.parse(
        await api.get(
          `/v1/activity/progress?exercise_id=${encodeURIComponent(id.value)}&${rangeQuery({ ...range.value, kind: null })}`,
          signal,
        ),
      ),
  })
}
export function useActivity() {
  const { t } = useI18n()
  const ledger = useLedger(ACTIVITY)
  const operate = (
    kind: string,
    payload: Record<string, unknown>,
    expected: Record<string, number> = {},
  ) => ledger.operate({ kind: `activity.${kind}`, payload, expected_revisions: expected })
  const describe = (entry: Submission) =>
    t('activity.unresolved', {
      kind: t(`activity.operations.${operationKind(entry).split('.').slice(1).join('_')}`),
    })
  const failed = (error: unknown) => toast.error(errorText(error, t))
  return { ...ledger, operate, describe, failed }
}
