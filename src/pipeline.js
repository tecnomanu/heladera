/**
 * Un turno: lo que dijiste entra, la lista cambia y sale una respuesta.
 *
 * Acá viven las protecciones. La tablet escucha la cocina entera, así que todo
 * lo que no tiene vuelta se pregunta antes y queda en un snapshot: en pruebas,
 * un "no, no, no" de fondo llegó a borrar la lista de la semana.
 */
import * as store from './store.js'
import * as brain from './brain.js'
import { esCorte } from './brain.js'
import * as notify from './notify.js'
import * as tts from './engines/tts.js'

const B0 = '(?<![\\p{L}\\p{N}])', B1 = '(?![\\p{L}\\p{N}])'
const rx = s => new RegExp(B0 + '(?:' + s + ')' + B1, 'iu')
const COMPRE_RE = rx('compr[eé]')
const SI_RE = rx('s[ií]|dale|obvio|confirmo|correcto|ok|okey|okay|va|hacelo|adelante|borr[aá]l[ao]')
const NO_RE = rx('no|nop|nones|cancel[aá]|par[aá]|dej[aá]|olvidalo|mejor no')
const UNDO_RE = rx('volv[eé] atr[aá]s|deshac[eé]|deshacer|me equivoqu[eé]|no era eso|repon[eé]')

const DESTRUCTIVE = new Set(['remove', 'clear', 'close', 'done', 'organizar'])
const CONFIRM_TTL_MS = 120000

let pending = null   // { actions, ts, question }

/** Devuelve la pregunta a hacer antes de ejecutar, o '' si se puede aplicar ya. */
export function needsConfirmation(actions) {
  for (const a of actions) {
    if (a.op === 'clear') return a.scope === 'all' ? '¿Seguro que borro toda la lista?' : '¿Saco todo lo que está tachado?'
    if (a.op === 'close') return '¿Cierro la compra y archivo la lista?'
  }
  const bulk = actions.filter(a => a.op === 'remove' || a.op === 'done').length
  return bulk >= 3 ? `¿Saco ${bulk} cosas de la lista?` : ''
}

export async function applyActions(cfg, actions, source = 'voz', lista = null) {
  if (actions.some(a => DESTRUCTIVE.has(a.op))) store.snapshot(source)
  const added = [], removed = [], marked = [], notes = [], sent = []
  let read = false, fin = false

  for (const a of actions) {
    try {
      if (a.op === 'add') {
        // Lo que pediste cae en la lista que estás mirando, salvo que hayas
        // nombrado otra. Si estás en "todo", va suelto como siempre.
        const { unitDicha, ...limpio } = a
        added.push(store.addItem({ ...limpio, group: a.group || lista, source }))
      } else if (a.op === 'organizar') {
        const n = a.deshacer ? store.desarmarTodo() : store.organizarPorCategoria()
        notes.push(a.deshacer ? `junté todo, ${n} sin lista` : `separé ${n} en listas`)
      } else if (a.op === 'group') {
        const n = store.groupDo(a.group, a.what || 'done')
        notes.push(`${a.what === 'remove' ? 'borré' : 'tildé'} ${n} de la lista de ${a.group}`)
      } else if (a.op === 'remove' || a.op === 'done' || a.op === 'undone'
                 || a.op === 'star' || a.op === 'due') {
        const it = store.findItem(a.name)
        if (!it) { notes.push(`no encontré ${a.name}`); continue }
        if (a.op === 'remove') { store.removeItem(it.id); removed.push(it) }
        else if (a.op === 'star') { store.updateItem(it.id, { star: true }); notes.push(`destaqué ${it.name}`) }
        else if (a.op === 'due') { store.updateItem(it.id, { due: a.due }); notes.push(`${it.name} para el ${a.due}`) }
        else { store.updateItem(it.id, { done: a.op === 'done' }); marked.push(it) }
      } else if (a.op === 'clear') {
        notes.push(`borré ${store.clear(a.scope || 'all')} ítems`)
      } else if (a.op === 'close') {
        notes.push(`cerré la compra con ${store.closeTrip().count} ítems`)
      } else if (a.op === 'undo') {
        const r = store.undo()
        notes.push(r ? 'volví atrás' : 'no hay nada para deshacer')
      } else if (a.op === 'send') {
        const lista = a.group ? store.items().filter(i => store.groupOf(i) === a.group) : store.items()
        sent.push(await notify.send(cfg, a.channel || 'telegram', lista, a.group))
      } else if (a.op === 'read') {
        read = true
      } else if (a.op === 'fin') {
        fin = true
      }
    } catch (e) {
      notes.push(String(e.message || e))
    }
  }
  return { added, removed, marked, notes, read, sent, fin }
}

/** Una coletilla distinta cada vez: siempre la misma cansa. */
const MAS = ['¿Algo más?', '¿Qué más?', '¿Necesitás algo más?', '¿Algo más te falta?']
const otra = () => MAS[Math.floor(Math.random() * MAS.length)]

function lista(nombres) {
  if (nombres.length === 1) return nombres[0]
  if (nombres.length === 2) return nombres[0] + ' y ' + nombres[1]
  return nombres.slice(0, -1).join(', ') + ' y ' + nombres.at(-1)
}

