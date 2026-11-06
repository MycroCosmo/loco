# 9번 묶음 — Firebase 발송·예약 작업 설계

상태: **관측·설계만 완료, 사용자 선택 대기. v3 발송·예약 작업은 구현하지 않았다.**
기준: `b3278fc`의 v2 원본, firebase-admin Node 13.10.0 / @nestjs/schedule 6.1.3 / cron 4.4.0 / PostgreSQL 17.
실제 Firebase·FCM·Google OAuth 요청과 서비스 계정 값 사용은 없다. 공개 문서/POM 열람과 Maven 후보 다운로드는 발송·인증 호출과 구별한다.

## 1. 범위와 읽는 순서

- v2: `apps/server/src/notifications/notifications.service.ts`, `maps/maps.service.ts` 189·279행, `places/places.service.ts` 377행, `tasks/token-cleanup.service.ts`, `app.module.ts`.
- v3: 기존 `NotificationSender`, `DeferredNotificationSender`, `MemberJoinedNotification`, `MapMembershipService`, `PlaceMutationService`. 이번에는 읽기만 했다.
- 관측: `src/test/resources/notifications/observe-v2.cjs` → `NotificationsObservationTest` → 같은 폴더의 `service-v2.json`, `call-sites-v2.json`, `cleanup-v2.json`.
- 의존성 측정: `scripts/notification-dependencies.init.gradle`, `src/test/resources/notifications/dependencies-observed.json`.

Node 스크립트는 원본 빌드된 v2 클래스를 실행한다. Firebase 모듈의 초기화·send 경계만 대역으로 바꿔 메시지/실패/완료를 제어한다. 실제 SDK의 잘못된 JSON/빈 객체 credential 검증은 네트워크 없이 실행한다. 활성 발송 분기는 **서비스 계정이 아닌 빈 객체 `{}`를 대역이 받아들인 경우**다. 이것을 실제 Firebase 초기화 성공으로 해석하지 않는다.

Firebase/Google 관련 환경 이름을 제거하고 fetch·HTTPS·HTTP/2를 차단한다. 소켓은 루프백 및 테스트 DB 주소/포트만 허용한다. `example.invalid`에 대한 fetch/HTTPS/TCP 차단 probe 3개가 모두 요청 전 실패하며, 그 외 예상하지 않은 외부 시도는 0이다. Prisma의 별도 엔진은 하네스가 만든 테스트 DB만 사용한다. v2/Flutter 원본, 공유 preload·계측, 운영 코드·의존성·Compose·V1은 변경하지 않았다.

## 2. v2 NotificationsService 계약

| 상황 | 관측·코드 기준 결과 |
| --- | --- |
| FIREBASE_SERVICE_ACCOUNT 없음/빈 문자열 | 경고 후 비활성화, 사용자/멤버 SELECT 0·send 0 |
| 잘못된 JSON `{broken`, 객체 `{}`, `null` | 생성자가 오류를 잡고 비활성화. 앱을 강제 종료하는 코드 없음, SELECT 0·send 0 |
| 유효 설정 분기 | 먼저 credential.cert를 호출한 뒤 admin.apps[0]이 있으면 재사용, 없으면 initializeApp. 계정 재로딩 없음 |
| 사용자 없음/null 토큰/빈 토큰 | SELECT 1·send 0 |
| 토큰 있음 | SELECT 1·SDK send 1, 메시지 ID를 응답으로 반환하지 않음 |
| 탈퇴 회원에 토큰이 남음 | deletedAt 필터가 없어 send 1. 정상 탈퇴 흐름이 토큰을 비우는 것과 별개 |
| 일반 발송 실패 / invalid-registration-token | 경고, Promise는 resolve, 토큰 유지 |
| registration-token-not-registered | userId로 fcmToken=null UPDATE, 다른 토큰 오류는 삭제하지 않음 |
| 발송 중 old→new 토큰 변경 후 old 토큰 UNREGISTERED | **new 토큰도 null로 지워짐**. 실제 DB 경합 gate로 재현 |
| 사용자 SELECT 실패 / 무효 토큰 UPDATE 실패 | sendToUser는 reject. SELECT는 try 밖, UPDATE 실패도 상위로 전파 |
| sendToMap 내부 사용자 실패 | allSettled 때문에 전체는 resolve. 멤버 목록 SELECT 자체가 실패하면 reject |

