#!/usr/bin/env node
/**
 * heladera — lista de compras por voz.
 *
 *   heladera init      configura todo preguntando
 *   heladera server    levanta la API y el panel
 *   heladera pair      link para configurar la tablet
 *   heladera status    qué está andando y qué no
 *   heladera voz       la charla de voz en vivo (instalar | servicio)
 */
import readline from 'node:readline/promises'
import { stdin, stdout } from 'node:process'
import fs from 'node:fs'
import * as config from './config.js'
import * as sidecar from './sidecar.js'
import * as server from './server.js'
import * as store from './store.js'
import * as voz from './voz.js'

const C = {
  dim: s => `\x1b[2m${s}\x1b[0m`,
  lima: s => `\x1b[38;5;148m${s}\x1b[0m`,
  bold: s => `\x1b[1m${s}\x1b[0m`,
  red: s => `\x1b[38;5;210m${s}\x1b[0m`,
  cyan: s => `\x1b[38;5;117m${s}\x1b[0m`,
}
const say = (...a) => console.log(...a)
const step = s => say('\n' + C.lima('▸ ') + C.bold(s))
const ok = s => say('  ' + C.lima('✓') + ' ' + s)
const bad = s => say('  ' + C.red('✗') + ' ' + s)
const info = s => say('  ' + C.dim(s))

function banner() {
  say('')
  say(C.lima('  ┌───────┐'))
  say(C.lima('  │ ▪   ▪ │') + '   ' + C.bold('heladera'))
  say(C.lima('  ├───────┤') + '   ' + C.dim('la lista de compras, por voz'))
  say(C.lima('  │       │'))
  say(C.lima('  └───────┘'))
}

async function ask(rl, q, def_ = '') {
  const d = def_ ? C.dim(` (${def_})`) : ''
  const a = (await rl.question(`  ${q}${d}: `)).trim()
  return a || def_
}

async function choose(rl, q, options, def_ = 0) {
  say(`\n  ${q}`)
  options.forEach((o, i) => say(`    ${C.cyan(String(i + 1))}. ${o.label}${o.hint ? C.dim('  — ' + o.hint) : ''}`))
  const a = (await rl.question(`  elegí${C.dim(` (${def_ + 1})`)}: `)).trim()
  const i = a ? Number(a) - 1 : def_
  return options[Number.isInteger(i) && options[i] ? i : def_].value
}

// ------------------------------------------------------------------- init

