/**
 * La API y el panel. node:http pelado, sin framework: son veinte rutas y así el
 * paquete no arrastra ni una dependencia.
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import QRCode from 'qrcode'
import * as config from './config.js'
import * as store from './store.js'
import * as pipeline from './pipeline.js'
import * as notify from './notify.js'
import * as wake from './wake.js'
import * as stt from './engines/stt.js'
import * as tts from './engines/tts.js'
import * as llm from './engines/llm.js'
import * as sidecar from './sidecar.js'
import * as labels from './labels.js'

const WEB_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web')
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
}

let cfg = config.load()
export const getCfg = () => cfg

const PAIR_TTL_MS = 10 * 60 * 1000
const pairTickets = new Map()

function prunePairTickets() {
  const now = Date.now()
  for (const [code, ticket] of pairTickets) if (ticket.expiresAt <= now) pairTickets.delete(code)
}

function createPairTicket() {
  prunePairTickets()
  const code = crypto.randomBytes(24).toString('base64url')
  const expiresAt = Date.now() + PAIR_TTL_MS
  const server = publicUrl()
  const deepLink = `heladera://pair?server=${encodeURIComponent(server)}&code=${encodeURIComponent(code)}`
  const ticket = { code, server, deepLink, expiresAt }
  pairTickets.set(code, ticket)
  return ticket
}

function getPairTicket(code, consume = false) {
  prunePairTickets()
  const ticket = pairTickets.get(String(code || ''))
  if (ticket && consume) pairTickets.delete(ticket.code)
  return ticket || null
}

function json(res, code, body) {
  const data = JSON.stringify(body)
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Access-Control-Allow-Origin': '*',
  })
  res.end(data)
}

async function readBody(req, limit = 25 * 1024 * 1024) {
  const chunks = []
  let size = 0
  for await (const c of req) {
    size += c.length
    if (size > limit) throw new Error('cuerpo muy grande')
    chunks.push(c)
  }
  return Buffer.concat(chunks)
}

async function readJson(req) {
  const b = await readBody(req, 1024 * 1024)
  if (!b.length) return {}
  try { return JSON.parse(b.toString('utf8')) } catch { return {} }
}

/**
 * Extrae el primer archivo de un multipart/form-data. Escrito a mano para no
 * depender de nada: solo necesitamos el wav y un par de campos de texto.
 */
function parseMultipart(buf, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '')
  if (!m) return { fields: {}, file: null }
  const boundary = Buffer.from('--' + (m[1] || m[2]).trim())
  const fields = {}
  let file = null
  let start = buf.indexOf(boundary)
  while (start !== -1) {
    const from = start + boundary.length
    if (buf.slice(from, from + 2).toString() === '--') break
    const headEnd = buf.indexOf('\r\n\r\n', from)
    if (headEnd === -1) break
    const head = buf.slice(from, headEnd).toString('utf8')
    const next = buf.indexOf(boundary, headEnd)
    if (next === -1) break
    const body = buf.slice(headEnd + 4, next - 2)     // -2 come el \r\n final
    const name = /name="([^"]*)"/i.exec(head)?.[1]
    if (name) {
      if (/filename="/i.test(head)) file = body
      else fields[name] = body.toString('utf8')
    }
    start = next
  }
  return { fields, file }
}

