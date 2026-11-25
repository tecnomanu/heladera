/**
 * La lista, el historial y el log, en un JSON.
 *
 * Es una lista de compras de una casa: son decenas de ítems, no millones. Un
 * archivo con escritura atómica alcanza y sobra, y nos ahorra una dependencia
 * nativa que rompería `npm i -g` en la mitad de las máquinas.
 */
import fs from 'node:fs'
import path from 'node:path'
import { DATA_PATH, HOME } from './config.js'

const EMPTY = { seq: 1, items: [], trips: [], events: [], outbox: [], snapshots: [] }

let db = null
let writeTimer = null

export function loadDb() {
  if (db) return db
  try {
    db = { ...EMPTY, ...JSON.parse(fs.readFileSync(DATA_PATH, 'utf8')) }
  } catch {
    db = JSON.parse(JSON.stringify(EMPTY))
  }
  return db
}

function flush() {
  writeTimer = null
  fs.mkdirSync(HOME, { recursive: true })
  const tmp = DATA_PATH + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(db))
  fs.renameSync(tmp, DATA_PATH)
}

/** Agrupa escrituras: un turno de voz toca la lista varias veces seguidas. */
function persist() {
  if (writeTimer) return
  writeTimer = setTimeout(flush, 120)
}

export function flushNow() {
  if (writeTimer) clearTimeout(writeTimer)
  flush()
}

export const now = () => new Date().toISOString()

export function normalize(text) {
  let t = (text || '').trim().toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
  t = t.split(/\s+/).join(' ')
  if (t.length > 3 && t.endsWith('es')) t = t.slice(0, -2)
  else if (t.length > 3 && t.endsWith('s')) t = t.slice(0, -1)
  return t
}

// ------------------------------------------------------------------ ítems

/**
 * El orden de la lista es de Manu, no nuestro.
 *
 * Cada item lleva un `pos`. Nada lo toca solo: ni tachar, ni destachar, ni que
 * llegue algo por voz (eso va al final). Se mueve cuando lo arrastras o cuando
 * pedis un orden explicito. Antes los tachados saltaban abajo solos y marea.
 */
function ensurePos() {
  loadDb()
  if (db.items.every(i => typeof i.pos === 'number')) return
  // La primera vez respetamos lo que ya se veia en pantalla.
  const base = [...db.items].sort((a, b) => (a.done - b.done) || (a.id - b.id))
  base.forEach((it, i) => { it.pos = i + 1 })
  persist()
}

const nextPos = () => db.items.reduce((m, i) => Math.max(m, i.pos || 0), 0) + 1

/**
 * Todo lo que se compra tiene una medida.
 *
 * Si algo quedó sin cantidad (de antes, o de una carga a mano), le ponemos una
 * unidad: sin eso "sumame dos bananas más" no tiene a qué sumarle.
 */
function ensureUnidad() {
  let toco = false
  for (const it of db.items) {
    if (it.qty) continue
    it.qty = 1
    if (!it.unit) it.unit = 'u'
    toco = true
  }
  if (toco) persist()
}

export const groupOf = it => (it.group && String(it.group).trim()) || null

/**
 * Los renglones de un mismo grupo tienen que quedar pegados.
 *
 * Los sueltos se quedan donde están; cada grupo se junta donde apareció su
 * primer integrante. Asi el grupo se ve como un bloque con sangria y no como
 * cosas desparramadas por toda la lista.
 */
function compactar() {
  const list = [...db.items].sort((a, b) => (a.pos - b.pos) || (a.id - b.id))
  const out = []
  const fin = new Map()
  for (const it of list) {
    const g = groupOf(it)
    if (g === null || !fin.has(g)) {
      out.push(it)
      if (g !== null) fin.set(g, out.length)
      continue
    }
    const i = fin.get(g)
    out.splice(i, 0, it)
    for (const [k, v] of fin) if (v > i) fin.set(k, v + 1)
    fin.set(g, i + 1)
  }
  out.forEach((it, i) => { it.pos = i + 1 })
}

/** Los grupos en el orden en que se ven, con cuánto les falta. */
export function groups() {
  const vistos = new Map()
  for (const it of items()) {
    const g = groupOf(it)
    if (!g) continue
    if (!vistos.has(g)) vistos.set(g, { name: g, total: 0, pending: 0 })
    const e = vistos.get(g)
    e.total++
    if (!it.done) e.pending++
  }
  return [...vistos.values()]
}

/** Asigna sublistas de a varias sin tocar el orden: lo acomoda `reorder`. */
export function setGroups(mapa) {
  ensurePos()
  for (const it of db.items) {
    const g = mapa[it.id]
    if (g === undefined) continue
    it.group = (g && String(g).trim()) || null
  }
  persist()
}

