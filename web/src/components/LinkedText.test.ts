import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LinkedText, { webLinks } from './LinkedText'

describe('plain text web links', () => {
  it('preserves prose, line breaks and punctuation around clickable URLs', () => {
    const text =
      'Канал https://t.me/+invite-1.\nСайт (https://example.org/path?q=1&b=2), ещё http://example.net.'
    const wrapper = mount(LinkedText, { props: { text } })
    expect(wrapper.element.textContent).toBe(text)
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://t.me/+invite-1',
      'https://example.org/path?q=1&b=2',
      'http://example.net',
    ])
    for (const link of wrapper.findAll('a')) {
      expect(link.attributes('target')).toBe('_blank')
      expect(link.attributes('rel')).toBe('noopener noreferrer')
      expect(link.classes()).toContain('pointer-events-auto')
    }
  })

  it('leaves HTML, formatting and unsupported URL schemes inert', () => {
    const text =
      '<img src=x onerror=alert(1)> **Текст** javascript:alert(1) data:text/html,test mailto:a@example.org ftp://example.org www.example.org'
    const wrapper = mount(LinkedText, { props: { text } })
    expect(wrapper.element.textContent).toBe(text)
    expect(wrapper.find('a, img, strong, script, [style]').exists()).toBe(false)
  })

  it('rejects credentials and incomplete authorities instead of linking their prefix', () => {
    const text =
      'https://user@example.org https://user:secret@example.org https://example.org:bad/path'
    const wrapper = mount(LinkedText, { props: { text } })
    expect(wrapper.element.textContent).toBe(text)
    expect(wrapper.find('a').exists()).toBe(false)
    expect(webLinks('https://example.org:8443/path:part')).toEqual([
      {
        text: 'https://example.org:8443/path:part',
        href: 'https://example.org:8443/path:part',
        start: 0,
        end: 34,
      },
    ])
  })

  it('handles Unicode addresses and balanced parentheses without changing visible text', () => {
    const text = 'Открыть https://пример.рф/путь и https://example.org/wiki/Test_(example).'
    const wrapper = mount(LinkedText, { props: { text } })
    expect(wrapper.element.textContent).toBe(text)
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://пример.рф/путь',
      'https://example.org/wiki/Test_(example)',
    ])
  })

  it('reacts to text changes without extracting or duplicating links', async () => {
    const wrapper = mount(LinkedText, {
      props: { text: 'Канал https://t.me/example и сайт https://example.org.' },
      attrs: { class: 'block text-sm' },
    })
    expect(wrapper.element.textContent).toBe(
      'Канал https://t.me/example и сайт https://example.org.',
    )
    expect(wrapper.classes()).toContain('block')
    await wrapper.setProps({ text: 'Обычный текст' })
    expect(wrapper.element.textContent).toBe('Обычный текст')
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.setProps({ text: 'https://example.net' })
    expect(wrapper.get('a').attributes('href')).toBe('https://example.net')
  })
})
