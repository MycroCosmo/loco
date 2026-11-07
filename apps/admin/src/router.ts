import { createRouter, createWebHistory } from 'vue-router'
import { h, nextTick } from 'vue'
import LoginView from './views/LoginView.vue'
import PasswordView from './views/PasswordView.vue'
import { session } from './composables/session'

export const router = createRouter({ history: createWebHistory('/admin/'), routes: [
  { path: '/', redirect: '/access' }, { path: '/login', component: LoginView },
  { path: '/password', component: PasswordView },
  { path: '/access', component: { render: () => h('h1', '내 접근 근거') } },
  { path: '/:pathMatch(.*)*', component: { render: () => h('h1', '없는 화면입니다') } },
] })
router.beforeEach(async to => {
  if (!session.state.ready) await session.start()
  const user = session.state.user
  if (!user && to.path !== '/login') return '/login'
  if (user?.mustChangePassword && to.path !== '/password' && to.path !== '/login') return '/password'
})
session.connectNavigation(path => { void router.replace(path) })
router.afterEach(async () => { await nextTick(); document.querySelector<HTMLElement>('main')?.focus() })