SDK send 인수는 `{token, notification:{title,body}, data, apns:{payload:{aps:{sound:'default'}}}, android:{priority:'high'}}`다. data가 없는 호출은 JS undefined 필드이므로 직렬화 시 생략한다. 단말 표시·실제 수신 성공은 관측하지 않았다.

사용자 조회·토큰 정리·멤버 조회의 실패 전파 3건은 DB 경계 대역에서 reject를 주입했다. 토큰 교체 경합과 cleanup의 삭제 실패는 실제 PostgreSQL 변경/트리거로 검증했다. 두 관측 방법을 같은 수준의 DB 장애 재현으로 해석하지 않는다.

sendToUser의 경고에는 userId와 SDK err.message가 들어간다. 원문 오류에 민감 내용이 섞일 수 있으므로 v3에서는 토큰·서비스 계정·Authorization·오류 원문을 기록하지 않고 오류 코드/유형과 내부 사용자 식별자만 남기는 방안을 제안한다. 비활성화/설정 오류는 시작 시 상태를 명확히 기록하되 원문 설정은 남기지 않는다.

### sendToMap — 논리적 N회 조회와 실제 SQL은 다르다

mapId의 멤버에서 excludeUserId를 제외한다. 방장 여부·deletedAt·토큰 중복을 추가로 검사하지 않는다. 방장이 map_members에 없으면 별도 수신자로 추가하지 않는다. Promise.allSettled로 각 sendToUser를 동시에 시작하고 전부 끝날 때까지 기다린다. API 호출자는 이 Promise를 기다리지 않는다.

| 제외 후 멤버 수 N | 논리적 user.findUnique | 실제 SELECT | SDK send 호출 | 비고 |
| ---: | ---: | ---: | ---: | --- |
| 0 | 0 | 1 | 0 | 멤버 목록만 |
| 3 | 3 | **2** | 2 | 두 회원이 같은 토큰, 한 회원은 빈 토큰 |
| 10 | 10 | **2** | 9 | 빈 토큰 한 회원 |

현재 Prisma는 같은 tick의 findUnique를 `users.id IN (...)`으로 배치한다. fixture에 실제 SQL 두 개를 보존했다. 따라서 **v2의 실제 SQL을 N+1이라고 단정하지 않는다.** Java에서 단순 순회 조회하면 오히려 N+1이 된다. 수신자 3명 조건에서 두 send Promise가 해제 전 모두 pending이 된 것도 확인했다. 동일 토큰은 중복 제거하지 않고 2회 보낸다. 이는 SDK 호출 수이며 SDK 내부 HTTP 재시도 횟수와는 다르다.

## 3. 호출 지점·응답·커밋 순서

| 호출 지점 | 발송 전 완료된 작업 | 알림 내용·대상 | 관측 |
| --- | --- | --- | --- |
| 공개방 join | 멤버 upsert 커밋 → 가입자 nickname 조회 | 방장에게 `새 멤버 가입`, `member_joined`, mapId | SDK 완료 gate를 닫아도 201, 별도 DB 연결에서 멤버 1행 |
| joinByCode | 멤버 upsert 커밋 → nickname 조회 | 동일, private방 | gate 상태에서 201, 커밋 보임. 호출 뒤 응답용 map.findUnique가 추가로 실행됨 |
| 장소 create | 장소 커밋 → 마커 셀 갱신 완료 → nickname 조회 | 작성자 제외 멤버에게 `새 장소 추가`, `place_added`, mapId/placeId | gate 상태에서 201, 장소 1행·마커 셀 3행 보임 |

