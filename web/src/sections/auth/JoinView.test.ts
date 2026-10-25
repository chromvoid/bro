import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import JoinView from './JoinView.vue'

let view: VueWrapper | undefined
let fetcher = vi.fn<typeof fetch>()

beforeEach(() => {
  fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    new Response(JSON.stringify({ release: { version_code: 42 } }), {
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  vi.stubGlobal('fetch', fetcher)
})

afterEach(() => {
  view?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  history.replaceState(null, '', '/')
})

function open(hash: string) {
  history.replaceState({ synthetic: true }, '', `/join?source=test${hash}`)
  const replace = vi.spyOn(history, 'replaceState')
  view = mount(JoinView, { global: { plugins: [i18n] } })
  return { wrapper: view, replace }
}

describe('JoinView', () => {
  it.each([
    ['invite', 'Приглашение в Bro'],
    ['reset', 'Сброс пароля'],
  ])('offers %s actions without sending fragment data', async (kind, title) => {
    const { wrapper, replace } = open(
      `#server=https%3A%2F%2Ffamily.example%3A8443&kind=${kind}&token=synthetic%2Btoken%2F%26`,
    )
    expect(replace).toHaveBeenCalledExactlyOnceWith({ synthetic: true }, '', '/join?source=test')
    expect(location.hash).toBe('')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe(title)
    expect(wrapper.get('p').text()).toBe('family.example:8443')
    const links = wrapper.findAll('a')
    expect(links.map((link) => link.text())).toEqual([
      'Скачать приложение для Android',
      'Продолжить в браузере',
    ])
    expect(links[0]!.attributes('href')).toBe('/v1/shell/android/42.apk')
    expect(new URL(links[0]!.attributes('href')!, location.href).origin).toBe(location.origin)
    expect(links[1]!.attributes('href')).toBe(
      `https://family.example:8443/login#kind=${kind}&token=synthetic%2Btoken%2F%26`,
    )
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/v1/shell/android', {
      method: 'GET',
      body: undefined,
      headers: {},
      credentials: 'same-origin',
      redirect: 'error',
      signal: expect.anything(),
    })
  })

  it.each(['unpublished', 'unavailable'])(
    'keeps the browser action when the Android release is %s',
    async (state) => {
      if (state === 'unpublished') {
        fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ release: null })))
      } else {
        fetcher.mockRejectedValueOnce(new Error('Synthetic network failure'))
      }
      const { wrapper } = open('#server=https%3A%2F%2Ffamily.example&kind=invite&token=synthetic')
      await flushPromises()

      expect(wrapper.findAll('a')).toHaveLength(1)
      expect(wrapper.get('a').text()).toBe('Продолжить в браузере')
      expect(wrapper.get('a').attributes('href')).toBe(
        'https://family.example/login#kind=invite&token=synthetic',
      )
      expect(fetcher).toHaveBeenCalledOnce()
    },
  )

  it('scrubs an invalid fragment and shows the expired screen without auth links', async () => {
    const { wrapper, replace } = open(
      '#server=http%3A%2F%2Ffamily.example&kind=invite&token=synthetic',
    )
    await flushPromises()

    expect(replace).toHaveBeenCalledExactlyOnceWith({ synthetic: true }, '', '/join?source=test')
    expect(location.hash).toBe('')
    expect(wrapper.get('[role="alert"] h1').text()).toBe('Ссылка больше не действует')
    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('accepts and scrubs another link opened while the page is showing', async () => {
    const { wrapper, replace } = open(
      '#server=https%3A%2F%2Ffamily.example&kind=invite&token=first',
    )
    await flushPromises()
    replace.mockClear()
    history.replaceState(
      { synthetic: true },
      '',
      '/join?source=test#server=https%3A%2F%2Fother.example&kind=reset&token=second',
    )
    replace.mockClear()
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()

    expect(replace).toHaveBeenCalledExactlyOnceWith({ synthetic: true }, '', '/join?source=test')
    expect(location.hash).toBe('')
    expect(wrapper.get('h1').text()).toBe('Сброс пароля')
    expect(wrapper.get('p').text()).toBe('other.example')
    expect(wrapper.findAll('a')[1]!.attributes('href')).toBe(
      'https://other.example/login#kind=reset&token=second',
    )
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('replaces an accepted link with the expired screen when a new fragment is invalid', async () => {
    const { wrapper } = open('#server=https%3A%2F%2Ffamily.example&kind=invite&token=first')
    await flushPromises()
    history.replaceState(
      null,
      '',
      '/join#server=http%3A%2F%2Ffamily.example&kind=invite&token=second',
    )
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()

    expect(location.hash).toBe('')
    expect(wrapper.get('[role="alert"] h1').text()).toBe('Ссылка больше не действует')
    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(fetcher).toHaveBeenCalledOnce()
  })
})
