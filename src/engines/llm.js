/**
 * El que entiende lo que pediste. Sirve cualquier ollama o cualquier API
 * compatible con OpenAI; se elige desde el panel.
 */

export async function chat(cfg, messages, { maxTokens = 200, temperature = 0 } = {}) {
  const t0 = Date.now()
  const base = cfg.llm.url.replace(/\/$/, '')
  const ctrl = AbortSignal.timeout(cfg.llm.timeoutMs || 25000)
  let text = ''

  if (cfg.llm.provider === 'ollama') {
    const r = await fetch(`${base}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: ctrl,
      body: JSON.stringify({
        model: cfg.llm.model, messages, stream: false, think: false,
        keep_alive: cfg.llm.keepAlive,
        options: { temperature, num_predict: maxTokens, stop: ['Dijo:', 'Lista:'] },
      }),
    })
    if (!r.ok) throw new Error(`ollama ${r.status}: ${(await r.text()).slice(0, 200)}`)
    text = (await r.json())?.message?.content || ''
  } else {
    const headers = { 'Content-Type': 'application/json' }
    if (cfg.llm.apiKey) headers.Authorization = `Bearer ${cfg.llm.apiKey}`
    const r = await fetch(`${base.replace(/\/v1$/, '')}/v1/chat/completions`, {
      method: 'POST', headers, signal: ctrl,
      body: JSON.stringify({
        model: cfg.llm.model, messages, temperature,
        max_tokens: maxTokens, stop: ['Dijo:', 'Lista:'],
        // Los qwen3 razonan en voz alta antes de contestar y toda la respuesta
        // queda adentro del bloque de pensamiento. Acá no hay nada que razonar:
        // son diez palabras a convertir en líneas.
        chat_template_kwargs: { enable_thinking: false },
        reasoning_effort: 'none',
      }),
    })
    if (!r.ok) throw new Error(`llm ${r.status}: ${(await r.text()).slice(0, 200)}`)
    const msg = (await r.json())?.choices?.[0]?.message || {}
    text = msg.content || ''
  }
  // Por las dudas: si igual razonó, nos quedamos con lo que dijo después.
  text = String(text).replace(/<think>[\s\S]*?<\/think>/gi, '').replace(/^[\s\S]*<\/think>/i, '').trim()
  return { text, ms: Date.now() - t0 }
}

/** Deja el modelo cargado para que el primer pedido no espere el arranque. */
export async function warm(cfg) {
  try {
    if (cfg.llm.provider === 'ollama') {
      await fetch(cfg.llm.url.replace(/\/$/, '') + '/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: AbortSignal.timeout(90000),
        body: JSON.stringify({
          model: cfg.llm.model, prompt: 'ok', stream: false, think: false,
          keep_alive: cfg.llm.keepAlive, options: { num_predict: 1 },
        }),
      })
    }
  } catch { /* ya se calentará solo en el primer pedido */ }
}

export async function check(cfg) {
  try {
    const { text, ms } = await chat(cfg, [{ role: 'user', content: 'Respondé solo: ok' }], { maxTokens: 5 })
    return { ok: true, detail: `${cfg.llm.model} · ${ms} ms`, sample: text.trim().slice(0, 40) }
  } catch (e) {
    return { ok: false, detail: String(e.message || e) }
  }
}

export async function models(cfg) {
  try {
    const base = cfg.llm.url.replace(/\/$/, '')
    if (cfg.llm.provider === 'ollama') {
      const r = await fetch(`${base}/api/tags`, { signal: AbortSignal.timeout(5000) })
      return (await r.json()).models?.map(m => m.name) || []
    }
    const headers = {}
    if (cfg.llm.apiKey) headers.Authorization = `Bearer ${cfg.llm.apiKey}`
    const r = await fetch(`${base.replace(/\/v1$/, '')}/v1/models`, { headers, signal: AbortSignal.timeout(5000) })
    return (await r.json()).data?.map(m => m.id) || []
  } catch { return [] }
}