메시지 본문은 각각 `가입자님이 "지도방"에 가입했습니다.`, `작성자님이 "지도방"에 "장소"을 추가했습니다.`이며 nickname이 null이면 `누군가`다. 반복 가입은 새 멤버가 아니어도 매번 알림이 발생한다. 방장이 자기 공개방에 가입하는 경우도 자기에게 보낸다. 관측 HTTP 6건에는 반복 가입·방장 자기 가입과 아래 실패가 포함된다.

세 호출 모두 `await`와 `.catch`가 없다. 서비스 호출은 API 반환 전에 시작되지만 **FCM 발송 완료와 실제 HTTP 응답의 도착 순서는 보장되지 않는다**. 빠른 발송은 응답보다 먼저 끝날 수도 있다. 검증할 계약은 “발송 완료를 기다리지 않음”이다. sendToUser/멤버 SELECT의 DB 예외가 reject되면 미처리 Promise 위험이 있다. 이 위험은 서비스 직접 호출로 확인했으며 실제 서버 프로세스를 크래시시키는 시험은 하지 않았다.

장소와 마커 셀은 한 트랜잭션이 아니다. 마커 INSERT 실패를 주입하면 **HTTP 500·장소 1행 잔존·알림 0회**였다. v3에서도 장소 첫 커밋 이벤트에 알림을 연결하면 안 된다. 세 정밀도의 셀 갱신까지 끝난 기존 호출 위치를 유지한다. 가입 커밋 실패 시 알림 0회는 기존 v3 계약 검사로 재검증했다.

현재 v3 가입 이벤트의 `@TransactionalEventListener`는 AFTER_COMMIT이지만 **동기 listener**다. 지금 sender가 no-op이므로 눈에 띄지 않으며 실제 송신을 바로 연결하면 HTTP가 발송을 기다리게 된다. 장소는 이미 applicationTaskExecutor에 제출한다. 9-2에서 둘을 전용 실행 경계로 통일하는 선택이 필요하다.

## 4. 예약 작업의 정확한 삭제 조건

`@Cron('0 3 * * *')`는 5필드 표현식으로 **프로세스 현지 시간 매일 03:00**이다. timeZone, waitForCompletion, 분산 잠금이 지정되지 않았다. Spring의 6필드 대응식은 `0 0 3 * * *`다. 단순히 원문 5필드를 복사하지 않는다.

| 테이블 | 삭제 조건 |
| --- | --- |
| refresh_tokens | revokedAt IS NOT NULL **OR** expiresAt < (현재 시각 − 30×24시간) |
| password_reset_tokens | usedAt IS NOT NULL **OR** expiresAt < 현재 시각 |

refresh의 “만료 후 30일”과 reset의 “즉시 만료 삭제”는 다르다. 30일은 달력 한 달이 아니라 2,592,000,000ms다. 두 비교는 모두 `<`라 경계와 같은 값은 남는다. revoked/used는 날짜가 미래여도 null이 아니면 즉시 지운다. 원본은 두 기준 시각을 각각 Date.now()/new Date()로 읽으므로 미세한 차이가 가능하다. v3는 기존 Clock으로 테스트하되 이관 시 두 읽기를 보존할 수 있다.

고정 시각 `2026-11-06T12:00:00Z` 실제 DB 관측:

- refresh 6행 중 2행 삭제: 30일보다 1ms 더 오래된 만료, 미래 만료지만 revoked. 경계 같음/1ms 뒤/최근 만료/미래는 4행 유지.
- reset 4행 중 2행 삭제: 현재보다 1ms 과거, 미래 만료지만 used. 현재와 같은 값/1ms 미래는 2행 유지.
- 재실행은 4/2행 그대로다.
- refresh 삭제를 실패시키면 **refresh 6·reset 2**, reset 삭제를 실패시키면 **refresh 4·reset 4**가 남는다. Promise.all의 두 독립 deleteMany이므로 부분 커밋이다. 하나의 @Transactional로 감싸면 호환 동작이 바뀐다.
- 같은 cron의 다음 실행을 UTC/Asia-Seoul에서 계산했다. 기준 `2026-11-06T00:00Z`에 UTC 설정은 `11-06T03:00Z`, Seoul은 `11-06T18:00Z`(현지 다음 날 03:00)다. 프로세스 TZ를 바꾼 실제 cron 라이브러리 계산이며 밤 3시까지 기다린 실행 시험은 아니다.

