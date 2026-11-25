/** Salidas: Telegram directo, y una cola para WhatsApp (la entrega un agente de APX vía mac-relay/). */
import * as store from './store.js'
import { categoryLabel, unitLabel, canonicalCategory } from './labels.js'

/**
 * Las categorías ahora las inventa el modelo, así que el emoji se busca sin
 * acentos y con lo que haya; si no está, va el cajón genérico. En pantalla no
 * usamos emojis, pero en Telegram y WhatsApp ayudan a barrer la lista de un ojo.
 */
const sinTilde = t => String(t || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
const CAT_EMOJI = {
  produce: '🥬', butcher: '🥩', bakery: '🥖', dairy: '🧊', pantry: '🛒',
  drinks: '🧉', cleaning: '🧽', pharmacy: '💊', pets: '🐶',
  hardware: '🔧', stationery: '✏️', other: '📦',
}
const emojiDe = cat => CAT_EMOJI[canonicalCategory(cat)] || '📦'

/**
 * Cómo se escribe una medida en una lista de compras.
 *
 * Guardamos la unidad canónica ('l') pero se lee "lts": nadie anota "1 l de
 * leche". La 'u' directamente no se escribe.
 */
// El idioma de la casa lo pone la configuración; acá solo se traduce.
let IDIOMA = 'es'
export const setIdioma = l => { IDIOMA = l || 'es' }

/** Para leer en voz alta va la palabra entera, no la abreviatura. */
const HABLADA = {
  kg: ['kilo', 'kilos'], g: ['gramo', 'gramos'], l: ['litro', 'litros'],
  ml: ['mililitro', 'mililitros'], docena: ['docena', 'docenas'],
  paquete: ['paquete', 'paquetes'], lata: ['lata', 'latas'], botella: ['botella', 'botellas'],
}

// Medio kilo se escribe "½ kg", no "0.5 kg"; el resto con coma, como acá.
const numero = q => q === 0.5 ? '½' : (q === Math.floor(q) ? String(Math.round(q)) : String(q).replace('.', ','))

export function qtyLabel(it) {
  if (!it.qty) return ''
  const u = unitLabel(it.unit, IDIOMA)
  return u ? `${numero(it.qty)} ${u}` : numero(it.qty)
}

/** "medio kilo de queso", "dos litros de leche": como lo diría una persona. */
export function qtyHablada(it) {
  if (!it.qty) return ''
  const par = HABLADA[it.unit]
  if (!par) return it.qty === 1 ? '' : numero(it.qty)   // sin unidad: "3 bananas"
  if (it.qty === 0.5) return 'medio ' + par[0]
  return numero(it.qty) + ' ' + (it.qty === 1 ? par[0] : par[1])
}

function line(it) {
  const bits = [qtyLabel(it), it.name, it.note ? `(${it.note})` : ''].filter(Boolean)
  const txt = bits.join(' ')
  return it.done ? `✅ ~${txt}~` : `• ${txt}`
}

const porCategoria = (out, pend) => {
  const byCat = {}
  for (const i of pend) (byCat[i.cat || 'varios'] ||= []).push(i)
  const cats = Object.keys(byCat)
  for (const cat of cats) {
    if (cats.length > 1) out.push(`${emojiDe(cat)} *${categoryLabel(canonicalCategory(cat) || cat, IDIOMA)}*`)
    out.push(...byCat[cat].map(line))
    if (cats.length > 1) out.push('')
  }
}

export function formatList(items, title = '🧊 Lista de la heladera') {
  if (!items.length) return `${title}\n\n(está vacía)`
  const pend = items.filter(i => !i.done)
  const done = items.filter(i => i.done)
  const out = [title, '']

  // Las sublistas van enteras y aparte; lo suelto se agrupa por góndola.
  const sueltos = pend.filter(i => !store.groupOf(i))
  porCategoria(out, sueltos)
  const grupos = []
  for (const i of pend) {
    const g = store.groupOf(i)
    if (g && !grupos.includes(g)) grupos.push(g)
  }
  for (const g of grupos) {
    out.push(`📋 *${g}*`)
    out.push(...pend.filter(i => store.groupOf(i) === g).map(line))
    out.push('')
  }

  if (done.length) { out.push('_ya comprado:_'); out.push(...done.map(line)) }
  out.push('', `_${pend.length} pendiente${pend.length === 1 ? '' : 's'}_`)
  return out.join('\n').trim()
}

/** Versión para leer en voz alta: sin emojis ni markdown. */
/** Plural sencillo, y solo si es una palabra sola: "pan" -> "panes". */
function plural(nombre, qty) {
  if (qty <= 1 || nombre.includes(' ') || nombre.endsWith('s')) return nombre
  return /[aeiouáéíóú]$/i.test(nombre) ? nombre + 's' : nombre + 'es'
}

export function speakableList(items) {
  const pend = items.filter(i => !i.done)
  if (!pend.length) return 'La lista está vacía.'
  const nombres = pend.map(i => {
    const q = qtyHablada(i)
    if (!q) return i.name
    // "dos litros DE leche", pero "tres bananas" sin el "de".
    return HABLADA[i.unit] ? `${q} de ${i.name}` : `${q} ${plural(i.name, i.qty)}`
  })
  if (nombres.length === 1) return `Falta ${nombres[0]}.`
  return `Faltan ${nombres.length} cosas: ${nombres.slice(0, -1).join(', ')} y ${nombres.at(-1)}.`
}

export async function sendTelegram(cfg, text) {
  if (!cfg.telegram.enabled || !cfg.telegram.token || !cfg.telegram.chatId) {
    return { ok: false, error: 'telegram sin configurar' }
  }
  try {
    const r = await fetch(`https://api.telegram.org/bot${cfg.telegram.token}/sendMessage`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: AbortSignal.timeout(15000),
      body: JSON.stringify({
        chat_id: cfg.telegram.chatId, text,
        parse_mode: 'Markdown', disable_web_page_preview: true,
      }),
    })
    const j = await r.json().catch(() => ({}))
    if (!r.ok || !j.ok) return { ok: false, error: (j.description || `http ${r.status}`).slice(0, 200) }
    store.log('telegram', text.slice(0, 200))
    return { ok: true }
  } catch (e) {
    return { ok: false, error: String(e.message || e) }
  }
}

/** Encola para que el relay de la Mac lo entregue con `apx send ... roby`. */
export function sendRoby(text) {
  const id = store.enqueue('roby', text)
  store.log('roby-queued', text.slice(0, 200), { id })
  return { ok: true, queued: id, note: 'en cola para WhatsApp' }
}

export async function send(cfg, channel, items, group = null) {
  const text = formatList(items, group ? `📋 ${group}` : undefined)
  if (channel === 'telegram') return { ...(await sendTelegram(cfg, text)), channel }
  if (channel === 'roby' || channel === 'whatsapp') return { ...sendRoby(text), channel }
  return { ok: false, error: `canal desconocido: ${channel}`, channel }
}
