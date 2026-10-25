import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import {
  Channel,
  Message,
  abilities,
  audienceOf,
  callLine,
  mayRemove,
  relationshipGroups,
  soleOwner,
  type Member,
} from './api'
import { band, challengeState, joinOpen } from './challenges'

const member = (user_id: string, role: Member['role']): Member => ({ user_id, role, name: user_id })

describe('circle rules', () => {
  it('names the audience of a chat', () => {
    const direct = Channel.parse({
      id: 'c1',
      kind: 'direct',
      name: 'Личная переписка',
      peer_name: 'Мария',
    })
    expect(audienceOf(direct, null)).toEqual({ title: 'Мария', kind: 'direct' })
    const space = Channel.parse({ id: 'c2', kind: 'space', name: 'Общий чат', space_id: 's1' })
    expect(audienceOf(space, 'Семья')).toEqual({ title: 'Семья', kind: 'space' })
  })

  it('words call lines for the reader', () => {
    const t = i18n.global.t
    const line = (outcome: string, duration_s: number | null, actor = 'me') =>
      callLine(
        Message.parse({
          id: 'm1',
          actor_user_id: actor,
          author: 'user',
          created_at: '2026-09-23T12:00:00Z',
          text: 'Пропущенный звонок',
          call: { call_id: 'c1', kind: 'audio', outcome, duration_s },
        }),
        'me',
        t,
      )
    expect(line('completed', 5)).toBe('Звонок · 0:05')
    expect(line('completed', 133)).toBe('Звонок · 2:13')
    expect(line('completed', 3723)).toBe('Звонок · 1:02:03')
    expect(line('lost', 133, 'anna')).toBe('Звонок прервался · 2:13')
    expect(line('declined', null)).toBe('Звонок отклонён')
    expect(line('missed', null)).toBe('Звонок без ответа')
    expect(line('missed', null, 'anna')).toBe('Пропущенный звонок')
    expect(line('cancelled', null)).toBe('Звонок без ответа')
    expect(line('cancelled', null, 'anna')).toBe('Пропущенный звонок')
    expect(line('failed', null)).toBe('Пропущенный звонок')
    expect(Message.parse({ id: 'm2', author: 'user', created_at: 'x' }).call).toBeUndefined()
  })

  it('follows the server rules for roles', () => {
    expect(abilities('owner')).toMatchObject({ invite: true, changeRoles: true, archive: true })
    expect(abilities('admin')).toMatchObject({ invite: true, changeRoles: false, archive: false })
    expect(abilities('member')).toMatchObject({ invite: false, manageResources: false })
    expect(mayRemove(member('a', 'admin'), member('b', 'member'))).toBe(true)
    expect(mayRemove(member('a', 'admin'), member('b', 'admin'))).toBe(false)
    expect(mayRemove(member('a', 'member'), member('a', 'member'))).toBe(true)
    expect(soleOwner([member('a', 'owner'), member('b', 'member')], 'a')).toBe(true)
    expect(soleOwner([member('a', 'owner'), member('b', 'owner')], 'a')).toBe(false)
  })

  it('groups people by what the user can do next', () => {
    const rows = [
      { user_id: 'b', sender_id: 'b', recipient_id: 'me', state: 'pending', name: 'B' },
      { user_id: 'c', sender_id: 'me', recipient_id: 'c', state: 'pending', name: 'C' },
      { user_id: 'd', sender_id: 'd', recipient_id: 'me', state: 'accepted', name: 'D' },
      { user_id: 'e', sender_id: 'me', recipient_id: 'e', state: 'blocked', name: 'E' },
    ] as const
    const groups = relationshipGroups([...rows], 'me')
    expect(
      Object.fromEntries(
        Object.entries(groups).map(([key, list]) => [key, list.map((r) => r.user_id)]),
      ),
    ).toEqual({
      incoming: ['b'],
      friends: ['d'],
      outgoing: ['c'],
      blocked: ['e'],
    })
  })

  it('describes challenge periods, joining and the day band', () => {
    const challenge = {
      id: 'x',
      name: 'Дни',
      start_date: '2026-10-01',
      end_date: '2026-10-14',
      starts_at: '2026-09-30T22:00:00Z',
      tolerance: '10',
    }
    expect(challengeState(challenge, '2026-09-25')).toBe('upcoming')
    expect(challengeState(challenge, '2026-10-01')).toBe('running')
    expect(challengeState(challenge, '2026-10-15')).toBe('finished')
    expect(joinOpen(challenge, Date.parse('2026-09-30T21:59:00Z'))).toBe(true)
    expect(joinOpen(challenge, Date.parse('2026-09-30T22:00:00Z'))).toBe(false)
    expect(band('2000', '10')).toBe('1 800–2 200 ккал')
  })
})
