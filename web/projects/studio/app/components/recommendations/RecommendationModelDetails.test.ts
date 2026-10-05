import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RecommendationModelDetails from './RecommendationModelDetails.vue'
import { createRecommendationWeights } from '~/utils/recommendationWeights'

describe('RecommendationModelDetails', () => {
  it('shows captured configuration including zeros and disabled collections without editing controls', async () => {
    const context = {
      name: 'Historical context', type: 'reading', description: 'Original description',
      weights: { ...createRecommendationWeights(), defaultTypePreference: 0, typePreferences: [{ type: 'devotional', weight: 0.9 }] },
      contentFilter: { metadata: { includedContentTypePrefixes: ['text/'], excludedContentTypePrefixes: [], includedAttributeTypes: [], excludedAttributeTypes: ['thumbnail'] }, collections: null },
    }
    const wrapper = mount(RecommendationModelDetails, {
      props: { version: 12, revision: 3, context },
      global: { stubs: {
        Modal: { props: ['title', 'subtitle'], template: '<div>{{ title }} {{ subtitle }}<slot /><slot name="footer" /></div>' },
        SectionCard: { props: ['title'], template: '<section>{{ title }}<slot /></section>' },
        Button: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
      } },
    })
    expect(wrapper.text()).toContain('Model 12 configuration')
    expect(wrapper.text()).toContain('Context revision 3')
    for (const value of ['Historical context', 'Original description', 'text/', 'thumbnail', 'devotional', '0.9', 'Not eligible']) expect(wrapper.text()).toContain(value)
    expect(wrapper.findAll('dd').map(node => node.text())).toContain('0')
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