async function cmdInit() {
  banner()
  const rl = readline.createInterface({ input: stdin, output: stdout })
  const cfg = config.load()
  try {
    step('Idioma')
    info('whisper acierta bastante más cuando le decís en qué idioma le hablás')
    cfg.language = await ask(rl, 'código de idioma', cfg.language || 'es')

    step('El modelo que entiende los pedidos')
    cfg.llm.provider = await choose(rl, '¿Qué usás?', [
      { label: 'Ollama', value: 'ollama', hint: 'local, gratis' },
      { label: 'API compatible con OpenAI', value: 'openai', hint: 'OpenAI, Groq, LM Studio, vLLM…' },
    ], cfg.llm.provider === 'openai' ? 1 : 0)
    cfg.llm.url = await ask(rl, 'dirección', cfg.llm.provider === 'ollama' ? cfg.llm.url : 'https://api.openai.com')
    if (cfg.llm.provider === 'openai') cfg.llm.apiKey = await ask(rl, 'api key', cfg.llm.apiKey)
    cfg.llm.model = await ask(rl, 'modelo', cfg.llm.model)

    step('Voz a texto')
    cfg.stt.provider = await choose(rl, '¿Cómo transcribimos?', [
      { label: 'Instalar whisper acá', value: 'local', hint: 'faster-whisper, anda sin internet' },
      { label: 'Usar uno que ya tengo', value: 'openai', hint: 'cualquier /v1/audio/transcriptions' },
    ], cfg.stt.provider === 'local' ? 0 : 1)
    if (cfg.stt.provider === 'openai') {
      cfg.stt.url = await ask(rl, 'dirección', cfg.stt.url)
      cfg.stt.apiKey = await ask(rl, 'api key (si hace falta)', '')
      cfg.stt.model = await ask(rl, 'modelo', 'whisper-1')
    } else {
      cfg.stt.url = `http://127.0.0.1:${cfg.sidecar.port}`
      cfg.stt.model = await ask(rl, 'modelo para los pedidos', cfg.stt.model)
      cfg.stt.fastModel = await ask(rl, 'modelo rápido (palabra de activación)', cfg.stt.fastModel)
    }

    step('Texto a voz')
    cfg.tts.provider = await choose(rl, '¿Cómo contesta?', [
      { label: 'Instalar piper acá', value: 'local', hint: 'voz argentina, 0,2 s' },
      { label: 'Usar uno que ya tengo', value: 'openai', hint: 'OpenAI, qvox, kokoro…' },
      { label: 'Que hable la tablet', value: 'device', hint: 'sin servidor, voz de Android' },
      { label: 'Muda', value: 'none' },
    ], ['local', 'openai', 'device', 'none'].indexOf(cfg.tts.provider))
    if (cfg.tts.provider === 'openai') {
      cfg.tts.url = await ask(rl, 'dirección', cfg.tts.url)
      cfg.tts.apiKey = await ask(rl, 'api key (si hace falta)', '')
      cfg.tts.model = await ask(rl, 'modelo', cfg.tts.model)
      cfg.tts.voice = await ask(rl, 'voz', 'alloy')
    } else if (cfg.tts.provider === 'local') {
      cfg.tts.url = `http://127.0.0.1:${cfg.sidecar.port}`
      cfg.tts.voice = await ask(rl, 'voz de piper', cfg.tts.voice)
    }

    step('Telegram (opcional)')
    info('para mandarte la lista al celular cuando salís a comprar')
    const tgTok = await ask(rl, 'token del bot, o Enter para saltear', cfg.telegram.token)
    if (tgTok) {
      cfg.telegram.token = tgTok
      cfg.telegram.chatId = await ask(rl, 'chat id', cfg.telegram.chatId)
      cfg.telegram.enabled = true
    }

    step('Puerto')
    cfg.port = Number(await ask(rl, 'puerto de la API', String(cfg.port))) || cfg.port

    config.save(cfg)
    ok(`configuración guardada en ${config.CONFIG_PATH}`)

    if (cfg.stt.provider === 'local' || cfg.tts.provider === 'local') {
      step('Instalando los motores locales')
      if (sidecar.installed()) {
        ok('el entorno de Python ya estaba')
      } else {
        info('esto tarda unos minutos la primera vez (whisper pesa)')
        await sidecar.install(cfg, { onLine: info })
        ok('whisper y piper instalados')
      }
      if (cfg.tts.provider === 'local') {
        info(`bajando la voz ${cfg.tts.voice}…`)
        try { await sidecar.downloadVoice(cfg.tts.voice); ok('voz lista') }
        catch (e) { bad(String(e.message || e)) }
      }
    }

    say('')
    ok('Listo. Ahora:')
    say(`     ${C.cyan('heladera server')}   ${C.dim('levanta todo')}`)
    say(`     ${C.cyan('heladera pair')}     ${C.dim('link para configurar la tablet')}`)
    say('')
  } finally {
    rl.close()
  }
}

// ----------------------------------------------------------------- server

async function cmdServer() {
  const { cfg, url } = await server.start()
  banner()
  say('')
  ok(`API y panel en ${C.cyan(url)}`)
  if (cfg.auth.enabled) {
    say(`  ${C.dim('panel:')} ${C.cyan(url + '/admin#token=' + cfg.auth.token)}`)
    say(`  ${C.dim('lista:')} ${C.cyan(url + '/#token=' + cfg.auth.token)}`)
  }
  say(`  ${C.dim('tablet:')} ${C.cyan(url + '/pair')}   ${C.dim('abrí este link en la tablet')}`)
  say('')
  info(`stt ${cfg.stt.provider} · tts ${cfg.tts.provider} · llm ${cfg.llm.provider}/${cfg.llm.model}`)
  say('')
  const bye = () => { store.flushNow(); sidecar.stop(); process.exit(0) }
  process.on('SIGINT', bye)
  process.on('SIGTERM', bye)
}

// ------------------------------------------------------------------ otros

async function cmdPair() {
  const cfg = config.load()
  const url = `http://${server.lanIp()}:${cfg.port}`
  banner()
  say('')
  say(`  Abrí este link ${C.bold('en la tablet')} y tocá el botón:`)
  say('')
  say('     ' + C.cyan(`${url}/pair`))
  say('')
  say(`  ${C.dim('Si preferís escribirlo a mano en Ajustes de la app:')}`)
  say(`     ${C.dim('servidor')}  ${url}`)
  say(`     ${C.dim('código  ')}  ${C.bold(cfg.auth.token.slice(0, 8))}`)
  say('')
}