스케줄러는 Nest 앱마다 등록된다. 서버 두 대면 각각 실행하며, 오래 걸릴 때 실행 겹침을 막는 옵션도 없다. 삭제는 반복 가능하지만 중복 SQL·락 경합·부분 실패 로그는 남을 수 있다. v2/v3 동시 운영 시 둘 다 활성화할지도 배포 설정으로 정해야 한다.

## 5. 발송 의존성 후보 실측

조사일 2026-11-06. 현재 앱 runtimeClasspath를 기반으로 **별도 Gradle configuration**에서만 후보를 해석했다. 운영 build.gradle은 그대로다. 숫자는 해석된 JAR 파일 크기의 합(MiB=2^20바이트)이며 Docker 이미지·JVM 상주 메모리·시작 시간 수치가 아니다. excluded/transitive 축소 최적화는 하지 않았다.

| 구성 | JAR 수 | 합계 MiB | 현재 대비 |
| --- | ---: | ---: | ---: |
| 현재 앱 | 90 | 62.223 | — |
| Firebase Admin Java 9.11.0 단순 추가 | 195 | 123.413 | **+61.190 MiB**, 새/교체 artifact 111, 기존 artifact 6 제거/교체 |
| 기존 HttpClient 5 + google-auth-library-oauth2-http 1.52.0 | 112 | 68.319 | **+6.096 MiB**, 추가 22, 기존 버전 교체 0 |

Admin 자체 JAR은 1,098,778바이트지만 Cloud Firestore/Storage/Monitoring, protobuf, gRPC, Netty, Google 인증/HTTP, Guava 등이 따라온다. 직접 호출 후보도 인증 라이브러리 때문에 Guava/Gson/Google HTTP, OpenCensus, gRPC API/context, Apache HttpClient **4** 등이 추가된다. “HttpClient 5가 이미 있으니 새 의존성 1개뿐”이 아니다. B에서 FCM은 기존 5를 쓰고 OAuth 인증 라이브러리의 HTTP 전송은 별도 경계로 통제해야 한다.

특히 A 단순 추가는 다음 버전을 바꿨다. Spring Boot의 일반 platform은 높은 전이 버전을 무조건 막지 않는다.

| 라이브러리 | 현재 | A 후보에서 해석 |
| --- | --- | --- |
| httpclient5 | 5.5.2 | 5.6.4 |
| httpcore5 / h2 | 5.3.6 | 5.4.3 |
| nimbus-jose-jwt | 9.37.4 | 10.9.1 |
| slf4j-api | 2.0.18 | 2.0.19 |

8-5의 Boot BOM HttpClient 유지 결정은 유효하다. **A 선택을 이 업그레이드까지 허가한 것으로 해석하지 않는다.** A를 고르면 먼저 SDK 버전/제약 조합으로 기존 버전을 유지 가능한지 작은 격리 계약 검증을 하고, 불가능하면 다시 선택을 요청한다. 강제 버전 고정만으로 바이너리 호환을 확인했다고 주장하지 않는다. B에서는 현재 조사 조합의 교체가 없었다.

## 6. 사용자 결정 항목 — 모두 제안, 미확정

### 9-1 발송 구현 방식

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| A. Firebase Admin Java SDK | 공식 메시지/플랫폼 옵션·오류 타입·토큰 갱신 지원, 직접 프로토콜 코드가 적음 | +61.190MiB, 전이 버전 충돌. 먼저 8-5 BOM 유지 검증 필요 |
| **B. HTTP v1 + 기존 HttpClient 5 + Google 인증 라이브러리 (권장)** | +6.096MiB, 기존 버전 유지, FCM 요청/timeout/retry를 전용 클라이언트로 통제 | JSON/error-code 매핑, 인증 토큰 캐시·갱신 경계, FCM/OAuth 두 transport 테스트를 직접 책임져야 함 |

