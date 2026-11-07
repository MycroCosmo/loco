import { object } from './contracts'

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message) }
}
export function csrfCookie(cookie: string): string {
  const part = cookie.split(';').map(s => s.trim()).find(s => s.startsWith('XSRF-TOKEN='))
  return part ? decodeURIComponent(part.substring('XSRF-TOKEN='.length)) : ''
}
export function message(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
  const labels: Record<string, string> = {
    CSRF_INVALID: '보안 토큰이 만료되었습니다. 갱신 후 내용을 확인하고 다시 시도하세요.',
    SESSION_EXPIRED: '세션이 만료되었습니다. 다시 로그인하세요.',
    AUTHENTICATION_REQUIRED: '로그인이 필요합니다.', UNAUTHORIZED: '인증 정보를 확인하세요.',
    PASSWORD_CHANGE_REQUIRED: '먼저 비밀번호를 변경하세요.', PERMISSION_DENIED: '이 작업의 권한이 없습니다. 내 접근 근거를 확인하세요.',
    STALE_REVISION: '다른 관리자가 메뉴를 변경했습니다. 최신 트리에서 위치를 다시 확인하세요.',
    RATE_LIMITED: '로그인은 IP·계정별 분당 5회까지입니다. 잠시 후 다시 시도하세요.',
  }
  return labels[error.code] ?? error.message
}
export function createClient(transport: typeof fetch, cookie: () => string) {
  const reads = new Map<string, Promise<Response>>()
  let failure: (error: ApiError) => Promise<void> = async () => {}
  async function response(path: string, method = 'GET', body?: unknown): Promise<Response> {
    const headers = new Headers()
    const form = body instanceof FormData
    if (body !== undefined && !form) headers.set('Content-Type', 'application/json')
    if (!['GET', 'HEAD'].includes(method)) headers.set('X-XSRF-TOKEN', csrfCookie(cookie()))
    const send = () => transport('/admin/api/core' + path, { method, credentials: 'same-origin', headers,
      body: body === undefined ? undefined : form ? body : JSON.stringify(body) })
    let request = method === 'GET' ? reads.get(path) : undefined
    if (!request) {
      request = send()
      if (method === 'GET') {
        reads.set(path, request)
        void request.finally(() => reads.delete(path)).catch(() => {})
      }
    }
    const result = (await request).clone()
    if (!result.ok) {
      let raw: Record<string, unknown> = {}
      try { raw = object(await result.json()) } catch { /* Proxy errors may have no JSON body. */ }
      const error = new ApiError(result.status, typeof raw.code === 'string' ? raw.code : 'HTTP_ERROR',
        typeof raw.message === 'string' ? raw.message : `요청 실패 (${result.status})`)
      await failure(error)
      throw error
    }
    return result
  }
  return {
    onFailure(handler: typeof failure) { failure = handler },
    async read<T>(path: string, parse: (value: unknown) => T) { return parse(await (await response(path)).json()) },
    async send<T>(path: string, method: string, body: unknown, parse: (value: unknown) => T) {
      const result = await response(path, method, body)
      return parse(result.status === 204 ? undefined : await result.json())
    },
    async csrf() { await response('/auth/csrf') },
    async download(path: string) { return (await response(path)).blob() },
  }
}
export const api = createClient((...args) => fetch(...args), () => document.cookie)
export const nothing = (_value: unknown): void => {}
