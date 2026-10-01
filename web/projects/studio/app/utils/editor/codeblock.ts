import type { Component } from 'vue'
import CodeBlockBase from '@tiptap/extension-code-block'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import DocumentCodeBlockNode from '~/components/document/DocumentCodeBlockNode.vue'

export const CodeBlock = CodeBlockBase.extend({
  addNodeView() {
    return VueNodeViewRenderer(DocumentCodeBlockNode as Component)
  },
})
