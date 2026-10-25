import { mount } from '@vue/test-utils'
import MarkdownIt from 'markdown-it'
import { describe, expect, it, vi } from 'vitest'
import MarkdownText from './MarkdownText'

describe('untrusted public Markdown', () => {
  it('sets the top two heading levels a little larger than the text', () => {
    const wrapper = mount(MarkdownText, {
      props: { text: '# Ужин\n\n## Курица с рисом\n\n### Соус\n\nТекст' },
    })
    expect(wrapper.get('h2').classes()).toEqual([
      'font-semibold',
      'pt-1',
      'text-lg',
      'leading-snug',
    ])
    expect(wrapper.get('h3').classes()).toContain('text-[1.0625rem]')
    expect(wrapper.get('h4').classes()).toEqual(['font-semibold'])
  })

  it('renders comparison tables and strikethrough without inline styles', () => {
    const wrapper = mount(MarkdownText, {
      props: { text: '| Продукт | Ккал |\n| :--- | ---: |\n| Йогурт | ~~200~~ **180** |' },
    })
    expect(wrapper.findAll('th').map((cell) => cell.text())).toEqual(['Продукт', 'Ккал'])
    expect(wrapper.findAll('td')).toHaveLength(2)
    expect(wrapper.get('s').text()).toBe('200')
    expect(wrapper.get('strong').text()).toBe('180')
    expect(wrapper.findAll('td')[1]!.classes()).toContain('text-right')
    expect(wrapper.find('[style]').exists()).toBe(false)
    expect(wrapper.get('table').element.parentElement?.className).toContain('overflow-x-auto')
  })

  it('renders conversation formatting, lists, quotes and literal code', () => {
    const wrapper = mount(MarkdownText, {
      props: {
        text: '# Завтрак\n\nЙогурт — **180 ккал**, *примерно*.\nВторая строка.\n\n- Яблоко\n- Чай\n\n3. Вода\n4. Кофе\n\n> Цитата\n\n`**literal**`\n\n```html\n<img src=x onerror=alert(1)>\n```',
      },
    })
    expect(wrapper.get('h2').text()).toBe('Завтрак')
    expect(wrapper.get('strong').text()).toBe('180 ккал')
    expect(wrapper.get('em').text()).toBe('примерно')
    expect(wrapper.findAll('ul > li').map((item) => item.text())).toEqual(['Яблоко', 'Чай'])
    expect(wrapper.get('ol').attributes('start')).toBe('3')
    expect(wrapper.get('blockquote').text()).toBe('Цитата')
    expect(wrapper.find('br').exists()).toBe(true)
    expect(wrapper.findAll('code').map((item) => item.text())).toEqual([
      '**literal**',
      '<img src=x onerror=alert(1)>',
    ])
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('allows only exact cited HTTP(S) URLs, never uncited or credential-bearing destinations', () => {
    const source = 'https://example.org/a'
    const credential = 'https://user:secret@example.org/a'
    const wrapper = mount(MarkdownText, {
      props: {
        text: `[Источник \\[архив\\]](<${source}>) [не источник](https://example.org/b) [пароль](${credential}) [код](javascript:alert) [данные](data:text/html,hello) [локально](/v1/private) https://example.org/a`,
        allowedLinks: [
          source,
          credential,
          'javascript:alert',
          'data:text/html,hello',
          '/v1/private',
        ],
      },
    })
    const links = wrapper.findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0]!.text()).toBe('Источник [архив]')
    expect(links[0]!.attributes()).toMatchObject({
      href: source,
      target: '_blank',
      rel: 'noopener noreferrer',
    })
    expect(wrapper.text()).toContain('[не источник](https://example.org/b)')
  })

  it.each([
    ['https://example.org/еда', 'https://example.org/%D0%B5%D0%B4%D0%B0'],
    ['https://example.org/?q=чай', 'https://example.org/?q=%D1%87%D0%B0%D0%B9'],
    ['https://пример.рф/a', 'https://xn--e1afmkfd.xn--p1ai/a'],
  ])('normalizes cited Unicode source %s without allowing near matches', (source, normalized) => {
    const wrapper = mount(MarkdownText, {
      props: {
        text: `[Источник](${source}) [Тот же источник](${normalized}) [Другой раздел](${source}#other)`,
        allowedLinks: [source],
      },
    })
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      normalized,
      normalized,
    ])
    expect(wrapper.text()).toContain(`[Другой раздел](${normalized}#other)`)
  })

  it('accepts a Unicode destination for an encoded source but never Unicode credentials', () => {
    const credential = 'https://логин:пароль@пример.рф/еда'
    const wrapper = mount(MarkdownText, {
      props: {
        text: `[Источник](https://example.org/еда) [Пароль](${credential})`,
        allowedLinks: ['https://example.org/%D0%B5%D0%B4%D0%B0', credential],
      },
    })
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://example.org/%D0%B5%D0%B4%D0%B0',
    ])
  })

  it('keeps HTML, images, escaped formatting and links in code inert', () => {
    const source = 'https://example.org/a'
    const wrapper = mount(MarkdownText, {
      props: {
        text: `<script>alert(1)</script>\n<img src="${source}" onerror="alert(1)">\n\n![photo](${source})\n\n\\*literal\\* \\*\\*literal bold\\*\\*\n\n\`[source](${source})\``,
        allowedLinks: [source],
      },
    })
    expect(
      wrapper.find('script, img, iframe, style, [style], [onerror], a, strong, em').exists(),
    ).toBe(false)
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
    expect(wrapper.text()).toContain(`![photo](${source})`)
    expect(wrapper.text()).toContain('*literal* **literal bold**')
    expect(wrapper.get('code').text()).toBe(`[source](${source})`)
  })

  it('keeps partial streamed syntax readable, then formats the current full snapshot', async () => {
    const wrapper = mount(MarkdownText, { props: { text: 'Йогурт — **180' } })
    expect(wrapper.text()).toBe('Йогурт — **180')
    expect(wrapper.find('strong').exists()).toBe(false)
    await wrapper.setProps({ text: 'Йогурт — **180 ккал**. [Источник](https://example.org/a' })
    expect(wrapper.get('strong').text()).toBe('180 ккал')
    expect(wrapper.text()).toContain('[Источник](https://example.org/a')
    await wrapper.setProps({ text: '```\nconst x = "<script>";' })
    expect(wrapper.get('pre code').text()).toBe('const x = "<script>";')
    expect(wrapper.find('script').exists()).toBe(false)
    await wrapper.setProps({ text: '[Источник](https://example.org/a)' })
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('parses again for changed allowed links, not for an equal new list', async () => {
    const parse = vi.spyOn(MarkdownIt.prototype, 'parse')
    const source = 'https://example.org/a'
    const wrapper = mount(MarkdownText, {
      props: { text: `[Источник](${source})`, allowedLinks: [source] },
    })
    expect(parse).toHaveBeenCalledOnce()
    await wrapper.setProps({ allowedLinks: [source] })
    expect(parse).toHaveBeenCalledOnce()
    expect(wrapper.get('a').attributes('href')).toBe(source)
    await wrapper.setProps({ allowedLinks: ['https://example.org/b'] })
    expect(parse).toHaveBeenCalledTimes(2)
    expect(wrapper.find('a').exists()).toBe(false)
    parse.mockRestore()
  })
})
