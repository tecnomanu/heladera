/**
 * Texto a voz, con cache en disco.
 *
 * La heladera repite las mismas diez frases todo el día ("Listo", "Anotado",
 * "Sí, acá estoy"), así que guardamos cada audio por hash del texto. Con eso
 * hasta un motor lento se vuelve instantáneo después de la primera vez.
 *
 *   local   → sidecar con piper
 *   openai  → /v1/audio/speech (OpenAI, qvox, kokoro...)
 *   device  → que hable el Android, sin servidor
 *   none    → muda
 */
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { AUDIO_DIR } from '../config.js'
import * as sidecar from '../sidecar.js'

export const FRASES_COMUNES = [
  'Sí, acá estoy.', 'Listo.', 'Anotado.', 'Dale, lo anoté.', 'Ya está en la lista.',
  'No te entendí, repetímelo.', 'La lista está vacía.', 'Listo, te la mandé.',
  'Dale, lo tacho.', 'Listo, lo saqué.', 'Volví atrás.', 'Dale, no toco nada.',
]

function cachePath(cfg, text) {
  const key = `${cfg.tts.provider}|${cfg.tts.voice}|${cfg.tts.model}|${text}`
  const h = crypto.createHash('sha1').update(key).digest('hex').slice(0, 20)
  return path.join(AUDIO_DIR, `${cfg.tts.provider}-${h}.wav`)
}

async function local(cfg, text) {
  // Voices are selected in the admin panel, so fetch an uninstalled voice on
  // its first real use instead of requiring the user to save/restart first.
  await sidecar.downloadVoice(cfg.tts.voice)
  const r = await fetch(cfg.tts.url.replace(/\/$/, '') + '/tts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, voice: cfg.tts.voice }),
  })
  if (!r.ok) throw new Error(`sidecar tts ${r.status}: ${(await r.text()).slice(0, 200)}`)
  return Buffer.from(await r.arrayBuffer())
}

async function openai(cfg, text) {
  const headers = { 'Content-Type': 'application/json' }
  if (cfg.tts.apiKey) headers.Authorization = `Bearer ${cfg.tts.apiKey}`
  const base = cfg.tts.url.replace(/\/$/, '').replace(/\/v1$/, '')
  const r = await fetch(`${base}/v1/audio/speech`, {
    method: 'POST', headers,
    body: JSON.stringify({
      model: cfg.tts.model || 'tts-1', input: text,
      voice: cfg.tts.voice, response_format: 'wav',
    }),
  })
  if (!r.ok) throw new Error(`tts ${r.status}: ${(await r.text()).slice(0, 200)}`)
  return Buffer.from(await r.arrayBuffer())
}

/** Devuelve {file, cached} o null si no hay que generar audio del lado del server. */
export async function speak(cfg, text) {
  text = (text || '').trim()
  if (!text) return null
  if (cfg.tts.provider === 'none' || cfg.tts.provider === 'device') return null

  fs.mkdirSync(AUDIO_DIR, { recursive: true })
  const file = cachePath(cfg, text)
  if (fs.existsSync(file) && fs.statSync(file).size > 1000) {
    return { file, cached: true, provider: cfg.tts.provider }
  }
  const impl = { local, openai }[cfg.tts.provider]
  if (!impl) return null
  const data = await impl(cfg, text)
  if (!data || data.length < 1000) throw new Error('audio vacío')
  const tmp = file + '.part'
  fs.writeFileSync(tmp, data)
  fs.renameSync(tmp, file)
  return { file, cached: false, provider: cfg.tts.provider }
}

/** Genera de antemano las frases fijas, para que salgan sin latencia. */
export async function prewarm(cfg) {
  let n = 0
  for (const f of FRASES_COMUNES) {
    try { if (await speak(cfg, f)) n++ } catch { /* el motor dirá por qué en /health */ }
  }
  return n
}

/** Los textos que ya tienen audio guardado, para poder mirarlos desde el panel. */
export function cachedPhrases() {
  return FRASES_COMUNES.slice()
}

export function cacheStats() {
  try {
    const files = fs.readdirSync(AUDIO_DIR).filter(f => f.endsWith('.wav'))
    return { files: files.length, bytes: files.reduce((a, f) => a + fs.statSync(path.join(AUDIO_DIR, f)).size, 0) }
  } catch { return { files: 0, bytes: 0 } }
}

export function cacheClear() {
  try {
    for (const f of fs.readdirSync(AUDIO_DIR)) fs.unlinkSync(path.join(AUDIO_DIR, f))
  } catch { /* no existía */ }
}

export async function check(cfg) {
  if (cfg.tts.provider === 'device') return { ok: true, detail: 'habla la tablet' }
  if (cfg.tts.provider === 'none') return { ok: true, detail: 'sin voz' }
  try {
    await speak(cfg, 'Listo.')
    return { ok: true, detail: `${cfg.tts.provider} · ${cfg.tts.voice}` }
  } catch (e) {
    return { ok: false, detail: String(e.message || e) }
  }
}