B는 개인 키 JWT 서명이나 OAuth를 직접 재구현하지 않고 인증 라이브러리에 맡긴다. 테스트는 고정 토큰을 주는 자격 증명 대역과 로컬 HTTP transport로만 수행하며 실제 service account/ADC를 쓰지 않는다. 후보 버전은 조사 기준이며 이번에 운영 의존성에 추가한 것이 아니다.

### 9-2 커밋 후 비동기 실행·종료

공통 제안: 가입은 AFTER_COMMIT listener에서 불변 메시지를 제출하고, 장소는 마지막 마커 갱신 뒤 제출한다. JPA 엔티티/영속성 컨텍스트를 작업에 넘기지 않는다. worker의 사용자 조회·토큰 UPDATE는 별도 작업/트랜잭션이다. AFTER_COMMIT의 이전 트랜잭션으로 UPDATE를 시도하지 않는다.

| 선택 | 장점 | 단점·제안 설정 |
| --- | --- | --- |
| **A. 전용 제한 스레드 풀 (권장)** | 동시 발송/DB 부하·대기열 상한 명확, Spring 종료 수명주기에 연결 쉬움 | 시작 제안 core 2/max 4/queue 100, 설정화·부하 측정 필요 |
| B. Java 21 가상 스레드 + 동시성/대기 상한 | blocking I/O당 플랫폼 스레드 비용이 작음 | 무제한 submit은 위험. 별도 permit/queue 필요, 단순 가상 스레드 교체만으로 해결 안 됨 |
| C. Outbox + 별도 worker | 재시작 시 미발송 복구 가능 | 테이블/폴링/중복 처리 추가, 현재 V1 유지·단순 이관 범위를 크게 넓힘 |

A/B의 실패 격리 제안: 제출 거절과 worker 예외를 모두 잡아 로그/실패 집계, API 성공/기존 커밋 유지. CallerRunsPolicy는 HTTP를 기다리게 하므로 사용하지 않는다. 무조건 조용히 폐기하지 않는다. shutdown은 신규 제출 중단 → 최대 30초 drain → 미완료 건수 기록 후 종료로 제안한다. 큐는 메모리이므로 강제 종료·30초 초과·포화에서 손실 가능하며 전달 보장/outbox와 다르다. @Async를 쓰면 별도 Bean의 proxy를 통해 호출해야 한다. 어떤 방식도 “HTTP 응답보다 반드시 늦게 발송”은 보장하지 않는다.

### 9-3 sendToMap 조회·발송 방식

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| A. 멤버 목록 → 회원별 조회·개별 발송 | v2 코드 흐름과 가장 유사 | Java 단순 구현은 1+N SELECT. v2 실측 2회보다 나빠지므로 그대로 이식했다는 설명은 부정확 |
| **B. 토큰 일괄 조회 + 개별 발송 (권장)** | JOIN projection 1 SELECT로 상한 고정, 사용자별 실패/삭제 처리 단순 | 토큰을 읽는 시점이 한 snapshot으로 바뀜. 동시 토큰 교체 검사 필요 |
| C. 일괄 조회 + SDK sendEachForMulticast | 최대 500개씩 결과 매핑 가능 | SDK 의존성 필요, 부분 실패/청크 관리. **HTTP 요청 1회로 줄어드는 방식이 아님** |

B/C에서도 v2와 같이 제외 사용자만 빼고 중복 토큰·탈퇴 회원 토큰을 임의 제거하지 않는 것이 기본 이관 제안이다. 중복 제거·탈퇴 필터는 후속 개선 후보. SDK sendEach/Multicast는 내부적으로 메시지마다 HTTP 호출하므로 네트워크 횟수 감소로 성능을 주장하지 않는다. 응답 배열 위치로 userId/전송 당시 token을 보존해야 한다.

