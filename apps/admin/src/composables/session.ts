import { reactive, readonly } from 'vue'
import { api, locoApi, ApiError, message, nothing } from '../api/client'
import { historyResources } from '../views/reportModel'
import { identity, explanation, tree, type Identity, type Tree } from '../api/contracts'

const state = reactive<{user: Identity | null; ready: boolean; notice: string; epoch: number; navigation: Tree; historyResources: string[]}>({
  user: null, ready: false, notice: '', epoch: 0, navigation: { revision: 0, nodes: [] }, historyResources: [] })
const grants = new Map<string, boolean>()
const pendingGrants = new Map<string, Promise<boolean>>()
let grantGeneration = 0
function invalidateGrants() { grantGeneration++; grants.clear(); pendingGrants.clear() }
let loading: Promise<void> | undefined
let csrfRefresh: Promise<void> | undefined
let navigate: (path: string) => void = () => {}
function clear() {
  api.invalidateSession()
  locoApi.invalidateSession()
  state.user = null; state.navigation = { revision: 0, nodes: [] }; state.historyResources = []; state.epoch++
  invalidateGrants(); loading = undefined
}
async function refresh() {
  if (loading) return loading
  const epoch = state.epoch
  const pending = (async () => {
    const user = await api.read('/auth/me', identity)
    if (epoch !== state.epoch) return
    state.user = user
    if (!user.mustChangePassword) {
      const navigation = await api.read('/navigation', tree)
      if (epoch === state.epoch) state.navigation = navigation
      if (epoch === state.epoch) await refreshHistoryResources()
    }
  })().finally(() => { if (loading === pending) loading = undefined; if (epoch === state.epoch) state.ready = true })
  loading = pending
  return pending
}
async function refreshHistoryResources() {
  const epoch = state.epoch
  try {
    const result = await api.read('/history/resources', historyResources)
    if (epoch === state.epoch) state.historyResources = result
    return epoch === state.epoch ? result : []
  } catch (cause) {
    if (epoch === state.epoch) state.historyResources = []
    if (cause instanceof ApiError && cause.status === 403) return []
    throw cause
  }
}
async function allowed(key: string, action: 'READ' | 'MANAGE') {
  const token = `${key}:${action}`
  if (grants.has(token)) return grants.get(token) === true
  const current = pendingGrants.get(token)
  if (current) return current
  const epoch = state.epoch
  const generation = grantGeneration
  const request = api.read(`/permissions/explain?permissionKey=${encodeURIComponent(key)}&action=${action}`, explanation)
    .then(result => {
      const current = epoch === state.epoch && generation === grantGeneration
      if (current) grants.set(token, result.allowed)
      return current && result.allowed
    })
    .finally(() => { if (pendingGrants.get(token) === request) pendingGrants.delete(token) })
  pendingGrants.set(token, request)
  return request
}
for (const client of [api, locoApi]) client.onFailure(async error => {
  if (error.status === 401 && (state.user !== null || error.code === 'SESSION_EXPIRED')) {
    clear(); state.notice = message(error); navigate('/login')
  } else if (error.code === 'PASSWORD_CHANGE_REQUIRED') navigate('/password')
  else if (error.code === 'CSRF_INVALID') {
    csrfRefresh ??= api.csrf().finally(() => { csrfRefresh = undefined })
    await csrfRefresh
  }
  if (error.status === 403) invalidateGrants()
})
export const session = {
  state: readonly(state), refresh, allowed, refreshHistoryResources,
  connectNavigation(handler: typeof navigate) { navigate = handler },
  async start() { try { await api.csrf(); await refresh() } catch { state.ready = true } },
  async login(loginId: string, password: string) {
    const user = await api.send('/auth/login', 'POST', { loginId, password }, identity)
    clear(); state.user = user; state.notice = ''; state.ready = true
    await refresh()
    navigate(user.mustChangePassword ? '/password' : '/access')
  },
  async logout() { await api.send('/auth/logout', 'POST', undefined, nothing); clear(); navigate('/login') },
  async password(currentPassword: string, newPassword: string) {
    await api.send('/auth/password', 'PUT', { currentPassword, newPassword }, nothing)
    clear(); state.notice = '비밀번호 변경 완료. 다시 로그인하세요.'; navigate('/login')
  },
  async changed() { invalidateGrants(); await refresh() },
}
