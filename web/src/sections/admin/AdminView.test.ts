import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import AdminView from './AdminView.vue'

/**
 * What the server records (tests/test_admin_audit_contract.py). Read when the test runs, not
 * imported: the image builds the client from web/ and plugins/ alone, without contracts/.
 */
const contract = JSON.parse(
  readFileSync(resolve(process.cwd(), '../contracts/admin-audit.json'), 'utf8'),
) as { actions: string[]; retired: string[] }

const mocks = vi.hoisted(() => ({ developer: false, events: [] as unknown[] }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => mocks.developer) }
})
vi.mock('./useAdmin', () => ({
  useAudit: () => ({
    data: ref({ events: mocks.events }),
    isPending: ref(false),
    isLoadingError: ref(false),
  }),
  useUsers: () => ({ data: ref({ users: [{ id: 'admin', name: 'Админ' }] }) }),
}))

const event = (action: string, index = 0) => ({
  id: `event-${index}`,
  action,
  actor_user_id: 'admin',
  target_id: null,
  details: {},
  created_at: '2026-10-07T09:00:00Z',
})
const view = () =>
  mount(AdminView, {
    global: {
      plugins: [i18n],
      stubs: { PageHeader: true, RefreshFailed: true, RouterLink: { template: '<a><slot /></a>' } },
    },
  })

describe('administrator journal', () => {
  it('has words for every action the server records, also retired ones', () => {
    const { te } = i18n.global
    const missing = [...contract.actions, ...contract.retired].filter(
      (code) => !te(`admin.audit.${code.replaceAll('.', '_')}`),
    )
    expect(missing).toEqual([])
  })

  it('reads an unknown code as «Другое действие», the code itself only for a developer', () => {
    mocks.events = [event('personal.ring.enroll', 1), event('future.thing', 2)]
    const items = () =>
      view()
        .findAll('[data-testid="audit"] li')
        .map((item) => item.text())
    expect(items()[0]).toContain('Подключено кольцо')
    expect(items()[1]).toContain('Другое действие')
    expect(items()[1]).not.toContain('future.thing')
    mocks.developer = true
    expect(items()[1]).toContain('future.thing')
    expect(items()[0]).not.toContain('personal.ring.enroll')
  })
})