/**
 * Separa lo pendiente en sublistas, una por góndola.
 *
 * La categoría ya la puso el modelo al anotar, así que "ordename esto en
 * listas" es simplemente usarla: lo que no tiene categoría queda suelto.
 */
export function organizarPorCategoria() {
  ensurePos()
  let n = 0
  for (const it of db.items) {
    if (it.done) continue
    const c = (it.cat || '').trim()
    if (!c || it.group === c) continue
    it.group = c
    n++
  }
  compactar()
  persist()
  return n
}

/** Deja todo suelto otra vez, sin sublistas. */
export function desarmarTodo() {
  ensurePos()
  let n = 0
  for (const it of db.items) if (it.group) { it.group = null; n++ }
  compactar()
  persist()
  return n
}

export function setGroup(ids, group) {
  ensurePos()
  const g = (group && String(group).trim()) || null
  const set = new Set(ids.map(Number))
  let n = 0
  for (const it of db.items) if (set.has(it.id)) { it.group = g; n++ }
  compactar()
  persist()
  return n
}

/** Tachar, destachar o borrar todo un grupo de una. */
export function groupDo(group, what) {
  ensurePos()
  const g = String(group || '').trim()
  const suyos = db.items.filter(i => groupOf(i) === g)
  if (!suyos.length) return 0
  if (what === 'remove' || what === 'ungroup') {
    if (what === 'remove') db.items = db.items.filter(i => groupOf(i) !== g)
    else for (const it of suyos) it.group = null
  } else {
    for (const it of suyos) { it.done = what === 'done'; it.doneAt = it.done ? now() : null }
  }
  compactar()
  persist()
  return suyos.length
}

export function items() {
  ensurePos()
  ensureUnidad()
  return [...db.items].sort((a, b) => (a.pos - b.pos) || (a.id - b.id))
}

/**
 * Reordena SOLO los que vinieron, sin tocar a los demás.
 *
 * Los que se mueven se reparten entre los lugares que ya ocupaban. Si en vez de
 * eso los pusiéramos a todos adelante, arrastrar algo mirando una sola sublista
 * le daría vuelta el orden a las otras, que ni estaban en pantalla.
 */
export function reorder(ids) {
  ensurePos()
  const orden = items()
  const byId = new Map(orden.map(it => [it.id, it]))
  const mueven = ids.map(Number).map(id => byId.get(id)).filter(Boolean)
  const set = new Set(mueven.map(it => it.id))
  const huecos = []
  orden.forEach((it, i) => { if (set.has(it.id)) huecos.push(i) })
  if (huecos.length !== mueven.length) return 0
  const salida = [...orden]
  huecos.forEach((i, k) => { salida[i] = mueven[k] })
  salida.forEach((it, i) => { it.pos = i + 1 })
  compactar()
  persist()
  return mueven.length
}

const COLL = new Intl.Collator('es', { sensitivity: 'base', numeric: true })

/**
 * Ordenar es una accion, no un modo: reescribe las posiciones una vez y listo.
 * Asi nada se reacomoda a tus espaldas mas tarde.
 */
export function sortBy(by) {
  ensurePos()
  const cmp = {
    cat: (a, b) => COLL.compare(a.cat || 'zzz', b.cat || 'zzz') || COLL.compare(a.name, b.name),
    abc: (a, b) => COLL.compare(a.name, b.name),
    // Sin fecha va al final: lo que tiene día es lo que apura.
    fecha: (a, b) => String(a.due || '9999').localeCompare(String(b.due || '9999'))
      || String(a.createdAt).localeCompare(String(b.createdAt)),
    star: (a, b) => (b.star ? 1 : 0) - (a.star ? 1 : 0)
      || String(b.starAt || '').localeCompare(String(a.starAt || '')) || (a.pos - b.pos),
    done: (a, b) => (a.done - b.done) || (a.pos - b.pos),
    nuevo: (a, b) => b.id - a.id,
  }[by]
  if (!cmp) return null
  const list = items()
  list.sort(cmp)
  list.forEach((it, i) => { it.pos = i + 1 })
  compactar()
  persist()
  return list.length
}

/**
 * exact: solo el mismo producto. Para anotar tiene que ser así: por parecido, "papa frita" caía encima de "papa" y le
 * pisaba el kilo. Para tachar o borrar sí sirve el parecido ("tachá el queso" → queso cremoso).
 */
export function findItem(name, { exact = false } = {}) {
  loadDb()
  const n = normalize(name)
  if (!n) return null
  const open = db.items.filter(i => !i.done)
  if (exact) return open.find(i => i.norm === n) || null
  return open.find(i => i.norm === n)
    || open.filter(i => i.norm.includes(n) || n.includes(i.norm)).sort((a, b) => b.norm.length - a.norm.length)[0]
    || db.items.find(i => i.norm === n)
    || null
}

