const {test} = require('node:test')
const assert = require('node:assert/strict')
const {parseSupportedUrls, isSupportedUrl, fetchSupportedImage, ImageUrlNotAllowed} = require('../lib/allowed-url.js')

test('URL prefixes restrict origins and preserve paths and queries', () => {
    const prefixes = parseSupportedUrls(['https://example.com/images/', 'http://localhost:8000/public/?key=example'])
    assert.equal(isSupportedUrl(new URL('https://EXAMPLE.com/images/image.png?size=100'), prefixes), true)
    assert.equal(isSupportedUrl(new URL('http://localhost:8000/public/?key=example&name=image'), prefixes), true)
    for (const url of [
        'https://example.com.attacker.invalid/images/image.png',
        'https://example.com@attacker.invalid/images/image.png',
        'https://user:password@example.com/images/image.png',
        'http://example.com/images/image.png',
        'https://example.com:444/images/image.png',
        'https://example.com/private/image.png',
        'https://example.com/images/../private/image.png',
        'https://example.com/images/%2e%2e/private/image.png',
        'http://localhost:8000/public/?key=other',
        'file:///images/image.png',
    ]) assert.equal(isSupportedUrl(new URL(url), prefixes), false, url)
    assert.equal(isSupportedUrl(new URL('https://example.com/image.png'), []), false)
})

test('invalid configured prefixes are ignored while valid entries remain usable', () => {
    const prefixes = parseSupportedUrls(['', 'invalid', 'file:///tmp/', 'https://user@example.com/', 'https://example.com/#secret', ' https://example.com/ '])
    assert.equal(prefixes.length, 1)
    assert.equal(isSupportedUrl(new URL('https://example.com/image.png'), prefixes), true)
})

test('blocked origins are rejected before fetching', async () => {
    let requested = false
    await assert.rejects(fetchSupportedImage(new URL('https://example.com.attacker.invalid/image'), parseSupportedUrls(['https://example.com']), async () => {
        requested = true
        return new Response('image')
    }), ImageUrlNotAllowed)
    assert.equal(requested, false)
})

test('redirects cannot escape the configured origins or paths', async () => {
    for (const location of ['http://127.0.0.1/private', 'https://example.com.attacker.invalid/image', '/private/image']) {
        const requests = []
        await assert.rejects(fetchSupportedImage(new URL('https://example.com/images/start'), parseSupportedUrls(['https://example.com/images/']), async (url, options) => {
            requests.push(url.toString())
            assert.equal(options.redirect, 'manual')
            return new Response(null, {status: 302, headers: {Location: location}})
        }), ImageUrlNotAllowed)
        assert.deepEqual(requests, ['https://example.com/images/start'])
    }
})

test('allowed relative and cross-origin redirects reach the image', async () => {
    const responses = [
        new Response(null, {status: 301, headers: {Location: './next'}}),
        new Response(null, {status: 307, headers: {Location: 'https://cdn.example.com/image.png'}}),
        new Response('image'),
    ]
    const requests = []
    const response = await fetchSupportedImage(new URL('https://example.com/images/start'), parseSupportedUrls(['https://example.com/images/', 'https://cdn.example.com/']), async url => {
        requests.push(url.toString())
        return responses.shift()
    })
    assert.equal(await response.text(), 'image')
    assert.deepEqual(requests, ['https://example.com/images/start', 'https://example.com/images/next', 'https://cdn.example.com/image.png'])
})

test('redirect loops are bounded and failed responses are preserved', async () => {
    const prefixes = parseSupportedUrls(['https://example.com/'])
    const url = new URL('https://example.com/image')
    let requests = 0
    await assert.rejects(fetchSupportedImage(url, prefixes, async () => {
        requests++
        return new Response(null, {status: 302, headers: {Location: '/image'}})
    }), /Too many image redirects/)
    assert.equal(requests, 21)
    const response = new Response(null, {status: 404})
    assert.equal(await fetchSupportedImage(url, prefixes, async () => response), response)
    const missingLocation = new Response(null, {status: 302})
    assert.equal(await fetchSupportedImage(url, prefixes, async () => missingLocation), missingLocation)
})