export function buildReply(base, result, items, seguir = false) {
  const mas = seguir ? ' ' + otra() : ''
  if (result.fin) return 'Dale, listo. Chau.'
  if (result.read) return notify.speakableList(items)
  if (!base) {
    if (result.added.length) return `Listo, anoté ${lista(result.added.slice(0, 4).map(i => i.name))}.${mas}`
    if (result.marked.length) return `Dale, ${result.marked.length === 1 ? 'lo tacho' : 'los tacho'}.${mas}`
    if (result.removed.length) return `Listo, ${result.removed.length === 1 ? 'lo saqué' : 'los saqué'}.${mas}`
  }
  if (result.read) return notify.speakableList(items)
  if (result.sent.length) {
    const s = result.sent[0]
    if (s.ok) return s.channel === 'telegram' ? 'Listo, te la mandé.' : 'Listo, la mando por WhatsApp.'
    return 'No pude mandarla, fijate la conexión.'
  }
  if (base) return base
  if (result.added.length) return `Listo, anoté ${result.added.slice(0, 3).map(i => i.name).join(', ')}.`
  if (result.marked.length) return 'Dale, lo tacho.'
  if (result.removed.length) return 'Listo, lo saqué.'
  if (result.notes.length) return result.notes[0][0].toUpperCase() + result.notes[0].slice(1) + '.'
  return 'No te entendí, repetímelo.'
}

async function withAudio(cfg, out, speak) {
  if (!speak) return out
  const t0 = Date.now()
  try {
    const a = await tts.speak(cfg, out.reply)
    out.ttsMs = Date.now() - t0
    if (a) {
      out.audioUrl = '/api/audio/' + a.file.split('/').pop()
      out.audioProvider = a.provider
    }
  } catch (e) {
    out.ttsError = String(e.message || e)
  }
  return out
}

export async function turn(cfg, transcript, { speak = false, source = 'voz', tiempos = null, lista = null } = {}) {
  const text = transcript || ''
  const alive = pending && Date.now() - pending.ts < CONFIRM_TTL_MS
  let understood

  if (alive && SI_RE.test(text) && !NO_RE.test(text)) {
    understood = { reply: '', actions: pending.actions, engine: 'confirmación' }
    pending = null
  } else if (alive && NO_RE.test(text)) {
    pending = null
    understood = { reply: 'Dale, no toco nada.', actions: [], engine: 'confirmación' }
  } else if (esCorte(text)) {
    // Antes que nada: si dijo que ya está, se termina. No lo dejamos en manos
    // del modelo, que con una transcripción rota nunca lo va a ver.
    pending = null
    understood = { reply: '', actions: [{ op: 'fin' }], engine: 'reglas' }
  } else if (UNDO_RE.test(text)) {
    understood = { reply: '', actions: [{ op: 'undo' }], engine: 'reglas' }
  } else {
    understood = await brain.understand(cfg, text, store.items())

    // "sacá el pan que ya lo compré" es tachar, no borrar: si dijo que lo compró
    // queremos que quede en el historial de la compra, no que desaparezca.
    if (COMPRE_RE.test(text)) {
      for (const a of understood.actions) if (a.op === 'remove') a.op = 'done'
    }

    const question = needsConfirmation(understood.actions)
    if (question) {
      pending = { actions: understood.actions, ts: Date.now(), question }
      store.log('confirmar', text, { actions: understood.actions, question })
      return withAudio(cfg, {
        transcript: text, reply: question, engine: understood.engine,
        actions: [], result: {}, awaitingConfirmation: true, seguir: true,
        entendido: true, ...store.state(),
      }, speak)
    }
  }

  const result = await applyActions(cfg, understood.actions, source, lista)
  const items = store.items()
  // La charla sigue salvo que haya dicho que ya está.
  const seguir = !result.fin
  const reply = buildReply(understood.reply, result, items, seguir && understood.actions.length > 0)

  const { read, ...rest } = result
  const out = await withAudio(cfg, {
    transcript: text, reply, engine: understood.engine,
    // Para que la tablet sepa si sigue la charla o si cortamos.
    entendido: understood.actions.length > 0,
    seguir,
    llmMs: understood.ms, llmError: understood.llmError,
    actions: understood.actions, result: rest, ...store.state(),
  }, speak)

  // Los tiempos quedan en el log: sin esto "está lento" no se puede perseguir.
  out.ms = {
    stt: tiempos && tiempos.stt != null ? tiempos.stt : null,
    llm: understood.ms != null ? understood.ms : null,
    tts: out.ttsMs != null ? out.ttsMs : null,
    total: tiempos && tiempos.t0 ? Date.now() - tiempos.t0 : null,
  }
  store.log('turno', text, {
    engine: understood.engine, actions: understood.actions, reply, ms: out.ms,
  })
  return out
}

export function clearPending() { pending = null }

export function pendingQuestion() {
  return pending && Date.now() - pending.ts < CONFIRM_TTL_MS ? pending.question : ''
}

/**
 * Responder la pregunta con un botón en vez de con la voz.
 *
 * Hace falta porque confirmar hablando depende de que el micrófono te escuche
 * justo cuando la heladera está esperando un sí, y si no te escucha quedás
 * trabado sin entender por qué.
 */
export async function confirm(cfg, yes, speak = false) {
  if (!pending || Date.now() - pending.ts >= CONFIRM_TTL_MS) {
    return withAudio(cfg, { reply: 'No había nada para confirmar.', actions: [], result: {}, ...store.state() }, speak)
  }
  const actions = pending.actions
  pending = null
  if (!yes) {
    return withAudio(cfg, { reply: 'Dale, no toco nada.', actions: [], result: {}, ...store.state() }, speak)
  }
  const result = await applyActions(cfg, actions, 'boton')
  const items = store.items()
  const reply = buildReply('', result, items)
  store.log('turno', '(confirmado con el botón)', { actions, reply })
  const { read, ...rest } = result
  return withAudio(cfg, { reply, actions, result: rest, ...store.state() }, speak)
}
