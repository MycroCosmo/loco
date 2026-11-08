export const screens = [
  { key: 'cms.accounts', path: '/accounts', label: '관리자 계정' },
  { key: 'cms.roles', path: '/roles', label: '역할·권한' },
  { key: 'cms.menus', path: '/menus', label: '메뉴 트리' },
  { key: 'cms.files', path: '/files', label: '파일' },
  { key: 'loco.users', path: '/loco/users', label: '회원 조회' },
  { key: 'loco.maps', path: '/loco/maps', label: '지도방 조회 (비공개 포함)' },
  { key: 'loco.places', path: '/loco/places', label: '장소 조회 (비공개 포함)' },
  { key: 'loco.reports', path: '/loco/reports', label: '신고 조회·처리 (비공개 대상 포함, 앱 제재 없음)' },
]
export function screen(key: string | null) { return screens.find(item => item.key === key) }
