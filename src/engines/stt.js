/**
 * Voz a texto. Tres formas de resolverlo, todas intercambiables desde el panel:
 *
 *   local     → el sidecar de heladera (faster-whisper), corre acá al lado
 *   whispercpp → whisper.cpp server, que corre el modelo en GPU por Vulkan
 *   openai    → cualquier API compatible con /v1/audio/transcriptions
 *   custom    → tu endpoint, recibe un wav y devuelve {text}
 *
 * El "fast" es para la palabra de activación: se llama seguido, así que usa un
 * modelo más chico. Si el proveedor no distingue, usa el mismo.
 */

function langOf(cfg) {
  return cfg.stt.language || cfg.language || 'es'
}

async function local(cfg, wav, { fast, prompt }) {
  const form = new FormData()
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'a.wav')
  form.append('model', fast ? cfg.stt.fastModel : cfg.stt.model)
  form.append('language', langOf(cfg))
  if (prompt) form.append('prompt', prompt)
  const r = await fetch(cfg.stt.url.replace(/\/$/, '') + '/stt', { method: 'POST', body: form })
  if (!r.ok) throw new Error(`sidecar stt ${r.status}: ${(await r.text()).slice(0, 200)}`)
  return r.json()
}

/**
 * whisper.cpp server. Vale la pena cuando hay GPU: large-v3-turbo por Vulkan
 * tarda 0,7 s donde faster-whisper en CPU tarda 4,6 s, y encima transcribe
 * mejor que los modelos chicos.
 */
async function whispercpp(cfg, wav) {
  const form = new FormData()
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'a.wav')
  form.append('language', langOf(cfg))
  form.append('response_format', 'json')
  form.append('temperature', '0')
  const headers = {}
  if (cfg.stt.apiKey) headers.Authorization = `Bearer ${cfg.stt.apiKey}`
  const r = await fetch(cfg.stt.url.replace(/\/$/, '') + '/inference', { method: 'POST', headers, body: form })
  if (!r.ok) throw new Error(`whisper.cpp ${r.status}: ${(await r.text()).slice(0, 200)}`)
  const j = await r.json()
  // Devuelve el texto con un espacio y un salto de línea pegados
  return { text: String(j.text || '').trim() }
}

async function openai(cfg, wav, { fast }) {
  const form = new FormData()
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'a.wav')
  form.append('model', cfg.stt.model || 'whisper-1')
  form.append('language', langOf(cfg))
  if (cfg.stt.prompt && !fast) form.append('prompt', cfg.stt.prompt)
  const headers = {}
  if (cfg.stt.apiKey) headers.Authorization = `Bearer ${cfg.stt.apiKey}`
  const base = cfg.stt.url.replace(/\/$/, '').replace(/\/v1$/, '')
  const r = await fetch(`${base}/v1/audio/transcriptions`, { method: 'POST', headers, body: form })
  if (!r.ok) throw new Error(`stt ${r.status}: ${(await r.text()).slice(0, 200)}`)
  const j = await r.json()
  return { text: (j.text || '').trim() }
}

async function custom(cfg, wav) {
  const headers = { 'Content-Type': 'audio/wav' }
  if (cfg.stt.apiKey) headers.Authorization = `Bearer ${cfg.stt.apiKey}`
  const r = await fetch(cfg.stt.url, { method: 'POST', headers, body: wav })
  if (!r.ok) throw new Error(`stt ${r.status}`)
  const j = await r.json()
  return { text: (j.text || j.transcript || '').trim() }
}

export async function transcribe(cfg, wav, opts = {}) {
  const t0 = Date.now()
  const impl = { local, whispercpp, openai, custom }[cfg.stt.provider] || local
  const out = await impl(cfg, wav, { fast: !!opts.fast, prompt: opts.fast ? '' : cfg.stt.prompt })
  return {
    text: (out.text || '').trim(),
    ms: Date.now() - t0,
    model: out.model || (opts.fast ? cfg.stt.fastModel : cfg.stt.model),
    provider: cfg.stt.provider,
  }
}

export async function check(cfg) {
  try {
    if (cfg.stt.provider === 'local') {
      const r = await fetch(cfg.stt.url.replace(/\/$/, '') + '/health', { signal: AbortSignal.timeout(4000) })
      const j = await r.json()
      return { ok: !!j.stt, detail: j.sttModels ? `modelos: ${j.sttModels.join(', ')}` : 'sidecar arriba' }
    }
    if (cfg.stt.provider === 'whispercpp') {
      const r = await fetch(cfg.stt.url.replace(/\/$/, '') + '/', { signal: AbortSignal.timeout(4000) })
      return { ok: r.status < 500, detail: `whisper.cpp · ${cfg.stt.url}` }
    }
    return { ok: true, detail: `${cfg.stt.provider} · ${cfg.stt.url}` }
  } catch (e) {
    return { ok: false, detail: String(e.message || e) }
  }
}
