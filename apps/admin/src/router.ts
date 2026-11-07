import { createRouter, createWebHistory } from 'vue-router'
import { h, nextTick } from 'vue'
import LoginView from './views/LoginView.vue'
import PasswordView from './views/PasswordView.vue'
import AccessView from './views/AccessView.vue'
import AccountsView from './views/AccountsView.vue'
import RolesView from './views/RolesView.vue'
import { session } from './composables/session'

export const router = createRouter({ history: createWebHistory('/admin/'), routes: [
  { path: '/', redirect: '/access' }, { path: '/login', component: LoginView },
  { path: '/password', component: PasswordView },
  { path: '/access', component: AccessView },
  { path: '/accounts', component: AccountsView, meta: { permission: 'cms.accounts' } },
  { path: '/roles', component: RolesView, meta: { permission: 'cms.roles' } },
  { path: '/:pathMatch(.*)*', component: { render: () => h('h1', '없는 화면입니다') } },
] })
router.beforeEach(async to => {
  if (!session.state.ready) await session.start()
  const user = session.state.user
  if (!user && to.path !== '/login') return '/login'
  if (user?.mustChangePassword && to.path !== '/password' && to.path !== '/login') return '/password'
  if (user && typeof to.meta.permission === 'string') {
    try { if (!await session.allowed(to.meta.permission, 'READ')) return '/access?denied=1' }
    catch { return '/access?unavailable=1' }
  }
})
session.connectNavigation(path => { void router.replace(path) })
router.afterEach(async () => { await nextTick(); document.querySelector<HTMLElement>('main')?.focus() })
