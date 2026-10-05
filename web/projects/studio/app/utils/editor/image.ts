/* eslint-disable @typescript-eslint/no-explicit-any */
// This is a modified version of https://github.com/ueberdosis/tiptap/tree/develop/packages/extension-image

import { mergeAttributes, Node, nodeInputRule } from '@tiptap/core'

export interface ImageOptions {
  inline?: boolean
  HTMLAttributes: Record<string, any>
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    image: {
      setImage: (
        _options: {
          src: string
          alt?: string
          title?: string
          metadataId: string
        }
      ) => ReturnType
    }
  }
}

export const inputRegex
  = /(?:^|\s)(!\[(.+|:?)]\((\S+)(?:(?:\s+)["'](\S+)["'])?\))$/

export const Image = Node.create<ImageOptions>({
  name: 'image',

  addOptions() {
    return {
      HTMLAttributes: {},
      inline: false
    }
  },

  inline() {
    return this.options.inline
  },

  group() {
    return this.options.inline ? 'inline' : 'block'
  },

  draggable: true,

  addAttributes() {
    return {
      src: {
        default: null
      },
      alt: {
        default: null
      },
      title: {
        default: null
      },
      metadataId: {
        default: null
      }
    }
  },

  parseHTML() {
    return [
      {
        tag: 'img[src]'
      }
    ]
  },

  renderHTML({ HTMLAttributes }) {
    return ['img', mergeAttributes(this.options.HTMLAttributes, HTMLAttributes)]
  },

  addCommands() {
    return {
      setImage: options => ({ commands }) => {
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
