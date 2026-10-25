import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import {
  Challenges,
  Channels,
  Grants,
  History,
  Leaderboard,
  Members,
  Relationships,
  SharedTaskPage,
  Spaces,
  TaskLists,
} from './api'

/** A resource that answers 404 was taken away (left, removed, archived): not a network error. */
export const isGone = (error: unknown) => error instanceof ApiError && error.status === 404

export function useSpaces() {
  return useQuery({
    queryKey: ['community', 'spaces'],
    queryFn: async ({ signal }) => Spaces.parse(await api.get('/v1/spaces', signal)),
  })
}

export function useChannels() {
  return useQuery({
    queryKey: ['community', 'channels'],
    queryFn: async ({ signal }) => Channels.parse(await api.get('/v1/channels', signal)),
  })
}

/**
 * What waits in Circle, for the shell's badge: unread messages in spaces and direct chats (the
 * personal channel is the assistant's own) and invitations to spaces.
 */
export function useCircleAttention() {
  const channels = useChannels()
  const spaces = useSpaces()
  return computed(() => {
    const unread =
      channels.data.value?.channels
        .filter((channel) => channel.kind !== 'personal')
        .reduce((sum, channel) => sum + channel.unread, 0) ?? 0
    return unread + (spaces.data.value?.invitations.length ?? 0)
  })
}

export function useRelationships() {
  return useQuery({
    queryKey: ['community', 'relationships'],
    queryFn: async ({ signal }) => Relationships.parse(await api.get('/v1/relationships', signal)),
  })
}

export function useMembers(space: Ref<string | null>) {
  return useQuery({
    queryKey: computed(() => ['community', 'members', space.value]),
    enabled: computed(() => space.value !== null),
    retry: (count, error) => !isGone(error) && count < 2,
    queryFn: async ({ signal }) =>
      Members.parse(
        await api.get(`/v1/spaces/${encodeURIComponent(space.value!)}/members`, signal),
      ),
  })
}

export function useMessages(channel: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['community', 'messages', channel.value]),
    retry: (count, error) => !isGone(error) && count < 2,
    queryFn: async ({ signal }) =>
      History.parse(
        await api.get(`/v1/channels/${encodeURIComponent(channel.value)}/messages`, signal),
      ),
  })
}

export function useTaskLists() {
  return useQuery({
    queryKey: ['tasks', 'lists'],
    queryFn: async ({ signal }) => TaskLists.parse(await api.get('/v1/tasks/lists', signal)),
  })
}

export function useListTasks(list: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['tasks', 'list', list.value]),
    retry: (count, error) => !isGone(error) && count < 2,
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ list_id: list.value, view: 'open', limit: '100' })
      return SharedTaskPage.parse(await api.get(`/v1/tasks/items?${query}`, signal))
    },
  })
}

export function useGrants(list: Ref<string>, enabled: Ref<boolean>) {
  return useQuery({
    queryKey: computed(() => ['community', 'grants', list.value]),
    enabled,
    queryFn: async ({ signal }) =>
      Grants.parse(await api.get(`/v1/resources/${encodeURIComponent(list.value)}/grants`, signal)),
  })
}

export function useChallenges(space: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['challenges', 'space', space.value]),
    retry: (count, error) => !isGone(error) && count < 2,
    queryFn: async ({ signal }) =>
      Challenges.parse(
        await api.get(`/v1/challenges?space_id=${encodeURIComponent(space.value)}`, signal),
      ),
  })
}

export function useLeaderboard(challenge: Ref<string>) {
  return useQuery({
    queryKey: computed(() => ['challenges', 'board', challenge.value]),
    retry: (count, error) => !isGone(error) && count < 2,
    queryFn: async ({ signal }) =>
      Leaderboard.parse(
        await api.get(`/v1/challenges/${encodeURIComponent(challenge.value)}`, signal),
      ),
  })
}

/**
 * Direct actions of Круг (membership, relationships, rights). They are guarded by revisions or
 * are safe to repeat, so they are sent directly; the affected lists reload afterwards.
 */
export function useCircleActions() {
  const { t } = useI18n()
  const queries = useQueryClient()
  const busy = ref(false)

  async function act<T>(
    call: () => Promise<T>,
    success: string | null,
    domains: string[] = ['community'],
  ): Promise<T | null> {
    if (busy.value) return null
    busy.value = true
    try {
      const result = await call()
      if (success) toast.success(success)
      return result
    } catch (error) {
      toast.error(
        error instanceof ApiError && error.status === 409
          ? (error.serverMessage ?? t('circle.changed'))
          : errorText(error, t),
      )
      return null
    } finally {
      busy.value = false
      for (const domain of domains) void queries.invalidateQueries({ queryKey: [domain] })
    }
  }

  const post = <T = unknown>(path: string, body: unknown) =>
    api.post<T>(path, body).then((reply) => reply.data)

  return { busy, act, post }
}
