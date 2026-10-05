import { VueRenderer } from '@tiptap/vue-3'
import tippy, { type GetReferenceClientRect, type Instance } from 'tippy.js'
import type { SuggestionOptions, SuggestionProps, SuggestionKeyDownProps } from '@tiptap/suggestion'
import type { EntitySearchResult } from '~/composables/useEntitySearch'
import MentionList from '~/components/document/DocumentMentionList.vue'

type SearchFn = (_query: string) => Promise<EntitySearchResult[]>

export interface MentionItem {
  id: string
  label: string
  entityType: string
}

export function mentionSuggestion(searchEntities: SearchFn): Omit<SuggestionOptions, 'editor'> {
  return {
    items: async ({ query }) => {
      if (!query) return []
      const results = await searchEntities(query)
      return results.map(r => ({ id: r.id, label: r.label, entityType: r.entityType }))
    },

    render: () => {
      let component: VueRenderer | null = null
      let popup: Instance[] = []

      return {
        onStart: (props: SuggestionProps<MentionItem>) => {
          component = new VueRenderer(MentionList, {
            props,
            editor: props.editor,
          })

          if (!props.clientRect) return

          popup = [tippy(document.body, {
            getReferenceClientRect: props.clientRect as GetReferenceClientRect,
            appendTo: () => document.body,
            content: component.element ?? undefined,
            showOnCreate: true,
            interactive: true,
            trigger: 'manual',
            placement: 'bottom-start',
          })]
        },

        onUpdate(props: SuggestionProps<MentionItem>) {
          if (popup.length === 0 || !component) return
          component.updateProps(props)
          if (!props.clientRect) return
          popup[0]?.setProps({
            getReferenceClientRect: props.clientRect as GetReferenceClientRect,
          })
        },

        onKeyDown(props: SuggestionKeyDownProps) {
          if (popup.length === 0) return false
          if (props.event.key === 'Escape') {
            popup[0]?.hide()
            return true
          }
          return component?.ref?.onKeyDown(props) ?? false
        },

        onExit() {
          if (popup.length !== 0) popup[0]?.destroy()
          if (component) component.destroy()
        },
      }
    },
  }
}
