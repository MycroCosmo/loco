# LOCO - Local Collection

좋아하는 맛집과 명소를 지도에 저장하고 공유하는 커뮤니티 플랫폼

## 기술 스택

| 영역 | 기술 |
|------|------|
| 모바일 앱 | Flutter (Android / iOS / Web) |
| 백엔드 | NestJS (TypeScript) |
| 데이터베이스 | PostgreSQL (Supabase) |
| ORM | Prisma |
| 인증 | JWT (Access + Refresh Token), 이메일 인증, 비밀번호 재설정 |
| 지도 | Kakao Map API |
| 이미지 저장 | 로컬 파일시스템 (WebP 변환) |
| 푸시 알림 | Firebase Cloud Messaging (FCM) |

## 주요 기능

- **지도방** — 공개/비공개 지도방 생성·가입·초대 코드 참여
- **장소 관리** — 카카오 장소 검색으로 추가, 메모·태그·이미지(최대 3장) 첨부
- **리뷰** — 별점 + 텍스트 리뷰, 평균 평점 집계
- **즐겨찾기** — 지도방 북마크
- **푸시 알림** — 장소 추가·멤버 가입 시 FCM 알림, 마이페이지에서 on/off 토글
- **계정** — 회원가입·로그인·프로필 수정·회원 탈퇴

## 프로젝트 구조

```
loco/
├── apps/
│   ├── mobile/          # Flutter 앱
│   │   ├── lib/
│   │   │   ├── core/    # 네트워크, 상수, 공통 위젯
│   │   │   └── features/ # auth, maps, places, profile, notifications …
│   │   └── pubspec.yaml
│   └── server/          # NestJS API 서버
│       ├── src/
│       │   ├── auth/
│       │   ├── maps/
│       │   ├── places/
│       │   ├── users/
│       │   ├── notifications/
│       │   ├── tasks/   # 만료 토큰 자동 정리 (cron)
│       │   └── …
│       └── prisma/
│           └── schema.prisma
└── README.md
```

## 환경 변수

### 서버 (`apps/server/.env`)

```env
DATABASE_URL=postgresql://...
JWT_SECRET=your-secret
JWT_REFRESH_SECRET=your-refresh-secret
PORT=3000
ALLOWED_ORIGINS=http://localhost:8080,https://your-app.com

# 이메일 인증 / 비밀번호 재설정
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USER=no-reply@example.com
SMTP_PASS=your-smtp-password
SMTP_FROM=LOCO <no-reply@example.com>

# FCM 푸시 알림
FIREBASE_SERVICE_ACCOUNT={"type":"service_account", ...}
```

### Flutter 앱 (빌드 시 `--dart-define`)

| 변수 | 설명 | 기본값 (개발) |
|------|------|------|
| `API_BASE_URL` | API 서버 주소 + `/api` | `http://10.0.2.2:3000/api` |
| `SERVER_URL` | 이미지 등 서버 루트 주소 | `http://10.0.2.2:3000` |

## 실행 방법

### 백엔드

```bash
cd apps/server
npm install
cp .env.example .env   # .env 값 설정
npx prisma migrate dev
npm run start:dev
```

서버: `http://localhost:3000/api`

### Flutter 앱 (개발)

```bash
cd apps/mobile
flutter pub get
# Android 에뮬레이터
flutter run
# 웹
flutter run -d chrome --web-port 8080
```

### Flutter 앱 (프로덕션 빌드)

```bash
flutter build apk --release \
  --dart-define=API_BASE_URL=https://api.example.com/api \
  --dart-define=SERVER_URL=https://api.example.com
```

## 배포 전 체크리스트

- [ ] `apps/mobile/android/app/google-services.json` 추가 (Firebase Android)
- [ ] `apps/mobile/ios/Runner/GoogleService-Info.plist` 추가 (Firebase iOS)
- [ ] 서버 `.env` — `SMTP_*`, `FIREBASE_SERVICE_ACCOUNT`, `ALLOWED_ORIGINS` 설정
- [ ] 앱 아이콘 교체 (`flutter_launcher_icons` 패키지 활용)
- [ ] `pubspec.yaml` version 업데이트 (현재 `0.1.0+1`)
- [ ] 앱스토어 개인정보처리방침 URL 준비 (구글·애플 필수)
