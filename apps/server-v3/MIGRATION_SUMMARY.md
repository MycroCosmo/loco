# LOCO v2 → v3 이관 정리

기준: 2026-11-07, 9번 묶음과 PR 전 전역 HTTP 보완 완료(구현 커밋 `92911d8`). 0~9번 묶음은 NestJS API를 Spring으로 옮기는 **전체 로드맵 1단계**다. 아래의 **2단계**는 공간 조회·성능 실험, **3단계**는 nginx·다중 서버 실험을 뜻한다. 0번 묶음 내부의 1~5단계와 구별한다.

업무 API 47개(method + path 기준), v3 전용 health 1개, 알림 발송과 토큰 정리를 구현했다. v2와 Flutter는 비교 기준으로 수정하지 않았다. PR 전 보완에서 v3 전역 HTTP 설정과 비교 검증을 추가했으며 DB 스키마는 유지했다. 수동 앱 검증은 [FLUTTER_SMOKE_TEST.md](FLUTTER_SMOKE_TEST.md), PR 본문 초안은 [PR_DRAFT.md](PR_DRAFT.md)에 있다.

## 1. 묶음별 대상 API

아래 경로는 `/api`를 포함한다. 정적 `/uploads/...`와 Actuator는 업무 API 개수에 넣지 않는다. API별 입력·권한·SQL·오류의 상세 계약은 각 설계 문서가 기준이다.

| 묶음 | 대상 | 근거 |
| --- | --- | --- |
| 0 기반 | `GET /api/health`; Java 21·Spring Boot 3.5·Gradle Groovy, Flyway V1, Compose, JWT·오류 호환, 비교 하네스 | [README](README.md), [하네스](COMPARISON_TESTS.md) |
| 1 인증: 7개 | `POST /api/auth/register`, `/login`, `/refresh`, `/logout`, `/oauth/{provider}`, `/forgot-password`, `/reset-password` (모두 `/api/auth` 아래) | [인증](AUTHENTICATION.md) |
| 2 사용자: 5개 | `GET/PUT/DELETE /api/users/me`, `POST /api/users/me/profile-image`, `PUT /api/users/me/fcm-token` | [사용자](USER_API_DESIGN.md), [코덱](CODEC_COMPATIBILITY.md) |
| 3 지도방: 14개 | `GET/POST /api/maps`, `GET /api/maps/me`, `GET/PUT/DELETE /api/maps/{mapId}`, `GET .../membership`, `POST .../join`, `POST /api/maps/join-by-code`, `POST .../leave`, `POST .../invite-code/regenerate`, `GET .../members`, `DELETE .../members/{userId}`, `POST .../cover` (`...` = `/api/maps/{mapId}`) | [지도방 API-01~14](MAP_API_DESIGN.md) |
| 4 장소: 6개 | `GET/POST /api/maps/{mapId}/places`, `GET/PUT/DELETE /api/places/{placeId}`, `GET /api/places/me`; 쓰기 시 마커 셀 재집계 포함 | [장소](PLACE_API_DESIGN.md) |
| 5 리뷰: 4개 | `GET/POST /api/places/{placeId}/reviews`, `DELETE /api/places/{placeId}/reviews/me`, `GET /api/places/reviews/me` | [리뷰](REVIEW_API_DESIGN.md) |
| 6 이미지: 2개 | `POST/DELETE /api/places/{placeId}/images` | [이미지](IMAGE_API_DESIGN.md) |
| 7 마커: 3개 | `GET /api/maps/{mapId}/places/markers`, `/marker-group`, `/marker-cluster` (모두 같은 places 아래) | [마커](MARKER_API_DESIGN.md) |
| 8-A 기타: 5개 | `GET/POST /api/favorites`, `DELETE /api/favorites/{favoriteId}`, `POST /api/feedback`, `POST /api/reports` | [즐겨찾기·피드백·신고](FAVORITES_FEEDBACK_REPORTS_DESIGN.md) |
| 8-B 검색: 1개 | `GET /api/places/search` | [카카오 검색](KAKAO_SEARCH_DESIGN.md) |
| 9 알림·예약 | 공개 HTTP 추가 없음. 지도방 가입 2곳·장소 추가 1곳에서 `sendToUser`/`sendToMap`, 예약 `TokenCleanupService` | [알림·예약](NOTIFICATIONS_DESIGN.md) |

## 2. 테스트 수와 전체 회귀 시간 추이

**최종 검증: 1,434건 = 성공 1,433 / 실패 0 / skip 1, 알려진 차이 11건, 전체 1,037.162초(17분 17초).** 빠른 `test` 282건(성공 281·skip 1), `comparisonTest` 1,152건 모두 성공, Spotless/build 통과다. Windows Java 21·Gradle 8.14.6·Docker Desktop, v2 이미지·Prisma/libvips 캐시가 있는 실행의 wall time이다. v2 이미지 빌드 호출·초기 기동은 각각 1회, 재시작 17회다. 9번 완료 시점 대비 기존 테스트 누락 0·추가 94건이다.

skip 1은 Windows HEIC 단독 코덱의 **v2·v3 공통 지원 제약**이다. HEIC 업로드의 HTTP 실패·파일 상태 비교는 필수 검사로 실행한다. 알려진 차이 11건은 skip과 별개이며 실제 관측·검사를 통과한 뒤 보고한다. OAuth는 v3 계약 테스트, 카카오·FCM·Google 인증은 로컬 대역 검증이며 실제 외부 서비스 성공을 뜻하지 않는다.

