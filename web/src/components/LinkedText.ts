import MarkdownIt from 'markdown-it'
import { computed, defineComponent, h, type VNodeChild } from 'vue'
import { safeWebLink } from '@/core/links'

// Use the existing URL grammar without treating prose as Markdown or guessing domain names.
const matcher = new MarkdownIt().linkify.set({
  fuzzyLink: false,
  fuzzyEmail: false,
  fuzzyIP: false,
})

export type WebLink = { text: string; href: string; start: number; end: number }

export function webLinks(text: string): WebLink[] {
  return (matcher.match(text) ?? []).flatMap((match) => {
    if (match.schema !== 'http:' && match.schema !== 'https:') return []
    // The matcher stops before credentials or an invalid port; do not link just their prefix.
    const next = text[match.lastIndex]
    if (next === '@' || (next === ':' && text[match.lastIndex + 1]?.trim())) return []
    if (!safeWebLink(match.url)) return []
    return [{ text: match.raw, href: match.url, start: match.index, end: match.lastIndex }]
  })
}

/** Plain task text with explicit web URLs; HTML and Markdown remain literal text. */
export default defineComponent({
  name: 'LinkedText',
  inheritAttrs: false,
  props: {
    text: { type: String, required: true },
  },
  setup(props, { attrs }) {
    const links = computed(() => webLinks(props.text))
    return () => {
      const children: VNodeChild[] = []
      let cursor = 0
      for (const link of links.value) {
        children.push(props.text.slice(cursor, link.start))
        children.push(
          h(
            'a',
            {
              href: link.href,
              target: '_blank',
              rel: 'noopener noreferrer',
              class:
                'text-primary pointer-events-auto relative underline underline-offset-2 [overflow-wrap:anywhere]',
            },
            link.text,
          ),
        )
        cursor = link.end
      }
      children.push(props.text.slice(cursor))
      return h('span', { ...attrs, class: ['whitespace-pre-wrap', attrs.class] }, children)
    }
  },
})
