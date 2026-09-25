# LOCO

좋아하는 맛집과 장소를 지도에 저장하고 다른 사용자와 공유하는 서비스입니다.

기존 React + Spring Boot 프로젝트 이후, **Flutter + NestJS + PostgreSQL/Prisma** 구조로 개인 재구현했습니다.  
단순 CRUD 구현보다 인증 상태, 권한, 토큰 생명주기, 지도 조회 전략과 같은 백엔드 문제를 중심으로 개선하고 있습니다.

## 아키텍처

\`\`\`text
Flutter App
   │
   ▼
NestJS REST API
   │
   ├── 인증 / OAuth / Token Rotation
   ├── 지도방 / 멤버 / 초대
   ├── 장소 / 리뷰 / 즐겨찾기
   ├── 지도 Marker 조회 / Cluster
   ├── 이미지 처리
   └── FCM 알림
   │
   ▼
PostgreSQL (Prisma)
\`\`\`

## 기술 스택

| 영역 | 기술 |
|---|---|
| Mobile | Flutter |
| Backend | NestJS, TypeScript |
| Database | PostgreSQL |
| ORM | Prisma |
| Authentication | JWT, bcrypt, OAuth |
| Map | Kakao API |
| Image | Sharp, WebP |
| Push | Firebase Cloud Messaging |
| Test | Jest, Nest Testing |

## 주요 백엔드 설계

### 1. Refresh Token 일회성 사용

Refresh Token 원문은 저장하지 않고 hash 형태로 저장합니다.

재발급 시 기존 token을 단순 조회 후 갱신하는 대신, \`revokedAt IS NULL\` 조건으로 UPDATE합니다.

\`\`\`text
Refresh 요청
  → Token Hash 조회
  → 사용자 / 만료 / 폐기 상태 검증
  → revokedAt IS NULL 조건 UPDATE
  → UPDATE에 성공한 요청만 다음 Refresh Token 생성
\`\`\`

동시에 같은 Refresh Token으로 두 요청이 들어오더라도 하나의 요청만 기존 token을 소비할 수 있도록 했습니다.

기존 token 폐기와 다음 token 생성은 하나의 transaction 안에서 처리합니다.

비밀번호 변경이나 회원 상태 변경 시에도 기존 Refresh Token을 폐기해 이전 세션이 계속 유지되지 않도록 했습니다.

### 2. 공개 여부와 권한을 분리했습니다

지도방의 공개/비공개 여부와 수정 권한을 같은 개념으로 처리하지 않았습니다.

예를 들어:

- 공개 지도방: 공개 조회 정책 적용
- 비공개 지도방: owner / member만 접근
- 초대 코드: 비공개 지도방 가입에 사용
- 수정·삭제: 조회 가능 여부와 별도로 작성자/방장 권한 확인

읽기 권한과 쓰기 권한을 각각 명시적으로 검사하도록 구성했습니다.

### 3. 목록 조회와 지도 Marker 조회를 분리했습니다

장소 목록과 지도 화면은 필요한 데이터 형태가 다르다고 판단했습니다.

장소 목록은:

- cursor pagination
- 검색/태그 필터
- 리뷰 통계 묶음 조회

방식으로 처리합니다.

지도 Marker 조회는 현재 viewport와 zoom level을 기준으로 필요한 범위만 조회합니다.

장소가 많아지는 경우 모든 장소를 그대로 반환하지 않고 여러 precision의 marker cell을 이용해 cluster 형태로 반환하도록 구성했습니다.

### 4. 파일과 DB 실패 흐름을 분리했습니다

이미지 업로드와 DB 변경은 서로 다른 저장소를 사용하므로 하나의 transaction으로 묶을 수 없습니다.

따라서 새 이미지를 저장한 뒤 DB 변경이 실패하면 새 파일을 삭제하고, 기존 이미지는 DB 변경이 성공한 이후에 삭제하도록 실패 시점을 구분했습니다.

## 주요 도메인

\`\`\`text
User
 ├── RefreshToken
 ├── PasswordResetToken
 ├── Map
 ├── MapMember
 ├── Place
 ├── PlaceReview
 └── Favorite

Map
 ├── MapMember
 ├── Place
 └── PlaceMarkerCell
\`\`\`

주요 제약 조건:

- \`(mapId, userId)\` 멤버 중복 방지
- \`(placeId, userId)\` 리뷰 중복 방지
- \`(mapId, userId)\` 즐겨찾기 중복 방지
- Refresh/Reset Token 만료 및 폐기 상태 관리

## 주요 기능

- 이메일 회원가입/로그인
- 비밀번호 재설정
- Kakao/Naver OAuth
- Access/Refresh Token 인증
- 공개/비공개 지도방
- 초대 코드 가입
- 장소 등록/검색/태그/이미지
- 리뷰 및 평균 평점
- 즐겨찾기
- viewport 기반 Marker 조회 및 Cluster
- FCM 알림
- 회원 탈퇴 및 Session 무효화

## 프로젝트 구조

\`\`\`text
loco/
├── apps/
│   ├── mobile/        # Flutter
│   └── server/        # NestJS
│       ├── src/
│       │   ├── auth/
│       │   ├── maps/
│       │   ├── places/
│       │   ├── users/
│       │   ├── notifications/
│       │   └── tasks/
│       └── prisma/
│           └── schema.prisma
└── README.md
\`\`\`

## 실행

### Backend

\`\`\`bash
cd apps/server
npm install
cp .env.example .env
npx prisma migrate dev
npm run start:dev
\`\`\`

필요한 환경 변수는 \`apps/server/.env.example\`에 정의되어 있습니다.

### Flutter

\`\`\`bash
cd apps/mobile
flutter pub get
flutter run
\`\`\`

## 보안 관련

- 실제 \`.env\` 파일은 Git에서 제외
- JWT / SMTP / OAuth / Firebase credential은 환경 변수로 주입
- 비밀번호는 bcrypt hash로 저장
- HTTP security middleware와 throttling 적용
- 저장소 예시는 placeholder만 사용
