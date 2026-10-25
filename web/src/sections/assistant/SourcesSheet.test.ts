import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import ru from '@/locales/ru.json'
import SourcesSheet from './SourcesSheet.vue'

describe('SourcesSheet', () => {
  it('shows Codex source quotes and their relation to the answer', async () => {
    const wrapper = mount(SourcesSheet, {
      props: {
        open: true,
        limitations: [],
        sources: [
          {
            url: 'https://www.cbr.ru/currency_base/daily/',
            title: 'Официальные курсы валют',
            text: '840 USD 1 Доллар США 83,4839',
            provenance: 'codex_web',
            relation: 'supports',
          },
          {
            url: 'https://news.example/usd',
            title: 'News',
            provenance: 'codex_web',
            relation: 'contradicts',
          },
        ],
      },
      global: {
        plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      },
      attachTo: document.body,
    })
    await flushPromises()
    // The sheet renders into a portal on the page body.
    const items = [...document.body.querySelectorAll('li')].map((item) => item.textContent ?? '')
    expect(items).toHaveLength(2)
    expect(items[0]).toContain('подтверждает')
    expect(items[0]).toContain('«840 USD 1 Доллар США 83,4839»')
    expect(items[1]).toContain('противоречит')
    wrapper.unmount()
  })
})