function authorized(req, url) {
  if (!cfg.auth.enabled) return true
  // Las páginas se sirven sin token: no tienen datos adentro, y el token llega
  // en el fragmento de la URL (#token=), que el navegador NO manda al servidor.
  // Si pidiéramos token acá, la página moriría antes de poder leerlo.
  const open = [
    '/', '/admin', '/pair', '/style.css', '/common.js', '/favicon.ico', '/icon.svg', '/iconos.svg',
    '/api/labels', '/api/pair/qr',
    '/api/health', '/api/pair/claim',
  ]
  if (open.includes(url.pathname)) return true
  const hdr = req.headers.authorization || ''
  const token = hdr.startsWith('Bearer ') ? hdr.slice(7) : (url.searchParams.get('token') || '')
  if (!token) return false
  const a = Buffer.from(token), b = Buffer.from(cfg.auth.token)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

function serveStatic(res, file) {
  const full = path.join(WEB_DIR, file)
  if (!full.startsWith(WEB_DIR) || !fs.existsSync(full)) return json(res, 404, { error: 'no está' })
  res.writeHead(200, {
    'Content-Type': MIME[path.extname(full)] || 'application/octet-stream',
    // Sin cache: son cuatro archivos chicos en la red de casa, y una página
    // vieja pegada en el navegador cuesta más que volver a bajarla.
    'Cache-Control': 'no-store, must-revalidate',
  })
  fs.createReadStream(full).pipe(res)
}

// El idioma de la casa: lo usan los textos que salen por Telegram y por voz.
let idiomaAvisado = ''

async function route(req, res, url) {
  if (cfg.language !== idiomaAvisado) { idiomaAvisado = cfg.language; notify.setIdioma(cfg.language) }
  const p = url.pathname
  const POST = req.method === 'POST'

  // ---------------------------------------------------------------- páginas
  if (p === '/' && req.method === 'GET') return serveStatic(res, 'index.html')
  if (p === '/admin' && req.method === 'GET') return serveStatic(res, 'admin.html')
  if (p === '/pair' && req.method === 'GET') return serveStatic(res, 'pair.html')
  if ((p === '/style.css' || p === '/common.js') && req.method === 'GET') return serveStatic(res, p.slice(1))
  if (p === '/favicon.ico' && req.method === 'GET') return serveStatic(res, 'favicon.ico')
  // Las palabras del vocabulario, ya traducidas: la pantalla no sabe idiomas.
  if (p === '/api/labels' && req.method === 'GET') {
    return json(res, 200, labels.dictionary(cfg.language))
  }
  if (p === '/icon.svg' && req.method === 'GET') return serveStatic(res, 'icon.svg')
  if (p === '/iconos.svg' && req.method === 'GET') return serveStatic(res, 'iconos.svg')

  // ----------------------------------------------------------------- estado
  if (p === '/api/health') {
    return json(res, 200, {
      ok: true, version: 2,
      name: 'heladera',
      auth: cfg.auth.enabled,
      language: cfg.language,
      engines: { stt: cfg.stt.provider, tts: cfg.tts.provider, llm: `${cfg.llm.provider}/${cfg.llm.model}` },
      sidecar: sidecar.status(),
      ttsCache: tts.cacheStats(),
      ttsPhrases: tts.cachedPhrases(),
      telegram: cfg.telegram.enabled,
      wakeWords: cfg.wakeWords,
      ...store.state(),
    })
  }
  if (p === '/api/list' && req.method === 'GET') {
    return json(res, 200, { ...store.state(), question: pipeline.pendingQuestion() })
  }
  if (p === '/api/events' && req.method === 'GET') return json(res, 200, { events: store.events(Number(url.searchParams.get('limit')) || 50) })
  if (p === '/api/trips' && req.method === 'GET') return json(res, 200, { trips: store.trips() })

  // ------------------------------------------------------------------ ítems
  if (p === '/api/items' && POST) {
    const b = await readJson(req)
    if (!b.name) return json(res, 400, { error: 'falta name' })
    store.addItem({ ...b, source: b.source || 'manual' })
    store.log('add-manual', b.name)
    return json(res, 200, store.state())
  }
  let m
  if (p === '/api/items/reorder' && POST) {
    const b = await readJson(req)
    if (!Array.isArray(b.ids)) return json(res, 400, { error: 'falta ids' })
    // Arrastrar algo adentro o afuera de una sublista es el mismo gesto que
    // reordenar, así que el cliente manda también dónde quedó cada uno.
    if (b.groups && typeof b.groups === 'object') store.setGroups(b.groups)
    store.reorder(b.ids)
    return json(res, 200, store.state())
  }
  // -------------------------------------------------------------- sublistas
  if (p === '/api/items/bulk' && POST) {
    const b = await readJson(req)
    if (!Array.isArray(b.ids)) return json(res, 400, { error: 'falta ids' })
    store.snapshot('varias')
    const set = new Set(b.ids.map(Number))
    let n = 0
    for (const it of store.items()) {
      if (!set.has(it.id)) continue
      n++
      if (b.what === 'remove') store.removeItem(it.id)
      else store.updateItem(it.id, { done: b.what !== 'undone' })
    }
    store.log('bulk-' + (b.what || 'done'), '', { items: n })
    return json(res, 200, store.state())
  }
  if (p === '/api/items/group' && POST) {
    const b = await readJson(req)
    if (!Array.isArray(b.ids)) return json(res, 400, { error: 'falta ids' })
    store.snapshot('agrupar')
    const n = store.setGroup(b.ids, b.group)
    store.log('group', b.group || '(sueltos)', { items: n })
    return json(res, 200, store.state())
  }
  if ((m = /^\/api\/groups\/(.+)\/(done|undone|remove|ungroup)$/.exec(p)) && POST) {
    const name = decodeURIComponent(m[1])
    store.snapshot('grupo-' + m[2])
    const n = store.groupDo(name, m[2])
    if (!n) return json(res, 404, { error: 'no existe esa lista' })
    store.log('group-' + m[2], name, { items: n })
    return json(res, 200, store.state())
  }
  if (p === '/api/list/organizar' && POST) {
    const b = await readJson(req)
    store.snapshot('organizar')
    const n = b.deshacer ? store.desarmarTodo() : store.organizarPorCategoria()
    store.log('organizar', b.deshacer ? 'juntar' : 'por góndola', { items: n })
    return json(res, 200, store.state())
  }
  if (p === '/api/list/sort' && POST) {
    const b = await readJson(req)
    store.snapshot('ordenar')
    const n = store.sortBy(b.by)
    if (n === null) return json(res, 400, { error: 'orden desconocido' })
    store.log('sort', b.by, { items: n })
    return json(res, 200, store.state())
  }
  if ((m = /^\/api\/items\/(\d+)\/toggle$/.exec(p)) && POST) {
    const it = store.items().find(i => i.id === Number(m[1]))
    if (!it) return json(res, 404, { error: 'no existe' })
    store.updateItem(it.id, { done: !it.done })
    return json(res, 200, store.state())
  }
  if ((m = /^\/api\/items\/(\d+)\/star$/.exec(p)) && POST) {
    const it = store.items().find(i => i.id === Number(m[1]))
    if (!it) return json(res, 404, { error: 'no existe' })
    store.updateItem(it.id, { star: !it.star })
    return json(res, 200, store.state())
  }
  if ((m = /^\/api\/items\/(\d+)\/delete$/.exec(p)) && POST) {
    store.removeItem(Number(m[1]))
    return json(res, 200, store.state())
  }
  if ((m = /^\/api\/items\/(\d+)$/.exec(p)) && POST) {
    const it = store.updateItem(Number(m[1]), await readJson(req))
    return it ? json(res, 200, store.state()) : json(res, 404, { error: 'no existe' })
  }
  if (p === '/api/list/clear' && POST) {
    const b = await readJson(req)
    store.snapshot('manual')
    const n = store.clear(b.scope || 'all')
    store.log('clear', b.scope || 'all', { borrados: n })
    return json(res, 200, { cleared: n, ...store.state() })
  }
  if (p === '/api/list/close' && POST) {
    store.snapshot('manual')
    const r = store.closeTrip((await readJson(req)).title)
    store.log('close-trip', '', r)
    return json(res, 200, { trip: r, ...store.state() })
  }
  if (p === '/api/undo' && POST) {
    const r = store.undo()
    if (!r) return json(res, 404, { error: 'no hay nada para deshacer' })
    store.log('undo', r.reason || '', r)
    return json(res, 200, { undone: r, ...store.state() })
  }

  // -------------------------------------------------------------------- voz
  if (p === '/api/text' && POST) {
    const b = await readJson(req)
    if (!b.text) return json(res, 400, { error: 'falta text' })
    return json(res, 200, await pipeline.turn(cfg, b.text, {
      speak: !!b.speak, source: b.source || 'texto', lista: b.list || null,
    }))
  }

  if (p === '/api/voice' && POST) {
    const t0 = Date.now()
    const buf = await readBody(req)
    const { fields, file } = parseMultipart(buf, req.headers['content-type'])
    if (!file || file.length < 2000) return json(res, 400, { error: 'audio muy corto' })
    const speak = ['1', 'true', 'yes'].includes(String(fields.speak || 'true').toLowerCase())
    let tr
    try {
      tr = await stt.transcribe(cfg, file)
    } catch (e) {
      return json(res, 503, { error: 'stt: ' + String(e.message || e), ...store.state() })
    }
    if (!tr.text) {
      const out = { transcript: '', reply: 'No te escuché, dale de nuevo.', engine: 'stt', actions: [], result: {}, ...store.state() }
      if (speak) { try { const a = await tts.speak(cfg, out.reply); if (a) out.audioUrl = '/api/audio/' + path.basename(a.file) } catch { /* sin voz */ } }
      return json(res, 200, out)
    }
    const out = await pipeline.turn(cfg, tr.text, {
      speak, source: fields.source || 'tablet', tiempos: { stt: tr.ms, t0 },
      lista: fields.list || null,
    })
    out.stt = { ms: tr.ms, model: tr.model, provider: tr.provider }
    return json(res, 200, out)
  }

  // Transcripción parcial: la tablet manda lo que lleva grabado cada ~1,2 s para
  // que veas el texto aparecer mientras hablás. Usa el modelo rápido y no toca
  // la lista: es solo para mirar.
  if (p === '/api/partial' && POST) {
    const buf = await readBody(req)
    const { file } = parseMultipart(buf, req.headers['content-type'])
    if (!file || file.length < 1500) return json(res, 200, { text: '' })
    try {
      const tr = await stt.transcribe(cfg, file, { fast: true })
      return json(res, 200, { text: tr.text, ms: tr.ms })
    } catch (e) {
      return json(res, 200, { text: '', error: String(e.message || e) })
    }
  }

  if (p === '/api/wake' && POST) {
    const buf = await readBody(req)
    const { file } = parseMultipart(buf, req.headers['content-type'])
    if (!file || file.length < 2000) return json(res, 200, { awake: false, text: '' })
    try {
      const tr = await stt.transcribe(cfg, file, { fast: true })
      const hit = wake.match(tr.text, cfg.wakeWords)
      if (hit) { store.log('wake', tr.text); llm.warm(cfg) }
      return json(res, 200, { awake: !!hit, matched: hit, text: tr.text, ms: tr.ms })
    } catch (e) {
      return json(res, 200, { awake: false, text: '', error: String(e.message || e) })
    }
  }

  if (p === '/api/tts' && POST) {
    const b = await readJson(req)
    if (!b.text) return json(res, 400, { error: 'falta text' })
    try {
      // The admin can audition its current form values without persisting them.
      const previewCfg = b.tts && typeof b.tts === 'object'
        ? { ...cfg, tts: { ...cfg.tts, ...b.tts } }
        : cfg
      const a = await tts.speak(previewCfg, b.text)
      if (!a) return json(res, 200, { audioUrl: null, provider: previewCfg.tts.provider })
      return json(res, 200, { audioUrl: '/api/audio/' + path.basename(a.file), provider: a.provider, cached: a.cached })
    } catch (e) {
      return json(res, 503, { error: String(e.message || e) })
    }
  }

  if ((m = /^\/api\/audio\/([\w.-]+\.wav)$/.exec(p))) {
    const full = path.join(config.AUDIO_DIR, m[1])
    if (!full.startsWith(config.AUDIO_DIR) || !fs.existsSync(full)) return json(res, 404, { error: 'no está' })
    res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': fs.statSync(full).size })
    return fs.createReadStream(full).pipe(res)
  }

  if (p === '/api/confirm' && POST) {
    const b = await readJson(req)
    return json(res, 200, await pipeline.confirm(cfg, b.yes !== false, !!b.speak))
  }

  if (p === '/api/warm' && POST) { llm.warm(cfg); return json(res, 200, { ok: true }) }

  // ---------------------------------------------------------------- salidas
  if (p === '/api/send' && POST) {
    const b = await readJson(req)
    const lista = b.group ? store.items().filter(i => store.groupOf(i) === b.group) : store.items()
    return json(res, 200, { sent: await notify.send(cfg, b.channel || 'telegram', lista, b.group || null), ...store.state() })
  }
  if (p === '/api/outbox' && req.method === 'GET') {
    return json(res, 200, { messages: store.outboxPending(url.searchParams.get('channel'), 10) })
  }
  if ((m = /^\/api\/outbox\/(\d+)\/ack$/.exec(p)) && POST) {
    const b = await readJson(req)
    store.outboxMark(Number(m[1]), b.ok === false ? 'error' : 'sent', b.error || null)
    return json(res, 200, { ok: true })
  }

  // ------------------------------------------------------------------ admin
  if (p === '/api/config' && req.method === 'GET') return json(res, 200, config.redact(cfg))
  if (p === '/api/config' && POST) {
    const patch = await readJson(req)
    // Las claves vienen ofuscadas al leer: si no la cambiaron, conservamos la real
    for (const part of ['stt', 'tts', 'llm']) {
      if (patch[part]?.apiKey?.startsWith('••••')) delete patch[part].apiKey
    }
    if (patch.telegram?.token?.startsWith('••••')) delete patch.telegram.token
    cfg = config.save({ ...cfg, ...deepMergeShallow(cfg, patch) })
    store.log('config', 'actualizada desde el panel')
    if (cfg.sidecar.autostart) sidecar.ensure(cfg).catch(() => {})
    return json(res, 200, config.redact(cfg))
  }
  if (p === '/api/config/check' && POST) {
    const [s, t, l] = await Promise.all([stt.check(cfg), tts.check(cfg), llm.check(cfg)])
    return json(res, 200, { stt: s, tts: t, llm: l, sidecar: sidecar.status() })
  }
  if (p === '/api/config/models' && req.method === 'GET') return json(res, 200, { models: await llm.models(cfg) })
  if (p === '/api/tts/cache' && req.method === 'DELETE') { tts.cacheClear(); return json(res, 200, { ok: true }) }
  if (p === '/api/tts/prewarm' && POST) return json(res, 200, { cached: await tts.prewarm(cfg) })
  if (p === '/api/sidecar/start' && POST) { await sidecar.ensure(cfg); return json(res, 200, sidecar.status()) }
  if (p === '/api/sidecar/stop' && POST) { sidecar.stop(); return json(res, 200, sidecar.status()) }
  if (p === '/api/sidecar/log' && req.method === 'GET') return json(res, 200, { log: sidecar.tail() })

  // ---------------------------------------------------------------- pairing
  if (p === '/api/pair/create' && POST) {
    const ticket = createPairTicket()
    return json(res, 200, {
      expiresAt: ticket.expiresAt,
      pageUrl: `/pair#code=${encodeURIComponent(ticket.code)}`,
      qrUrl: `/api/pair/qr?code=${encodeURIComponent(ticket.code)}`,
    })
  }
  if (p === '/api/pair/qr' && req.method === 'GET') {
    const ticket = getPairTicket(url.searchParams.get('code'))
    if (!ticket) return json(res, 404, { error: 'QR vencido' })
    const svg = await QRCode.toString(ticket.deepLink, {
      type: 'svg', errorCorrectionLevel: 'M', margin: 1,
      color: { dark: '#0B0F0A', light: '#F2F5EE' },
    })
    res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' })
    return res.end(svg)
  }
  if (p === '/api/pair/claim' && POST) {
    const b = await readJson(req)
    if (!cfg.auth.enabled) return json(res, 200, { server: publicUrl(), token: '' })
    const ticket = getPairTicket(b.code, true)
    if (ticket) return json(res, 200, { server: ticket.server, token: cfg.auth.token })
    if (b.code !== cfg.auth.token.slice(0, 8)) return json(res, 403, { error: 'código inválido' })
    return json(res, 200, { server: publicUrl(), token: cfg.auth.token })
  }

  return json(res, 404, { error: 'no existe esa ruta' })
}

function deepMergeShallow(base, patch) {
  const out = {}
  for (const [k, v] of Object.entries(patch || {})) {
    out[k] = (v && typeof v === 'object' && !Array.isArray(v) && typeof base[k] === 'object' && !Array.isArray(base[k]))
      ? { ...base[k], ...v } : v
  }
  return out
}

export function lanIp() {
  const nets = Object.values(os.networkInterfaces()).flat()
  const v4 = nets.filter(n => n && n.family === 'IPv4' && !n.internal)
  return (v4.find(n => /^192\.168\.|^10\./.test(n.address)) || v4[0])?.address || '127.0.0.1'
}

export function publicUrl() { return `http://${lanIp()}:${cfg.port}` }

export function start(options = {}) {
  cfg = config.load()
  config.ensureHome()
  store.loadDb()

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x')
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,Authorization',
      })
      return res.end()
    }
    if (!authorized(req, url)) return json(res, 401, { error: 'falta el token' })
    try {
      await route(req, res, url)
    } catch (e) {
      if (!res.headersSent) json(res, 500, { error: String(e.message || e) })
    }
  })

  return new Promise(resolve => {
    server.listen(cfg.port, cfg.host, async () => {
      if (cfg.sidecar.autostart && (cfg.stt.provider === 'local' || cfg.tts.provider === 'local')) {
        sidecar.ensure(cfg).catch(e => console.error('sidecar:', e.message))
      }
      llm.warm(cfg)
      tts.prewarm(cfg).catch(() => {})
      resolve({ server, cfg, url: publicUrl() })
    })
  })
}
