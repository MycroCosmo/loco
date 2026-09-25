# 계정 탈퇴 회귀 수정과 검증

## 변경

JWT 서명·만료 검증 이후 DB에서 현재 사용자를 조회해 존재하지 않거나 탈퇴한 계정을 거부합니다. 탈퇴 상태, FCM 토큰 해제, 활성 리프레시 토큰 폐기와 미사용 비밀번호 재설정 토큰 사용 처리는 하나의 Prisma 트랜잭션으로 묶습니다. 직접 프로필 조회도 탈퇴 사용자를 제외합니다.

## 실제 CI 결과

코드 기준: `90be21fcb62d9bed8e32eef13d9de63aed18a83c`.

[GitHub Actions 실행 36164621746](https://github.com/MycroCosmo/loco/actions/runs/36164621746)에서 Node.js 20, PostgreSQL 16, 실제 npm 의존성을 사용했습니다.

| 단계 | 결과 |
| --- | --- |
| npm 의존성 설치 | 성공 |
| Prisma 클라이언트 생성·테스트 DB 스키마 준비 | 성공 |
| Jest 단위 테스트 | 9/9 통과 |
| NestJS 빌드 | 성공 |
| API E2E | 6/6 통과 |

E2E 6개 중 3개는 기존 입력·미인증 응답 검사이고, 3개는 이번에 추가한 PostgreSQL 계정 상태 검사입니다.

1. 탈퇴 후 이전 액세스 토큰과 리프레시 토큰이 거부되고, 활성 인증 정보가 남지 않는지 확인합니다.
2. 같은 리프레시 토큰의 동시 요청 두 건 중 한 건만 성공하는지 확인합니다.
3. DB 트리거로 토큰 폐기 실패를 강제로 발생시켜 탈퇴 상태 변경도 롤백되는지 확인합니다.

세 번째 테스트의 의도된 500/DB 오류 로그는 실패 주입의 결과이며 테스트 실패가 아닙니다.

## 재현

단위 테스트와 빌드:

```bash
cd apps/server
npm ci
npx prisma generate
npm test -- --runInBand
npm run build
```

DB 테스트는 폐기 가능한 전용 `loco_test` DB만 사용합니다. 아래 값은 로컬 테스트용이며 운영 키가 아닙니다.

```bash
export DATABASE_URL=postgresql://test:test@localhost:5432/loco_test
export JWT_SECRET=local-regression-only-secret-not-for-production-1234567890
export RUN_DB_REGRESSION=1
npx prisma db push --skip-generate
npm run test:e2e -- --runInBand
```

실행 위치는 `apps/server`입니다. 테스트에는 트리거 생성·삭제 권한이 필요합니다. 공유 DB나 운영 DB에서는 실행하지 마세요. `RUN_DB_REGRESSION`이 없으면 신규 DB 테스트는 건너뛰므로, 통과 개수뿐 아니라 skipped 여부도 확인해야 합니다.

## 제한

탈퇴 후 새로 인증되는 요청을 차단하는 변경입니다. 이미 인증을 통과한 실행 중 요청을 취소하지 않으며, 비밀번호 변경·로그아웃 시 모든 액세스 토큰을 즉시 폐기하는 세션 버전 기능은 포함하지 않습니다. 탈퇴 요청과 재발급 요청을 서로 경합시키는 모든 순서 조합도 별도 대상입니다.

CI의 의존성 검사에는 취약점 경고 43건(낮음 5, 보통 20, 높음 16, 심각 2)이 남았습니다. 이 수치는 해당 실행 시점의 결과이며, 영향 분석이나 의존성 교체까지 끝났다는 의미는 아닙니다.

운영 배포·실제 DB 변경·저장소 공개 전환은 수행하지 않았습니다. 이 브랜치의 README는 위 코드·검증 범위를 반영하며, 기본 브랜치와의 차이는 PR 병합 여부로 구분합니다.
