/**
 * El sidecar de Python: whisper y piper corriendo al lado.
 *
 * Es opcional a propósito. Si ya tenés un whisper o un piper andando en otro
 * lado, lo apuntás desde el panel y esto nunca se instala. Si no, `heladera
 * init` arma un venv acá y este módulo lo levanta y lo vigila.
 */
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ENGINES_DIR, HOME } from './config.js'

const PKG_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
export const VENV = path.join(ENGINES_DIR, 'venv')
export const VOICES_DIR = path.join(ENGINES_DIR, 'voices')
const LOG = path.join(HOME, 'sidecar.log')

let child = null
let starting = null

export function venvPython() {
  const p = process.platform === 'win32'
    ? path.join(VENV, 'Scripts', 'python.exe')
    : path.join(VENV, 'bin', 'python')
  return fs.existsSync(p) ? p : null
}

export function installed() {
  return !!venvPython()
}

export function findPython() {
  for (const c of ['python3.12', 'python3.11', 'python3', 'python']) {
    const r = spawnSync(c, ['-c', 'import sys;print(sys.version_info[0],sys.version_info[1])'], { encoding: 'utf8' })
    if (r.status === 0) {
      const [maj, min] = r.stdout.trim().split(' ').map(Number)
      if (maj === 3 && min >= 9) return c
    }
  }
  return null
}

/** Crea el venv e instala whisper y piper. Devuelve un stream de progreso. */
export function install(cfg, { onLine } = {}) {
  return new Promise((resolve, reject) => {
    const py = cfg.sidecar.python || findPython()
    if (!py) return reject(new Error('No encontré Python 3.9+. Instalalo o usá un STT/TTS remoto.'))
    fs.mkdirSync(ENGINES_DIR, { recursive: true })
    const say = l => { onLine?.(l); }
    say(`usando ${py}`)
    const steps = [
      [py, ['-m', 'venv', VENV]],
      [path.join(VENV, 'bin', 'pip'), ['install', '-q', '--upgrade', 'pip', 'wheel']],
      [path.join(VENV, 'bin', 'pip'), ['install', '-q', 'fastapi', 'uvicorn', 'python-multipart', 'faster-whisper', 'piper-tts']],
    ]
    ;(async () => {
      for (const [cmd, args] of steps) {
        say(`> ${path.basename(cmd)} ${args.slice(0, 3).join(' ')}…`)
        const r = spawnSync(cmd, args, { encoding: 'utf8' })
        if (r.status !== 0) throw new Error((r.stderr || r.stdout || '').slice(-400))
      }
      say('listo')
    })().then(resolve, reject)
  })
}

/** Baja una voz de piper desde HuggingFace (rhasspy/piper-voices). */
export async function downloadVoice(name) {
  fs.mkdirSync(VOICES_DIR, { recursive: true })
  const onnx = path.join(VOICES_DIR, `${name}.onnx`)
  if (fs.existsSync(onnx) && fs.statSync(onnx).size > 1e6) return onnx
  const m = /^([a-z]{2})_([A-Z]{2})-(.+)-(x_low|low|medium|high)$/.exec(name)
  if (!m) throw new Error(`nombre de voz raro: ${name}`)
  const base = `https://huggingface.co/rhasspy/piper-voices/resolve/main/${m[1]}/${m[1]}_${m[2]}/${m[3]}/${m[4]}`
  for (const ext of ['.onnx', '.onnx.json']) {
    const r = await fetch(`${base}/${name}${ext}`)
    if (!r.ok) throw new Error(`no pude bajar ${name}${ext}: http ${r.status}`)
    fs.writeFileSync(path.join(VOICES_DIR, name + ext), Buffer.from(await r.arrayBuffer()))
  }
  return onnx
}

export function status() {
  return {
    installed: installed(),
    running: !!child && child.exitCode === null,
    pid: child?.pid || null,
    venv: VENV,
    voices: fs.existsSync(VOICES_DIR) ? fs.readdirSync(VOICES_DIR).filter(f => f.endsWith('.onnx')).map(f => f.replace('.onnx', '')) : [],
  }
}

export function tail(lines = 40) {
  try { return fs.readFileSync(LOG, 'utf8').split('\n').slice(-lines).join('\n') } catch { return '' }
}

export async function ensure(cfg) {
  if (cfg.stt.provider !== 'local' && cfg.tts.provider !== 'local') return status()
  if (child && child.exitCode === null) return status()
  if (starting) return starting
  const py = venvPython()
  if (!py) throw new Error('el sidecar no está instalado: corré `heladera init` o elegí un motor remoto')

  starting = new Promise((resolve, reject) => {
    const out = fs.openSync(LOG, 'a')
    child = spawn(py, [path.join(PKG_DIR, 'engines-py', 'engines.py')], {
      env: {
        ...process.env,
        HELADERA_SIDECAR_PORT: String(cfg.sidecar.port),
        HELADERA_VOICES_DIR: VOICES_DIR,
        HELADERA_STT_MODEL: cfg.stt.model,
        HELADERA_STT_FAST_MODEL: cfg.stt.fastModel,
        HELADERA_TTS_VOICE: cfg.tts.voice,
      },
      stdio: ['ignore', out, out],
      detached: false,
    })
    child.on('exit', code => { if (code) fs.appendFileSync(LOG, `\n[sidecar salió con ${code}]\n`) })

    // Esperamos a que conteste, sin dejarlo colgado si nunca arranca
    const deadline = Date.now() + 90000
    const poll = async () => {
      try {
        const r = await fetch(`http://127.0.0.1:${cfg.sidecar.port}/health`, { signal: AbortSignal.timeout(2000) })
        if (r.ok) { starting = null; return resolve(status()) }
      } catch { /* todavía cargando modelos */ }
      if (Date.now() > deadline) { starting = null; return reject(new Error('el sidecar no respondió en 90 s, mirá `heladera logs`')) }
      setTimeout(poll, 1000)
    }
    setTimeout(poll, 800)
  })
  return starting
}

export function stop() {
  if (child && child.exitCode === null) child.kill()
  child = null
}
