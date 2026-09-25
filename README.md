# LOCO

좋아하는 맛집과 장소를 지도에 저장하고 다른 사용자와 공유하는 서비스입니다. 기존 React·Spring Boot 프로젝트 이후 Flutter·NestJS·PostgreSQL/Prisma로 개인 재구현했습니다.

## 구성

```text
Flutter 앱 → NestJS REST API
                ├─ 인증·OAuth·토큰 재발급
                ├─ 지도방·멤버·장소·리뷰·즐겨찾기
                ├─ 지도 영역별 마커 조회·집계
                ├─ Prisma → PostgreSQL
                ├─ Sharp → WebP·로컬 파일 저장
                └─ FCM 알림
```

| 영역 | 기술 |
| --- | --- |
| 앱 | Flutter |
| 백엔드 | NestJS, TypeScript |
| 데이터베이스 | PostgreSQL, Prisma |
| 인증 | JWT, bcrypt, Kakao/Naver OAuth |
| 지도 | Kakao API |
| 이미지 | Sharp, WebP, 로컬 파일 시스템 |
| 알림 | Firebase Cloud Messaging |
| 테스트 | Jest, Nest Testing, Supertest |

## 구현에서 다룬 문제

### 리프레시 토큰의 중복 소비

리프레시 토큰은 원문 대신 해시로 저장합니다. 재발급 시 만료·폐기·탈퇴 여부를 확인하고, `revokedAt IS NULL` 조건으로 기존 토큰을 갱신합니다. 갱신 건수가 1인 요청만 다음 토큰을 저장하도록 기존 토큰 폐기와 새 토큰 저장을 하나의 트랜잭션으로 묶었습니다.

PostgreSQL을 사용하는 API 테스트에서 동일 토큰의 동시 재발급 두 건 중 한 요청만 성공하고 활성 토큰이 한 개 남는 것을 확인했습니다.

### 탈퇴와 인증 정보 폐기

이 버전에서는 JWT 서명·만료 검증 후 DB에서 현재 사용자를 조회해 탈퇴했거나 존재하지 않는 계정을 거부합니다. 탈퇴 상태 기록, FCM 토큰 해제, 활성 리프레시 토큰 폐기, 미사용 비밀번호 재설정 토큰 사용 처리를 하나의 Prisma 트랜잭션으로 묶었습니다.

비밀번호 재설정은 비밀번호 변경과 활성 리프레시 토큰 폐기를 같은 트랜잭션에서 처리합니다. 로그아웃은 전달받은 리프레시 토큰을 폐기합니다. 비밀번호 변경·로그아웃 시 모든 액세스 토큰을 즉시 폐기하는 세션 버전 기능은 포함하지 않습니다. 이미 인증을 통과한 실행 중 요청도 취소하지 않습니다.

### 조회 권한과 수정 권한의 분리

공개/비공개 지도방의 조회 정책과 방장·가입자·작성자의 수정 정책을 구분합니다. 비공개 지도방 접근은 방장 또는 멤버 여부로 확인하고, 장소 수정은 작성자, 지도방 수정·삭제는 방장 권한을 검사합니다. 공개 지도방도 현재 API에서는 인증 가드를 통과해야 합니다.

### 목록과 지도 마커의 조회 분리

장소 목록에는 커서 기반 페이지 처리, 검색·태그 필터, 페이지 내 장소의 리뷰 통계 묶음 조회를 적용했습니다. 마커는 현재 지도 영역과 확대 수준을 기준으로 조회하며, 장소가 많으면 여러 정밀도의 `PlaceMarkerCell` 집계를 사용합니다. 운영 처리량이나 응답 시간 개선 수치는 별도로 측정하지 않은 상태입니다.

### 이미지와 데이터베이스의 실패 처리

지도 커버·프로필 이미지는 새 파일 저장 후 DB를 변경하고, DB 변경이 실패하면 새 파일 삭제를 시도합니다. 기존 파일은 DB 변경 성공 후 삭제합니다. 파일 삭제 함수는 오류를 삼키는 최선 노력 방식이므로, 삭제 실패 시 파일이 남을 수 있습니다. 지속적인 재시도나 고아 파일 정리까지 보장하는 구조는 아닙니다.

## 주요 데이터 제약

| 데이터 | 중복 방지 조건 |
| --- | --- |
| 지도방 멤버 | `(mapId, userId)` |
| 장소 리뷰 | `(placeId, userId)` |
| 즐겨찾기 | `(userId, mapId)` |
| 리프레시 토큰 | `tokenHash` 고유 제약 |
| 비공개방 초대 코드 | `inviteCode` 고유 제약 |

## 로컬 실행

명령은 저장소 루트에서 시작합니다. Node.js, Flutter SDK와 개발용 PostgreSQL이 필요합니다. 운영 DB를 개발 명령에 연결하지 마세요.

### 서버

```bash
cd apps/server
npm ci
cp .env.example .env
# .env의 DATABASE_URL, JWT_SECRET 등을 본인 개발 환경에 맞게 설정
npx prisma generate
npx prisma migrate dev
npm run start:dev
```

기본 API 주소는 `http://localhost:3000/api`입니다. 마이그레이션 명령에는 개발 DB 및 필요한 권한이 있어야 합니다. SMTP·OAuth·FCM 기능은 각 외부 서비스 설정도 필요합니다. 기존 장소 데이터의 마커 집계를 다시 만들 때는 서버 디렉터리에서 `npm run marker-cells:rebuild`를 실행합니다.

### 앱 — 새 터미널에서 저장소 루트 기준

```bash
cd apps/mobile
flutter pub get
flutter run
```

API 주소와 Kakao 설정은 [API 안내](docs/api.md), [Kakao 설정](docs/kakao-setup.md), [Android 빌드 안내](docs/android-release.md)를 참고하세요.

## 검증 결과

코드 기준 `90be21f`, GitHub Actions 실행 [36164621746](https://github.com/MycroCosmo/loco/actions/runs/36164621746)에서 아래를 확인했습니다. 링크는 비공개 저장소 권한이 필요합니다.

| 검사 | 결과 |
| --- | --- |
| 계정 상태 단위 테스트 | 9개 통과 |
| NestJS 서버 빌드 | 성공 |
| PostgreSQL API E2E | 6개 통과: 기존 응답 검사 3개 + 새 계정 시나리오 3개 |

새 E2E는 탈퇴 후 기존 액세스·리프레시 토큰 거부, 동일 토큰의 동시 재발급, DB 트리거로 토큰 폐기 실패를 주입했을 때의 실제 롤백을 검사합니다.

```bash
cd apps/server
npm test -- --runInBand
npm run build
```

DB 회귀 테스트는 전용 `loco_test` 데이터베이스와 `RUN_DB_REGRESSION=1` 설정이 필요합니다. 임시 트리거를 생성·삭제하므로 공유 DB나 운영 DB에서 실행하지 마세요. 자세한 명령과 범위는 [계정 회귀 검증](docs/account-regression.md)에 기록했습니다.

## 보안과 배포 전 확인

테스트 통과는 실제 배포·외부 OAuth/SMTP/FCM 연동·Flutter 전체 화면·모든 보안 검증을 뜻하지 않습니다. 위 CI의 npm 의존성 검사에서는 취약점 경고가 남아 있어 별도 검토가 필요합니다.

실제 비밀정보는 저장소에 넣지 않습니다. `.gitignore`만으로 과거 이력이 정리되지는 않으며, 이 문서는 전체 이력의 비밀정보 검사 완료를 뜻하지 않습니다. 저장소 공개 범위는 비공개로 유지합니다.
