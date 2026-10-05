// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { getElementIdentifier, getElementType, getElementText } from './element_identifier'

describe('getElementIdentifier', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('should use data-ba-id when present', () => {
    document.body.innerHTML = '<div data-ba-id="hero-banner"><button>Click</button></div>'
    const button = document.querySelector('button')!
    const id = getElementIdentifier(button)
    expect(id).toBe('[data-ba-id="hero-banner"] > button')
  })

  it('should use element id when present', () => {
    document.body.innerHTML = '<div id="main"><span>Text</span></div>'
    const span = document.querySelector('span')!
    const id = getElementIdentifier(span)
    expect(id).toBe('#main > span')
  })

  it('should use nth-of-type for sibling elements of same tag', () => {
    document.body.innerHTML = '<ul><li>A</li><li>B</li><li>C</li></ul>'
    const secondLi = document.querySelectorAll('li')[1]
    const id = getElementIdentifier(secondLi)
    expect(id).toContain('li:nth-of-type(2)')
  })

  it('should handle element with no parent (detached node)', () => {
    const el = document.createElement('div')
    const id = getElementIdentifier(el)
    expect(id).toBe('div')
  })

  it('should handle deeply nested elements', () => {
    document.body.innerHTML = '<div id="root"><section><article><p>Hello</p></article></section></div>' // eslint-disable-line
    const p = document.querySelector('p')!
    const id = getElementIdentifier(p)
    expect(id).toBe('#root > section > article > p')
  })
})

describe('getElementType', () => {
  it('prefers an explicit analytics element type', () => {
    document.body.innerHTML = '<article data-ba-element-type="recommendation_item"></article>' // eslint-disable-line
    expect(getElementType(document.querySelector('article')!)).toBe('recommendation_item')
  })

  it('ignores an explicit analytics element type containing only whitespace', () => {
    document.body.innerHTML = '<article data-ba-element-type="   "></article>' // eslint-disable-line
    expect(getElementType(document.querySelector('article')!)).toBe('article')
  })

  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('should return "link" for anchor tags', () => {
    document.body.innerHTML = '<a href="/">Home</a>'
    expect(getElementType(document.querySelector('a')!)).toBe('link')
  })

  it('should return "button" for button tags', () => {
    document.body.innerHTML = '<button>Submit</button>'
    expect(getElementType(document.querySelector('button')!)).toBe('button')
  })

  it('should return "button" for submit controls', () => {
    document.body.innerHTML = '<input type="submit" />'
    expect(getElementType(document.querySelector('input')!)).toBe('button')
  })

  it('should return input type for input elements', () => {
    document.body.innerHTML = '<input type="email" />'
    expect(getElementType(document.querySelector('input')!)).toBe('input:email')
  })

  it('should fall back to text for an input without a resolved type', () => {
    const input = {
      getAttribute: () => null,
      tagName: 'INPUT',
      type: '',
    } as unknown as Element
    expect(getElementType(input)).toBe('input:text')
  })

  it.each([
    ['select', 'select'],
    ['textarea', 'textarea'],
    ['video', 'video'],
    ['audio', 'audio'],
    ['form', 'form'],
  ])('should return "%s" semantics for %s elements', (tag, expected) => {
    document.body.innerHTML = `<${tag}></${tag}>`
    expect(getElementType(document.querySelector(tag)!)).toBe(expected)
  })

  it('should return "image" for img tags', () => {
    document.body.innerHTML = '<img alt="photo" />'
    expect(getElementType(document.querySelector('img')!)).toBe('image')
  })

  it('should return tag name for unknown elements', () => {
    document.body.innerHTML = '<main>Content</main>'
    expect(getElementType(document.querySelector('main')!)).toBe('main')
  })
})

describe('getElementText', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('should prefer aria-label', () => {
    document.body.innerHTML = '<button aria-label="Close dialog">X</button>'
    expect(getElementText(document.querySelector('button')!)).toBe('Close dialog')
  })

  it('should suppress text when data-ba-no-text is present', () => {
    document.body.innerHTML = '<button data-ba-no-text aria-label="Private">Secret</button>'
    expect(getElementText(document.querySelector('button')!)).toBe('')
  })

  it('should truncate a long aria-label', () => {
    document.body.innerHTML = `<button aria-label="${'A'.repeat(20)}">X</button>`
    expect(getElementText(document.querySelector('button')!, 5)).toBe('AAAAA')
  })

  it('should fall back to title attribute', () => {
    document.body.innerHTML = '<div title="Tooltip text">Content</div>'
    expect(getElementText(document.querySelector('div')!)).toBe('Tooltip text')
  })

  it('should truncate a long title', () => {
    document.body.innerHTML = `<div title="${'T'.repeat(20)}">Content</div>`
    expect(getElementText(document.querySelector('div')!, 4)).toBe('TTTT')
  })

  it('should use and truncate alt text', () => {
    document.body.innerHTML = `<img alt="${'Z'.repeat(20)}" />`
    expect(getElementText(document.querySelector('img')!, 3)).toBe('ZZZ')
  })

  it('should use short alt text without truncating it', () => {
    document.body.innerHTML = '<img alt="Photo" />'
    expect(getElementText(document.querySelector('img')!)).toBe('Photo')
  })

  it('should fall back to text content', () => {
    document.body.innerHTML = '<span>Click here</span>'
    expect(getElementText(document.querySelector('span')!)).toBe('Click here')
  })

  it('should truncate long text', () => {
    const longText = 'A'.repeat(200)
    document.body.innerHTML = `<p>${longText}</p>`
    const text = getElementText(document.querySelector('p')!, 50)
    expect(text.length).toBe(50)
  })

  it('should return an empty string when text content is null', () => {
    const element = {
      hasAttribute: () => false,
      getAttribute: () => null,
      textContent: null,
    } as unknown as Element
    expect(getElementText(element)).toBe('')
  })
})