### 9-4 비교 대역·비동기 완료 대기

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. v2 모듈 preload send 경계 + v3 전송 대역 (권장)** | 서비스 계정 없이 원본 비즈니스/DB/실제 HTTP/메시지 비교, 현재 관측 재사용 | SDK 직렬화·내부 retry 자체는 이 경계만으로 검증하지 못함 |
| B. SDK의 저수준 HTTP transport까지 양쪽 교체 | 인증 갱신·HTTP 상태·SDK 재시도도 검사 가능 | Node SDK 내부 지점에 결합, SDK 변경 시 대역 재검증 필요 |

A도 선택한 v3 adapter는 별도의 루프백 transport 계약으로 JSON/header/오류/timeout을 검사한다. 인증 토큰 공급은 대역이고 OAuth outbound도 차단한다. 가짜 token URI만 허용하며 ADC/metadata fallback은 금지한다. v2 preload는 정확한 firebase-admin 모듈만 교체하고 다른 transport는 fail-closed, 대역 호출이 없으면 테스트 성공으로 처리하지 않는다.

비동기 비교는 caseId별 작업 등록/시작/종료 카운터와 CompletableFuture/latch를 쓴다. HTTP 응답 뒤 barrier를 열고 **그 케이스의 작업 전부 종료**를 제한 시간 안에 기다린 뒤 DB/발송 multiset을 비교한다. 없는 발송은 executor drain 후 0회로 검사한다. 작업이 남은 채 시드 초기화·다음 케이스로 넘어가지 않는다. sleep으로 적당히 기다리기/대기 시간 초과 skip은 쓰지 않는다. 이번 관측도 pending gate로 응답의 비대기를 검증했다. 메시지 순서는 Promise.allSettled에서 보장되지 않으므로 수신자+payload 기준으로 비교한다.

### 9-5 예약 시간대·다중 서버

서로 독립적인 두 선택이다. 답변에 시간대와 중복 정책을 함께 적는다.

| 시간대 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. 명시 UTC 03:00, 설정으로 변경 (권장)** | 배포 호스트 TZ에 무관, 현재 UTC 기준 운영 제안에 명확한 계약 | 한국 시간 12:00이다. 한국 새벽 의도면 다름 |
| B. 명시 Asia/Seoul 03:00 | 한국 새벽 청소 의도 명확 | UTC 기준과 실행 시각이 9시간 달라지는 의도된 차이 |
| C. 프로세스 기본 TZ 유지 | v2의 설정 미지정 의미 유지 | Windows/Linux/배포 설정에 따라 시각이 달라짐 |

| 중복 정책 선택 | 장점 | 단점 |
| --- | --- | --- |
| **A. 지금 잠금 없이 인스턴스별 실행 (권장: 이관 범위 유지)** | 반복 가능한 삭제에 새 스키마/라이브러리 없음 | 서버 2대와 v2/v3 병행 시 중복 SQL·락 경합. 설정으로 한쪽 scheduler를 끌 수 있게 함 |
| B. 지금 PostgreSQL advisory lock | 스키마 추가 없이 같은 DB의 v3 간 중복 실행 차단 | 연결 고정·finally unlock·락 키 관리 필요. **v2는 참여하지 않아 v2 중복은 못 막음** |
| C. ShedLock 또는 외부 scheduler | 운영상 소유권/다중 인스턴스 관리 쉬움 | 새 테이블/의존성 또는 배포 구성이 필요 |

본체 cleanup 메서드와 schedule trigger를 분리하고 Clock으로 경계를 검증한다. 자동 발동은 테스트에서 끄고 본체를 직접 실행한다. 겹침 허용을 유지할지 B/C로 막을지도 중복 정책에 포함된다. 연결을 고정하지 않은 pool에서 pg_advisory_lock/unlock을 따로 호출하는 구현은 제안하지 않는다.

### 9-6 무효 토큰 삭제 경합

- A. userId만으로 null 처리: v2와 동일, 새 토큰 소실도 유지하고 개선 후보 기록.
- **B. userId AND fcmToken=발송 당시 토큰 조건부 UPDATE (권장)**: 새 토큰 보존. v2의 old→new→UNREGISTERED 사례와 다른 **개선된 알려진 차이** 1개로 등록. 스키마 변경 없음.

