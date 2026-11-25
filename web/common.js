/* Lo que comparten las tres páginas: token, fetch y estilo. */
export const TOKEN_KEY = 'heladera.token'

export function grabToken() {
  const m = /[#&]token=([a-f0-9]+)/i.exec(location.hash)
  if (m) {
    localStorage.setItem(TOKEN_KEY, m[1])
    history.replaceState(null, '', location.pathname + location.search)
  }
  return localStorage.getItem(TOKEN_KEY) || ''
}

export async function api(path, opts = {}) {
  const token = localStorage.getItem(TOKEN_KEY) || ''
  const headers = { ...(opts.headers || {}) }
  if (token) headers.Authorization = 'Bearer ' + token
  if (opts.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json'
  const r = await fetch(path, { ...opts, headers })
  // /pair links Android. Redirecting an unlinked browser there creates a dead end.
  if (r.status === 401) throw new Error('Esta web no está vinculada. Abrí un enlace de acceso o recuperala desde Conectar app.')
  const text = await r.text()
  let data = {}
  try { data = text ? JSON.parse(text) : {} } catch { data = { raw: text } }
  if (!r.ok) throw new Error(data.error || `http ${r.status}`)
  return data
}

/**
 * Avisito abajo. Con `action` queda un "Deshacer" al lado, como el de Google
 * Tasks: es la red de contencion de cualquier cosa que reacomode la lista.
 */
export function toast(msg, bad = false, action = null) {
  let t = document.getElementById('toast')
  if (!t) {
    t = document.createElement('div')
    t.id = 'toast'
    document.body.appendChild(t)
  }
  t.innerHTML = ''
  const span = document.createElement('span')
  span.textContent = msg
  t.appendChild(span)
  if (action) {
    const b = document.createElement('button')
    b.className = 'undo'
    b.textContent = action.label || 'Deshacer'
    b.onclick = () => { t.className = 'toast'; action.fn() }
    t.appendChild(b)
  }
  t.className = 'toast on' + (bad ? ' bad' : '') + (action ? ' act' : '')
  clearTimeout(t._h)
  t._h = setTimeout(() => { t.className = 'toast' }, action ? 6000 : 2600)
}
