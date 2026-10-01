import type { Editor } from '@tiptap/core'
import { Extension } from '@tiptap/core'
import Suggestion, { type SuggestionProps } from '@tiptap/suggestion'
import type { Range } from '@tiptap/vue-3'

export default Extension.create({
  name: 'Commands',

  addOptions() {
    return {
      suggestion: {
        char: '/',
        command: (
          { editor, range, props }: {
            editor: Editor
            range: Range
            props: SuggestionProps
          }
        ) => {
          props.command({ editor, range })
        }
      }
    }
  },

  addProseMirrorPlugins() {
    return [
      Suggestion({
        editor: this.editor,
        ...this.options.suggestion
      })
    ]
  }
})