### 9-7 SDK 재시도·timeout 정책

카카오의 “재시도 없음”을 FCM에 자동 적용하지 않는다. Node SDK 13.10.0 기본 코드는 503/ECONNRESET/ETIMEDOUT에 최대 4회 retry를 정의하며 메시징 요청에 timeout=15,000ms를 전달한다. SDK send 1회 관측을 HTTP 1회로 해석하면 안 된다. Java SDK도 기본 retry가 있으며, 선택 adapter의 실제 동작은 로컬 transport에서 검증해야 한다.

- **A. v2 SDK 정책을 기준으로 명시적 상한 유지 (권장)**: 일시 실패 복원에 가깝다. FCM의 중복 가능성·종료 시 미완료가 남음. 15초 timeout과 최대 4회 retry를 기준으로 선택 구현의 대상/지연/timeout 의미를 로컬 관측해 고정하고 auth 갱신과 FCM retry를 구별한다. v2 앱에 별도 전체 deadline은 없다.
- B. 연결 2초·소켓 무활동 5초, retry 없음으로 제한: 부하·실패 시간이 단순하지만 v2 대비 전송 성공률/시점 차이를 알려진 차이로 기록. 설정화하며 OAuth transport에도 별도 제한이 필요하다.

이번 SDK 경계 대역은 내부 retry를 실행하지 않았다. A의 모든 네트워크 상한을 검증했다고 주장하지 않는다. A 선택 뒤 하위 transport 관측에서 새 결정이 필요하면 구현 전에 다시 제시한다.

### 9-8 cleanup 트랜잭션 경계

- **A. 두 독립 삭제의 부분 커밋 유지 (권장)**: v2와 일치. 한쪽 실패 후 다른 쪽 성공을 보고하고 다음 실행에서 재시도한다.
- B. 하나의 트랜잭션으로 묶음: 둘 다 성공/롤백으로 단순하지만 v2의 실패 시 DB 결과가 달라져 알려진 차이. 실패 한쪽 때문에 정상 쪽도 계속 남을 수 있음.

### 9-9 Firebase 설정 오류 처리

- **A. 없음·잘못된 값 모두 발송 비활성화 후 앱 시작 (권장: v2 유지)**: API 가용성 유지, 시작 로그/상태로 비활성화를 드러냄. 설정 실수를 늦게 알아챌 수 있음.
- B. 없음은 비활성화, 명시한 값이 잘못되면 시작 실패: 설정 오류를 즉시 발견하지만 v2와 다르고 알림 때문에 API 전체를 못 띄울 수 있음.

어느 선택이든 실제 계정 값은 외부 환경에서만 받으며 예제에는 키만 둔다. 이번에는 환경 키·Compose를 추가하지 않았다. 기존 Compose는 FIREBASE_SERVICE_ACCOUNT를 앱에 전달하지 않으므로 구현 단계에서 양쪽 전달 설정도 필요하다.

## 7. 기존 알려진 차이 두 건 해소 계획

현재 `maps.firebase-delivery`, `places.firebase-delivery`는 보류 상태다. 이번 설계만으로 삭제하지 않으며 전체 알려진 차이 10개도 그대로다.

선택 후 필수 검사로 바꿀 항목:

1. 없음/잘못된 설정의 비활성화, 올바른 경계 주입 시 실제 adapter 호출, 메시지·APNs sound·Android priority·data 비교.
2. 공개/코드/반복/방장 자기 가입, 장소 추가의 수신자·제외 사용자·메시지. 일반 실패·UNREGISTERED와 토큰 교체 경합.
3. 발송 gate를 닫아도 HTTP 완료, 별도 DB 연결의 커밋 확인, 가입 롤백·마커 실패 시 0회. executor 거절·worker DB 실패·종료 중 제출도 API/커밋과 격리.
4. N=0/1/3/10에서 실제 SQL 수 고정, 중복 토큰/탈퇴 토큰의 명시된 정책, fan-out 부분 실패와 완료 barrier.
5. cleanup 경계 ±1ms·revoked/used·반복 실행·한쪽 삭제 실패·선택한 다중 서버/종료 정책.
6. 선택 adapter의 로컬 전송/인증 경계 계약까지 통과하면 보류 항목 둘을 제거한다. 다른 개선 선택은 별도 알려진 차이로 등록하며, 실제 단말 배달·운영 자격 증명 검증은 별개 미검증으로 남긴다.

