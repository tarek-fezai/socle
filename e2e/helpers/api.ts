const apiBase = process.env.API_BASE_URL ?? process.env.BASE_URL ?? 'http://127.0.0.1'

export async function apiJson<T>(
  path: string,
  token: string,
  init: RequestInit = {},
): Promise<T> {
  const res = await fetch(`${apiBase}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(init.headers ?? {}),
    },
  })
  if (!res.ok) {
    throw new Error(`${init.method ?? 'GET'} ${path} → ${res.status}: ${await res.text()}`)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export type SpaceSummary = { id: string; name: string }

export type TemplateSummary = { id: string; name: string }

export type DocumentResponse = { id: string; title: string }

export type GlobalRole = { id: string; name: string }
