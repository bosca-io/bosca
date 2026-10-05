import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import CommentThreadNode from './CommentThreadNode.vue'

const mutationMock = vi.fn().mockResolvedValue({})

vi.stubGlobal('useGraphQL', () => ({
  mutation: mutationMock,
  query: vi.fn().mockResolvedValue({}),
}))

vi.stubGlobal('useProfileSearch', () => ({
  searchProfiles: vi.fn().mockResolvedValue([]),
}))

const stubs = {
  Icon: { template: '<i />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span class="badge"><slot /></span>', props: ['color'] },
  Tooltip: { template: '<div><slot /></div>', props: ['content'] },
  Select: {
    template: '<div class="mock-select" />',
    props: ['modelValue', 'options', 'size', 'accent', 'disabled', 'onSearch', 'searchable', 'placeholder'],
  },
  Textarea: { template: '<textarea />', props: ['modelValue', 'rows', 'placeholder'] },
  Button: {
    template: '<button :data-icon="icon" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['icon', 'size', 'accent', 'disabled', 'primary'],
    emits: ['click'],
  },
  ConfirmModal: {
    template: `
      <div class="confirm-modal">
        <div class="confirm-modal-body"><slot /></div>
        <button class="confirm-modal-confirm" @click="$emit('confirm')" />
        <button class="confirm-modal-cancel" @click="$emit('close')" />
      </div>`,
    props: ['title', 'subtitle', 'confirmLabel', 'loading'],
    emits: ['close', 'confirm'],
  },
}

const baseComment = {
  id: 7,
  content: 'A comment that should require confirmation before deletion.',
  status: 'APPROVED',
  created: '2026-06-01T12:00:00Z',
  likes: 0,
  visibility: 'PUBLIC',
  profile: { id: 'p1', name: 'Alice' },
}

function mountNode(comment: Record<string, unknown> = {}) {
  return mount(CommentThreadNode, {
    props: {
      comment: { ...baseComment, ...comment },
      metadataId: 'm1',
      version: 1,
      accent: '#5ec5ff',
      isRoot: true,
    },
    global: { stubs },
  })
}

const flush = () => new Promise(r => setTimeout(r, 0))

beforeEach(() => {
  mutationMock.mockClear()
  mutationMock.mockResolvedValue({})
})

describe('CommentThreadNode delete confirmation', () => {
  it('opens the confirmation modal instead of deleting on trash click', async () => {
    const w = mountNode()
    expect(w.find('.confirm-modal').exists()).toBe(false)
    await w.find('button[data-icon="trash"]').trigger('click')
    expect(w.find('.confirm-modal').exists()).toBe(true)
    expect(mutationMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('shows the author and a content excerpt in the modal body', async () => {
    const w = mountNode()
    await w.find('button[data-icon="trash"]').trigger('click')
    const body = w.find('.confirm-modal-body').text()
    expect(body).toContain('Alice')
    expect(body).toContain('A comment that should require confirmation before deletion.')
    w.unmount()
  })

  it('truncates long comment content in the excerpt', async () => {
    const w = mountNode({ content: 'x'.repeat(200) })
    await w.find('button[data-icon="trash"]').trigger('click')
    const quote = w.find('.confirm-quote').text()
    expect(quote).toBe(`${'x'.repeat(140)}…`)
    w.unmount()
  })

  it('mentions replies only when the comment has replies', async () => {
    const withReplies = mountNode({
      replies: { comments: [{ ...baseComment, id: 8, content: 'a reply' }] },
    })
    await withReplies.find('button[data-icon="trash"]').trigger('click')
    expect(withReplies.find('.confirm-modal-body').text()).toContain('Replies to it will no longer appear')
    withReplies.unmount()

    const withoutReplies = mountNode()
    await withoutReplies.find('button[data-icon="trash"]').trigger('click')
    expect(withoutReplies.find('.confirm-modal-body').text()).not.toContain('Replies to it will no longer appear')
    withoutReplies.unmount()
  })

  it('runs the delete mutation, emits changed, and closes the modal on confirm', async () => {
    const w = mountNode()
    await w.find('button[data-icon="trash"]').trigger('click')
    await w.find('.confirm-modal-confirm').trigger('click')
    await flush()
    expect(mutationMock).toHaveBeenCalledTimes(1)
    expect(mutationMock).toHaveBeenCalledWith(expect.anything(), {
      commentId: 7,
      metadataId: 'm1',
      metadataVersion: 1,
    })
    expect(w.emitted('changed')).toBeTruthy()
    expect(w.find('.confirm-modal').exists()).toBe(false)
    w.unmount()
  })

  it('closes the modal without deleting on cancel', async () => {
    const w = mountNode()
    await w.find('button[data-icon="trash"]').trigger('click')
    await w.find('.confirm-modal-cancel').trigger('click')
    expect(w.find('.confirm-modal').exists()).toBe(false)
    expect(mutationMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('surfaces the error and closes the modal when the delete fails', async () => {
    mutationMock.mockRejectedValueOnce(new Error('boom'))
    const w = mountNode()
    await w.find('button[data-icon="trash"]').trigger('click')
    await w.find('.confirm-modal-confirm').trigger('click')
    await flush()
    await flush()
    expect(w.emitted('changed')).toBeFalsy()
    expect(w.find('.node-error').text()).toBe('boom')
    expect(w.find('.confirm-modal').exists()).toBe(false)
    w.unmount()
  })
})
