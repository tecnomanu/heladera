/**
 * ¿Dijo la palabra de activación?
 *
 * Comparamos por palabras y no por texto suelto: el modelo chico escribe
 * "ok te ableta" y eso tiene que valer, pero "la tableta está prendida" no
 * puede despertarla. Por eso exigimos disparador + sustantivo pegados y al
 * principio de la frase, tolerando que el sustantivo venga partido en dos.
 */

// whisper tiny escribe "oc", "okey", "o k"... todas valen como disparador.
const EXTRA = ['ok', 'oka', 'okey', 'okay', 'okei', 'oc', 'o', 'hey', 'ey', 'eh', 'che', 'hola']

export function strip(s) {
  return String(s || '').toLowerCase().normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

export function lev(a, b) {
  if (a === b) return 0
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i)
  for (let i = 1; i <= a.length; i++) {
    const cur = [i]
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] !== b[j - 1] ? 1 : 0))
    }
    prev = cur
  }
  return prev[b.length]
}

function parts(wakeWords) {
  const triggers = new Set(EXTRA)
  const nouns = new Set()
  for (const w of wakeWords || []) {
    const p = strip(w).split(' ').filter(Boolean)
    if (p.length >= 2) { triggers.add(p[0]); nouns.add(p.slice(1).join('')) }
    else if (p.length === 1) nouns.add(p[0])
  }
  return { triggers, nouns }
}

export function match(text, wakeWords) {
  const toks = strip(text).split(' ').filter(Boolean)
  if (!toks.length) return null
  const { triggers, nouns } = parts(wakeWords)
  for (let i = 0; i < Math.min(3, toks.length); i++) {
    if (!triggers.has(toks[i])) continue
    for (const join of [1, 2]) {
      if (i + join >= toks.length) break
      const cand = toks.slice(i + 1, i + 1 + join).join('')
      for (const n of nouns) {
        if (lev(cand, n) <= Math.max(1, Math.floor(n.length / 5))) return `${toks[i]} ${cand}`
      }
    }
  }
  return null
}