export function addItem({ name, qty = null, unit = null, note = null, cat = null, group = null, source = 'voz' }) {
  ensurePos()
  name = (name || '').trim().split(/\s+/).join(' ')
  if (!name) throw new Error('nombre vacío')
  const g = (group && String(group).trim()) || null
  const existing = findItem(name, { exact: true })
  if (existing && !existing.done) {
    // Ya está en la lista: sumamos cantidad en vez de repetir el renglón.
    // Si ya lo compraste (tachado) no sumamos: eso es un pedido nuevo.
    if (qty) existing.qty = existing.unit === unit ? (existing.qty || 1) + qty : qty
    if (unit) existing.unit = unit
    if (note) existing.note = note
    if (cat && !existing.cat) existing.cat = cat
    // Si lo pediste para una sublista y estaba suelto, se muda: era eso lo que
    // querías, no dejarlo afuera y que parezca que no pasó nada.
    if (g && existing.group !== g) { existing.group = g; compactar() }
    persist()
    return existing
  }
  const item = {
    id: db.seq++, name, norm: normalize(name), qty, unit, note, cat,
    done: false, createdAt: now(), doneAt: null, source, pos: nextPos(),
    group: (group && String(group).trim()) || null,
    star: false, starAt: null, due: null,
  }
  db.items.push(item)
  if (item.group) compactar()
  persist()
  return item
}

export function updateItem(id, patch) {
  loadDb()
  const it = db.items.find(i => i.id === id)
  if (!it) return null
  Object.assign(it, patch)
  if (patch.name) it.norm = normalize(patch.name)
  if ('done' in patch) it.doneAt = patch.done ? now() : null
  // Guardamos cuándo la destacaste: "destacadas recientemente" lo necesita.
  if ('star' in patch) it.starAt = patch.star ? now() : null
  persist()
  return it
}

export function removeItem(id) {
  loadDb()
  const n = db.items.length
  db.items = db.items.filter(i => i.id !== id)
  persist()
  return db.items.length < n
}

export function clear(scope = 'all') {
  loadDb()
  const before = db.items.length
  db.items = scope === 'done' ? db.items.filter(i => !i.done) : []
  persist()
  return before - db.items.length
}

export function closeTrip(title = null) {
  loadDb()
  const trip = { id: db.trips.length + 1, closedAt: now(), title, items: items() }
  db.trips.unshift(trip)
  db.trips = db.trips.slice(0, 100)
  db.items = []
  persist()
  return { id: trip.id, count: trip.items.length }
}

export function trips(limit = 20) {
  loadDb()
  return db.trips.slice(0, limit)
}

// --------------------------------------------------------------- deshacer

export function snapshot(reason = '') {
  loadDb()
  db.snapshots.unshift({ ts: now(), reason, items: JSON.parse(JSON.stringify(db.items)) })
  db.snapshots = db.snapshots.slice(0, 10)
  persist()
}

export function undo() {
  loadDb()
  const snap = db.snapshots.shift()
  if (!snap) return null
  db.items = snap.items
  persist()
  return { restored: snap.items.length, reason: snap.reason }
}

// -------------------------------------------------------------- log y cola

export function log(kind, text = '', payload = null) {
  loadDb()
  db.events.unshift({ ts: now(), kind, text, payload })
  db.events = db.events.slice(0, 500)
  persist()
}

export function events(limit = 50) {
  loadDb()
  return db.events.slice(0, limit)
}

export function enqueue(channel, text) {
  loadDb()
  const msg = { id: db.seq++, ts: now(), channel, text, status: 'pending', sentAt: null, error: null }
  db.outbox.push(msg)
  persist()
  return msg.id
}

export function outboxPending(channel = null, limit = 10) {
  loadDb()
  return db.outbox.filter(m => m.status === 'pending' && (!channel || m.channel === channel)).slice(0, limit)
}

export function outboxMark(id, status, error = null) {
  loadDb()
  const m = db.outbox.find(x => x.id === id)
  if (!m) return false
  Object.assign(m, { status, error, sentAt: now() })
  db.outbox = db.outbox.filter(x => x.status === 'pending').concat(
    db.outbox.filter(x => x.status !== 'pending').slice(-50))
  persist()
  return true
}

// ------------------------------------------------------------------ estado

/** Hash del contenido *en orden*: si arrastras algo, el rev cambia y redibuja. */
export function rev() {
  const list = items()
  const s = list.map(i => [i.id, i.name, i.qty, i.unit, i.note, i.cat, i.group,
    i.due, i.star ? 1 : 0, i.done ? 1 : 0].join('|')).join('~')
  let h = 2166136261
  for (let k = 0; k < s.length; k++) { h ^= s.charCodeAt(k); h = Math.imul(h, 16777619) }
  return (h >>> 0).toString(36) + '-' + list.length
}

export function state() {
  const list = items()
  const pending = list.filter(i => !i.done).length
  return { items: list, rev: rev(), pending, done: list.length - pending, groups: groups() }
}
