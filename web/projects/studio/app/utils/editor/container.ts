/* eslint-disable @typescript-eslint/no-explicit-any */
import { mergeAttributes, Node } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import ContainerNode from '~/components/document/DocumentContainerNode.vue'
import type { DocumentTemplateContainer, Metadata } from '~/types/graphql'

export interface ContainerOptions {
  metadata: Metadata
  containers: Array<DocumentTemplateContainer>
  HTMLAttributes: Record<string, any>
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    container: {
      setContainer: (_attributes: { name: string }) => ReturnType
      unsetContainer: () => ReturnType
    }
  }
}

export const Container = Node.create<ContainerOptions>({
  name: 'container',

  group: 'block',

  content: 'block*',

  addOptions() {
    return {
      metadata: {} as Metadata,
      containers: [],
      HTMLAttributes: {}
    }
  },

  addAttributes() {
    return {
      name: {
        default: null,
        isRequired: true
      },
      references: {
        default: null,
        isRequired: false
      },
      metadataId: {
        default: null,
        isRequired: false
      },
      version: {
        default: null,
        isRequired: false
      },
      renderer: {
        default: null,
        isRequired: false
      }
    }
  },

  parseHTML() {
    return []
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'span',
      mergeAttributes(this.options.HTMLAttributes, HTMLAttributes),
      0
    ]
  },

  addNodeView() {
    // @ts-expect-error this is ok
    return VueNodeViewRenderer(ContainerNode, { draggable: true })
  },

  addCommands() {
    return {
      setContainer: ({ name }) => ({ editor, tr, commands }) => {
        if (commands.wrapIn(this.name, { name })) {
          const paragraphNode = editor.schema.nodes.paragraph?.create()
          if (paragraphNode) {
            tr.insert(tr.selection.$to.pos + 2, paragraphNode)
          }
          return true
        }
        return false
      },
      unsetContainer: () => ({ editor, commands }) => {
        const { $from, $to } = editor.state.selection
        const nodeRange = $from.blockRange($to)
        if (!nodeRange) {
          console.error('No container to unwrap.')
          return false
        }
        const targetParentNode = nodeRange.depth && $from.node(nodeRange.depth)
        if (!targetParentNode || targetParentNode.type.name !== this.name) {
          console.error('Selection is not inside a container node.')
          return false
        }
        return commands.lift('container')
      }
    }
  },

  addKeyboardShortcuts() {
    return {}
  },

  addInputRules() {
    return []
  },

  addPasteRules() {
    return []
  }
})
