const assert = require('node:assert/strict')
const { test } = require('node:test')
const sharp = require('sharp')
const { createImageServer } = require('../lib/main')

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
