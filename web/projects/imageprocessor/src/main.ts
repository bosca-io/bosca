import {fastify} from 'fastify'
import sharp from 'sharp'
import {encode} from 'blurhash'
import {fetchSupportedImage, ImageUrlNotAllowed, isSupportedUrl, parseSupportedUrls} from './allowed-url'

interface QueryOpts {
    u: string | undefined // url
    w: string | undefined // width
    h: string | undefined // height
    pw: string | undefined // percentage width
    ph: string | undefined // percentage height
    f: string | undefined // format
    ch: string | undefined // component X / width
    cw: string | undefined // component Y / height
    q: string | undefined // quality
    t: string | undefined // crop top
    l: string | undefined // crop left
}

type Resize = {
    width: number | undefined
    height: number | undefined
}

export function createImageServer(prefixes = process.env.SUPPORTED_URLS?.split(',') || []) {
    const supportedUrls = parseSupportedUrls(prefixes)
    const server = fastify()
    server.setErrorHandler((error, request, reply) => {
        if (error instanceof ImageUrlNotAllowed) {
            reply.status(401).send()
            return
        }
        console.error({error, request}, 'uncaught error')
        reply.status(500).send({ok: false})
    })
    server.get('/health', {}, async function (_, reply) {
        reply.code(200).send({ok: true})
    })
    server.get('/metadata', {}, async function (request, reply) {
        const opts = request.query as QueryOpts
        if (!opts.u) {
            reply.code(400).send()
            return
        }
        let imageUrl: URL
        try {
            imageUrl = new URL(opts.u)
        } catch {
            reply.code(400).send()
            return
        }
        if (!isSupportedUrl(imageUrl, supportedUrls)) {
            reply.code(401).send()
            return
        }
        const response = await fetchSupportedImage(imageUrl, supportedUrls)
        if (!response.ok || !response.body) {
            if (response.status === 404) {
                reply.code(404).send()
                return
            }
            reply.code(500).send()
            return
        }
        // @ts-ignore
        const image = await response.arrayBuffer()
        if (image.byteLength === 0) {
            reply.code(404).send()
            return
        }
        const transformer = sharp(image)
        const metadata = await transformer.metadata()
        delete metadata.exif
        delete metadata.icc
        delete metadata.iptc
        delete metadata.xmp
        const data = JSON.stringify(metadata)
        await reply.header('Content-Type', 'text/json').send(data)
    })
    server.get('/image', {}, async function (request, reply) {
        const opts = request.query as QueryOpts
        if (!opts.u) {
            reply.code(400).send()
            return
        }
        console.log('processing image', opts.u)
        let imageUrl: URL
        try {
            imageUrl = new URL(opts.u)
        } catch {
            reply.code(400).send()
            return
        }
        if (!isSupportedUrl(imageUrl, supportedUrls)) {
            reply.code(401).send()
            return
        }
        const response = await fetchSupportedImage(imageUrl, supportedUrls)
        if (!response.ok || !response.body) {
            if (response.status === 404) {
                reply.code(404).send()
                return
            }
            reply.code(500).send()
            return
        }
        // @ts-ignore
        const image = await response.arrayBuffer()
        if (image.byteLength === 0) {
            reply.code(404).send()
            return
        }
        let contentType = response.headers.get('Content-Type')
        let transformer = sharp(image)
        // @ts-ignore
        if (opts.w || opts.h) {
            let resize: Resize = {width: undefined, height: undefined}
            if (opts.w) {
                resize.width = parseInt(opts.w)
                if (isNaN(resize.width)) {
                    return reply.code(400).send()
                }
            }
            if (opts.h) {
                resize.height = parseInt(opts.h)
                if (isNaN(resize.height)) {
                    return reply.code(400).send()
                }
            }
            if (opts.t || opts.l) {
                const top = opts.t ? parseInt(opts.t) : 0
                const left = opts.l ? parseInt(opts.l) : 0
                if (top != 0 || left != 0) {
                    transformer = transformer.extract({
                        top: top || 0,
                        left: left || 0,
                        width: resize.width || 0,
                        height: resize.height || 0
                    })
                } else {
                    transformer = transformer.resize(resize)
                }
            } else {
                transformer = transformer.resize(resize)
            }
        }
        if (opts.pw || opts.ph) {
            let resize: Resize = {width: undefined, height: undefined}
            if (opts.pw) {
                resize.width = parseFloat(opts.pw)
                if (isNaN(resize.width)) {
                    return reply.code(400).send()
                }
            }
            if (opts.ph) {
                resize.height = parseFloat(opts.ph)
                if (isNaN(resize.height)) {
                    return reply.code(400).send()
                }
            }
            const {width, height} = await transformer.metadata()
            if (resize.width && width) {
                resize.width = Math.floor(resize.width * width)
            }
            if (resize.height && height) {
                resize.height = Math.floor(resize.height * height)
            }
            transformer = transformer.resize(resize)
        }
        switch (opts.f) {
            case 'blurhash': {
                const result = await transformer.raw().ensureAlpha().toBuffer({resolveWithObject: true})
                const img = Uint8ClampedArray.from(result.data)
                const cWidth = opts.cw ? parseInt(opts.cw) : 4
                const cHeight = opts.ch ? parseInt(opts.ch) : 4
                const blurhash = encode(img, result.info.width, result.info.height, cWidth, cHeight)
                return reply
                    .header('Content-Type', 'text/plain')
                    .send(blurhash)
            }
            case 'jpeg': {
                contentType = 'image/jpeg'
                const quality = opts.q ? parseInt(opts.q) : 80
                if (isNaN(quality)) {
                    return reply.code(400).send()
                }
                transformer = transformer.toFormat(sharp.format.jpeg, {
                    quality: quality,
                    mozjpeg: true,
                })
                break
            }
            case 'webp': {
                contentType = 'image/webp'
                const quality = opts.q ? parseInt(opts.q) : 80
                if (isNaN(quality)) {
                    return reply.code(400).send()
                }
                transformer = transformer.toFormat(sharp.format.webp, {
                    quality: quality,
                })
                break
            }
        }
        const newBuffer = await transformer.toBuffer()
        await reply.header('Content-Type', contentType).send(newBuffer)
    })
    return server
}

if (require.main === module) {
    void createImageServer().listen({host: '0.0.0.0', port: 8003}).then(() => {
        console.log('server listening on 0.0.0.0:8003')
    })
}
