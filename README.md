# LOCO — Local Collection

좋아하는 장소를 지도방에 저장하고 다른 사용자와 공유하는 모바일 서비스입니다. 개인 프로젝트로 Flutter client와 NestJS API를 함께 구현했으며, 이 문서는 백엔드 설계와 검증 가능한 범위에 집중합니다.

## 해결하려는 문제

- 장소를 개인 목록이 아니라 공개·비공개 지도방 단위로 함께 관리합니다.
- 위치가 많은 지도에서도 현재 화면에 필요한 marker를 조회할 수 있어야 합니다.
- JWT, OAuth, 비밀번호 재설정, 회원 탈퇴에서 계정과 token 상태가 일치해야 합니다.
- 이미지 저장 실패와 DB 변경 실패가 서로 다른 시스템에 불일치를 남기지 않아야 합니다.

## Architecture

```text
Flutter client
    │ REST / JWT
NestJS API
 ├─ Auth / Users       access + refresh token, OAuth, email verification
 ├─ Maps / Places      permissions, place and marker-cell queries
 ├─ Upload             MIME/metadata validation, WebP conversion
 ├─ Notifications      optional FCM delivery
 └─ Scheduled Tasks    expired token cleanup
    │ Prisma
PostgreSQL
```

## 주요 기술

- NestJS, TypeScript, Prisma
- PostgreSQL
- JWT access/refresh token, Passport
- Sharp WebP processing
- class-validator, Helmet, CORS, request throttling
- Jest

## 핵심 기술적 문제와 해결

### 1. refresh token replay 방지

refresh token 원문 대신 SHA-256 hash를 저장합니다. rotation은 Prisma interactive transaction 안에서 `revokedAt IS NULL`인 row를 조건부 갱신하고, 갱신 건수가 1건일 때만 새 token을 발급합니다. 같은 token이 동시에 제출돼도 한 요청만 소비할 수 있습니다.

### 2. 비밀번호 재설정 token 동시 재사용

token 조회 후 별도 update하던 흐름은 두 요청이 모두 통과할 수 있었습니다. `id + usedAt IS NULL + expiresAt > now` 조건의 원자적 `updateMany`를 transaction 안에서 실행하고, 1건을 선점한 요청만 비밀번호 변경과 기존 refresh token 폐기를 진행하도록 수정했습니다.

### 3. 탈퇴 계정의 기존 access token

회원 탈퇴 시 user soft delete와 refresh token 폐기를 한 transaction으로 처리합니다. JWT strategy도 서명된 payload만 신뢰하지 않고 현재 user와 `deletedAt`을 확인해 이미 발급된 access token을 즉시 거부합니다.

Trade-off는 보호 API마다 사용자 상태 조회가 추가되는 점입니다. 현재는 즉시 차단의 명확성을 우선하며, 실제 병목이 측정될 때 token version 또는 짧은 TTL을 검토합니다.

### 4. OAuth 자동 계정 연결 차단

provider가 반환한 email이 기존 LOCAL 계정과 같다는 이유만으로 provider 계정으로 변경하지 않습니다. 이미 가입된 email은 명시적인 account-linking 절차가 필요하다는 conflict로 처리합니다.

### 5. 지도 marker 조회 모델

장소 원본과 별도로 `(mapId, precision, cellLatKey, cellLngKey)` 복합 키를 가진 marker cell을 유지하고, 현재 viewport와 precision에 맞는 집계를 조회합니다. 이는 조회 최적화를 위한 파생 모델이며, 장소 변경과 cell 재집계가 아직 완전히 원자적이지 않으므로 “동시성까지 해결한 공간 인덱스”로 표현하지 않습니다.

### 6. 이미지 처리

업로드 파일의 MIME과 image metadata를 확인하고 WebP로 변환합니다. DB 반영 실패 시 업로드된 파일을 삭제하는 보상 처리가 구현돼 있습니다.

## 권한 모델

- 지도 owner, 공개 범위, member 여부를 `MapPermissionService`에서 판정
- 공개 지도 쓰기는 가입 여부 확인
- 장소 수정은 작성자, 삭제는 작성자 또는 지도 owner로 제한
- ValidationPipe whitelist와 unknown field 거부 적용

## 테스트 전략

현재 제출 브랜치의 핵심 단위 테스트는 다음을 검증합니다.

- 탈퇴 사용자의 access token 거부
- 탈퇴 transaction에서 refresh token 전체 폐기
- 비밀번호 재설정 token의 조건부 원자 소비
- 기존 LOCAL 계정의 암묵적 OAuth 연결 차단

```bash
cd apps/server
npm run build
npm test -- --runInBand
```

## 로컬 실행

```bash
cd apps/server
npm install
cp .env.example .env
npx prisma generate
npm run start:dev
```

필수 환경변수는 `DATABASE_URL`, `JWT_SECRET`, `JWT_REFRESH_SECRET`입니다. SMTP와 FCM 설정은 해당 기능을 사용할 때만 필요합니다. secret은 repository에 저장하지 않습니다.

## 현재 한계

- Prisma migration이 없어 DB schema 변경 이력을 재현하는 작업이 남아 있습니다.
- place 변경과 marker-cell 재집계, 이미지 3장 제한은 동시 요청 정합성 보완이 필요합니다.
- 수치로 검증된 성능 개선 자료는 없으므로 응답시간·트래픽 수치를 주장하지 않습니다.

## 결과와 배운 점

인증은 정상 로그인만으로 완성되지 않습니다. token 폐기, 동시 재사용, 탈퇴 상태, provider account-linking을 각각 별도의 상태 전이로 보고 DB 조건부 갱신과 현재 사용자 확인으로 경계를 명확히 했습니다.
