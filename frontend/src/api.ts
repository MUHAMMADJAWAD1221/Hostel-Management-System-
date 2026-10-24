const base = import.meta.env.VITE_API_URL ?? ''
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const method = (options.method ?? 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrfResponse = await fetch(`${base}/api/auth/csrf`, { credentials: 'include' })
    const csrf = csrfResponse.ok ? await csrfResponse.json() as { token?: string } : null
    if (!csrf?.token) throw new Error(`Unable to prepare secure request (${csrfResponse.status})`)
    headers.set('X-XSRF-TOKEN', csrf.token)
  }
  const res = await fetch(`${base}${path}`, { ...options, headers, credentials: 'include' })
  if (res.status === 204) return undefined as T
  const type = res.headers.get('content-type') ?? ''
  if (!res.ok) { const data = type.includes('json') ? await res.json().catch(() => null) : null; throw new Error(data?.message ?? `Request failed (${res.status})`) }
  return (type.includes('json') ? res.json() : res.blob()) as Promise<T>
}
export const api = {
  get: <T>(p: string) => request<T>(p),
  post: <T>(p: string, body?: unknown) => request<T>(p, { method: 'POST', body: body instanceof FormData ? body : JSON.stringify(body ?? {}) }),
  put: <T>(p: string, body: unknown) => request<T>(p, { method: 'PUT', body: JSON.stringify(body) }),
  del: <T>(p: string) => request<T>(p, { method: 'DELETE' }),
  file: (p: string) => request<Blob>(p),
  base
}