표의 테스트 수는 **해당 시점 누적 전체**다. 묶음 자체의 신규 테스트 수와 다르며 JUnit 1건이 여러 HTTP 요청·관측 사례를 포함할 수 있다. 테스트 범위가 늘었으므로 행 사이 시간 차이를 서버 성능 향상/퇴보로 해석하지 않는다.

| 완료 시점 | 누적 전체 / 성공 / 실패 / skip | 전체 실행 시간(초) | 출처·해석 |
| --- | --- | ---: | --- |
| 0 비교 하네스 최초 | 55 / 54 / 0 / 1 | 확인 가능한 원문 수치 없음 | [당시 하네스](https://github.com/MycroCosmo/loco/blob/d4bd553/apps/server-v3/COMPARISON_TESTS.md). 이때 skip은 미구현 429. 이후 필수 통과로 전환 |
| 0 하네스 정리 | 59 / 58 / 0 / 1 | 확인 가능한 원문 수치 없음 | [시나리오·빌더 4건 추가](https://github.com/MycroCosmo/loco/blob/fef0f7f/apps/server-v3/COMPARISON_TESTS.md) |
| 1 인증 | 149 / 149 / 0 / 0 | 293.303 | [완료 커밋](https://github.com/MycroCosmo/loco/commit/6c851fd), 당시 `test bootJar`. 작업 분리 후 전체 재실행 329.238초, 빠른 48건 28.232초·비교 101건 281.937초는 각각 별도 실행 |
| 2 사용자 전체 실행 | 226 / 225 / 0 / 1 | 518.233 | [완료 커밋](https://github.com/MycroCosmo/loco/commit/5a55c4d). 후속 빠른 테스트 추가 확인으로 누적 식별자 227건이 됨. **227건 전체를 518.233초에 실행한 것은 아님** |
| 3 지도방 | 437 / 436 / 0 / 1 | 955.571 | [완료 커밋](https://github.com/MycroCosmo/loco/commit/40a94b1), v2 재시작 321회 |
| 시간 계측·재시작 개선 | 개선 전 439 / 438 / 0 / 1 → 후 447 / 446 / 0 / 1 | 1261.867 → 467.687 | [동일 계측 전후](TEST_PERFORMANCE.md). 재시작 321→6회, 62.94% 단축. 기존 955초는 구간 계측이 없어 소급 분해하지 않음 |
| 4 장소 | 633 / 632 / 0 / 1 | 350.515 | [시간 기록](TEST_PERFORMANCE.md), 빠른 191·비교 442 |
| 5 리뷰 | 733 / 732 / 0 / 1 | 410.372 | [리뷰](REVIEW_API_DESIGN.md), 빠른 207·비교 526 |
| 6 이미지 | 844 / 843 / 0 / 1 | 492.740 | [이미지](IMAGE_API_DESIGN.md), 빠른 229·비교 615 |
| 7 마커 | 1019 / 1018 / 0 / 1 | 638.288 | [마커](MARKER_API_DESIGN.md), 빠른 230·비교 789 |
| 8-A 기타 | 1145 / 1144 / 0 / 1 | 729.391 | [기타](FAVORITES_FEEDBACK_REPORTS_DESIGN.md), 빠른 230·비교 915 |
| 8-B 검색 | 1286 / 1285 / 0 / 1 | 838.209 | [검색](KAKAO_SEARCH_DESIGN.md), 빠른 234·비교 1052 |
| 9 알림·예약 최종 | 1340 / 1339 / 0 / 1 | 885.749 | [최종 검증·실패 재현 이력](NOTIFICATIONS_DESIGN.md), 빠른 279·비교 1061 |
| PR 전 전역 HTTP 보완 | 1434 / 1433 / 0 / 1 | 1037.162 | [전역 HTTP 관측·비교](src/test/resources/globals/README.md), 빠른 282·비교 1152 |

최신 수치의 원본은 로컬 `build/performance/globals-final/result.json`, `quota.json`, `test-manifest.txt`다. `build/`는 생성물이며 GitHub에는 포함하지 않는다. 영구 기록은 [TEST_PERFORMANCE.md](TEST_PERFORMANCE.md)와 각 단계 커밋 본문이다. 동시 이미지 처리에서는 계측 구간이 겹치므로 `categoriesSeconds`를 정확한 전체 시간 분해로 발표하지 않는다.

재검증 명령(저장소 루트에서 PowerShell, Java 21·Node/npm·Docker 필요):

```powershell
cd apps/server-v3
.\gradlew.bat test --rerun --console=plain
.\gradlew.bat comparisonTest --rerun --console=plain
# 두 그룹만 함께: .\gradlew.bat allTests --rerun --console=plain
# 두 그룹 + 포맷 + 패키징:
.\gradlew.bat build --rerun --console=plain
```

기본 `test`도 v3 DB용 Docker를 사용할 수 있다. “빠른 테스트”는 Docker가 전혀 없다는 뜻이 아니라 **v2 앱을 띄우지 않는 그룹**이다. 최종 실행은 `scripts/measure-tests.ps1 -Label globals-final`로 두 그룹을 재실행하고 build까지 검증했다.

## 3. 주요 결정 색인

`3-1`처럼 원래 결정 번호가 있는 항목은 그대로 썼다. 번호 없는 상속 결정은 “새 번호 없음”, 초기 묶음의 내부 번호는 “묶음/결정”으로 표시한다. 근거 링크의 결정 표와 관측 결과를 함께 읽는다.

| 번호·항목 이름 | 확정 내용과 이유 | 근거 문서 |
| --- | --- | --- |
| 0번/1단계 빌드·DB 기반 | Java 21, Boot 3.5, Groovy DSL, JPA만 사용. 기존 UUID 문자열·TEXT·스키마를 유지 | [README](README.md) |
| 0번/2단계 Flyway 기준 | Prisma `migrate diff --from-empty` 생성 SQL을 V1로 사용. 기본 baselineOnMigrate=false, 기존 DB는 비교 후 최초 실행만 true. 검증은 target=1에 고정 | [README](README.md) |
| 0번/3단계 기동·자원 | PostgreSQL 17/PostGIS 이미지, 공간 확장 미활성화. Flyway만 스키마 생성: DB→v3 healthy→v2. 앱 각 CPU 2/1.5GB, DB CPU 2/4GB·shared_buffers 1GB, Mailpit 0.5/256MB | [README](README.md), [Compose](../../compose.yaml) |
| 0번/4단계 보안·prefix (c) | 필터는 JWT 해석, 인증 필요 여부는 핸들러 선언. 공개/인증 선언 누락도 검사. MVC `/api` prefix로 `/uploads` 분리, stateless·CSRF 비활성화, 404/401/400을 v2 관측대로 처리 | [README](README.md), [인증](AUTHENTICATION.md) |
| 0번/5단계 하네스 1A·2A·3A·4A·5A | JUnit/Testcontainers 환경 공유, 두 DB 모두 같은 V1. JSON 경로 제외·명시적 문구 예외, UUID 별칭으로 참조 검증, 묶음별 명명 빌더·연속 시나리오. 준비 JWT 직접 서명 | [하네스](COMPARISON_TESTS.md) |
| 1번/결정 3 ID, 결정 4 감사 시각 | String `@UuidGenerator`로 null ID 신규 판별, JPA Auditing+Clock으로 앱 updatedAt. 기존 TEXT·TIMESTAMP(3) 매핑 | [인증](AUTHENTICATION.md) |
| 1번/재발급·재설정 경쟁 | 조건부 UPDATE와 TransactionTemplate로 토큰을 한 번만 소비. 실제 DB 경합 도구는 이후 락 비교에도 재사용. bcrypt `$2b$`, cost 10·UTF-8 72바이트 호환 | [인증](AUTHENTICATION.md) |
| 1번/v2 기준 갱신 예외 | 탈퇴 회원 차단, 재설정 원자적 소비 `88975d9`, OAuth 이메일 자동 연결 차단 `3fb485f`를 반영한 main이 비교 기준. SMTP 실패 시 토큰 잔존은 유지 | [인증](AUTHENTICATION.md) |
| 2번/결정 1 저장·2 변환기 | 상대 `/uploads/...`·로컬 디스크 유지. **A로 결정했으나 의사소통 오류로 B(libvips)로 구현, 결과 검토 후 유지**. AVIF·애니메이션 WebP 통과, HEIC 공통 제약 | [사용자 결정 표](USER_API_DESIGN.md), [양 플랫폼 코덱](CODEC_COMPATIBILITY.md) |
| 2번/결정 3 이미지 비교·4 실패 경계·5 권한 | 바이트가 아닌 형식·치수·픽셀 허용 범위, DB 커밋 전후 파일 보상 처리. v3 UID/GID 1000:1000으로 기존 공유 볼륨 사용 | [사용자](USER_API_DESIGN.md) |
| 3-1 엔티티 관계 | LAZY ManyToOne, cascade 없음. API-12 단순 순회 N+1을 측정한 뒤 fetch join: 멤버 3/10명에서 SQL 6/13→각 3회, 이후 회귀 고정 | [지도방](MAP_API_DESIGN.md) |
| 3-2 목록·집계 / 3-3 초대 코드 | native projection으로 DTO/집계, SecureRandom 8자리 코드로 명시적 생성 정책 차이 | [지도방](MAP_API_DESIGN.md) |
| 3-4 중복 가입 / 3-5 이미지 / 3-6 알림 | 충돌 안전 삽입으로 v2 가입 경쟁 500 개선, 저장 코드 공유. 알림 인터페이스·mock까지만 했던 보류는 9번에서 실제 발송 구현으로 해소 | [지도방](MAP_API_DESIGN.md), [알림](NOTIFICATIONS_DESIGN.md) |
| 4-1 장소 관계 / 4-2 목록·리뷰 집계 | LAZY 관계·native projection. 엔티티 직렬화 대신 명시적 응답과 SQL 예산 유지 | [장소](PLACE_API_DESIGN.md) |
| 4-3 마커 셀 / 4-4 부분 커밋 | native SELECT+UPSERT/DELETE, 비트랜잭션 조정 서비스+TransactionTemplate. 장소 저장과 셀 재집계의 분리 커밋을 v2대로 유지 | [장소](PLACE_API_DESIGN.md) |
| 4-5 JavaScript 좌표 키 / 4-6 알림 인터페이스 | `new BigDecimal(double)` 정확 이진 값+HALF_UP, 음수 문자열·입력 -0.0을 Node toFixed와 대조. PrismaFloats도 별도 적용. 장소 알림은 공통 인터페이스 후 9번 해소 | [장소](PLACE_API_DESIGN.md) |
| 5번·7번 새 번호 없음 | 3-1·4-1 LAZY/native/TransactionTemplate·JS 호환 패턴 상속. 리뷰 유니크 UPSERT, 읽을 때 평점 집계. 마커 기존 SQL·EXPLAIN 보존, PostGIS 개선은 시작하지 않음 | [리뷰](REVIEW_API_DESIGN.md), [마커](MARKER_API_DESIGN.md) |
| 6-1 동시성 버그 / 6-2 수정 방식 | v2 경쟁 재현 유지+v3 정합성 필수 검사. cardinality<3·array_append/array_remove 조건부 원자적 UPDATE, 개선 차이 3건 | [이미지](IMAGE_API_DESIGN.md) |
| 6-3 변환·락 순서 / 6-4 측정 범위 | 변환은 락 밖, 거절/DB 실패 시 자기 파일만 삭제. 초기 0~3장·N=2/4/8/16 정합성과 SQL 수까지 구현; 비관적 락 성능 비교는 2단계 | [이미지](IMAGE_API_DESIGN.md) |
| 8-A 새 번호 없음 | native 조회·기존 검증·v2 중복/실패 동작 유지. 동시 즐겨찾기 삭제의 500도 개선 후보로 남김 | [기타](FAVORITES_FEEDBACK_REPORTS_DESIGN.md) |
| 8-1 외부 대역 / 8-2 timeout | fail-closed fetch preload+루프백, v3 base URL 주입. 연결/TLS/풀 대기 2초, 응답 무활동 5초, 재시도 없음; 전체 deadline 아님 | [검색](KAKAO_SEARCH_DESIGN.md) |
| 8-3 오류 노출 / 8-4 잘못된 타입 | 고정 500 `카카오 검색 API 호출 실패`, 원인 제한 로그·키/Authorization 미기록. 정상 외부 응답의 느슨한 v2 타입 변환 유지 | [검색](KAKAO_SEARCH_DESIGN.md) |
| 8-5 HTTP 클라이언트 | Boot BOM의 Apache HttpClient 5, 카카오 인스턴스만 retry 비활성화. **새 의존성 예외: JDK HttpClient 연결 재시도는 JVM 전역 속성으로만 제어** | [검색](KAKAO_SEARCH_DESIGN.md) |
| 9-1 발송 구현 | HTTP v1+기존 HttpClient 5+Google 인증만 직접 추가. runtime JAR 증가 +6.096MiB, Firebase Admin 후보 +61.190MiB. 전이 의존성·기존 버전 영향도 비교 | [알림 5절](NOTIFICATIONS_DESIGN.md) |
| 9-2 비동기·종료 / 9-3 조회·발송 | 커밋 후 이벤트 1건=작업 1건, core2/max4/queue100. JOIN 1 SELECT+작업 내부 개별 발송. v2 Prisma 자동 배치 실제 2 SELECT, Java 단순 이식 1+N 방지 | [알림](NOTIFICATIONS_DESIGN.md) |
| 9-4 대역·완료 / 9-5 예약 | v2 SDK 경계 fail-closed·v3 전송 대역·완료 barrier. 서울 03:00(설정 가능), 분산 잠금 없음·scheduler on/off | [알림](NOTIFICATIONS_DESIGN.md) |
| 9-6 무효 토큰 / 9-7 전송 정책 | userId AND 발송 당시 fcmToken 조건부 제거. 소켓 15초·일시 오류 최대 4회 재시도와 backoff를 로컬 검증 | [알림](NOTIFICATIONS_DESIGN.md) |
| 9-8 cleanup 경계 / 9-9 설정 오류 | 두 삭제의 독립 커밋 유지. Firebase 없음/잘못된 값은 시작 로그와 함께 발송 비활성화, API 시작 유지 | [알림](NOTIFICATIONS_DESIGN.md) |
| 10-1 런타임 표시 헤더 | A: 조기 파서 오류의 X-Powered-By: Express만 생략하고 의도된 차이 등록. 파서→Helmet→CORS 순서와 조기 오류의 CORS 부재는 v2 유지 | [전역 HTTP](src/test/resources/globals/README.md) |

## 4. 알려진 차이 전체 11건

**개선 6건 + 의도 5건 = 11건, 보류 0건.** 9번까지 10건에 10-1 A의 런타임 헤더 차이 1건을 추가했다. 아래 ID는 하네스 보고서의 식별자다. 과거 `maps.firebase-delivery`, `places.firebase-delivery` 두 보류는 9번에서 해소했다. 실제 단말 수신 미검증과 보류 구현은 다른 개념이다. 공통으로 유지한 v2 문제는 이 목록에 추가하지 않고 5절 후보로 관리한다.

| ID | 분류 | v2 → v3 관측/계약 | 출처 |
| --- | --- | --- | --- |
| `maps.concurrent-public-join` | 개선 | 같은 회원 동시 공개 가입 [201,500] → [201,201], 멤버 행 1개 | [지도방](MAP_API_DESIGN.md) |
| `maps.concurrent-code-join` | 개선 | 같은 코드 동시 가입 [201,500] → [201,201], 멤버 행 1개 | [지도방](MAP_API_DESIGN.md) |
| `images.concurrent-limit` | 개선 | 초기 2장에 동시 업로드 2건: 최종 4장·201 두 건 → 3장·201/400 | [이미지](IMAGE_API_DESIGN.md) |
| `images.concurrent-delete` | 개선 | 서로 다른 URL 삭제: DB URL 1/파일 0·깨진 URL 1 → URL 0/파일 0·깨진 URL 0 | [이미지](IMAGE_API_DESIGN.md) |
| `images.concurrent-upload-delete` | 개선 | 재현 순서에서 DB URL 1/파일 2·유실 URL 1/고아 1 → URL 2/파일 2·유실/고아 0 | [이미지](IMAGE_API_DESIGN.md) |
| `notifications.token-replacement` | 개선 | 발송 중 교체한 새 FCM 토큰까지 삭제 → 발송 당시 토큰과 다르면 새 토큰 보존 | [알림](NOTIFICATIONS_DESIGN.md) |
| `maps.invite-code-generator` | 의도 | Math.random/base36 가변 최대 8자리 → SecureRandom 고정 8자리 대문자·숫자 | [지도방](MAP_API_DESIGN.md) |
| `kakao.failure-message` | 의도 | 외부 상태·본문 또는 런타임 실패 문구 → 고정 500 메시지. 비밀·외부 본문 노출 제한 | [검색](KAKAO_SEARCH_DESIGN.md) |
| `kakao.socket-timeout` | 의도 | 무활동 6.2초 뒤 정상 200 가능 → 기본 5초 무활동에서 500 | [검색](KAKAO_SEARCH_DESIGN.md) |
| `tokens.cleanup-timezone` | 의도 | TZ 미지정 UTC 컨테이너 03:00=한국 12:00 → Asia/Seoul 03:00 | [알림](NOTIFICATIONS_DESIGN.md) |
| `http.runtime-header` | 의도 | 조기 파서 오류의 X-Powered-By: Express → 헤더 생략. 나머지 파서→Helmet→CORS 순서 유지 | [10-1 A](src/test/resources/globals/README.md) |

## 5. 이관 후 개선 후보·2/3단계 실험 통합

아래는 **백로그이며 이번에 구현하지 않는다**. “후보”는 채택된 해결책이 아니다. 승인된 실험 범위와 아직 정책 결정이 필요한 항목을 구분했다. 수치가 없으면 미측정이라고 적었으며 테스트 실행 시간을 API 부하 성능으로 쓰지 않는다.

| 항목 | 근거·수치 / 남은 검증 | 관련 단계·후속 작업 | 출처 |
| --- | --- | --- | --- |
| 조기 파서 오류의 브라우저 CORS | JSON/form 파서400/413/415가 Helmet/CORS보다 먼저 반환된다. 허용 Origin이어도 CORS 헤더가 없어 Flutter 웹에서는 실제 상태/본문 대신 CORS 오류로 보임 | 이관 후 개선 후보: 오류에도 CORS를 적용할지 별도 결정. 이번에는 v2 순서 유지 | [전역 HTTP 관측·비교](src/test/resources/globals/README.md) |
| 마커 셀 누락 | JS toFixed와 PostgreSQL ROUND 불일치. 37.05의 precision1, -0.00001의 precision1/2/3에서 누락. 줌 11 또는 6/9/11 집계 조회에서 드러남; 낮은 줌 개별 조회와 불일치 | 2단계: 좌표 키·집계 기준 통일 후보. 기존 동작/fixture를 의도적으로 변경하는 별도 결정 필요 | [장소](PLACE_API_DESIGN.md), [마커](MARKER_API_DESIGN.md) |
| 마커 집계 경합·부분 커밋 | 장소 쓰기와 SELECT·셀 반영이 같은 트랜잭션이 아님. 중간 실패·오래된 count=99 조회 관측. 실제 경쟁 발생률은 미측정 | 2단계: 일관성 경계·동시 저장 재현 및 집계 전략 비교 | [장소](PLACE_API_DESIGN.md), [마커](MARKER_API_DESIGN.md) |
| 마커 갱신 SQL 비용 | 좌표당 precision 3개×(SELECT+UPSERT/DELETE)=6 SQL. 이동은 이전/새 좌표=12 SQL. HTTP 전체 예산은 생성 10/11, 수정 no-op 9·일반 10·이동 16, 삭제 10 | 2단계: 재집계 SQL/DB 시간 분리, 중복 셀 갱신 비용 측정. 현재 예산을 먼저 기준선으로 유지 | [장소](PLACE_API_DESIGN.md), [생성 SQL 검사](src/test/java/com/loco/places/PlaceCreateComparisonTest.java) |
| PostGIS EXPLAIN 기준 | 작은 7개 장소와 다른 지도방 10,000개 데이터를 구분. 작은 places Seq Scan/Sort, 큰 데이터 mapId_lat_lng Index Scan, cells mapId_precision_maxLng Index Scan, cluster PK Index Scan. ROUND 조회는 mapId 이후 filter | 2단계: 동일 데이터·파라미터·SQL로 PostGIS 전후 EXPLAIN 비교. 현재 공간 확장·인덱스 개선 없음 | [마커 SQL·실행 계획](MARKER_API_DESIGN.md) |
| 마커 의미·정렬·조회량 | marker-group ±0.0000005 반개구간과 JS 키 차이, 동률 정렬 미정·태그 무시·날짜변경선 미지원·overflow 정밀도 없음, 셀 전체 조회 뒤 699개 분할. markers SQL3/대체4, group3, cluster3/4/5(비공개 권한 +1) | 2단계: 공간 조회 개선 시 기존 응답 경계/SQL 예산과 비교; 의미 변경은 별도 승인 | [마커](MARKER_API_DESIGN.md) |
| 탈퇴한 지도방 작성자의 권한 | 비공개방을 나가도 작성자는 장소 수정·삭제·이미지 쓰기 가능. 상세 403인데 쓰기 200인 사례 | 이관 후 권한 정책 결정. 성능 최적화와 분리 | [장소](PLACE_API_DESIGN.md), [이미지](IMAGE_API_DESIGN.md) |
| 개인 목록·즐겨찾기의 공개 범위 | 내 장소/리뷰의 비공개방 노출, 즐겨찾기의 비회원 비공개 지도 정보·inviteCode 노출 가능. v2 호환으로 보존 | 이관 후 접근 권한·응답 필드 정책 검토 | [리뷰](REVIEW_API_DESIGN.md), [기타](FAVORITES_FEEDBACK_REPORTS_DESIGN.md) |
| 리뷰 목록·집계의 동시 읽기 | 통계와 items의 분리 조회, 동률 순서 미지정, 커밋 후 응답 조회 실패의 부분 성공 | 2단계 일관성 측정 / 이후 읽기 스냅샷·정렬 정책 결정 | [리뷰](REVIEW_API_DESIGN.md), [장소](PLACE_API_DESIGN.md) |
| 중복 요청·입력 정책 | 즐겨찾기 동시 삭제 [200,500], 순차 재삭제 404. 피드백·신고 중복 요청은 각각 2행 허용. 공백/대상/자기 신고 규칙은 v2 기준 유지 | 이관 후 멱등성·검증 정책 개선 후보 | [기타](FAVORITES_FEEDBACK_REPORTS_DESIGN.md) |
| 이미지 조건부 UPDATE 대 비관적 락 | 정합성은 초기 0~3장×N=2/4/8/16, 삭제·중복 삭제·혼합 두 순서와 SQL 수를 필수 검증. 정상 I/O의 깨진 URL·고아·유실 0. 성능 우열은 아직 측정하지 않음 | **2단계 실험 백로그**: N=1/2/4/8/16, 예열 제외·방식 교차·5회 이상, p50/p95/max/처리량·변환/DB/대기 분리. UPDATE 경과 전체를 락 대기라 부르지 않음. 7절 성능 계획 전체 유지 | [이미지 7절](IMAGE_API_DESIGN.md) |
| libvips 실행·PNG 중간 변환 | 업로드 1건당 프로세스 3회(메타데이터·decode·encode), PNG 중간 파일. Windows/Linux libvips 8.15.3 호환 검증 완료 | 2단계: 프로세스 기동·CPU·I/O·피크 메모리 분리 측정, 변환기 교체는 이후 결정 | [README 후속 메모](README.md), [코덱](CODEC_COMPATIBILITY.md) |
| 파일·DB 원자성 / 공유 저장소 | 실패 시 자기 파일 보상 삭제, 프로세스 종료·삭제 실패까지 원자성 보장하지 않음. HEIC 실패의 빈 파일 잔여도 v2와 비교. 상대 URL·공유 로컬 볼륨·UID1000 | 이관 후 정리 작업 후보, 3단계 다중 서버 저장소/MinIO 별도 결정. Flutter URL 조합도 같이 검토 | [사용자](USER_API_DESIGN.md), [이미지](IMAGE_API_DESIGN.md), [README](README.md) |
| 인증 조건부 UPDATE 대 락 | refresh/reset 경합에서 소비 1회 보장. DatabaseRequestRace로 실제 요청 재현. 비관적 락 상대 성능 미측정 | 2단계: 같은 경합 테스트를 재사용한 별도 실험 | [인증](AUTHENTICATION.md) |
| SMTP 실패 토큰 잔존 | 메일 실패에도 PasswordResetToken 데이터가 남는 v2 동작 유지 | 이관 후 재시도·정리·발송 트랜잭션 정책 후보 | [인증](AUTHENTICATION.md) |
| 카카오 커넥션 풀·전체 deadline | 전용 classic pool 한 route 기본 **5**, 전체 25. 풀 대기 2초·소켓 무활동 5초. 조금씩 오는 6.2초 body는 완료 가능, 전체 deadline 없음 | 2단계: 동시 검색 시 풀 대기·스레드 점유·취소 전파 측정. Flutter 10초도 데이터 이벤트 대기 제한이며 전체 다운로드 상한은 아님 | [검색](KAKAO_SEARCH_DESIGN.md), [클라이언트](src/main/java/com/loco/places/KakaoSearchClient.java), [Apache 기본값](https://github.com/apache/httpcomponents-client/blob/rel/v5.5.2/httpclient5/src/main/java/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManager.java#L108-L109) |
| 카카오 외부 타입 검증 | 정상 200의 잘못된 필드 타입도 느슨하게 매핑, 런타임 schema 검사 없음 | 이관 후 응답 스키마 검증 정책 후보 | [검색](KAKAO_SEARCH_DESIGN.md) |
| 알림 풀 core/max/queue | core2가 실행 중이면 먼저 queue100을 채우고 이후 max4까지 증가. 멈춘 작업 기준 104건 수용·105번째 거절/로그, CallerRuns 없음. sendToMap 이벤트 1건이 작업 1개 | **2단계 실험**: 제한 풀 대 가상 스레드+동시성 상한, 큐 포화·지연·유실·종료 측정 | [알림 6절](NOTIFICATIONS_DESIGN.md) |
| 알림 재시도·종료의 한계 | 토큰당 최대 5회, 소켓15초. backoff 조건에서 82초, Retry-After 최대60초 조건에서315초 계산. 연결/인증/DB 제외라 절대 상한 아님. N명 직렬 발송, 종료 drain30초·Compose70초, 메모리 큐 유실 복구 없음 | 2단계: 장애 중 워커 점유·다수 멤버 fanout·종료 관측. 내구성 큐/전체 deadline은 추가 결정 후보 | [알림](NOTIFICATIONS_DESIGN.md) |
| 알림 수신자 필터 | v2 실제 자동 배치 2 SELECT→v3 JOIN 1 SELECT. 중복 토큰·탈퇴 토큰은 별도 필터하지 않음 | 이관 후 중복·탈퇴 필터의 의미/발송 수 검토 | [알림](NOTIFICATIONS_DESIGN.md) |
| 예약 작업 다중 서버 중복 | 인스턴스마다 서울03시 실행, 현재 잠금 없음·on/off만 제공. v2를 병행하면 UTC03시 작업도 존재 | **3단계 실험**: PostgreSQL advisory lock 대 ShedLock. 전환 때 활성 scheduler 수 먼저 결정 | [알림](NOTIFICATIONS_DESIGN.md) |
| 전체 테스트 약 886초·시나리오 창 61초 | 9번 완료 시점885.749초, v2재시작17회, 준비 인증 대기0ms·시나리오 내부 창 대기61013ms. 요청 제한 핸들러별 계산 유지 | 2단계/테스트 운영: 기타 비용 분리, 묶음별 작업·시드 준비 최적화 검토. 전체 범위/429/경합 케이스를 빼는 단축은 하지 않음 | [시간 기록](TEST_PERFORMANCE.md), [하네스](COMPARISON_TESTS.md) |
| Windows TCP 4227 | 중간 전체882.148초 실행에서 DB 검증 연결 BindException 1건. TCPIp4227·TIME_WAIT2083 관측. 대상31건35.538초 재실행과 최종전체885.749초 통과 | 환경/테스트 운영: JDBC 연결 생성·재사용·OS 이벤트 측정. 코드/OS 설정으로 해결했다고 주장하지 않으며 TIME_WAIT만으로 전체 포트 고갈을 단정하지 않음 | [알림 최종 검증](NOTIFICATIONS_DESIGN.md), [시간 기록](TEST_PERFORMANCE.md) |
| RequestRateLimiter 단일 잠금 | 프로세스 내부 단일 synchronized, 핸들러/IP 기준 기본120/분·인증3~10/분 | **2단계**: 잠금 경합 측정; **3단계**: 서버별 카운터·분산 제한 정책 결정 | [README](README.md), [인증](AUTHENTICATION.md) |
| getRemoteAddr·신뢰 프록시 | 현재 직접 연결 IP 사용, 임의 X-Forwarded-For를 신뢰하지 않음 | **3단계 nginx**: 신뢰할 프록시와 실제 IP 전달·위조 방지, 요청 제한 키 검증 | [README](README.md) |
| N+1·SQL 예산 회귀 | 지도방 멤버 3/10명에서 6/13→3 SQL 개선은 완료. API별 SQL 수 필수 검사 유지 | 2단계: 대량 데이터 EXPLAIN·N+1 재발 검증의 기준선 | [지도방](MAP_API_DESIGN.md), [장소](PLACE_API_DESIGN.md), [마커](MARKER_API_DESIGN.md) |
| 하네스 계측·Prisma 비교 수명 | timing-v2.cjs는 원본 호출 결과/예외를 보존하고 관측값만 읽음. 미주입/주입 독립 A/B는 별도 자동화되지 않음. V1 대 Prisma 비교는 현재 comparisonTest 포함 | 테스트 운영: 계측 변경 때 A/B 검증, 이관 후 Prisma 비교 태그/작업 분리는 별도 정리 결정 | [시간 기록](TEST_PERFORMANCE.md), [하네스](COMPARISON_TESTS.md), [README](README.md) |

## 6. 전환 시 유지할 조건

- DB는 Flyway V1과 기존 v2 Prisma 업무 스키마가 일치해야 한다. 기존 DB에 baselineOnMigrate=true를 상시 켜서 차이를 숨기지 않는다. PostGIS 이미지 사용과 공간 확장 활성화는 별개다.
- v2·v3가 같은 DB에 **이미지 쓰기를 섞으면 v3의 조건부 UPDATE 정합성 보장이 깨진다**. 공유 DB 비교 배치는 가능해도 실제 쓰기 전환은 한쪽으로 모아야 한다. 공유 업로드 볼륨을 삭제하지 않는다.
- JWT_SECRET은 양쪽 동일한 외부 설정이며 저장소에 넣지 않는다. 현재 v2 기준의 탈퇴·없는 회원 차단과 토큰 원자적 소비를 전제로 한다.
- 발송/cleanup 활성 인스턴스 수와 시간대를 확인한다. Firebase 설정 비활성 로그는 API 실패가 아니지만 발송 성공도 아니다. 실제 FCM·OAuth·카카오 및 Flutter 화면 검증은 별도 수동 항목이다.
- [수동 체크리스트](FLUTTER_SMOKE_TEST.md)의 웹 동일 출처 연결·Android HTTP 조건도 이관 완료와 별도로 확인한다. 이번 정리는 배포나 PR 승인·머지를 의미하지 않는다.

## 7. PR 전 전역 설정 대조 — 2026-11-07 구현·전체 검증 완료

기준은 [v2 main.ts](../server/src/main.ts)와 [app.module.ts](../server/src/app.module.ts)다. 기존 0~9번 검증이 전역 HTTP 헤더·파서 경계까지 모두 증명한 것은 아니었다. 원본 HTTP 79사례 [fixture](src/test/resources/globals/http-v2.json)와 v3를 비교하고 아래 누락을 보완했다. **10-1 A: X-Powered-By만 생략**, 다른 순서는 유지한다. 앞선 대조표의 “JSON 제한 없음”은 조사 누락이었다. 기존 JsonBodyFilter의 JSON 10MiB·인증 전 선검사를 확인했으며 이를 이동·확장했다. 관련 94건 성공(68.198초), form 경계 추가 교차 검사 1건 성공/HTTP38사례(22.970초); 전체 회귀/build도 1,434건 중 성공1,433·실패0·HEIC skip1, 알려진 차이11, 1,037.162초로 통과했다.

| v2 전역 설정·순서 | 현재 v3 대응 | 대조 결과·남은 확인 |
| --- | --- | --- |
| dotenv/config와 PORT/HOST | `.env` import·PORT·HOST→server.address·Compose 양쪽 전달 | HOST=127.0.0.1의 실제 Tomcat 바인딩 검사. 기본0.0.0.0, v3포트8081은 기존 동시 실행 결정 |
| express.json limit10mb, strict JSON·inflate | RequestBodyParser | 기존 JSON 제한을 보존하고 UTF-8 다중 바이트·chunked·gzip/deflate 해제 후 10MiB 및 charset 오류를 보완·비교. 10MiB는 통과, +1은413 |
| express.urlencoded limit10mb, extended=true | RequestBodyParser+ExtendedForm | form10MiB·1,000개 파라미터·깊이32, 반복/배열/객체 해석을 원본 parser와 비교. 실제 API의 정상/오류 응답·DB 비교. multipart 파일 제한과 별개 |
| helmet() 기본값 | GlobalHttpFilter 고정 헤더, Spring 기본 헤더 중복 비활성화 | CSP·COOP·CORP·Origin-Agent-Cluster·Referrer-Policy·SAMEORIGIN·HTTP HSTS 포함 전체 응답 헤더 비교 |
| enableCors(ALLOWED_ORIGINS) | GlobalHttpFilter, application.yml·Compose 연결 | trim·빈 목록 기본8080/3000, 반사·Vary, 고정 methods/headers, OPTIONS204. 미허용 Origin 비차단, credentials 헤더 없음. 예제 파일에는 키만 저장 |
| 파서→Helmet→CORS 순서 | 단일 전역 필터가 순서를 명시 | 조기400/413/415는 Helmet/CORS/인증/한도 전에 종료. X-Powered-By만 [10-1 A](src/test/resources/globals/README.md)로 생략 |
| useStaticAssets /uploads, 실제 파일의 ACAO *·CORP cross-origin | UploadWebConfig 실제 resource 발견 후 헤더 적용 | 없는 파일404의 일반 헤더·본문, Cache-Control/ETag/Last-Modified·HEAD·Range206·조건부304 비교. 파일 본문은 스트리밍 유지. 관측한 파일 경로 범위의 계약 검사 |
| setGlobalPrefix api | ApiWebConfig의 MVC `/api` prefix | API와 루트 `/uploads` 분리 구현. prefix 밖404는 기존 검사 및 새 관측에 포함. context-path로 되돌리지 않음 |
| ValidationPipe whitelist/transform/forbidNonWhitelisted | 입력별 검증·ApiExceptionHandler | form 이후 검증과 조기 JSON 처리 순서까지 비교. 깨진 JSON의 문구만 명시적 예외, 상태·JSON 구조는 동일 |
| APP_GUARD ThrottlerGuard, 120/60000ms | RequestRateLimiter·order(-100) RateLimitInterceptor | 기존429 필수 검사 유지. preflight와 조기 파서 실패는 인증/한도에 도달하지 않고 보호 경로의 큰 본문도401보다413이 먼저 반환됨 |
| trust proxy 미설정, 직접 연결 IP | getRemoteAddr, 별도 forwarded-header 구성 없음 | 직접 연결 기준 유지. 임의 X-Forwarded-For를 신뢰하도록 바꾸지 않음. 3단계 nginx 신뢰 프록시 백로그 유지 |
| ScheduleModule+TokenCleanupService | 스케줄러·명시적 시간대·on/off | 구현됨. UTC컨테이너03시→서울03시는 기존 알려진 차이, 분산 잠금 없음은 백로그 |
| Prisma/Auth/Users/Maps/Places/Favorites/Feedback/Reports/Upload/Notifications 모듈 | JPA·해당 API·저장소·FCM 전송 및 호출 지점 | 0~9번 구현 범위와 대응. Firebase 없음/잘못된 값 비활성화·알림 실행기는 기존 결정. HTTP 전역 누락과 분리 |

관측 상세·가변/전송 헤더 제외 목록·자기 본문 길이와 validator 검사·재검증 명령은 [전역 HTTP fixture 설명](src/test/resources/globals/README.md)에 있다. 실제 Flutter 브라우저 수동 실행은 별도 체크리스트이며 HTTP 자동 비교 통과와 구별한다.
