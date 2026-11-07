export const screens = [
  { key: 'cms.accounts', path: '/accounts', label: '관리자 계정' },
  { key: 'cms.roles', path: '/roles', label: '역할·권한' },
  { key: 'cms.menus', path: '/menus', label: '메뉴 트리' },
  { key: 'cms.files', path: '/files', label: '파일' },
  { key: 'loco.users', path: '/loco/users', label: '회원 조회' },
]
export function screen(key: string | null) { return screens.find(item => item.key === key) }
