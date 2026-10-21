const assert = require('node:assert/strict')
const { test } = require('node:test')
const sharp = require('sharp')
const { createImageServer } = require('../lib/main')

test('scheme-only SUPPORTED_URLS processes images and metadata through cross-origin redirects', async (t) => {
    const originalPrefixes = process.env.SUPPORTED_URLS
    process.env.SUPPORTED_URLS = 'http://,https://'
    const server = createImageServer()
    const originalFetch = globalThis.fetch
    t.after(async () => {
        globalThis.fetch = originalFetch
        if (originalPrefixes === undefined) delete process.env.SUPPORTED_URLS
        else process.env.SUPPORTED_URLS = originalPrefixes
        await server.close()
    })
    const png = await sharp({create: {width: 4, height: 4, channels: 3, background: '#ffffff'}}).png().toBuffer()
    const fetched = []
    globalThis.fetch = async (url, options) => {
        fetched.push(String(url))
        assert.equal(options.redirect, 'manual')
        if (url.hostname === 'images.example') return new Response(null, {
            status: 302, headers: {location: 'https://cdn.example/image.png'}
        })
        return new Response(png, {headers: {'Content-Type': 'image/png'}})
    }
    for (const protocol of ['http:', 'https:']) {
        const u = `${protocol}//images.example/source.png?expires=1792561298&signature=test`
        const resized = await server.inject({method: 'GET', url: '/image', query: {u, f: 'jpeg', w: '2', h: '2'}})
        assert.equal(resized.statusCode, 200)
        assert.equal(resized.headers['content-type'], 'image/jpeg')
        const size = await sharp(resized.rawPayload).metadata()
        assert.equal(size.width, 2)
        assert.equal(size.height, 2)
        const metadata = await server.inject({method: 'GET', url: '/metadata', query: {u}})
        assert.equal(metadata.statusCode, 200)
        assert.equal(metadata.json().width, 4)
        assert.equal(metadata.json().height, 4)
    }
    assert.equal(fetched.length, 8)
    assert.equal(fetched.filter(url => url === 'https://cdn.example/image.png').length, 4)
    for (const route of ['/image', '/metadata']) {
        for (const u of ['file:///tmp/image.png', 'https://user:password@images.example/image.png']) {
            assert.equal((await server.inject({method: 'GET', url: route, query: {u}})).statusCode, 401)
        }
    }
    assert.equal(fetched.length, 8)
})

test('both image routes enforce origin and redirect permissions before image processing', async (t) => {
    const server = createImageServer(['https://images.example/allowed/'])
    const originalFetch = globalThis.fetch
    t.after(async () => { globalThis.fetch = originalFetch; await server.close() })
    const png = await sharp({create: {width: 2, height: 2, channels: 3, background: '#ffffff'}}).png().toBuffer()
    const fetched = []
    globalThis.fetch = async (url, options) => {
        fetched.push(String(url))
        assert.equal(options.redirect, 'manual')
        if (url.pathname.endsWith('/redirect')) return new Response(null, {
            status: 302, headers: {location: 'http://127.0.0.1/private'}
        })
        return new Response(png, {headers: {'Content-Type': 'image/png'}})
    }
    for (const route of ['/image', '/metadata']) {
        for (const url of ['https://images.example.attacker/allowed/p.png', 'https://images.example/other/p.png']) {
            assert.equal((await server.inject({method: 'GET', url: route, query: {u: url}})).statusCode, 401)
        }
        assert.equal(fetched.length, route === '/image' ? 0 : 2)
        assert.equal((await server.inject({method: 'GET', url: route, query: {u: 'invalid'}})).statusCode, 400)
        assert.equal((await server.inject({method: 'GET', url: route, query: {u: 'https://images.example/allowed/redirect'}})).statusCode, 401)
        assert.equal((await server.inject({method: 'GET', url: route, query: {u: 'https://images.example/allowed/p.png'}})).statusCode, 200)
    }
    assert.equal(fetched.some(url => url.startsWith('http://127.0.0.1')), false)
})
