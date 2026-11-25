/**
 * La categoría es una palabra del vocabulario, no lo que se le ocurra.
 *
 * Con una lista abierta el modelo inventa ("comida") y la misma góndola termina
 * escrita de cinco maneras, así que agrupar deja de funcionar. Si lo que vino no
 * está en la lista, mejor sin categoría que con una mentira.
 */
function limpiarCat(v) {
  const c = canonicalCategory(v)
  return c && c !== 'other' ? c : null
}

/**
 * Entender qué pidió la persona y convertirlo en acciones sobre la lista.
 *
 * El modelo NO devuelve JSON: devuelve una línea por acción. Son unas tres veces
 * menos tokens de salida que el JSON equivalente, y en un 8B eso es la
 * diferencia entre contestar en un segundo o en ocho.
 *
 * Si el LLM no está o tarda, hay un parser por reglas que cubre lo típico: la
 * heladera tiene que andar igual.
 */
import * as llm from './engines/llm.js'
import { canonicalCategory, canonicalUnit, categoryLabel, shoppingToolSpec, unitLabel } from './labels.js'

export function systemPrompt(language = 'es') {
  const tool = shoppingToolSpec(language)
  const categories = tool.categories.map(c => `  ${c.label}: ${c.scope}`).join('\n')
  const units = tool.units.map(u => u.label ? `${u.id} (${u.label})` : u.id).join(', ')
  return `Sos el intérprete de una lista de compras de casa. Convertís lo que dice la persona en líneas de acción.
Contestás SOLO con líneas. Nada de JSON, markdown, explicaciones ni comentarios.

Idioma configurado: ${tool.language}. Conservá nombre, nota y categoría en ese idioma.

Una línea por acción:
+|nombre|cantidad|unidad|categoria|nota    agregar a la lista
-|nombre                                   BORRAR el renglón (se equivocó, ya no lo quiere anotado)
x|nombre                                   TACHAR: ya lo compró, ya lo tiene, lo puso en el changuito
o|nombre                                   volver a ponerlo como pendiente (solo si ya estaba tachado)
A|                                         separar la lista en sublistas por góndola
A|no                                       deshacer eso: dejar todo suelto otra vez
*|nombre                                   destacar (ponerle la estrellita)
F|nombre|cuando                            ponerle día: hoy, mañana, el sábado, 20/9
G|nombre de la sublista                    abrir una sublista: los + que siguen caen adentro
G|                                         volver a la lista general
C|all                                      vaciar la lista entera (C|done borra solo los tachados)
Z|                                         cerrar la compra y archivarla
>|telegram                                 mandar la lista (o >|roby para WhatsApp)
?|                                         leer la lista en voz alta
.|                                         terminar: dijo que no necesita nada más
!|que le preguntás                         no entendiste

Reglas:
- Una línea por ítem: "leche, pan y yerba" son TRES líneas.
- BORRAR y TACHAR no son lo mismo y es el error más caro: "borrá", "eliminá",
  "sacá de la lista", "no lo quiero más" son "-". "ya lo compré", "ya lo tengo",
  "tachá", "marcá", "listo el pan" son "x".
- "borrá la lista", "borrá todo", "limpiá la lista", "empecemos de cero" es C|all,
  UNA sola línea. Nunca una x o un - por cada ítem.
- Sublistas: si arma un conjunto con nombre ("una lista para el asado", "lo del
  cumple", "las cosas del desayuno"), abrí un G| con ese nombre corto y después
  los +. Si solo enumera cosas sueltas, NO abras ninguna sublista.
- Si nombra una sublista que ya existe (te las paso en "Sublistas:"), usá ese
  mismo nombre tal cual en el G|.
- El día y la estrella van en su propia línea, DESPUÉS del + del ítem:
  "anotá pan para mañana" son dos líneas, el + y el F|pan|mañana.
- "que no me olvide", "importante", "urgente", "destacá" son estrella (*).
- La charla sigue abierta después de cada pedido. Cuando diga que ya está
  ("nada más", "listo, eso es todo", "ya está", "gracias, chau"), devolvé ".|"
  y nada más. Ojo: "listo el pan" es tachar el pan, no terminar.
- "ordename esto en listas", "separá por góndola", "armá listas con lo que hay",
  "agrupá la lista" es A| solo, UNA línea. No enumeres los ítems.
- Cualquier forma de pedir que algo entre a la lista es un "+": anotá, agregá, poné,
  sumá, guardá, acordate de, traé, comprá, metele, falta, necesito, hay que comprar.
- NUNCA uses "+" para charla, pruebas o control de la app. "probá eso", "y probá
  eso", "a ver", "escuchame", "repetí" y frases parecidas NO son productos: devolvé "!|".
  Un "+" siempre nombra un producto concreto que se puede comprar.
- Nombre en singular y minúscula, sin la cantidad adentro ("dos litros de leche" -> leche, 2, l).
- Herramienta agregar: +|nombre|cantidad|unidad|categoria|nota. Los cinco campos
  importan: nombre singular, cantidad número positivo, unidad, categoría y nota.
  Nota queda vacía si no hay marca o detalle.
- SIEMPRE poné cantidad y unidad. Si no dijo cantidad, usá 1. Si no dijo unidad,
  usá la medida natural: kg para peso, l para líquido, dozen para huevos, u para
  contables. Unidades válidas: ${units}.
- categoria: elegí una etiqueta exacta del idioma configurado, nunca ID interno:
${categories}
- nota es opcional (marca, "descremada", "sin sal"). Si no hay, dejala vacía.

MUY IMPORTANTE: la tablet vive en la cocina y escucha la tele, la radio y charlas
que no son para ella. Si lo que dijo no es claramente un pedido sobre la lista de
compras, devolvé SOLO "!|" y nada más. Ante la duda no borres ni saques nada.`
}

