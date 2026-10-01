import Document from '@tiptap/extension-document'
import StarterKit from '@tiptap/starter-kit'
import { Container } from '~/utils/editor/container'
import Link from '@tiptap/extension-link'
import Underline from '@tiptap/extension-underline'
import Superscript from '@tiptap/extension-superscript'
import Subscript from '@tiptap/extension-subscript'
import { Table } from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import TextAlign from '@tiptap/extension-text-align'
import Placeholder from '@tiptap/extension-placeholder'
import { Plugin } from '@tiptap/pm/state'
import type { Metadata, Profile } from '~/types/graphql'
import Commands from '~/utils/editor/commands'
import Suggestion from '~/utils/editor/suggestion'
import Mention from '@tiptap/extension-mention'
import { Bible } from '~/utils/editor/bible'
import { CodeBlock } from '~/utils/editor/codeblock'
import { Image } from '~/utils/editor/image'
import { mentionSuggestion } from '~/utils/editor/mentionSuggestion'
import { Collaboration } from '@tiptap/extension-collaboration'
import { CollaborationCursor } from '@tiptap/extension-collaboration-cursor'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { useAuth } from '@bosca/auth-client-browser'
import type * as Y from 'yjs'
import { markYDocReady } from '~/utils/editor/ydoc'

type EntitySearchFn = (_query: string) => Promise<{ id: string; label: string; entityType: 'metadata' | 'collection' | 'profile' }[]>
function mentionHref(entityType: string, id: string): string {
  switch (entityType) {
    case 'metadata': return `/cms/editor/${id}`
    case 'collection': return `/cms/collections/${id}`
    case 'profile': return `/audience/profiles/${id}`
    default: return `/cms/editor/${id}`
  }
}

export function newExtensions(
  metadata: Metadata,
  profile: Profile,
  ydoc: Y.Doc | null = null,
  onTitleUpdate?: (_title: string) => void,
  searchEntities?: EntitySearchFn,
  onSynced?: () => void,
) {
  const CustomDocument = Document.extend({
    content: metadata.documentTemplate?.schema || 'heading block+',
    addProseMirrorPlugins() {
      return [
        new Plugin({
          appendTransaction: (_, __, newState) => {
            const { doc, tr } = newState
            let h1Count = 0
            doc.descendants((node, pos) => {
              if (node.type.name === 'heading' && node.attrs.level === 1) {
                h1Count++
                if (h1Count > 1) {
                  tr.setNodeAttribute(pos, 'level', 2)
                }
                if (onTitleUpdate) {
                  onTitleUpdate(node.textContent)
                }
              }
            })
            return h1Count > 1 ? tr : null
          }
        })
      ]
    }
  })

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const extensions: any[] = [
    CustomDocument,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    StarterKit.configure({ document: false, history: false, underline: false, codeBlock: false } as any),
    CodeBlock,
    Container.configure({
      metadata: metadata,
      containers: metadata.document?.template?.documentTemplate?.containers
    }),
    // Image,
    Link.configure({
      openOnClick: false,
      autolink: true,
      defaultProtocol: 'https'
    }),
    Underline,
    Superscript,
    Subscript,
    Table.configure({ resizable: true }),
    TableRow,
    TableHeader,
    TableCell,
    Image,
    TaskList,
    TaskItem.configure({ nested: true }),
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    Placeholder.configure({
      showOnlyCurrent: false,
      placeholder: ({ node }) => {
        if (node.type.name === 'heading' && node.attrs.level === 1) {
          return 'Title...'
        }
        if (node.type.name === 'container') {
          return ''
        }
        return 'Type / to view options'
      }
    }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Commands.configure({ suggestion: Suggestion as any }),
    Bible,
  ]

  if (searchEntities) {
    const MentionExtension = Mention.extend({
      addAttributes() {
        return {
          ...this.parent?.(),
          entityType: { default: 'metadata', parseHTML: el => el.getAttribute('data-entity-type') },
        }
      },
    })
    extensions.push(
      MentionExtension.configure({
        HTMLAttributes: { class: 'mention' },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        renderHTML({ node, HTMLAttributes }: any) {
          const entityType = node.attrs.entityType || 'metadata'
          return ['a', {
            ...HTMLAttributes,
            'href': mentionHref(entityType, node.attrs.id),
            'data-mention-id': node.attrs.id,
            'data-entity-type': entityType,
          }, `@${node.attrs.label ?? node.attrs.id}`]
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        suggestion: mentionSuggestion(searchEntities) as any,
      }),
    )
  }

  if (ydoc) {
    const { auth } = useAuth()
    function randomColor(saturation = 100, lightness = 50): string {
      const hue = Math.floor(Math.random() * 360)
      return `hsl(${hue}, ${saturation}%, ${lightness}%)`
    }
    const provider = new HocuspocusProvider({
      url: '/collaboration',
      name: metadata.id + '.' + metadata.version,
      document: ydoc,
      token: async () => await auth.getToken() ?? '',
      onSynced: () => {
        // When the caller owns readiness (DocumentEditor waits for the editor
        // to exist so it can flush schema normalization first), defer to it —
        // marking ready here would let that flush count as an unsaved change.
        if (onSynced) {
          onSynced()
        } else {
          markYDocReady(ydoc)
        }
        window.dispatchEvent(new Event('document-synced'))
      },
      onAuthenticationFailed: () => {
        console.error('authentication failed')
      },
      onUnsyncedChanges: (ev) => {
        if (ev.number === 0) {
          window.dispatchEvent(new Event('document-synced'))
        } else {
          window.dispatchEvent(new Event('document-unsynced'))
        }
      },
      onClose: () => {
        console.warn('close')
      },
      onDisconnect: () => {
        console.warn('disconnected')
      }
    })
    // A token adopted from another tab replaces the one this connection presented.
    const sendToken = () => { void provider.sendToken() }
    const stopTokenRefresh = auth.on('tokenRefreshed', sendToken)
    const stopTokenAdopted = auth.on('tokenAdopted', sendToken)
    const stopSignOut = auth.on('signedOut', () => provider.destroy())
    provider.on('destroy', () => {
      stopTokenRefresh()
      stopTokenAdopted()
      stopSignOut()
    })
    extensions.push(Collaboration.configure({ document: ydoc }))
    extensions.push(CollaborationCursor.configure({
      provider: provider,
      user: {
        name: profile.name,
        color: randomColor()
      }
    }))
    onBeforeUnmount(() => {
      provider.destroy()
    })
  }

  return extensions
}
