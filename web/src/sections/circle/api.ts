/**
 * Круг: spaces, people, chats, shared lists and challenges. Rights are decided by the server;
 * the client only hides actions the user's role cannot take and explains refusals.
 */
import { CallOutcome, duration } from '@/calls/api'
import { z } from '@/core/zod'
import { Task } from '@/sections/tasks/api'

const id = z.string()
const text = z.string()
const maybe = z.string().nullish()

export const ROLES = ['owner', 'admin', 'member'] as const
export type Role = (typeof ROLES)[number]

export const Space = z.looseObject({
  id,
  name: text,
  archived: z.boolean().nullish(),
  revision: z.number(),
  role: z.enum(ROLES),
})
export type Space = z.infer<typeof Space>

export const Invitation = z.looseObject({
  space_id: id,
  name: text,
  inviter_name: maybe,
  expires_at: maybe,
})
export type Invitation = z.infer<typeof Invitation>

export const Spaces = z.looseObject({
  spaces: z.array(Space),
  invitations: z.array(Invitation).default([]),
})

export const Member = z.looseObject({
  user_id: id,
  role: z.enum(ROLES),
  name: text,
  login: maybe,
})
export type Member = z.infer<typeof Member>
export const Members = z.looseObject({ members: z.array(Member) })

export const Relationship = z.looseObject({
  user_id: id,
  sender_id: id,
  recipient_id: id,
  state: z.enum(['pending', 'accepted', 'blocked']),
  label: maybe,
  name: text,
  login: maybe,
})
export type Relationship = z.infer<typeof Relationship>
export const Relationships = z.looseObject({ relationships: z.array(Relationship) })
export const Person = z.object({
  user: z.looseObject({ id, name: text, login: text }).nullable(),
})

export const Channel = z.looseObject({
  id,
  kind: z.enum(['personal', 'space', 'direct', 'resource']),
  name: text,
  space_id: maybe,
  unread: z.number().default(0),
  audience_resource_id: maybe,
  subject_kind: maybe,
  subject_id: maybe,
  peer_id: maybe,
  peer_name: maybe,
  peer_login: maybe,
})
export type Channel = z.infer<typeof Channel>
export const Channels = z.looseObject({ channels: z.array(Channel) })

export const Message = z.looseObject({
  id,
  actor_user_id: maybe,
  author: text,
  reply_to_id: maybe,
  created_at: text,
  name: maybe,
  text: z.string().nullish(),
  /** A call line (docs/calls-spec.md); the server alone measures the duration. */
  call: z
    .looseObject({
      call_id: id,
      kind: text,
      outcome: CallOutcome,
      duration_s: z.number().int().nullable(),
    })
    .nullish(),
})
export type Message = z.infer<typeof Message>
export const History = z.looseObject({
  channel: z.looseObject({ id }).optional(),
  messages: z.array(Message),
  next_cursor: maybe,
})
export type History = z.infer<typeof History>

export const PERMISSIONS = ['view', 'edit', 'manage'] as const
export type Permission = (typeof PERMISSIONS)[number]

export const TaskList = z.looseObject({
  id,
  name: text,
  space_id: maybe,
  member_access: z.string(),
  revision: z.number(),
  effective: z.string(),
  list_kind: z.enum(['list', 'project']).optional(),
  outcome: maybe,
  project_state: z.enum(['active', 'done', 'archived']).optional(),
  /** Set on a person's own «Личные задачи». */
  personal_user_id: maybe,
  /** Top-level tasks outside the trash by state: a project's progress. */
  counts: z.record(z.string(), z.number()).optional(),
})
export type TaskList = z.infer<typeof TaskList>
export const TaskLists = z.looseObject({ lists: z.array(TaskList) })

export const SharedTask = Task.extend({ assigned_to: maybe })
export type SharedTask = z.infer<typeof SharedTask>
export const SharedTaskPage = z.looseObject({
  items: z.array(SharedTask),
  next_cursor: maybe,
})

export const Grants = z.looseObject({
  resource: z.looseObject({ id, name: text, member_access: z.string(), revision: z.number() }),
  grants: z.array(
    z.looseObject({ user_id: id, permission: z.enum(PERMISSIONS), name: text, login: maybe }),
  ),
})
export type Grants = z.infer<typeof Grants>

export const Challenge = z.looseObject({
  id,
  name: text,
  start_date: text,
  end_date: text,
  starts_at: text,
  tolerance: z.union([z.string(), z.number()]).transform(String),
})
export type Challenge = z.infer<typeof Challenge>
export const Challenges = z.looseObject({ challenges: z.array(Challenge) })

export const Leaderboard = z.looseObject({
  challenge: Challenge,
  participation: z.enum(['pending', 'active', 'no_goal']).nullish(),
  terms: z
    .looseObject({
      calorie_target: z.union([z.string(), z.number()]).transform(String),
      timezone: text,
    })
    .nullish(),
  ranking: z.array(
    z.looseObject({
      user_id: id,
      name: text,
      days: z.number(),
      streak: z.number(),
      place: z.number(),
    }),
  ),
})
export type Leaderboard = z.infer<typeof Leaderboard>

/** Who a chat reaches, in words: the audience is always shown above the conversation. */
export function audienceOf(
  channel: Channel,
  spaceName: string | null,
): { title: string; kind: 'direct' | 'space' } {
  return channel.kind === 'direct'
    ? { title: channel.peer_name ?? channel.name, kind: 'direct' }
    : { title: spaceName ?? channel.name, kind: 'space' }
}

/**
 * A call line in the chat, in words for this reader: the caller (the line's actor) sees an
 * unanswered call, the other person a missed one. The stored text is the fallback.
 */
export function callLine(
  message: Message,
  me: string | undefined,
  t: (key: string, values?: Record<string, unknown>) => string,
): string {
  const call = message.call
  switch (call?.outcome) {
    case 'completed':
      return t('calls.line.call', { duration: duration(call.duration_s ?? 0) })
    case 'lost':
      return t('calls.line.lost', { duration: duration(call.duration_s ?? 0) })
    case 'declined':
      return t('calls.line.declined')
    case 'missed':
    case 'cancelled':
      return t(message.actor_user_id === me ? 'calls.line.missedOut' : 'calls.line.missedIn')
    default:
      return message.text ?? ''
  }
}

/** What a role may do in its space; the server enforces the same rules. */
export function abilities(role: Role) {
  return {
    invite: role === 'owner' || role === 'admin',
    changeRoles: role === 'owner',
    archive: role === 'owner',
    /** Owners and admins manage every resource of the space. */
    manageResources: role === 'owner' || role === 'admin',
  }
}

/** Whether `actor` may remove `target` (leaving is always allowed; an admin removes members). */
export function mayRemove(actor: Member, target: Member): boolean {
  if (actor.user_id === target.user_id) return true
  return actor.role === 'owner' || (actor.role === 'admin' && target.role === 'member')
}

/** The only owner cannot leave: ownership must pass to someone else first. */
export function soleOwner(members: Member[], user: string): boolean {
  const owners = members.filter((member) => member.role === 'owner')
  return owners.length === 1 && owners[0]!.user_id === user
}

/** Mixed incoming/outgoing relationships, in the order the user acts on them. */
export function relationshipGroups(relationships: Relationship[], me: string) {
  return {
    incoming: relationships.filter((item) => item.state === 'pending' && item.recipient_id === me),
    friends: relationships.filter((item) => item.state === 'accepted'),
    outgoing: relationships.filter((item) => item.state === 'pending' && item.sender_id === me),
    blocked: relationships.filter((item) => item.state === 'blocked'),
  }
}
