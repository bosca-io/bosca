const {test} = require('node:test')
const assert = require('node:assert/strict')
const {parseSupportedUrls, isSupportedUrl, fetchSupportedImage, ImageUrlNotAllowed} = require('../lib/allowed-url.js')

test('scheme prefixes allow any host only for the configured HTTP schemes', () => {
    const http = parseSupportedUrls([' http:// '])
    const https = parseSupportedUrls([' HTTPS:// '])
    const both = parseSupportedUrls('http://,https://'.split(','))
    assert.equal(http.length, 1)
    assert.equal(https.length, 1)
    assert.equal(both.length, 2)
    for (const url of ['http://images.example/image.png', 'http://localhost:8000/private/image.png']) {
        assert.equal(isSupportedUrl(new URL(url), http), true, url)
        assert.equal(isSupportedUrl(new URL(url), https), false, url)
        assert.equal(isSupportedUrl(new URL(url), both), true, url)
    }
    for (const url of ['https://images.example/image.png', 'https://cdn.example:8443/image.png?key=image']) {
        assert.equal(isSupportedUrl(new URL(url), https), true, url)
        assert.equal(isSupportedUrl(new URL(url), http), false, url)
        assert.equal(isSupportedUrl(new URL(url), both), true, url)
    }
    for (const url of ['file:///tmp/image.png', 'ftp://images.example/image.png', 'https://user:password@images.example/image.png']) {
        assert.equal(isSupportedUrl(new URL(url), both), false, url)
    }
})

test('scheme prefixes can be combined with restricted URL prefixes', () => {
    const prefixes = parseSupportedUrls(['https://', 'http://images.example/allowed/'])
    assert.equal(isSupportedUrl(new URL('https://cdn.example/image.png'), prefixes), true)
    assert.equal(isSupportedUrl(new URL('http://images.example/allowed/image.png'), prefixes), true)
    assert.equal(isSupportedUrl(new URL('http://images.example/private/image.png'), prefixes), false)
    assert.equal(isSupportedUrl(new URL('http://other.example/allowed/image.png'), prefixes), false)
})

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

test('scheme prefixes are enforced on redirect destinations', async () => {
    const requests = []
    await assert.rejects(fetchSupportedImage(new URL('https://images.example/start'), parseSupportedUrls(['https://']), async url => {
        requests.push(url.toString())
        return new Response(null, {status: 302, headers: {Location: 'http://images.example/image.png'}})
    }), ImageUrlNotAllowed)
    assert.deepEqual(requests, ['https://images.example/start'])
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