후속 구현 순서 제안: 선택/의존성 호환 확인 → 발송 adapter → 비동기·세 호출 지점 → cleanup → 두 보류 해소·전체 회귀/build. **이 문서에서는 어느 단계도 구현하지 않는다.**

## 8. 검증 결과·재실행

- 원본 v2 관측 3 JUnit 안에 서비스 21·HTTP 호출 지점 6·cleanup 6, 합계 **33개 관측 기록**을 고정했다. 33개 전부가 HTTP 요청이라는 뜻은 아니다.
- 최초 관측 성공 3/3, 19.977초(v2 이미지 캐시). 작성 중 로그의 JSON 출력 혼입, 장소 address 누락, Prisma SQL을 N+1로 가정한 오류를 고쳐 원본 v2는 변경하지 않았다.
- 고정 fixture 재검증 + 기존 v3 알림 계약: **10/10 성공(test 7 + comparisonTest 3), 실패/skip 0, 이번 실행 알려진 차이 0, 37.602초**, Spotless 통과. 9번 발송 구현이 완료됐다는 의미가 아니다.
- 의존성 해석 107.436초(후보 최초 다운로드 포함). 이 시간은 테스트 시간과 따로 기록한다. 후보 라이브러리를 실행해 Firebase/Google로 연결하지 않았다.
- 전체 1,286건 회귀/build는 설계·관측 단계여서 재실행하지 않았다. 직전 8번 전체 통과 기록을 이번 실행 결과로 바꿔 쓰지 않는다.

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat spotlessCheck test --tests '*MapNotificationContractTest' --tests '*PlaceNotificationContractTest' comparisonTest --tests '*NotificationsObservationTest' --rerun --console=plain
# 운영 build.gradle 변경 없이 후보 의존성만 조사
.\gradlew.bat -I scripts/notification-dependencies.init.gradle notificationDependencyReport --console=plain
```

fixture를 새로 관측하려면 NOTIFICATIONS_OBSERVE=true로 실행한다. 자동으로 기대 파일을 덮어쓰지 않으며 `build/reports/notifications/` 결과를 검토한 뒤 별도로 반영해야 한다. 외부 네트워크 대역은 같은 방식으로 계속 강제한다.

## 9. 공식 근거

- [Firebase Admin Java setup](https://firebase.google.com/docs/admin/setup), [9.11.0 POM](https://raw.githubusercontent.com/firebase/firebase-admin-java/v9.11.0/pom.xml): 후보 버전/전이 의존성. 실제 크기는 본 저장소에서 직접 해석한 JSON이 근거다.
- [FCM HTTP v1 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api): 인증 라이브러리와 FCM 전송 경계.
- [FirebaseMessaging sendEach/Multicast](https://firebase.google.com/docs/reference/admin/java/reference/com/google/firebase/messaging/FirebaseMessaging): 최대 500개·개별 HTTP 호출.
- [Node SDK 13.10.0 retry 코드](https://raw.githubusercontent.com/firebase/firebase-admin-node/v13.10.0/src/utils/api-request.ts), [Java SDK 기본 retry 코드](https://raw.githubusercontent.com/firebase/firebase-admin-java/v9.11.0/src/main/java/com/google/firebase/internal/ApiClientUtils.java).
- [Node 메시징 15초 timeout](https://raw.githubusercontent.com/firebase/firebase-admin-node/v13.10.0/src/messaging/messaging-api-request-internal.ts).
- [Spring 6.2 scheduling/executor](https://docs.spring.io/spring-framework/reference/6.2/integration/scheduling.html), [AFTER_COMMIT 자원 주의사항](https://docs.spring.io/spring-framework/docs/6.2.6/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html).
