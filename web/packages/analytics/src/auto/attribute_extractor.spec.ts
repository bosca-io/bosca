// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { extractAttributes } from './attribute_extractor'

describe('extractAttributes', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  describe('extras', () => {
    it('should extract data-ba-* attributes from the element', () => {
      document.body.innerHTML = '<button data-ba-campaign="spring" data-ba-variant="b">Buy</button>'
      const { extras } = extractAttributes(document.querySelector('button')!)
      expect(extras).toEqual({ campaign: 'spring', variant: 'b' })
    })

    it('should convert kebab-case attribute names to snake_case keys', () => {
      document.body.innerHTML = '<div data-ba-user-segment="premium"></div>'
      const { extras } = extractAttributes(document.querySelector('div')!)
      expect(extras).toEqual({ user_segment: 'premium' })
    })

    it('should map the recommendation experiment attribute to its goal key', () => {
      document.body.innerHTML = '<div data-ba-experiment-source="recommendations"></div>'
      const { extras } = extractAttributes(document.querySelector('div')!)
      expect(extras).toEqual({ experiment_source: 'recommendations' })
    })

    it('carries recommendation request provenance from a card into its child events', () => {
      document.body.innerHTML = `
        <article data-ba-recommendation-source-id="source-item"
                 data-ba-recommendation-context="reading"
                 data-ba-recommendation-model-version="17"
                 data-ba-recommendation-request-id="request-17"
                 data-ba-content-id="recommended-item" data-ba-content-type="metadata">
          <button>Read</button>
        </article>
      `
      const { extras, content } = extractAttributes(document.querySelector('button')!)
      expect(extras).toEqual({
        recommendation_source_id: 'source-item',
        recommendation_context: 'reading',
        recommendation_model_version: '17',
        recommendation_request_id: 'request-17',
      })
      expect(content).toEqual([{ id: 'recommended-item', type: 'metadata' }])
    })

    it('should merge extras from ancestor elements', () => {
      document.body.innerHTML = `
        <div data-ba-section="hero">
          <button data-ba-action="signup">Sign Up</button>
        </div>
      `
      const { extras } = extractAttributes(document.querySelector('button')!)
      expect(extras).toEqual({ action: 'signup', section: 'hero' })
    })

    it('should prefer child attributes over ancestor attributes', () => {
      document.body.innerHTML = `
        <div data-ba-source="parent">
          <span data-ba-source="child">Text</span>
        </div>
      `
      const { extras } = extractAttributes(document.querySelector('span')!)
      expect(extras.source).toBe('child')
    })

    it('should return empty object when no data-ba-* attributes exist', () => {
      document.body.innerHTML = '<div id="plain"><span>Text</span></div>'
      const { extras } = extractAttributes(document.querySelector('span')!)
      expect(extras).toEqual({})
    })

    it('should ignore non-bosca data attributes', () => {
      document.body.innerHTML = '<div data-testid="foo" data-ba-real="yes"></div>'
      const { extras } = extractAttributes(document.querySelector('div')!)
      expect(extras).toEqual({ real: 'yes' })
    })

    it('should exclude content-reserved attributes from extras', () => {
      document.body.innerHTML =
        '<div data-ba-content-id="p1" data-ba-content-type="product" data-ba-campaign="fall"></div>'
      const { extras } = extractAttributes(document.querySelector('div')!)
      expect(extras).toEqual({ campaign: 'fall' })
    })
  })

  describe('content', () => {
    it('should extract a content element from data-ba-content-* attributes', () => {
      document.body.innerHTML = '<div data-ba-content-id="product-123" data-ba-content-type="product"></div>'
      const { content } = extractAttributes(document.querySelector('div')!)
      expect(content).toEqual([{ id: 'product-123', type: 'product' }])
    })

    it('should include index and percent when present', () => {
      document.body.innerHTML =
        '<div data-ba-content-id="item-1" data-ba-content-type="article" data-ba-content-index="3" data-ba-content-percent="75"></div>'
      const { content } = extractAttributes(document.querySelector('div')!)
      expect(content).toEqual([
        { id: 'item-1', type: 'article', index: 3, percent: 75 },
      ])
    })

    it('should collect content from nested ancestors (child first)', () => {
      document.body.innerHTML = `
        <div data-ba-content-id="category-5" data-ba-content-type="category">
          <div data-ba-content-id="product-10" data-ba-content-type="product">
            <button>Add to Cart</button>
          </div>
        </div>
      `
      const { content } = extractAttributes(document.querySelector('button')!)
      expect(content).toHaveLength(2)
      expect(content[0]).toEqual({ id: 'product-10', type: 'product' })
      expect(content[1]).toEqual({ id: 'category-5', type: 'category' })
    })

    it('should return empty array when no content attributes exist', () => {
      document.body.innerHTML = '<span>Plain text</span>'
      const { content } = extractAttributes(document.querySelector('span')!)
      expect(content).toEqual([])
    })

    it('should default type to empty string when missing', () => {
      document.body.innerHTML = '<div data-ba-content-id="abc"></div>'
      const { content } = extractAttributes(document.querySelector('div')!)
      expect(content).toEqual([{ id: 'abc', type: '' }])
    })
  })

  describe('shared empty result', () => {
    it('should return the same object reference when no attributes found', () => {
      document.body.innerHTML = '<div><span>A</span><span>B</span></div>'
      const a = extractAttributes(document.querySelectorAll('span')[0])
      const b = extractAttributes(document.querySelectorAll('span')[1])
      expect(a).toBe(b)
    })
  })

  describe('combined extraction', () => {
    it('should collect extras and content in a single walk', () => {
      document.body.innerHTML = `
        <section data-ba-section="featured" data-ba-content-id="cat-1" data-ba-content-type="category">
          <div data-ba-content-id="prod-5" data-ba-content-type="product">
            <button data-ba-action="buy">Buy</button>
          </div>
        </section>
      `
      const { extras, content } = extractAttributes(document.querySelector('button')!)
      expect(extras).toEqual({ action: 'buy', section: 'featured' })
      expect(content).toEqual([
        { id: 'prod-5', type: 'product' },
        { id: 'cat-1', type: 'category' },
      ])
    })
  })
})
