/**
 * Configuración: un JSON en ~/.heladera/config.json que el CLI y el panel web
 * escriben, y variables de entorno que lo pisan.
 *
 * Nada de esto vive en el código: la gracia es que otra persona clone el repo,
 * corra `heladera init` y lo apunte a SU ollama, SU whisper y SU piper.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'

export const HOME = process.env.HELADERA_HOME || path.join(os.homedir(), '.heladera')
export const CONFIG_PATH = path.join(HOME, 'config.json')
export const DATA_PATH = path.join(HOME, 'data.json')
export const AUDIO_DIR = path.join(HOME, 'audio')
export const ENGINES_DIR = path.join(HOME, 'engines')

export const DEFAULTS = {
  port: 8790,
  host: '0.0.0.0',
  language: 'es',
  // Cómo habla y cómo se le habla
  wakeWords: ['ok heladera', 'okey heladera', 'che heladera', 'hola heladera',
               'ok tableta', 'okey tableta', 'che tableta', 'hola tableta'],

  auth: { enabled: true, token: '' },

  stt: {
    // local  = sidecar propio con faster-whisper
    // openai = cualquier API compatible con /v1/audio/transcriptions
    // custom = tu propio endpoint que recibe un wav y devuelve {text}
    provider: 'local',
    model: 'small',
    fastModel: 'tiny',
    language: '',            // vacío = usa config.language
    url: 'http://127.0.0.1:8791',
    apiKey: '',
    // Nombrarle los productos típicos sube bastante el acierto: sin esto,
    // "fideos" sale "videos".
    prompt: 'Lista de compras del supermercado en español rioplatense. Productos: '
      + 'leche, pan, yerba, fideos, arroz, tomate, papa, cebolla, queso, jamón, huevos, '
      + 'azúcar, café, aceite, lavandina, detergente, papel higiénico, gaseosa, cerveza, '
      + 'yogur, manteca, harina, carne, pollo, banana, manzana.',
  },

  tts: {
    // local  = sidecar propio con piper
    // openai = /v1/audio/speech (OpenAI, qvox, kokoro, lo que sea)
    // device = que hable el Android, sin servidor
    // none   = muda
    provider: 'local',
    url: 'http://127.0.0.1:8791',
    apiKey: '',
    voice: 'es_AR-daniela-high',
    model: 'tts-1',
  },

  llm: {
    // ollama = /api/chat   |   openai = /v1/chat/completions
    provider: 'ollama',
    url: 'http://127.0.0.1:11434',
    model: 'qwen3:8b',
    apiKey: '',
    keepAlive: '2h',
    timeoutMs: 25000,
    enabled: true,
  },

  telegram: { enabled: false, token: '', chatId: '' },
  roby: { enabled: false },

  sidecar: { autostart: true, port: 8791, python: '' },
}

function deepMerge(base, extra) {
  const out = Array.isArray(base) ? [...base] : { ...base }
  for (const [k, v] of Object.entries(extra || {})) {
    if (v && typeof v === 'object' && !Array.isArray(v) && typeof base?.[k] === 'object' && !Array.isArray(base?.[k])) {
      out[k] = deepMerge(base[k], v)
    } else if (v !== undefined) {
      out[k] = v
    }
  }
  return out
}

export function load() {
  let saved = {}
  try {
    saved = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'))
  } catch { /* primera vez */ }
  const cfg = deepMerge(DEFAULTS, saved)

  // Las variables de entorno mandan, para docker y para probar sin tocar el json
  const env = process.env
  if (env.HELADERA_PORT) cfg.port = Number(env.HELADERA_PORT)
  if (env.HELADERA_HOST) cfg.host = env.HELADERA_HOST
  if (env.HELADERA_LANGUAGE) cfg.language = env.HELADERA_LANGUAGE
  if (env.HELADERA_TOKEN) { cfg.auth.token = env.HELADERA_TOKEN; cfg.auth.enabled = true }
  if (env.HELADERA_NO_AUTH) cfg.auth.enabled = false
  for (const part of ['stt', 'tts', 'llm']) {
    const P = part.toUpperCase()
    if (env[`HELADERA_${P}_PROVIDER`]) cfg[part].provider = env[`HELADERA_${P}_PROVIDER`]
    if (env[`HELADERA_${P}_URL`]) cfg[part].url = env[`HELADERA_${P}_URL`]
    if (env[`HELADERA_${P}_MODEL`]) cfg[part].model = env[`HELADERA_${P}_MODEL`]
    if (env[`HELADERA_${P}_KEY`]) cfg[part].apiKey = env[`HELADERA_${P}_KEY`]
  }
  if (env.HELADERA_TTS_VOICE) cfg.tts.voice = env.HELADERA_TTS_VOICE
  if (env.HELADERA_TELEGRAM_TOKEN) { cfg.telegram.token = env.HELADERA_TELEGRAM_TOKEN; cfg.telegram.enabled = true }
  if (env.HELADERA_TELEGRAM_CHAT_ID) cfg.telegram.chatId = env.HELADERA_TELEGRAM_CHAT_ID

  // El token se genera una sola vez y se guarda. Si no lo persistiéramos, cada
  // reinicio inventaría uno nuevo y la tablet quedaría afuera sin motivo.
  if (!cfg.auth.token) {
    cfg.auth.token = crypto.randomBytes(16).toString('hex')
    try { save(cfg) } catch { /* solo lectura: seguimos con el token en memoria */ }
  }
  return cfg
}

export function save(cfg) {
  fs.mkdirSync(HOME, { recursive: true })
  const tmp = CONFIG_PATH + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2))
  fs.renameSync(tmp, CONFIG_PATH)
  try { fs.chmodSync(CONFIG_PATH, 0o600) } catch { /* windows */ }
  return cfg
}

export function ensureHome() {
  fs.mkdirSync(HOME, { recursive: true })
  fs.mkdirSync(AUDIO_DIR, { recursive: true })
}

/** Lo que se puede mostrar sin filtrar claves ajenas. */
export function redact(cfg) {
  const c = JSON.parse(JSON.stringify(cfg))
  for (const p of ['stt', 'tts', 'llm']) if (c[p].apiKey) c[p].apiKey = '••••' + c[p].apiKey.slice(-4)
  if (c.telegram.token) c.telegram.token = '••••' + c.telegram.token.slice(-4)
  return c
}