const FEWSHOT = [
  ['anotame dos litros de leche descremada y un kilo de pan',
   '+|leche|2|l|dairy|descremada\n+|pan|1|kg|pantry|'],
  ['agregá papel higiénico y lavandina',
   '+|papel higiénico|1|u|cleaning|\n+|lavandina|1|u|cleaning|'],
  ['anotá manzanas y mandarinas',
   '+|manzana|1|kg|produce|\n+|mandarina|1|kg|produce|'],
  ['poné una banana', '+|banana|1|u|produce|'],
  ['che poné tres bananas y medio kilo de queso cremoso',
   '+|banana|3|u|produce|\n+|queso cremoso|0.5|kg|dairy|'],
  ['guardame un pan y acordate del azúcar',
   '+|pan|1|kg|bakery|\n+|azúcar|1|kg|pantry|'],
  ['metele dos cervezas y traeme papel de cocina',
   '+|cerveza|2|u|drinks|\n+|papel de cocina|1|u|cleaning|'],
  ['sacá la yerba que ya la compré', 'x|yerba'],
  ['ya compramos el pan y las bananas', 'x|pan\nx|banana'],
  ['borrá la zanahoria que me equivoqué', '-|zanahoria'],
  ['sacá el detergente de la lista, no lo quiero', '-|detergente'],
  ['borrá la lista', 'C|all'],
  ['ordename lo que hay en listas separadas', 'A|'],
  ['separá todo por góndola', 'A|'],
  ['juntá todo de nuevo en una sola lista', 'A|no'],
  ['anotá pan para mañana', '+|pan|1|kg|bakery|\nF|pan|mañana'],
  ['sumá lavandina y que no me olvide',
   '+|lavandina|||cleaning|\n*|lavandina'],
  ['el sábado hay que comprar carbón', '+|carbón|||other|\nF|carbón|sábado'],
  ['sacá el pan de la lista', '-|pan'],
  ['sumale al asado una provoleta y dos chinchulines',
   'G|asado\n+|provoleta|1|u|butcher|\n+|chinchulín|2|u|butcher|',
   'Lista: chorizo, carbón, leche\nSublistas: asado'],
  ['armame una lista para el asado con chorizo, vacío y carbón',
   'G|asado\n+|chorizo|||butcher|\n+|vacío|||butcher|\n+|carbón|||other|'],
  ['agregá al desayuno medialunas y dulce de leche, y aparte anotá lavandina',
   'G|desayuno\n+|medialuna|||bakery|\n+|dulce de leche|||pantry|\nG|\n+|lavandina|||cleaning|'],
  ['media docena de facturas y alimento para el perro',
   '+|factura|6|u|bakery|\n+|alimento para perro|||pets|'],
  ['anotá dos kilos de asado, una docena de huevos y un shampoo',
   '+|asado|2|kg|butcher|\n+|huevo|12|u|dairy|\n+|shampoo|||pharmacy|'],
  ['mandame la lista por telegram', '>|telegram'],
  ['qué falta comprar', '?|'],
  ['qué falta', '?|'],
  ['leeme la lista', '?|'],
  ['nada más, gracias', '.|'],
  ['listo, eso es todo', '.|'],
  ['ya está, chau', '.|'],
  ['no, no, no, pero eso no tiene nada que ver', '!|'],
  ['bueno a ver, podemos guardar una parte y después vemos', '!|'],
  ['y probá eso', '!|'],
  ['probá la voz', '!|'],
]

