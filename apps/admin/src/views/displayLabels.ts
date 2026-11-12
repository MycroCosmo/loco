const labels: Record<string, string> = {
  READ: '조회', MANAGE: '관리', ACTIVE: '활성', DELETED: '탈퇴', ALL: '전체',
  LOCAL: '이메일', GOOGLE: '구글', KAKAO: '카카오', NAVER: '네이버',
  PUBLIC: '공개', PRIVATE: '비공개', MAP: '지도방', PLACE: '장소', USER: '회원',
  RECEIVED: '접수', IN_REVIEW: '검토 중', RESOLVED: '검토 완료', DISMISSED: '기각',
  SYSTEM_ADMIN: '최고관리자', READ_ONLY: '조회 전용', MANAGER: '관리 담당',
  'admin-account': '관리자 계정', role: '역할', menu: '메뉴', file: '파일',
  'cms.account': '관리자 계정', 'cms.role': '역할', 'cms.menu': '메뉴', 'cms.file': '파일',
  'loco.report': '신고 처리',
}
export function displayCode(code: string) { return labels[code] ? `${labels[code]} (${code})` : code }