async function cmdStatus() {
  const cfg = config.load()
  banner()
  say('')
  const stt = await import('./engines/stt.js')
  const tts = await import('./engines/tts.js')
  const llm = await import('./engines/llm.js')
  let alive = false
  try {
    const r = await fetch(`http://127.0.0.1:${cfg.port}/api/health`, { signal: AbortSignal.timeout(3000) })
    alive = r.ok
  } catch { /* apagado */ }
  alive ? ok(`servidor andando en el puerto ${cfg.port}`) : bad(`el servidor no responde en el puerto ${cfg.port}`)

  const [s, t, l] = await Promise.all([stt.check(cfg), tts.check(cfg), llm.check(cfg)])
  for (const [name, r] of [['stt ' + cfg.stt.provider, s], ['tts ' + cfg.tts.provider, t], ['llm ' + cfg.llm.provider, l]]) {
    r.ok ? ok(`${name.padEnd(14)} ${C.dim(r.detail)}`) : bad(`${name.padEnd(14)} ${r.detail}`)
  }
  const sc = sidecar.status()
  info(`sidecar: ${sc.installed ? 'instalado' : 'no instalado'}${sc.running ? ', corriendo' : ''}${sc.voices.length ? ', voces: ' + sc.voices.join(', ') : ''}`)
  let vozViva = false
  try {
    const r = await fetch(`http://127.0.0.1:${voz.PORT_DEFAULT}/health`, { signal: AbortSignal.timeout(2000) })
    vozViva = r.ok
  } catch { /* apagada */ }
  info(`charla en vivo: ${voz.installed() ? 'instalada' : 'no instalada (heladera voz instalar)'}${vozViva ? ', andando en el ' + voz.PORT_DEFAULT : ''}`)
  const st = store.state()
  info(`lista: ${st.pending} pendientes, ${st.done} tachados`)
  say('')
}

async function cmdVoz() {
  const sub = process.argv[3] || ''
  if (sub === 'instalar' || sub === 'install') {
    banner()
    step('Instalo la charla de voz en vivo')
    info('Se baja una vez: escuchar (whisper), darse cuenta de cuándo terminaste y la voz. Unos minutos, ~1 GB.')
    voz.install({ onLine: l => info(l) })
    ok('instalada en ' + voz.VENV)
    info('La primera vez que arranca baja los modelos (VAD, fin de turno, whisper y la voz).')
    say(`
  Probala:  ${C.cyan('heladera voz')}     y para que arranque sola:  ${C.cyan('heladera voz servicio')}
`)
    return
  }
  if (sub === 'servicio' || sub === 'service') {
    const unit = voz.systemdUnit()
    const dest = '/etc/systemd/system/heladera-voz.service'
    if (process.getuid?.() === 0) {
      fs.writeFileSync(dest, unit)
      ok('escribí ' + dest)
      info('systemctl daemon-reload && systemctl enable --now heladera-voz')
    } else {
      process.stdout.write(unit)
      process.stderr.write(`
# Para instalarla:
#   heladera voz servicio | sudo tee ${dest}
#   sudo systemctl daemon-reload && sudo systemctl enable --now heladera-voz
`)
    }
    return
  }
  if (sub && sub !== 'servir' && sub !== 'serve') return cmdHelp()
  if (!voz.installed()) {
    bad('La charla todavía no está instalada.')
    info('heladera voz instalar')
    process.exit(1)
  }
  process.exit(await voz.run())
}

function cmdLogs() { say(sidecar.tail(100) || '(sin log del sidecar todavía)') }

function cmdHelp() {
  banner()
  say(`
  ${C.bold('Uso')}
    heladera init            configura todo preguntando
    heladera server          levanta la API y el panel web
    heladera pair            link para configurar la tablet
    heladera status          qué está andando y qué no
    heladera logs            log del sidecar de whisper/piper
    heladera config          muestra la configuración

  ${C.bold('Charla de voz en vivo')} ${C.dim('(la tablet habla y la heladera contesta, como una llamada)')}
    heladera voz instalar    arma su entorno de Python (una vez)
    heladera voz             la levanta (puerto 8796, pide el mismo token que la heladera)
    heladera voz servicio    la unidad de systemd para que arranque sola

  ${C.bold('Todo se puede configurar también desde el panel')}, en /admin.
  ${C.dim('La configuración vive en ' + config.CONFIG_PATH)}
`)
}

const cmds = {
  init: cmdInit, server: cmdServer, start: cmdServer, web: cmdServer,
  pair: cmdPair, status: cmdStatus, logs: cmdLogs, voz: cmdVoz,
  config: () => say(JSON.stringify(config.redact(config.load()), null, 2)),
  help: cmdHelp,
}

const cmd = process.argv[2] || 'help'
;Promise.resolve().then(() => (cmds[cmd] || cmdHelp)()).catch(e => {
  bad(String(e?.message || e))
  process.exit(1)
})