function localizeToolOutput(output, language) {
  return String(output).split('\n').map(line => {
    const fields = line.split('|')
    if (fields[0] !== '+') return line
    const unit = canonicalUnit(fields[3])
    const category = canonicalCategory(fields[4])
    if (unit) fields[3] = unitLabel(unit, language) || unit
    if (category) fields[4] = categoryLabel(category, language)
    return fields.join('|')
  }).join('\n')
}

const DIAS = { domingo: 0, lunes: 1, martes: 2, miercoles: 3, jueves: 4, viernes: 5, sabado: 6 }
const iso = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')

/**
 * "mañana", "el sábado", "20/9" -> una fecha de verdad.
 *
 * Lo resolvemos acá y no en el prompt: un modelo chico no sabe qué día es hoy
 * y se inventa el año. Pedirle la palabra y traducirla nosotros nunca falla.
 */
export function fechaDe(txt) {
  const t = String(txt || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim()
  if (!t) return null
  const hoy = new Date()
  hoy.setHours(12, 0, 0, 0)
  const mas = n => { const d = new Date(hoy); d.setDate(d.getDate() + n); return iso(d) }
  if (/^hoy$/.test(t)) return mas(0)
  if (/^manana$/.test(t)) return mas(1)
  if (/^pasado( manana)?$/.test(t)) return mas(2)
  if (/^\d{4}-\d{2}-\d{2}$/.test(t)) return t
  let m = /^(\d{1,2})[\/-](\d{1,2})(?:[\/-](\d{2,4}))?$/.exec(t)
  if (m) {
    const anio = m[3] ? (m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3])) : hoy.getFullYear()
    const d = new Date(anio, Number(m[2]) - 1, Number(m[1]), 12)
    if (!m[3] && d < hoy) d.setFullYear(anio + 1)
    return iso(d)
  }
  for (const [nombre, n] of Object.entries(DIAS)) {
    if (!new RegExp('(^|\\s)(el |este |proximo )?' + nombre, 'i').test(t)) continue
    let falta = (n - hoy.getDay() + 7) % 7
    if (falta === 0) falta = 7
    return mas(falta)
  }
  return null
}

