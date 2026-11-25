/**
 * La charla de voz en vivo (voz/server.py): la tablet habla y la heladera contesta mientras piensa, como una llamada.
 *
 * Es Python aparte del núcleo porque escucha y habla con modelos locales (VAD, whisper, voz). Vive en su propio
 * entorno (~/.heladera/voz/venv) y lee la misma config que el resto: puerto, token, whisper y modelo.
 *
 *   heladera voz instalar     arma el entorno e instala lo que hace falta (una vez)
 *   heladera voz              la levanta en primer plano (puerto 8796)
 *   heladera voz servicio     escribe la unidad de systemd para que arranque sola
 */
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { HOME, CONFIG_PATH } from './config.js'
import { findPython } from './sidecar.js'

const PKG_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
export const VOZ_DIR = path.join(PKG_DIR, 'voz')
export const VENV = path.join(HOME, 'voz', 'venv')
export const PORT_DEFAULT = 8796

export function venvPython() {
  const p = process.platform === 'win32'
    ? path.join(VENV, 'Scripts', 'python.exe')
    : path.join(VENV, 'bin', 'python')
  return fs.existsSync(p) ? p : null
}

export function installed() { return !!venvPython() }

/** Crea el entorno e instala voz/requirements.txt. Tarda unos minutos la primera vez (~1 GB). */
export function install({ python, onLine } = {}) {
  const py = python || findPython()
  if (!py) throw new Error('No encontré Python 3.9+. Instalalo y volvé a probar.')
  fs.mkdirSync(path.dirname(VENV), { recursive: true })
  const pip = process.platform === 'win32' ? path.join(VENV, 'Scripts', 'pip.exe') : path.join(VENV, 'bin', 'pip')
  const steps = [
    ['armo el entorno', py, ['-m', 'venv', VENV]],
    ['actualizo pip', pip, ['install', '-q', '--upgrade', 'pip', 'wheel']],
    ['instalo escuchar, entender y hablar', pip, ['install', '-q', '-r', path.join(VOZ_DIR, 'requirements.txt')]],
  ]
  for (const [what, cmd, args] of steps) {
    onLine?.(what + '…')
    const r = spawnSync(cmd, args, { stdio: 'inherit' })
    if (r.status !== 0) throw new Error(`falló: ${what}`)
  }
}

/** Levanta la charla en primer plano. Las variables VOZ_* de la doc (voz/README.md) pisan lo que diga la config. */
export function run({ port } = {}) {
  const py = venvPython()
  if (!py) throw new Error('Falta instalarla: heladera voz instalar')
  const env = { ...process.env, PYTHONUNBUFFERED: '1', VOZ_HELADERA_CONFIG: CONFIG_PATH }
  if (port) env.VOZ_PORT = String(port)
  const child = spawn(py, [path.join(VOZ_DIR, 'server.py')], { cwd: VOZ_DIR, env, stdio: 'inherit' })
  for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => child.kill(s))
  return new Promise(resolve => child.on('exit', code => resolve(code)))
}

/** La unidad de systemd para que la charla arranque sola, con el usuario de quien la instaló. */
export function systemdUnit() {
  const user = os.userInfo().username
  const node = process.execPath
  const cli = path.join(PKG_DIR, 'src', 'cli.js')
  return `[Unit]
Description=Heladera — charla de voz en vivo (la tablet se conecta acá)
After=network-online.target heladera.service
Wants=network-online.target

[Service]
User=${user}
Environment=HELADERA_HOME=${HOME}
# Voz, puerto o whisper distintos: Environment=VOZ_VOICE=es_AR-daniela-high (ver voz/README.md)
ExecStart=${node} ${cli} voz
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
`
}
