/* eslint-disable @typescript-eslint/no-explicit-any */
import type { Editor } from '@tiptap/core'
import type { Range } from '@tiptap/vue-3'

export class OpenMediaPickerEvent extends Event {
  readonly editor: Editor
  readonly range: Range | undefined
  constructor(editor: Editor, range: Range | undefined) {
    super(OpenMediaPickerEvent.NAME)
    this.editor = editor
    this.range = range
  }

  static readonly NAME = 'openMediaPicker'
}

export class NewContainerEvent extends Event {
  readonly range: Range | undefined
  constructor(range: Range | undefined) {
    super(NewContainerEvent.NAME)
    this.range = range
  }

  static readonly NAME = 'newContainer'
}

export class NewLinkEvent extends Event {
  readonly range: Range | undefined
  constructor(range: Range | undefined) {
    super(NewLinkEvent.NAME)
    this.range = range
  }

  static readonly NAME = 'newLink'
}

export interface CommandProperties {
  editor: Editor
  range?: Range
}

export interface CommandItem {
  label: string
  name?: string | null
  attributes?: Record<string, any>
  icon: string
  command: (_cmd: CommandProperties) => void
  isActive: (_cmd: CommandProperties) => boolean
}

function newCommand(editor: Editor, range: Range | undefined) {
  const chain = editor.chain().focus()
  if (range) {
    chain.deleteRange(range)
  }
  return chain
}

export const CommandItems = [
  {
    name: 'heading',
    label: 'Heading 2',
    attributes: { level: 2 },
    icon: 'heading2',
    command: ({ editor, range }) => {
      if (!editor.isActive('heading', { level: 2 })) {
        newCommand(editor, range).setNode('heading', { level: 2 }).run()
      } else {
        newCommand(editor, range).setNode('paragraph').run()
      }
    },
    isActive: ({ editor }) => {
      return editor.isActive({ level: 2 })
    }
  },
  {
    name: 'heading',
    label: 'Heading 3',
    attributes: { level: 3 },
    icon: 'heading3',
    command: ({ editor, range }) => {
      if (!editor.isActive('heading', { level: 3 })) {
        newCommand(editor, range).setNode('heading', { level: 3 }).run()
      } else {
        newCommand(editor, range).setNode('paragraph').run()
      }
    },
    isActive: ({ editor }) => {
      return editor.isActive({ level: 3 })
    }
  },
  {
    name: 'bold',
    label: 'Bold',
    icon: 'bold',
    command: ({ editor, range }) =>
      newCommand(editor, range).toggleBold().run(),
    isActive: ({ editor }) => {
      return editor.isActive('bold')
    }
  },
  {
    name: 'italic',
    label: 'Italic',
    icon: 'italic',
    command: ({ editor, range }) =>
      newCommand(editor, range).toggleItalic().run(),
    isActive: ({ editor }) => {
      return editor.isActive('italic')
    }
  },
  {
    name: 'strike',
    label: 'Strike',
    icon: 'strikethrough',
    command: ({ editor, range }) =>
      newCommand(editor, range).toggleStrike().run(),
    isActive: ({ editor }) => {
      return editor.isActive('strike')
    }
  },
  {
    name: 'underline',
    label: 'Underline',
    icon: 'underline',
    command: ({ editor, range }) =>
      newCommand(editor, range).toggleUnderline().run(),
    isActive: ({ editor }) => {
      return editor.isActive('underline')
    }
  },
  {
    label: 'Left Align',
    attributes: { textAlign: 'left' },
    icon: 'alignLeft',
    command: ({ editor, range }) =>
      editor.isActive({ textAlign: 'left' })
        ? newCommand(editor, range).unsetTextAlign().run()
        : newCommand(editor, range).setTextAlign('left').run(),
    isActive: ({ editor }) => {
      return editor.isActive({ textAlign: 'left' })
    }
  },
  {
    label: 'Center Align',
    attributes: { textAlign: 'center' },
    icon: 'alignCenter',
    command: ({ editor, range }) =>
      editor.isActive({ textAlign: 'center' })
        ? newCommand(editor, range).unsetTextAlign().run()
        : newCommand(editor, range).setTextAlign('center').run(),
    isActive: ({ editor }) => {
      return editor.isActive({ textAlign: 'center' })
    }
  },
  {
    label: 'Right Align',
    attributes: { textAlign: 'right' },
    icon: 'alignRight',
    command: ({ editor, range }) =>
      editor.isActive({ textAlign: 'right' })
        ? newCommand(editor, range).unsetTextAlign().run()
        : newCommand(editor, range).setTextAlign('right').run(),
    isActive: ({ editor }) => {
      return editor.isActive({ textAlign: 'right' })
    }
  },
  {
    label: 'Justify',
    attributes: { textAlign: 'justify' },
    icon: 'alignJustify',
    command: ({ editor, range }) =>
      editor.isActive({ textAlign: 'justify' })
        ? newCommand(editor, range).unsetTextAlign().run()
        : newCommand(editor, range).setTextAlign('justify').run(),
    isActive: ({ editor }) => {
      return editor.isActive({ textAlign: 'justify' })
    }
  },
  {
    name: 'blockquote',
    label: 'Blockquote',
    icon: 'quote',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleBlockquote().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('blockquote')
    }
  },
  {
    name: 'link',
    label: 'Link',
    icon: 'link',
    command: ({ range }) => {
      globalThis.dispatchEvent(new NewLinkEvent(range))
    },
    isActive: ({ editor }) => {
      return editor.isActive('link')
    }
  },
  {
    name: 'superscript',
    label: 'Superscript',
    icon: 'superscript',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleSuperscript().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('superscript')
    }
  },
  {
    name: 'subscript',
    label: 'Subscript',
    icon: 'subscript',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleSubscript().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('subscript')
    }
  },
  {
    name: 'orderedlist',
    label: 'Ordered List',
    icon: 'listOrdered',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleOrderedList().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('orderedlist')
    }
  },
  {
    name: 'bulletlist',
    label: 'Bullet List',
    icon: 'list',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleBulletList().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('bulletlist')
    }
  },
  {
    name: 'taskList',
    label: 'Task List',
    icon: 'listChecks',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleTaskList().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('taskList')
    }
  },
  {
    name: 'container',
    label: 'Container',
    icon: 'container',
    command: ({ editor, range }) => {
      if (!editor.isActive('container')) {
        globalThis.dispatchEvent(new NewContainerEvent(range))
      } else {
        newCommand(editor, range).unsetContainer().run()
      }
    },
    isActive: ({ editor }) => {
      return editor.isActive('container')
    }
  },
  {
    label: 'Add Image',
    icon: 'image',
    command: ({ editor, range }) => {
      globalThis.dispatchEvent(new OpenMediaPickerEvent(editor, range))
    },
    isActive: () => {
      return false
    }
  },
  {
    name: 'codeBlock',
    label: 'Code Block',
    icon: 'code',
    command: ({ editor, range }) => {
      newCommand(editor, range).toggleCodeBlock().run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('codeBlock') && !editor.isActive('codeBlock', { language: 'mermaid' })
    }
  },
  {
    name: 'mermaid',
    label: 'Mermaid Diagram',
    icon: 'workflow',
    command: ({ editor, range }) => {
      newCommand(editor, range).setCodeBlock({ language: 'mermaid' }).run()
    },
    isActive: ({ editor }) => {
      return editor.isActive('codeBlock', { language: 'mermaid' })
    }
  }
] as CommandItem[]