const esc = t => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const normalize = t => String(t || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')

/**
 * A short meta utterance is never a shopping item. This guard runs before the
 * LLM because a small model can otherwise turn “y probá eso” into an add.
 */
export function isMetaUtterance(text) {
  const t = String(text || '').toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
  return /^(?:y )?(?:probar|proba|probemos|testear|testea|a ver|veamos|escuchar|escuchame|repetir|repeti|repetime)(?: (?:eso|esto|la voz|si funciona|como suena))?$/.test(t)
}

const NUMS = {
  un: 1, una: 1, uno: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7,
  ocho: 8, nueve: 9, diez: 10, once: 11, doce: 12, media: 0.5, medio: 0.5,
}
function num(v) {
  v = String(v || '').trim().replace(',', '.')
  if (!v) return null
  const f = parseFloat(v)
  // Un 0 es el modelo diciendo "no dijo cantidad": no tiene sentido anotar
  // "0 kg de yerba" en una lista de compras.
  if (!Number.isNaN(f)) return f === 0 ? null : (f === Math.floor(f) ? Math.round(f) : f)
  return NUMS[v.toLowerCase()] ?? null
}

/** Convierte las líneas del modelo en acciones. Ignora cualquier basura. */
export function parseLines(text) {
  const actions = []
  let ask = ''
  let grupo = null
  for (const raw of String(text || '').split('\n')) {
    const line = raw.trim().replace(/^`+|`+$/g, '').trim()
    if (!line) continue
    const op = line[0]
    if (!'+-xoCZGA*F>?!.'.includes(op)) continue
    const rest = line[1] === '|' ? line.slice(2) : ''
    const f = rest.split('|').map(s => s.trim())
    const g = i => {
      const v = f[i]
      return v && !['null', 'none', '-', 'n/a'].includes(v.toLowerCase()) ? v : null
    }
    if (op === 'G') {
      // No es una acción: marca en qué sublista caen los "+" que vienen abajo.
      grupo = (g(0) || '').toLowerCase() || null
      continue
    }
    if (op === '+') {
      const name = (g(0) || '').toLowerCase()
      if (!name) continue
      const unit = canonicalUnit(g(2))
      const cat = (g(3) || '').toLowerCase()
      // Todo lo que se compra tiene una medida. Si el modelo no la puso, una
      // unidad: así "una banana" y después "dos bananas" se pueden sumar.
      const cant = num(g(1))
      actions.push({
        op: 'add', name, qty: cant == null ? 1 : cant,
        unit: unit || 'u',
        // Si dijo "dos litros", la unidad que eligió el modelo manda sobre la
        // típica de la góndola: pediste dos litros, no dos kilos.
        unitDicha: !!unit && cant != null,
        cat: limpiarCat(cat),
        note: g(4),
        group: grupo,
      })
    } else if (op === 'A') {
      actions.push({ op: 'organizar', deshacer: /^no|desarm/i.test(g(0) || '') })
    } else if (op === '*') {
      const name = (g(0) || '').toLowerCase()
      if (name) actions.push({ op: 'star', name })
    } else if (op === 'F') {
      const name = (g(0) || '').toLowerCase()
      const due = fechaDe(g(1))
      if (name && due) actions.push({ op: 'due', name, due })
    } else if ('-xo'.includes(op)) {
      const name = (g(0) || '').toLowerCase()
      if (name) actions.push({ op: { '-': 'remove', x: 'done', o: 'undone' }[op], name })
    } else if (op === 'C') {
      actions.push({ op: 'clear', scope: g(0) === 'done' ? 'done' : 'all' })
    } else if (op === 'Z') {
      actions.push({ op: 'close' })
    } else if (op === '>') {
      const ch = (g(0) || 'telegram').toLowerCase()
      actions.push({ op: 'send', channel: ['telegram', 'roby', 'whatsapp'].includes(ch) ? ch : 'telegram' })
    } else if (op === '?') {
      actions.push({ op: 'read' })
    } else if (op === '.') {
      actions.push({ op: 'fin' })
    } else if (op === '!') {
      ask = g(0) || 'No te entendí, repetímelo.'
    }
  }
  return { reply: ask, actions, engine: 'llm' }
}

// ------------------- parser por reglas (cuando no hay LLM) -------------------

// Cómo la persona nombra una medida hablando, y a qué unidad canónica va.
const MEDIDA_HABLADA = {
  kilo: 'kg', kilos: 'kg', kg: 'kg', kilogramo: 'kg', kilogramos: 'kg',
  gramo: 'g', gramos: 'g', g: 'g', litro: 'l', litros: 'l', l: 'l',
  mililitro: 'ml', mililitros: 'ml', ml: 'ml',
  paquete: 'pack', paquetes: 'pack', docena: 'dozen', docenas: 'dozen',
  unidad: 'u', unidades: 'u',
}
// El \b de JavaScript es ASCII: después de "sacá" no ve un límite de palabra y
// la expresión no matchea nunca. Estos son límites que sí entienden tildes.
const B0 = '(?<![\\p{L}\\p{N}])'
const B1 = '(?![\\p{L}\\p{N}])'
const re = src => new RegExp(B0 + '(?:' + src + ')' + B1, 'iu')

const ADD_RE = re('anot[aá](?:me|nos)?|apunt[aá](?:me)?|agreg[aá](?:me)?|sum[aá](?:me)?|pon[eé](?:me)?|'
  + 'guard[aá](?:me|nos)?|gu[aá]rdame|acordate de|acord[aá]te|tra[eé](?:me)?|met[eé](?:le)?|'
  + 'necesit(?:o|amos)|falta[n]?|compr[aá](?:me)?|comprar|hay que comprar|añad[ií]')
const DONE_RE = re('ya (?:lo |la |los |las )?compr[eé]|tach[aá]|march[aá]|list[oa] (?:el|la|los|las)|compr[eé]')
const REMOVE_RE = re('sac[aá]|quit[aá]|borr[aá]|elimin[aá]|no (?:hace falta|va|quiero)')
const ORGANIZAR_RE = re('(?:orden[aá]|separ[aá]|agrup[aá]|arm[aá]).{0,24}(?:list|g[oó]ndola|categor[ií]a)')
const CLEAR_RE = re('limpi[aá] (?:la )?lista|vaci[aá] (?:la )?lista|borr[aá] (?:toda )?la lista|empez(?:ar|amos) de cero')
const CLOSE_RE = re('cerr[aá] (?:la )?compra|termin[eé] (?:de comprar|la compra)|ya (?:termin[eé]|compr[eé] todo)')
const SEND_RE = new RegExp(B0 + '(?:mand[aá](?:me|mela|nos)?|envi[aá](?:me|mela)?|pas[aá](?:me|mela)?|compart[ií])' + B1 + '.*' + B0 + '(?:lista|telegram|whatsapp|celular|tel[eé]fono)' + B1, 'iu')
/**
 * "Ya está, cortá": hay que reconocerlo aunque whisper lo destroce.
 *
 * En la cocina "nada más" llegó como "Chata Namas" y "ya cortá" como "ya te
 * cortamos". Cortar la charla no rompe nada — como mucho volvés a tocar
 * Hablar — así que acá conviene pasarse de generoso: alcanza con que una frase
 * corta tenga alguna de estas señales.
 */
const CORTES = [
  'nada mas', 'namas', 'nomas mas', 'ninguna mas', 'ninguno mas', 'nada mas gracias',
  'ya esta', 'yaesta', 'es todo', 'eso es todo', 'esto es todo', 'terminamos',
  'listo gracias', 'gracias', 'chau', 'basta', 'nada q', 'nada que',
]
const CORTE_VERBO = /(^|\s)(ya\s+)?(me\s+|te\s+)?cort(a|á|as|ás|ame|ámelo|amos|emos|alo|ala)?(\s|$|[.,!?])/i

export function esCorte(texto) {
  const t = String(texto || '').toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
  if (!t || t.length > 42) return false
  if (CORTE_VERBO.test(t)) return true
  return CORTES.some(c => t.includes(c))
}
const READ_RE = re('le[eé]|lee?me|dec[ií]me|qu[eé] (?:hay|falta|ten[eé]?mos)|c[oó]mo va|repet[ií]')

function splitItems(chunk) {
  return chunk.replace(/\b(y|adem[aá]s|tambi[eé]n)\b/gi, ',')
    .split(',').map(s => s.trim().replace(/^[.,;\s]+|[.,;\s]+$/g, '')).filter(Boolean)
}

function parseItem(raw) {
  const words = raw.split(/\s+/)
  let qty = null, unit = null, i = 0
  while (i < words.length && words.length - i > 1) {
    const w = words[i].toLowerCase().replace(/[.,]/g, '')
    if (qty === null && (w in NUMS || /^\d+([.,]\d+)?$/.test(w))) { qty = NUMS[w] ?? parseFloat(w.replace(',', '.')); i++; continue }
    if (unit === null && w in MEDIDA_HABLADA) { unit = MEDIDA_HABLADA[w]; i++; continue }
    break
  }
  let name = words.slice(i).join(' ')
    .replace(/^\s*(de|del|la|el|los|las|un|una)\s+/i, '')
    .replace(/\b(por favor|porfa|che)\b/gi, '')
    .replace(/^[.,;\s]+|[.,;\s]+$/g, '')
  if (!name || name.length < 2) return null
  if (qty !== null && qty === Math.floor(qty)) qty = Math.round(qty)
  return { op: 'add', name: name.toLowerCase(), qty, unit, cat: null, note: null }
}

export function rules(transcript) {
  const t = (transcript || '').trim()
  if (!t) return { reply: 'No te escuché, dale de nuevo.', actions: [], engine: 'reglas' }

  // "qué falta" tiene que ganarle a ADD_RE, que también matchea "falta". Pedimos
  // que la pregunta esté al principio para no confundirla con "anotá lo que falta".
  if (esCorte(t)) return { reply: 'Dale, hasta luego.', actions: [{ op: 'fin' }], engine: 'reglas' }
  const read = READ_RE.exec(t)
  if (read && read.index < 15) return { reply: '', actions: [{ op: 'read' }], engine: 'reglas' }

  if (ORGANIZAR_RE.test(t)) return { reply: '', actions: [{ op: 'organizar' }], engine: 'reglas' }
  if (CLOSE_RE.test(t)) return { reply: '', actions: [{ op: 'close' }], engine: 'reglas' }
  if (CLEAR_RE.test(t)) return { reply: '', actions: [{ op: 'clear', scope: 'all' }], engine: 'reglas' }
  if (SEND_RE.test(t)) return { reply: '', actions: [{ op: 'send', channel: 'telegram' }], engine: 'reglas' }

  for (const [rx, op] of [[DONE_RE, 'done'], [REMOVE_RE, 'remove']]) {
    const m = rx.exec(t)
    if (m) {
      const acts = splitItems(t.slice(m.index + m[0].length))
        .map(parseItem).filter(Boolean).map(p => ({ op, name: p.name }))
      if (acts.length) return { reply: '', actions: acts, engine: 'reglas' }
    }
  }

  // Sin verbo explícito no inventamos nada. Estas reglas son el plan B de cuando
  // el LLM no está, y equivocarse acá significa tocar la lista por una charla.
  const m = ADD_RE.exec(t)
  if (!m) return { reply: 'No te entendí, repetímelo.', actions: [], engine: 'reglas' }
  const acts = splitItems(t.slice(m.index + m[0].length)).map(parseItem).filter(Boolean)
  if (acts.length) return { reply: '', actions: acts, engine: 'reglas' }
  return { reply: 'No te entendí, repetímelo.', actions: [], engine: 'reglas' }
}

/**
 * Pasa la jerga a una forma que cualquier modelo entienda.
 *
 * Un modelo chico no asocia "metele dos cervezas" con agregar, y termina
 * borrando en vez de anotar. Cambiar el verbo antes de preguntarle sale mucho
 * más barato que pelearse con el prompt, y la persona sigue hablando como habla.
 */
const JERGA = [
  [re('met[eé]le|met[eé]me|met[eé]'), 'agregá'],
  [re('gu[aá]rdame|guard[aá](?:me|nos)?'), 'agregá'],
  [re('acord[aá]te de(?:l| la| los| las)?|acord[aá]te'), 'agregá'],
  [re('tra[eé](?:me|nos)?'), 'agregá'],
  [re('hac[eé]me acordar de'), 'agregá'],
]

// "sacá" solo es ambiguo: "sacá el pan" es tacharlo, "sacá el pan DE LA LISTA"
// es borrarlo. Lo resolvemos acá y no en el prompt, que ya tiene bastante.
const SACAR_DE_LA_LISTA = /sac[aá](?:me|nos)?\s+(.{2,40}?)\s+de (?:la |mi )?lista/iu

export function normalizarJerga(texto) {
  let t = texto || ''
  t = t.replace(SACAR_DE_LA_LISTA, (_, que) => 'borrá ' + que)
  for (const [rx, verbo] of JERGA) t = t.replace(rx, verbo)
  return t
}

export async function understand(cfg, transcript, items) {
  transcript = normalizarJerga(transcript)
  if (isMetaUtterance(transcript)) {
    return { reply: 'Eso es una prueba, no lo anoto. Decime qué te falta.', actions: [], engine: 'guard' }
  }
  if (cfg.llm.enabled) {
    try {
      const lista = items.map(i => i.name + (i.done ? ' (comprado)' : '')).join(', ') || '(vacía)'
      const subs = [...new Set(items.map(i => (i.group || '').trim()).filter(Boolean))]
      const messages = [{ role: 'system', content: systemPrompt(cfg.language) }]
      for (const [user, out, ctx] of FEWSHOT) {
        messages.push({ role: 'user', content: `${ctx || 'Lista: (vacía)'}\nDijo: ${user}` })
        messages.push({ role: 'assistant', content: localizeToolOutput(out, cfg.language) })
      }
      const ctx = subs.length ? `Lista: ${lista}\nSublistas: ${subs.join(', ')}` : `Lista: ${lista}`
      messages.push({ role: 'user', content: `${ctx}\nDijo: ${transcript}` })
      const { text, ms } = await llm.chat(cfg, messages)
      const out = parseLines(text)
      // Red de seguridad: si nombró una sublista que ya existe ("al asado una
      // provoleta") y el modelo no la abrió, la ponemos nosotros.
      if (subs.length) {
        const dicho = normalize(transcript)
        for (const g of subs) {
          if (!new RegExp('(?:^|\\s)(?:a|al|de|del|para|en|la|el)\\s+(?:lista (?:de|del) )?' + esc(normalize(g)) + '(?:$|\\s|[,.])', 'i').test(dicho)) continue
          for (const a of out.actions) if (a.op === 'add' && !a.group) a.group = g
          break
        }
      }
      // Si te devuelve un "borrá" por cada ítem de la lista, lo que quisiste
      // decir fue "borrá la lista". Así la pregunta de confirmación se entiende.
      const abiertos = items.filter(i => !i.done).length
      if (abiertos >= 3 && out.actions.length >= abiertos && out.actions.every(a => a.op === 'remove')) {
        return { reply: out.reply, actions: [{ op: 'clear', scope: 'all' }], engine: 'llm', ms }
      }
      if (out.actions.length || out.reply) return { ...out, ms }
    } catch (e) {
      // Sin LLM seguimos con reglas: la lista tiene que poder cargarse igual
      return { ...rules(transcript), llmError: String(e.message || e) }
    }
  }
  return rules(transcript)
}
