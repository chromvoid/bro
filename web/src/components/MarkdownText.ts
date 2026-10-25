import MarkdownIt, { type Token } from 'markdown-it'
import { computed, defineComponent, h, type PropType, type VNodeChild } from 'vue'
import { safeWebLink } from '@/core/links'

// Parse Markdown, never HTML. Even parser-generated tags/attributes are not trusted:
// only the explicit Vue elements below can reach the DOM. No renderer plugins.
const markdown = new MarkdownIt('commonmark', { html: false, linkify: false, breaks: true })
markdown.disable('autolink')
markdown.enable(['table', 'strikethrough'])

type Frame = { token: Token | null; children: VNodeChild[] }

/** Non-struck text without formatting markers, for matching confirmed facts. */
export function markdownTextContent(text: string): string {
  function contents(tokens: Token[]): string {
    let struck = 0
    return tokens
      .map((token) => {
        if (token.type === 's_open') {
          struck++
          return ' '
        }
        if (token.type === 's_close') {
          struck--
          return ' '
        }
        // Crossed-out prose cannot replace an authoritative confirmation.
        if (struck) return ''
        if (token.children) return contents(token.children)
        if (token.type === 'softbreak' || token.type === 'hardbreak') return '\n'
        if (token.block && token.nesting === -1) return '\n'
        return token.content + (token.block ? '\n' : '')
      })
      .join('')
  }
  return contents(markdown.parse(text, {})).replace(/\s+/g, ' ').trim()
}

function element(token: Token, children: VNodeChild[], allowed: ReadonlySet<string>): VNodeChild {
  switch (token.type) {
    case 'paragraph_open':
      return token.hidden ? children : h('p', { class: 'whitespace-pre-wrap' }, children)
    case 'strong_open':
      return h('strong', children)
    case 'em_open':
      return h('em', children)
    case 's_open':
      return h('s', children)
    case 'table_open':
      return h('div', { class: 'max-w-full overflow-x-auto' }, [
        h('table', { class: 'min-w-full border-collapse text-left text-sm' }, children),
      ])
    case 'thead_open':
      return h('thead', children)
    case 'tbody_open':
      return h('tbody', children)
    case 'tr_open':
      return h('tr', children)
    case 'th_open':
    case 'td_open': {
      // Markdown table alignment becomes fixed classes, never a style attribute.
      const alignment = {
        'text-align:left': 'text-left',
        'text-align:center': 'text-center',
        'text-align:right': 'text-right',
      }[token.attrGet('style') ?? '']
      return h(
        token.type === 'th_open' ? 'th' : 'td',
        {
          // Narrower cells on a phone: a three-column table fits 360 px without wrapping a day.
          class: [
            'border-b px-2 py-2 md:px-3',
            token.type === 'th_open' && 'font-semibold',
            alignment,
          ],
        },
        children,
      )
    }
    case 'blockquote_open':
      return h('blockquote', { class: 'text-muted-foreground space-y-2 border-l-2 pl-3' }, children)
    case 'bullet_list_open':
      return h('ul', { class: 'list-disc space-y-1 pl-6' }, children)
    case 'ordered_list_open': {
      const start = Number(token.attrGet('start') ?? 1)
      return h('ol', { class: 'list-decimal space-y-1 pl-6', start }, children)
    }
    case 'list_item_open':
      return h('li', { class: 'space-y-2' }, children)
    case 'heading_open': {
      // The page owns h1; assistant headings remain below it in the document outline.
      const heading = { h1: 'h2', h2: 'h3', h3: 'h4', h4: 'h5', h5: 'h6', h6: 'h6' }[token.tag]
      // A little larger than the text for the top two levels, so a long answer can be scanned.
      const size = { h1: 'pt-1 text-lg leading-snug', h2: 'pt-1 text-[1.0625rem] leading-snug' }[
        token.tag
      ]
      return h(heading ?? 'p', { class: ['font-semibold', size] }, children)
    }
    case 'link_open': {
      const href = String(token.attrGet('href') ?? '')
      if (!allowed.has(href) || !safeWebLink(href)) return ['[', ...children, '](', href, ')']
      return h(
        'a',
        {
          href,
          target: '_blank',
          rel: 'noopener noreferrer',
          class: 'text-primary underline underline-offset-2',
        },
        children,
      )
    }
    default:
      return children
  }
}

function nodes(tokens: Token[], allowed: ReadonlySet<string>): VNodeChild[] {
  const root: Frame = { token: null, children: [] }
  const stack = [root]
  for (const token of tokens) {
    const parent = stack.at(-1)!
    if (token.nesting === 1) {
      stack.push({ token, children: [] })
    } else if (token.nesting === -1 && stack.length > 1) {
      const frame = stack.pop()!
      stack.at(-1)!.children.push(element(frame.token!, frame.children, allowed))
    } else if (token.type === 'inline') {
      parent.children.push(...nodes(token.children ?? [], allowed))
    } else if (token.type === 'image') {
      // Image syntax stays inert text, including images nested in source links.
      parent.children.push(`![${token.content}](${token.attrGet('src') ?? ''})`)
    } else if (token.type === 'code_inline') {
      parent.children.push(
        h('code', { class: 'bg-muted rounded px-1 py-0.5 text-[0.9em]' }, token.content),
      )
    } else if (token.type === 'fence' || token.type === 'code_block') {
      parent.children.push(
        h('pre', { class: 'bg-muted max-w-full overflow-x-auto rounded-lg p-3 text-sm' }, [
          h('code', token.content),
        ]),
      )
    } else if (token.type === 'softbreak' || token.type === 'hardbreak') {
      parent.children.push(h('br'))
    } else if (token.type === 'hr') {
      parent.children.push(h('hr'))
    } else if (token.content) {
      parent.children.push(token.content)
    }
  }
  return root.children
}

/** Public untrusted text, with links restricted to the caller's verified source URLs. */
export default defineComponent({
  name: 'MarkdownText',
  props: {
    text: { type: String, required: true },
    allowedLinks: { type: Array as PropType<readonly string[]>, default: () => [] },
  },
  setup(props) {
    // Inline destinations already use this normalization (Unicode paths/IDN hosts).
    // Compare the verified sources in the same representation, without broadening
    // approval to other URLs on the source's host.
    // An equal list keeps the previous set, so the text is not parsed again.
    const allowed = computed<ReadonlySet<string>>((previous) => {
      const next = new Set(
        props.allowedLinks.filter(safeWebLink).map((link) => markdown.normalizeLink(link)),
      )
      return previous?.size === next.size && [...next].every((link) => previous.has(link))
        ? previous
        : next
    })
    const content = computed(() => nodes(markdown.parse(props.text, {}), allowed.value))
    return () => h('div', { class: 'min-w-0 space-y-3 leading-relaxed break-words' }, content.value)
  },
})
