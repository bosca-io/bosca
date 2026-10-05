/* eslint-disable @typescript-eslint/no-explicit-any */
import { mergeAttributes, Node, nodeInputRule } from '@tiptap/core'

export interface BibleOptions {
  inline?: boolean
  HTMLAttributes: Record<string, any>
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    bible: {
      setReferences: (
        _options: {
          usfm?: Array<string>
          metadataIds?: Array<string>
        }
      ) => ReturnType
    }
  }
}

export const inputRegex
  = /(?:^|\s)(!\[(.+|:?)]\((\S+)(?:(?:\s+)["'](\S+)["'])?\))$/

export const Bible = Node.create<BibleOptions>({
  name: 'bible',

  addOptions() {
    return {
      HTMLAttributes: {}
    }
  },

  inline() {
    return false
  },

  group() {
    return 'block'
  },

  draggable: true,

  addAttributes() {
    return {
      usfm: {
        default: null
      },
      metadataIds: {
        default: null
      }
    }
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-usfm]'
      }
    ]
  },

  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(this.options.HTMLAttributes, HTMLAttributes)]
  },

  addCommands() {
    return {
      setReferences: options => ({ commands }) => {
        return commands.insertContent({
          type: this.name,
          attrs: options
        })
      }
    }
  },

  addInputRules() {
    return [
      nodeInputRule({
        find: inputRegex,
        type: this.type,
        getAttributes: (match) => {
          const [, , alt, src, title, metadataId] = match

          return { src, alt, title, metadataId }
        }
      })
    ]
  }
})
