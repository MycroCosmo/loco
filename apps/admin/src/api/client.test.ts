import { describe, expect, it, vi } from 'vitest'
import { createClient, csrfCookie, ApiError, nothing } from './client'
import { identity, list, text } from './contracts'

describe('CMS HTTP boundary', () => {
  it('uses the current CSRF cookie for each write and does not set a multipart boundary', async () => {
    let cookie = 'CMSSESSION=hidden; XSRF-TOKEN=first'
    const transport = vi.fn<typeof fetch>().mockImplementation(async () => new Response(null, {status: 204}))
    const client = createClient(transport, () => cookie)
    await client.send('/auth/logout', 'POST', undefined, nothing)
    cookie = 'XSRF-TOKEN=second'
    await client.send('/files', 'POST', new FormData(), nothing)
    expect(new Headers(transport.mock.calls[0]?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('first')
    expect(new Headers(transport.mock.calls[1]?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('second')
    expect(new Headers(transport.mock.calls[1]?.[1]?.headers).has('Content-Type')).toBe(false)
    expect(transport.mock.calls[1]?.[1]?.credentials).toBe('same-origin')
  })
  it('shares concurrent GET transport while parsing each response', async () => {
    const transport = vi.fn<typeof fetch>().mockImplementation(async () => Response.json(['a']))
    const client = createClient(transport, () => '')
    expect(await Promise.all([client.read('/roles', list(text)), client.read('/roles', list(text))])).toEqual([['a'], ['a']])
    expect(transport).toHaveBeenCalledTimes(1)
    await client.read('/roles', list(text))
    expect(transport).toHaveBeenCalledTimes(2)
  })
  it.each(['CSRF_INVALID', 'PERMISSION_DENIED', 'STALE_REVISION', 'SESSION_EXPIRED'])(
    'preserves %s without retrying writes', async code => {
      const transport = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({ code, message: 'denied' }, {status: 403}))
      const client = createClient(transport, () => '')
      const failure = vi.fn(async (_error: ApiError) => {})
      client.onFailure(failure)
      await expect(client.send('/menus/id/position', 'PUT', {}, nothing)).rejects.toMatchObject({code})
      expect(transport).toHaveBeenCalledTimes(1)
      expect(failure).toHaveBeenCalledTimes(1)
    })
  it('does not turn HTML proxy failures into JSON parsing crashes or expose its body', async () => {
    const client = createClient(async () => new Response('<secret>', {status: 502}), () => '')
    await expect(client.read('/auth/me', identity)).rejects.toThrow('요청 실패 (502)')
  })
  it('rejects an identity whose privilege flag is a string', () => {
    expect(() => identity({id:'a',loginId:'b',displayName:'c',active:true,authVersion:1,mustChangePassword:false,systemAdmin:'true'})).toThrow()
  })
  it('reads only the exact CSRF cookie', () => {
    expect(csrfCookie('XSRF-TOKEN-OTHER=bad; XSRF-TOKEN=good%2Dtoken')).toBe('good-token')
    expect(csrfCookie('CMSSESSION=secret')).toBe('')
  })
})
