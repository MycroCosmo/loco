# LOCO v3 — Spring 기반과 v2 인증·오류 호환

CMS 5-B부터 호스트 JAR에 관리자 SPA를 포함하므로 Gradle의 리소스 처리·테스트·빌드에도 **Node 24.22.2 / npm 11.19.1**이 필요하다(Java 21 유지). Windows에서는 이 디렉터리에서 `. ../admin/prepare-node.ps1`을 먼저 실행한다. 시스템 Node와 v2의 Node 20은 변경하지 않는다. Docker 빌드는 고정 Node를 자체 준비한다. 관리자 화면 실행과 별도 Chromium E2E 명령은 [관리자 README](../admin/README.md)를 참고한다.

PR 전 전역 HTTP 보완: JSON/form 10MiB, form 파라미터 1,000개·중첩 깊이 32, Helmet 기본 헤더와 CORS를 v2 순서대로 적용한다. `ALLOWED_ORIGINS`는 쉼표로 나눈 정확한 Origin 목록이며 비어 있으면 `http://localhost:8080,http://localhost:3000`이다. localhost:5173 웹 개발 서버는 해당 Origin을 명시하고 Compose v3를 재생성한다. 미허용 Origin 자체를 서버 403으로 막지 않으며, CORS는 인증을 대신하지 않는다.
`HOST`는 `server.address`에 연결되며 기본은 0.0.0.0이다. Compose에서 컨테이너 포트를 공개할 때는 이 기본값을 유지한다. `X-Powered-By`만 생략하는 10-1 A 결정과 전체 헤더 비교 방법은 [전역 HTTP fixture 설명](src/test/resources/globals/README.md)에 있다.

Java 포맷은 Spotless 8.1.0 + google-java-format 1.28.0으로 고정한다. Java 21·Gradle 8.14.6에서 실행하며, `./gradlew spotlessApply`로 `src`의 운영·테스트 Java 전체를 정리한다. `./gradlew spotlessCheck`가 검사하고 `check`·`build`에서도 포맷 위반은 실패한다. 빌드가 소스를 자동 수정하지는 않는다.
포매터 실행에는 Gradle 자체도 JDK 21로 실행해야 한다. 최소 실행 조건과 단일 스타일을 기준으로 선택했다: [Spotless 8.1.0 요구사항](https://raw.githubusercontent.com/diffplug/spotless/gradle/8.1.0/plugin-gradle/README.md), [google-java-format 1.28.0 실행 조건](https://raw.githubusercontent.com/google/google-java-format/v1.28.0/README.md).

현재 health·인증·사용자·지도방·장소·리뷰·이미지·마커·즐겨찾기·피드백·신고·카카오 검색 API를 이관했다. 승인된 v2 기준 갱신 외에는 v2와 Flutter 코드를 변경하지 않는다.
빈 PostgreSQL DB에는 Flyway V1으로 v2와 같은 업무 테이블을 만든다. 인증용 JPA 엔티티는 기존 스키마에 매핑한다. 자세한 설계와 읽는 순서는 [인증 문서](AUTHENTICATION.md)를 참고한다.
DB 기준은 사용자가 확정한 PostgreSQL 17(`postgis/postgis:17-3.6`)이다. 맞춰야 할 기존 운영 DB 버전은 없다.
JWT 검증과 v2의 401·404·ValidationPipe 400 응답 기반을 구현했다.
사용자 조회를 포함한 보호 API는 실제 컨트롤러다. 하네스 전용 대역은 실제 API와 겹치지 않는 `/api/__test/harness/...` 경로에만 둔다.

5단계 비교 하네스의 설계·실행·케이스 추가 방법은 [COMPARISON_TESTS.md](COMPARISON_TESTS.md)에 정리했다.
v2 컨테이너를 공유하고, 같은 Flyway V1으로 만든 두 DB에서 응답과 데이터를 비교한다.

기준은 [업데이트된 작업 프롬프트](https://app.notion.com/p/40c140eabf0e81db996df610eed7c32f)다.
이 문서의 1~5단계는 **0번 묶음 안의 작업 순서**다. 전체 로드맵의 2단계(공간 조회·캐시)와 구분한다.
`postgis/postgis` 이미지는 지금 사용하지만 공간 확장은 전체 로드맵 2단계까지 활성화하지 않는다.
이미지 기본 초기화 스크립트도 막아 공간 테이블이 미리 생기지 않게 한다.

후속 작업에서도 v2·Flutter는 읽기만 하며, 이전 직장 코드는 사용하거나 재현하지 않는다.
외부 API는 테스트에서 mock으로 대체하고, 현재 단계 밖의 기술·보류 항목은 추가하지 않는다.
확정된 결정 밖의 새 설계 선택은 선택지와 장단점을 설명한 뒤 사용자 답을 기다린다.
0~7번 묶음 확인을 마쳤다. 8-A 즐겨찾기·피드백·신고 5개 API와 8-B 카카오 검색을 이관했다. 카카오 검증은 실제 API·키 없이 가짜 서버만 사용한다.

[9번 Firebase 발송·예약 작업](NOTIFICATIONS_DESIGN.md)을 확정한 9-1~9-9 결정대로 구현했다. FCM HTTP v1과 Google 인증 라이브러리, 이벤트 단위 제한 실행기, JOIN 수신자 조회, 조건부 무효 토큰 정리, 서울 03:00 cleanup을 사용한다. 원본 v2와 루프백 대역만 비교하며 실제 Firebase/Google 인증·단말 수신은 검증하지 않는다.

알림 설정은 루트 `.env`의 `FIREBASE_SERVICE_ACCOUNT`를 v2·v3에 전달한다. 미설정/잘못된 값은 시작 로그에 비활성화로 표시하고 API는 계속 동작한다. 계정·키·토큰·오류 원문은 알림 로그에 남기지 않는다. `FCM_TIMEOUT_MS` 기본 15000은 전체 deadline이 아닌 소켓 무활동 제한이다. 재시도/워커 점유 한계는 설계 문서 6장의 수치를 참고한다.

전용 실행기는 `NOTIFICATION_CORE_THREADS=2`, `NOTIFICATION_MAX_THREADS=4`, `NOTIFICATION_QUEUE_CAPACITY=100`, `NOTIFICATION_SHUTDOWN_SECONDS=30`을 기본으로 쓴다. 지도방 1건도 작업 1개이며 내부에서 멤버에게 개별 발송한다. 큐 포화·종료 초과는 로그를 남기고 유실될 수 있다. 내구성 큐/outbox는 아니다.

Compose의 `V3_STOP_GRACE_PERIOD` 기본은 70초(HTTP 종료 30초 + 알림 30초 + 정리 여유 10초)다. Docker 기본 10초가 알림 drain을 먼저 끊지 않도록 맞춘 값이며 앱 종료 대기를 늘리면 함께 조정한다. DB/OAuth의 임의 지연까지 종료를 보장하는 값은 아니다.

cleanup 기본은 `TOKEN_CLEANUP_ENABLED=true`, `TOKEN_CLEANUP_CRON=0 0 3 * * *`, `TOKEN_CLEANUP_ZONE=Asia/Seoul`이다. 설정이 필요 없는 예제 키는 환경에 빈 값으로 export하지 말고 생략한다. `TOKEN_CLEANUP_ENABLED=false`는 v3 예약 등록만 끈다. v2에는 해당 스위치가 없으므로 병행 기동하면 v2 UTC 03:00(한국 12:00)도 실행된다. 다중 v3 인스턴스에서는 한 인스턴스만 켜는 운영 설정이 가능하며 분산 잠금은 이번에 추가하지 않았다.

후속 실험: 제한 풀 대 가상 스레드+동시성 상한, 중복·탈퇴 토큰 필터, 3단계 다중 서버에서 advisory lock 대 ShedLock. 현재의 새 토큰 보존 및 서울 새벽 실행은 비교 보고서에 별도 알려진 차이로 남긴다.

9번 최종 전체 회귀·Spotless/build: **1,340건 중 성공 1,339·실패 0·HEIC 공통 제약 skip 1**, 알려진 차이 10, **885.749초**(v2 이미지 캐시). test 279건, comparisonTest 1,061건이다. 중간 Windows TCP 4227 연결 오류 1건은 소스 변경 없는 31건 재검증과 최종 전체 실행에서 통과했으며 [검증 이력](NOTIFICATIONS_DESIGN.md)에 보존했다.

## 탑다운으로 읽는 순서

1. `src/main/java/com/loco/LocoApplication.java`: `main()` → `SpringApplication.run()`이 내장 HTTP 서버와 Spring을 시작한다. `@SpringBootApplication`은 이 클래스가 있는 `com.loco` 아래의 컨트롤러·설정을 검색한다.
2. `src/main/resources/application.yml`: 기본 포트는 8081이다. `/api`는 MVC에서 붙인다. DB 연결 정보와 JWT 비밀 값은 외부에서 받는다. `ddl-auto: none`은 Hibernate가 테이블을 만들거나 바꾸지 않게 한다. `open-in-view: false`는 HTTP 응답 처리까지 JPA 세션을 유지하지 않게 한다. 정적 파일 기본 매핑은 끈다.
3. `src/main/java/com/loco/config/SecurityConfig.java`: JWT 필터를 설치하고 메서드 권한 검사를 켠다. 필터 단계에서는 요청을 통과시키며 컨트롤러의 선언으로 인증 필요 여부를 결정한다. CSRF·세션·요청 저장·로그인 폼·HTTP Basic·기본 logout 처리는 사용하지 않는다.
4. `src/main/java/com/loco/health/HealthController.java`: `@GetMapping("/health")`가 GET을 받는다. 반환한 Map은 JSON으로 직렬화되어 `200 {"status":"ok"}`가 된다. 서버의 HTTP 응답 여부를 확인하는 용도이며 DB 장애를 진단하지는 않는다.
5. `src/test/java/com/loco/health/HealthIntegrationTest.java`: 임시 DB → 임의 포트의 실제 서버 → HTTP 요청 순으로 검증한다. `@DynamicPropertySource`가 테스트 DB 접속 정보를 주입하므로 개발용 `.env`의 DB에 붙지 않는다. 공간 확장이 생성되지 않는지도 확인한다. 자원 제한은 테스트에 설정하거나 검증하지 않고 3단계 Docker Compose에서 적용한다. Docker가 없으면 테스트를 건너뛰지 않고 실패한다.
6. `build.gradle`: Java 21, Spring Boot 3.5.16, 필요한 의존성, JUnit 5 실행을 선언한다. Boot BOM은 함께 사용할 라이브러리 버전 목록이다. PostgreSQL용 Flyway 모듈은 Flyway 본체와 별도로 필요하다. `preparePrisma`는 v2 lockfile의 Prisma 버전(현재 5.22.0)을 읽어 테스트용 CLI를 `build/prisma-cli`에 설치한다. v2 파일은 수정하지 않는다.
7. `src/main/resources/db/migration/V1__baseline.sql`: Prisma가 생성한 SQL 원문이다. 테이블 11개, enum 3개, 보조 인덱스 13개(PK 제외), 외래 키 14개를 만든다. 적용한 V1은 고치지 않고 이후 변경은 다음 버전의 마이그레이션으로 추가한다.
8. `src/test/java/com/loco/migration/FlywayBaselineIntegrationTest.java`: 각 테스트마다 독립된 임시 DB를 만든다. 빈 DB에 V1을 적용하고, 기존 DB는 Prisma `db push`로 별도로 만든 뒤 baseline과 데이터 보존을 확인한다. 실제 DB와 원본 Prisma 스키마를 CLI로 비교한다.

V1 비교 테스트는 `target("1")`로 적용 범위를 고정해, V2 이후 마이그레이션이 추가되어도 V1과 v2 Prisma 스키마의 일치만 검증한다.

요청 흐름: JSON 구문 확인 → JWT 필터(있으면 검증) → MVC가 `/api` 컨트롤러 선택 → 요청 제한 → 보호 경로의 현재 회원 확인 → DTO 바인딩·검증 → `@PreAuthorize` → 컨트롤러 → JSON 응답.
없는 경로는 컨트롤러가 선택되지 않으므로 인증 여부와 관계없이 404가 된다.

DB 시작 흐름: DB 연결 → Flyway 이력 확인 및 V1 적용 → JPA 초기화 → HTTP 요청 처리.
`ddl-auto: none`을 유지하므로 업무 스키마 변경은 Flyway가 담당한다.

Compose 기동 흐름: DB의 TCP 준비 확인 → v3 시작·Flyway 적용·health 확인 → v2 시작.
`depends_on`의 `service_healthy` 조건으로 단순 실행 순서가 아니라 준비 완료까지 기다린다.

## 나머지 파일의 역할

| 파일 | 필요한 이유 / 핵심 설정 |
| --- | --- |
| `settings.gradle` | Gradle 프로젝트 이름을 지정한다. |
| `gradlew`, `gradlew.bat` | 각각 Linux/macOS, Windows에서 프로젝트의 Gradle을 실행한다. |
| `gradle/wrapper/gradle-wrapper.jar` | 지정한 Gradle 배포본을 내려받고 실행하는 공식 부트스트랩이다. |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.14.6과 배포 ZIP의 SHA-256을 고정한다. |
| `.env.example` | 외부에서 제공할 설정 키만 보여준다. 비밀번호·JWT 비밀 값은 넣지 않는다. |
| `.gitignore` | 빌드 결과·Gradle 캐시·실제 `.env`의 커밋을 방지한다. |
| `.gitattributes` | Wrapper 스크립트의 OS별 줄바꿈과 JAR의 바이너리 처리를 지정한다. |
| `README.md` | 실행·검증 방법, 결정 이유, 학습 순서를 기록한다. |

4단계에서 읽을 파일 (`src/main/java/com/loco/` 기준):

| 파일 | 필요한 이유 / 핵심 설정 |
| --- | --- |
| `web/ApiController.java` | `@RestController`를 포함하며 MVC가 `/api`를 붙일 앱 컨트롤러를 표시한다. |
| `security/PublicApi.java` | 공개 메서드·클래스 선언이다. `@PreAuthorize("permitAll()")`를 포함한다. |
| `security/AuthenticatedApi.java` | 인증이 필요한 메서드·클래스 선언이다. `@PreAuthorize("isAuthenticated()")`를 포함한다. |
| `security/JwtPrincipal.java` | 검증된 `sub`를 `userId`, `email`을 그대로 전달한다. 토큰 원문을 보관하지 않는다. |
| `security/JwtAuthenticationFilter.java` | 토큰을 검증해 인증 정보를 만든다. 실패하면 익명으로 계속 진행한다. Security 체인 안에만 등록해 이중 실행을 막는다. |
| `config/ApiWebConfig.java` | MVC prefix, DTO 검증 전 인증 확인, 시작 시 선언 누락·충돌 검사를 연결한다. |
| `web/ApiExceptionHandler.java` | 인증 실패·권한 거절·없는 경로·Bean Validation 오류를 상황별 JSON으로 변환한다. |

`src/test/java/com/loco/security/ApiCompatibilityIntegrationTest.java`는 5단계 하네스의 실제 v2 서버와 격리된 두 DB를 공유한다.
준비 토큰은 직접 발급하고, 실제 v2 로그인 토큰 검증은 해당 테스트에서만 로그인 API를 호출한다.
`ApiDeclarationTest.java`는 선언을 빠뜨리거나 중복한 컨트롤러 때문에 앱 시작이 실패하는지 확인한다.

저장소 루트의 Compose 관련 파일:

| 파일 | 필요한 이유 / 핵심 설정 |
| --- | --- |
| `compose.yaml` | DB 하나에 v2·v3를 연결한다. CPU·메모리, healthcheck, 기동 순서, 데이터 볼륨을 정의한다. |
| `.env.example` | Compose용 DB 계정·비밀번호와 공통 JWT 키의 이름만 제공한다. `apps/server-v3/.env.example`은 v3를 호스트에서 단독 실행할 때 사용한다. |
| `.dockerignore` | 서버 소스와 Docker 파일만 빌드에 전달하고 실제 `.env`·캐시·빌드 결과·모바일 파일을 제외한다. |
| `infra/docker/server-v2.Dockerfile` | v2 CI와 같은 Node 20에서 기존 v2 소스를 빌드·실행한다. `prisma generate`는 클라이언트 코드 생성이며 DB 스키마를 변경하지 않는다. 실행 이미지에는 개발 의존성을 제거한다. |
| `infra/docker/server-v3.Dockerfile` | Java 21에서 Gradle Wrapper로 JAR를 빌드하고 JRE로 실행한다. 빌드 시에는 테스트용 Prisma를 설치하지 않는다. curl은 컨테이너 healthcheck에 사용한다. |
| `infra/docker/start-v2.cjs` | DB 계정·비밀번호를 URL 인코딩한 뒤 기존 v2 진입점을 실행한다. 비밀번호의 `@`, `:`, `#` 등이 접속 URL을 깨뜨리지 않도록 한다. |

## 자동 검증

Java 21과 실행 중인 Docker가 필요하다. v2 비교에는 PATH에 등록된 Node.js/npm도 필요하다. 저장소 루트에서 PowerShell로 실행한다.

```powershell
cd apps/server-v3
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' # 설치 위치가 다르면 변경
.\gradlew.bat test --console=plain           # 단위·v3 단독
.\gradlew.bat comparisonTest --console=plain # v2 비교
.\gradlew.bat allTests bootJar --console=plain # 두 작업 + 실행 JAR
```

Linux/macOS에서는 Java 21을 설정하고 `./gradlew`로 같은 작업을 실행한다.
Docker 기본 주소 풀이 소진된 로컬 환경은 사용하지 않는 CIDR을 `LOCO_TEST_NETWORK_SUBNET` 환경변수로 지정할 수 있다(예: PowerShell `$env:LOCO_TEST_NETWORK_SUBNET = '10.253.30.0/24'`). 비교 하네스의 임시 네트워크에만 적용하며 미지정 시 기존 자동 할당을 사용한다. 기존 컨테이너·네트워크·볼륨을 삭제할 필요가 없고 운영 Compose에는 영향을 주지 않는다.
`test`는 `v2-comparison` 태그를 제외하고, `comparisonTest`는 해당 태그만 실행한다. 새 v2 HTTP·라이브러리·Prisma 비교 클래스에는 `@Tag("v2-comparison")`을 붙인다.
`allTests`는 두 작업을 순서대로 실행하며 `check`와 `build`도 양쪽 검증을 포함한다. 이미 통과한 작업을 실제로 다시 측정하려면 `test --rerun`, `comparisonTest --rerun`, 전체는 `allTests --rerun-tasks`를 사용한다.
기본 테스트도 v3 통합 검증용 PostgreSQL 컨테이너는 필요하지만 v2·Mailpit·Prisma CLI는 준비하지 않는다. OAuth는 외부 API를 mock으로 대체한다.
v3 단독 시드 암호는 Java bcrypt($2b$, cost 10), 비교 시드는 기존처럼 실제 v2 bcrypt로 생성한다. 빠른 테스트에서 v2에 잘못 접근하면 즉시 실패한다.
Gradle을 별도로 설치할 필요는 없다. 첫 실행은 라이브러리·DB 이미지·Prisma CLI 다운로드와 v2 테스트 이미지 빌드 시간이 든다.
`clean`을 생략하면 준비된 Prisma CLI를 재사용한다. Prisma는 테스트 도구이며 Spring 실행 JAR에는 포함되지 않는다.

빠른 테스트(코덱 6건 포함)와 v2 비교 테스트를 분리한다. `allTests`는 둘을 모두 실행하며, 선택 실행의 건수는 각 JUnit 보고서에서 확인한다. 기대 결과:

- `BUILD SUCCESSFUL`, 실패 0개. Windows HEIC 원시 코덱 제약 1개는 SKIPPED이며 AVIF와 애니메이션 WebP는 필수 통과 대상이다.
- 지도방 초대 코드 생성은 의도된 차이다. Firebase 발송 보류는 9번에서 해소했고 새 토큰 보존·서울 새벽 cleanup을 알려진 차이로 보고한다. 동시 공개 가입·코드 가입에서 v2의 500과 v3의 모두 성공도 각각 개선된 차이로 보고한다. 테스트를 조용히 건너뛰지 않는다.
- 429는 모든 인증 API의 한도·응답·헤더를 비교하는 필수 통과 대상이다.
- 인증 없는 `/api/health`: 200, JSON 구조와 값이 정확히 `{"status":"ok"}`.
- `/health`: 404. `/api`를 거치지 않으면 컨트롤러에 접근할 수 없다.
- `/api/actuator/health`: 404. Actuator HTTP 엔드포인트는 노출하지 않는다.
- 실제 v2 로그인 토큰(HS256, 기본 만료 7일)의 `sub`·`email`이 v3에 전달된다.
- 만료·다른 서명·잘못된 형식·`sub` 누락·미래 `nbf` 토큰은 보호 경로에서 v2와 같은 401을 반환한다.
- 없는 경로와 지원하지 않는 HTTP 메서드는 잘못된 토큰이 있어도 v2와 같은 404를 반환한다.
- 공개 DTO 오류는 400 배열, 보호 DTO 오류는 인증이 없으면 401이고 유효한 인증이 있으면 400 배열이다.
- JWT만으로 PUT이 가능하며 CSRF 토큰·세션 쿠키가 필요 없다. 다음 요청에서 JWT를 빼면 다시 401이다.
- 인증되어도 메서드 권한 조건을 만족하지 않으면 403이다. HS384·HS512는 허용하지 않는다.
- 컨트롤러의 prefix·공개/보호 선언 누락과 충돌은 시작 시 실패한다.
- DB에는 기본 `plpgsql` 이외의 추가 확장이 생성되지 않는다.
- 빈 DB에는 V1이 한 번만 적용되며 이력의 버전은 `1`, 유형은 `SQL`이다. 다시 migrate해도 V1을 재실행하지 않는다.
- 기존 스키마에는 기본 설정으로 baseline하지 않는다. 명시적으로 허용하면 유형 `BASELINE`으로 버전 `1`을 기록하고 기존 데이터를 보존한다.
- SQL 재생성 결과는 V1과 동일하며(줄바꿈 차이 제외), 실제 DB와 Prisma 스키마의 차이는 Flyway 관리 테이블 하나뿐이다.
- 테스트 보고서: `build/reports/tests/test/index.html`, `build/reports/tests/comparisonTest/index.html`.
- 실행 JAR: `build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar`.

테스트가 띄운 DB는 종료 시 제거된다. 테스트 비밀번호는 실행마다 무작위로 생성한다.
호환 테스트의 v2·DB도 격리되며 개발 Compose의 계정·데이터를 사용하지 않는다.
v2 테스트 이미지는 기존 Dockerfile과 필요한 소스만 전달해 빌드한다. `.env`나 업로드 파일은 전달하지 않는다.
외부 메일·OAuth API는 호출하지 않는다. 비밀번호 찾기 메일은 테스트 전용 Mailpit으로 수신한다.
하네스 자체 검증과 실제 이관 API의 호환 검증은 구분한다. 실행 시간·인증 대기 시간·알려진 차이는 `build/reports/compatibility/summary.json`에서 확인한다.
빠른 테스트의 환경 보고서는 `build/reports/fast/summary.json`이며 v2 빌드·기동·재시작 횟수가 모두 0이어야 한다.
이미지 코덱 검증은 [실행 방법과 결과](CODEC_COMPATIBILITY.md)를 따른다. `codecTest`로 5건만 실행할 수 있으며 Windows·Linux 재현 스크립트와 v2 관측 결과를 함께 제공한다. 코덱의 알려진 차이/제약은 `build/reports/codec/`에 별도로 표시한다.

스키마 비교는 테이블·컬럼 타입·nullable·기본값·enum·PK·고유 제약·인덱스·외래 키 등 Prisma가 표현하는 구조를 대상으로 한다.
Prisma가 지원하지 않는 임의의 트리거나 뷰까지 검증하는 것은 아니다. 비교 기준은 저장소의 v2 Prisma 스키마이며 기존 개발 DB에 직접 연결하지 않는다.

## V1 생성 방법과 의미

Prisma 5.22.0은 v2의 `package-lock.json`에 고정된 버전이다.
`migrate diff --from-empty --to-schema-datamodel ... --script`는 빈 스키마에서 목표 모델로 가는 SQL을 생성하며 DB에 실행하지 않는다.
그 결과를 편집 없이 V1으로 사용했다. 수작업으로 타입·컬럼명·제약을 옮길 필요가 없다.

v2의 UUID 문자열 컬럼은 DB에서 `TEXT`다. Prisma의 `uuid()`는 클라이언트가 생성하므로 SQL에 UUID 기본값을 추가하지 않는다.
`@updatedAt`도 Prisma가 처리하므로 DB 트리거나 자동 갱신 기본값을 추가하지 않는다. 인증 엔티티는 JPA Auditing으로 이 동작을 처리한다.

`apps/server-v3`에서 아래 명령으로 별도 파일에 SQL을 재생성할 수 있다.

```powershell
.\gradlew.bat preparePrisma
New-Item -ItemType Directory -Force -Path build/baseline-review | Out-Null
Copy-Item ../server/prisma/schema.prisma build/baseline-review/schema.prisma
node build/prisma-cli/node_modules/prisma/build/index.js migrate diff `
  --from-empty --to-schema-datamodel build/baseline-review/schema.prisma `
  --script --output build/baseline-review/V1__baseline.sql
git diff --no-index --ignore-space-at-eol `
  src/main/resources/db/migration/V1__baseline.sql build/baseline-review/V1__baseline.sql
```

기대 결과는 차이 없음, 마지막 명령의 종료 코드 0이다. 스키마 복사본을 쓰므로 v2의 `.env`를 읽지 않는다.

## 기존 DB와 baselineOnMigrate

`V1__baseline.sql`은 빈 DB에 실행하는 SQL 파일이고, Flyway의 **baseline**은 기존 DB를 특정 버전으로 인정하는 이력 등록 작업이다.
baseline은 기존 테이블을 생성하거나 V1과 같은지 확인하는 작업이 아니다.

| DB 상태 | 설정과 동작 |
| --- | --- |
| 빈 DB | 기본 `baseline-on-migrate: false`로 V1을 실행한다. |
| 테이블은 있지만 Flyway 이력이 없음 | 기본값 false에서는 시작을 거절한다. |
| Prisma와 동일함을 확인한 기존 DB | 최초 실행에만 true를 지정하면 버전 1을 `BASELINE`으로 기록하고 V1 실행을 건너뛴다. |
| Flyway 이력이 이미 있음 | 다시 false로 실행한다. 적용한 V1은 반복하지 않고 이후 버전만 실행한다. |

`baseline-version: 1`은 기존 DB를 V1까지 적용된 상태로 인정한다는 뜻이다.
`baselineOnMigrate=true`를 항상 켜두면 다른 비어 있지 않은 DB도 V1 상태로 인정할 수 있으므로 기본값 false를 유지한다.

기존 개발 DB를 가져올 때는 먼저 백업하고, 위 명령으로 준비한 스키마 복사본과 실제 DB를 비교한다.
현재 대상 스키마는 `public`이다. Prisma 접속 URL의 `schema=public`과 앱 JDBC 연결의 대상 스키마를 일치시킨다.
아래 예시는 PowerShell 7의 마스킹 입력으로 Prisma 접속 URL을 받는다. 비밀번호 등은 코드나 명령 이력에 쓰지 않는다.

```powershell
$env:DATABASE_URL = Read-Host '비교할 개발 DB의 Prisma 접속 URL' -MaskInput
node build/prisma-cli/node_modules/prisma/build/index.js migrate diff `
  --from-schema-datasource build/baseline-review/schema.prisma `
  --to-schema-datamodel build/baseline-review/schema.prisma `
  --script --exit-code
$LASTEXITCODE
```

Flyway 이력이 없는 기존 DB는 `-- This is an empty migration.`과 종료 코드 0이어야 한다.
차이가 있으면 종료 코드 2가 되므로 baseline하지 말고 차이를 먼저 확인한다. 명령 실패는 종료 코드 1이다.
이 비교는 읽기 전용이며, 출력된 SQL을 DB에 실행하지 않는다.

Flyway 적용 후 같은 비교를 실행하면 관리 테이블 때문에 종료 코드 2가 된다. 다음 차이 **하나만** 있으면 업무 스키마는 동일하다.
JUnit 테스트도 이 한 문장만 허용하고 다른 차이는 모두 실패시킨다.

```sql
-- 비교 결과의 예시이며 실행할 SQL이 아니다.
DROP TABLE "flyway_schema_history";
```

이력이 없는 기존 DB의 비교가 통과한 경우에만, 앱의 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`가 비교한 DB와 같은 대상인지 확인한 뒤 실행한다.

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'
.\gradlew.bat bootRun --console=plain
```

최초 시작을 확인하고 Ctrl+C로 서버를 종료한 다음 해당 설정을 제거한다. 이후에는 기본 false로 실행한다.

```powershell
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = $null
$env:DATABASE_URL = $null
.\gradlew.bat bootRun --console=plain
```

빈 DB로 시작하는 경우에는 이 수동 baseline 절차가 필요 없다.

## Docker Compose로 v2·v3 함께 실행

아래 명령은 **저장소 루트**에서 실행한다. Docker가 켜져 있고 호스트의 3000·8081·8025·1025 포트가 비어 있어야 한다.
첫 실행에는 `.env.example`을 `.env`로 복사하고 `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `CMS_STORAGE_ACCESS_KEY`, `CMS_STORAGE_SECRET_KEY`를 로컬용 값으로 채운다. 저장소 키는 실제 AWS 키가 아니다. 기존 `.env`는 덮어쓰지 않는다.
JWT 비밀 값은 UTF-8 기준 최소 32바이트의 충분히 긴 무작위 값으로 설정하며 v2·v3에 같은 값이 주입된다.
Nimbus의 HS256 키 길이 요구 사항 때문에 v3는 비어 있거나 32바이트 미만인 값으로 시작하지 않는다. 짧은 기존 개발 키가 있다면 두 앱을 함께 변경하고 토큰을 다시 발급한다.
`.env`에서 `$` 등 치환될 수 있는 문자를 값에 포함할 때는 작은따옴표로 감싼다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# .env의 빈 값을 채운 뒤 실행
docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 180
docker compose ps
```

`docker compose up`으로도 빌드와 기동이 가능하다. 이미지를 만든 뒤 소스를 수정했다면 `--build`로 다시 빌드한다.
프로젝트 이름은 `loco-v3`이며, 기대 결과는 `db`, `mailpit`, `v3`, `v2` 네 서비스 모두 `healthy`다.
필수 환경변수가 비어 있으면 기본 비밀 값으로 실행하지 않고 Compose가 오류로 중단한다.

| 서비스 | 호스트 주소 | CPU | 메모리 |
| --- | --- | --- | --- |
| v2 | `http://localhost:3000/api` | 2 | 1.5GB (`1536m`) |
| v3 | `http://localhost:8081/api` | 2 | 1.5GB (`1536m`) |
| DB | 컨테이너 내부 `db:5432`, DB 이름 `loco` | 2 | 4GB |
| Mailpit | 화면 `http://localhost:8025`, SMTP `localhost:1025` | 0.5 | 256MB (`256m`) |

개발 메일은 기본적으로 Mailpit(`axllent/mailpit:v1.27.4`, 테스트와 같은 버전)에 모인다. 앱의 SMTP 기본값은 `mailpit:1025`, 발신자는 `noreply@loco.app`이며 `.env.example`의 SMTP 키를 비워 두면 이 값을 사용한다.
Mailpit은 외부로 전달하지 않고 메모리에 보관하므로 재생성하면 메일이 사라진다. 개발 Compose는 인증 없는 로컬 SMTP를 사용하며 UI·SMTP 포트는 localhost에만 공개한다. v2가 빈 암호로 AUTH를 시도하지 않도록 Mailpit의 AUTH 기능을 켜지 않는다. `/mailpit readyz`가 통과한 후 v3가 시작한다.
회원가입 후 각 앱의 `/api/auth/forgot-password`로 요청하고 `http://localhost:8025`에서 수신 메일을 확인한다.
실제 메일 서버를 쓰려면 루트 `.env`에 `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_SECURE`를 명시하고 앱을 다시 생성한다. 기존 `.env`에 실제 SMTP 설정이 있으면 그 값이 우선하므로 로컬 수신을 원할 때는 SMTP 키를 비운다. Compose 밖에서 v3를 실행할 때는 호스트용 `SMTP_HOST=localhost`, `SMTP_PORT=1025`를 직접 설정한다.

DB는 호스트 포트를 열지 않으며, 두 앱의 포트도 로컬 PC에서만 접속하도록 바인딩한다.
`shared_buffers=1GB`는 DB 페이지 캐시에 할당하는 값이며 DB 컨테이너 전체 메모리 제한과 다르다.
JVM 힙과 Node 메모리는 우선 런타임 기본값을 사용하며 성능 실험 전에는 추가 튜닝하지 않는다.

스키마는 **v3의 Flyway만** 관리한다. v2 기동에는 `prisma db push`나 migration 명령을 넣지 않는다.
DB healthcheck는 초기화용 임시 소켓 서버를 준비 완료로 오인하지 않도록 TCP로 확인한다.
v3가 마이그레이션을 마치고 `/api/health`에 200을 반환해야 v2가 시작한다.
v2에는 health API를 추가하지 않고 기존 보호 경로 `/api/users/me`의 401 응답으로 기동을 확인한다.
이 순서 보장은 시작 시점에 적용된다. v3가 나중에 중단돼도 이미 실행 중인 v2를 자동으로 중단하지는 않는다.

```powershell
curl.exe -i http://localhost:8081/api/health
curl.exe -i http://localhost:3000/api/users/me
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SHOW shared_buffers"'
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, type, success FROM flyway_schema_history"'
docker inspect (docker compose ps -q) --format '{{.Name}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}'
```

기대 결과는 v3의 `200 {"status":"ok"}`, v2의 401, `shared_buffers`의 `1GB`, Flyway 이력의 `1 / SQL / t`다.
Docker inspect의 CPU는 앱·DB `2000000000`(2 CPU), Mailpit `500000000`(0.5 CPU)다. 메모리는 앱 각각 `1610612736`, DB `4294967296`, Mailpit `268435456` 바이트다.
v3는 같은 비밀 값으로 JWT를 검증한다. 실행 JAR에는 회원가입·로그인이 포함된다. 보호 경로의 인증 통과는 위 통합 테스트로 확인한다.
외부 API 인증 정보는 주입하지 않으며 이 확인 과정에서는 카카오·FCM·이메일 API를 호출하지 않는다.

DB와 v2 업로드 파일은 각각 named volume에 저장한다. 다음 명령으로 종료해도 데이터는 남는다.

```powershell
docker compose down
docker compose up --detach --wait --wait-timeout 180
```

전체 로컬 데이터를 버리고 새 DB로 시작할 때만 `docker compose down --volumes`를 사용한다.
기존 볼륨에서는 `.env`의 DB 비밀번호를 바꾸는 것만으로 DB 계정 비밀번호가 변경되지는 않는다.
Flyway 이력이 없는 기존 DB를 가져오는 경우에는 앞의 스키마 비교·최초 baseline 절차를 먼저 수행한다.

## v3만 호스트에서 따로 실행 (선택)

아래는 Compose를 내린 뒤 v3를 호스트에서 따로 실행할 때 사용하는 선택 절차다. 기존 DB에 영향을 주지 않는 임시 PostGIS 컨테이너로 확인한다.
아래 명령은 `apps/server-v3`에서 실행한다. 55432 포트가 비어 있어야 한다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:DB_URL = 'jdbc:postgresql://localhost:55432/loco'
$env:DB_USERNAME = 'loco'
$env:DB_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $env:DB_PASSWORD
$env:JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')

docker run --detach --rm --name loco-v3-step1-db `
  --publish 127.0.0.1:55432:5432 `
  --tmpfs /var/lib/postgresql/data `
  --tmpfs /docker-entrypoint-initdb.d `
  --env POSTGRES_DB=loco --env POSTGRES_USER=loco --env POSTGRES_PASSWORD `
  postgis/postgis:17-3.6

docker exec loco-v3-step1-db pg_isready -h 127.0.0.1 -U loco -d loco
```

`/docker-entrypoint-initdb.d`를 빈 임시 디렉터리로 덮어 이미지의 공간 확장 설치 스크립트를 실행하지 않는다.
PostgreSQL 자체의 DB·사용자 초기화는 그대로 수행한다. 이후 스키마 변경은 Flyway가 담당한다.
CPU·메모리 제한과 `shared_buffers` 설정은 Docker Compose에서 적용하며 이 임시 컨테이너에는 적용하지 않는다.

`accepting connections`가 나오면 같은 터미널에서 서버를 시작한다. 초기화 중이면 `pg_isready`를 다시 실행한다.

```powershell
.\gradlew.bat bootRun --console=plain
```

다른 터미널에서 요청한다.

```powershell
curl.exe -i http://localhost:8081/api/health
```

기대 결과는 `HTTP/1.1 200`과 `{"status":"ok"}`다.
JAR 실행을 확인하려면 `bootRun`을 종료한 뒤 동일한 환경변수로 아래 명령을 실행한다.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar build/libs/loco-server-v3-0.0.1-SNAPSHOT.jar
```

확인이 끝나면 서버 터미널에서 Ctrl+C를 누르고 임시 DB를 종료한다.
이 컨테이너의 데이터는 임시 메모리 영역에 있으므로 종료 시 사라진다.

```powershell
docker stop loco-v3-step1-db
Remove-Item Env:DB_PASSWORD, Env:POSTGRES_PASSWORD, Env:JWT_SECRET
```

기존 개발 DB를 사용할 때는 `.env.example`을 `.env`로 복사하고 값을 채울 수도 있다.
`DB_URL`은 `jdbc:postgresql://호스트:포트/DB이름` 형식이며 v2의 Prisma URL과 다르다.
Spring은 실행 디렉터리의 `.env`를 Java properties 형식으로 읽으므로 값에 따옴표를 붙이지 않는다.
환경변수가 `.env`보다 우선한다. `JWT_SECRET`도 필수이며 v2 토큰을 검증하려면 v2와 같은 값을 사용한다.

## 인증과 오류 호환의 범위

선택한 방식은 **(c) 토큰 검증 필터 + 컨트롤러·메서드 권한 선언**이다.
URL 보호 목록을 별도로 관리하는 (a)는 경로가 중복되고, 모든 요청에 인증을 요구하는 (b)는 없는 경로를 401로 바꾼다.
(c)는 선언 누락 위험이 있어 앱 시작 시 각 컨트롤러가 `@ApiController`와 공개/보호 선언을 갖췄는지 검사한다.
`@PublicApi` 또는 `@AuthenticatedApi`를 클래스 기본값으로 두거나 메서드마다 지정한다. 메서드 선언이 클래스 기본값보다 우선한다.
보호 경로에 세부 권한이 필요하면 `@AuthenticatedApi` 선언을 유지한 채 메서드에 `@PreAuthorize` 조건을 추가한다.
공개 클래스의 메서드를 보호로 바꿀 때도 `@AuthenticatedApi`를 명시해야 DTO 검증 전 인증 확인이 적용된다.

`@PreAuthorize`만으로는 Spring MVC의 DTO 검증보다 먼저 인증할 수 없다.
따라서 선택된 `HandlerMethod`의 동일한 선언을 인터셉터가 먼저 확인한다. URL 문자열 목록을 별도로 매칭하지 않는다.
실제 메서드 호출 시에는 Spring Security가 권한을 다시 검사한다. 프록시 내부의 자기 호출은 메서드 보안을 우회할 수 있으므로 보호 서비스는 다른 Spring 빈을 통해 호출한다.

`/api`는 context path에서 **MVC path prefix**로 바꿨다. 컨트롤러는 `/health`처럼 내부 경로만 선언한다.
앱 컨트롤러에만 prefix가 적용되고 `/api` 밖의 없는 경로도 Spring에서 JSON 404를 처리한다.
LOCO 앱 API는 기존 stateless·CSRF off 체인을 유지한다. `/admin/**`는 CMS 전용 세션·CSRF 체인으로 분리했으며 앱 JWT와 관리자 세션을 서로 인증 수단으로 인정하지 않는다. 관리자 초기 생성·쿠키 설정·검증 명령은 [CMS 설계 12~13절](CMS_CORE_DESIGN.md#12-2단계-결정-변경-기록-2026-11-07)에 있다. 로컬 HTTP만 `CMS_COOKIE_SECURE=false`, 운영 HTTPS는 기본 Secure=true를 사용한다.

JWT는 비밀 문자열을 Base64 디코딩하지 않고 UTF-8 바이트로 사용한다. 이는 v2의 문자열 secret과 같다.
HS256만 허용하고 만료(`exp`)·사용 시작(`nbf`) 허용 오차는 0초다. v2처럼 `sub`가 있어야 하며 `iss`·`aud`를 새로 요구하지 않는다.
필터는 발급·갱신이나 사용자 DB 조회를 하지 않는다. 로그인·회원가입의 발급은 TokenIssuer가 담당한다. 서명 검증은 Spring Security JOSE의 NimbusJwtDecoder에 맡긴다.

실제 v2 응답을 확인하고 비교한 사례:

| 상황 | 상태 | 응답 |
| --- | --- | --- |
| 보호 GET, 토큰 없음/만료/다른 서명/잘못된 형식 | 401 | `{"message":"Unauthorized","statusCode":401}` |
| 없는 GET (잘못된 토큰 포함) | 404 | `{"message":"Cannot GET /api/__missing","error":"Not Found","statusCode":404}` |
| 등록되지 않은 메서드 `POST /api/users/me` | 404 | `{"message":"Cannot POST /api/users/me","error":"Not Found","statusCode":404}` |
| 공개 로그인 DTO: email=`bad`, password=`x` | 400 | `{"message":["email must be an email","password must be longer than or equal to 6 characters"],"error":"Bad Request","statusCode":400}` |
| 보호 수정 DTO: nickname 31자, 유효한 토큰 | 400 | `{"message":["nickname must be shorter than or equal to 30 characters"],"error":"Bad Request","statusCode":400}` |
| 같은 잘못된 수정 DTO, 토큰 없음 | 401 | 위 Unauthorized 응답과 동일 |

404 메시지는 원래 URL의 쿼리 문자열도 보존한다. JSON 객체의 키 순서는 비교하지 않지만 키의 유무·값과 배열 순서는 비교한다.
검증 오류의 문구는 DTO 제약의 `message`에 v2 문구를 지정하고, 배열은 DTO 속성 순서로 정렬한다.
Nest의 모든 `class-validator` 규칙을 자동 변환하는 기능은 아니다. 기능 DTO를 이관할 때 누락 값·타입 변환·알 수 없는 속성·중첩 검증·한 속성의 여러 오류 순서를 실제 v2와 함께 검증해야 한다.
현재 테스트 DTO는 위 사례만 재현하며 운영 JAR에 포함되지 않는다.

## 결정 기록

| 결정 | 근거 / 결정 주체 |
| --- | --- |
| Java 21, Boot 3.5, JPA, Flyway, UUID 문자열 유지 | 사용자가 지정했다. String ID에는 Hibernate @UuidGenerator를 사용한다. |
| 현재 폴더에 새 clone, 별도 작업 브랜치 | 사용자가 선택했다. 기존 체크아웃의 변경과 분리한다. |
| Gradle Groovy DSL | 사용자가 선택했다. 애플리케이션 언어는 Java다. |
| Boot 3.5.16, Gradle Wrapper 8.14.6 | 구현 선택을 위임받아 3.5·8.x의 안정 패치 버전으로 고정했다. |
| `com.loco` 아래 기능·설정별 패키지 | `health`, `config`, `security`, `web`으로 책임을 구분한다. |
| 8081, MVC prefix `/api`, YAML | 포트는 v2·앱 개발용과 분리한다. 4단계에서 사용자가 MVC prefix로 변경하기로 선택했다. |
| 0번 묶음은 health와 기반만 구현 | 1번 묶음부터 실제 인증 API를 추가하고 테스트 대역은 전용 경로에 둔다. |
| (c) JWT 검증 필터 + 메서드 권한, DTO 검증 전 인증 확인 보완 | 사용자가 선택했다. 없는 경로 404와 보호 경로 401을 함께 유지하며 선언 누락은 시작 시 검출한다. |
| Spring Security JOSE / NimbusJwtDecoder | 사용자가 JJWT와 비교 후 선택했다. Boot BOM이 버전을 관리하며 HS256 검증에 사용한다. |
| 앱 API CSRF 비활성화, stateless | 사용자 요구다. 세션·로그인 폼·HTTP Basic 인증은 사용하지 않는다. |
| PostgreSQL 17, PostGIS 17-3.6 이미지 + 공간 확장 미활성화 | 사용자가 확정했다. 맞춰야 할 기존 운영 DB 버전은 없다. 공간 확장 도입은 전체 로드맵 2단계로 미룬다. |
| v2·v3 각각 CPU 2 / 메모리 1.5GB | 사용자가 v2도 v3와 같은 제한으로 선택했다. Compose에 적용한다. |
| DB CPU 2 / 메모리 4GB / shared_buffers 1GB | Compose에 적용한다. JUnit 테스트에는 자원 제한 설정·검증을 넣지 않는다. |
| v3 Flyway만 스키마 관리, DB → v3 → v2 순서 | 사용자가 선택했다. v2와 v3가 같은 DB를 쓰되 스키마 변경 주체는 하나로 유지한다. |
| Hibernate DDL 비활성화, Flyway V1으로 스키마 생성 | Prisma 생성 SQL을 그대로 사용하며 인증용 JPA 엔티티는 기존 스키마에 매핑한다. 자세한 설계와 읽는 순서는 [인증 문서](AUTHENTICATION.md)를 참고한다. |
| baselineOnMigrate 기본 false, 기존 DB 최초 실행에만 true | 사용자가 선택했다. 스키마 비교 후에만 기존 DB를 V1 상태로 인정한다. 별도 Flyway CLI를 추가하지 않는다. |
| JUnit에서 고정된 Prisma CLI로 스키마 비교 | Node/npm이 필요하며 `gradlew comparisonTest`로 DB 동등성까지 재검증한다. |
| Actuator HTTP 노출 없음 | 상태 확인은 `/api/health`를 사용한다. 인증 API는 별도 공개 경로다. |
| JUnit·Testcontainers, v2 빌드·최초 기동 1회 | 핸들러별 잔여량이 부족할 때만 케이스 시작에 같은 컨테이너를 재시작한다. 단일 JVM에서 순차 실행한다. |
| 하네스 DB 2개, 양쪽 Flyway V1 적용 | 사용자가 선택했다. 케이스마다 같은 시드를 주입하며 개발 Compose DB는 사용하지 않는다. |
| HTTP 경로별 값 제외, DB UUID 별칭·참조 비교 | 사용자가 선택했다. 키·타입·null·배열 구조는 유지하고 모호한 행 대응은 실패한다. |
| C 방식 + 시나리오 내부 요청량 관리, 준비 JWT 직접 서명 | 사용자가 C 전환을 승인했다. 연속 요청 중간에는 재시작하지 않는다. |
| Mailpit 1개, 429 필수 통과 | 실제 SMTP 성공·실패와 DB 상태를 비교하며 요청 제한 차이를 해소했다. |

## 공부할 개념

- Spring Boot 자동 설정과 컴포넌트 검색: `main()` 하나로 서버가 시작되는 이유.
- Gradle Wrapper, Java toolchain, BOM: 빌드 도구·JDK·라이브러리 버전을 각각 누가 정하는지.
- MVC path prefix와 context path의 차이, 필터 → 인터셉터 → DTO 검증 → 메서드 보안의 순서.
- JWT HS256, 서명과 암호화의 차이, `sub`·`email`·`iat`·`exp`·`nbf`, clock skew.
- SecurityContext와 stateless, CSRF를 끄는 이유, 인증 실패(401)와 권한 거절(403)의 차이.
- `@PreAuthorize`, Spring AOP 프록시와 자기 호출, 선언 누락을 시작 시 검출하는 방법.
- `@RestControllerAdvice`, Validation 오류 배열과 DTO별 호환성.
- 환경변수와 설정 파일 우선순위, JDBC URL: 비밀 값을 코드 밖에서 주입하는 방법.
- JPA의 `ddl-auto`, `open-in-view`: 스키마 변경과 DB 세션 수명을 제어하는 설정.
- JUnit 5, Testcontainers, 실제 HTTP 통합 테스트: 격리된 DB로 외부 동작을 검증하는 방법.
- 이미지 초기화 스크립트와 PostgreSQL 확장.
- Docker 다단계 빌드, Compose healthcheck·depends_on, named volume, CPU·메모리 제한과 shared_buffers.
- Flyway 버전 SQL과 baseline의 차이, 이력 테이블·체크섬·재실행 방지.
- Prisma 스키마와 실제 DB의 차이 비교, 클라이언트에서 처리하는 UUID·updatedAt.

## 다음 단계

- 조기 파서 오류(400/413/415)는 v2의 파서→Helmet→CORS 순서 때문에 CORS 헤더가 없다. Flutter 웹의 다른 출처 요청에서는 JavaScript가 실제 상태/본문을 읽지 못하고 CORS 오류로 보인다. 이번에는 유지하며, 파서 오류에도 CORS를 적용할지는 이관 후 개선 후보로 남긴다.

Prisma 기반 비교 테스트는 `v2-comparison` 태그로 분리했으며 `comparisonTest`·`allTests`·`check`에서 계속 필수로 검증한다.
요청 제한의 IP 기준(`getRemoteAddr`)은 3단계 nginx 도입 시 `X-Forwarded-For`와 신뢰 프록시 처리가 필요하다.
`RequestRateLimiter`의 단일 `synchronized` 잠금은 2단계 부하 테스트 측정 대상이다. 현재 구현은 유지한다.
업로드 1건당 libvips 프로세스 3회 실행(메타데이터·디코딩·인코딩)과 PNG 중간 변환은 부하 테스트 측정 대상이다. 정상 업로드 경로 기준이며 실패 경로의 실행 횟수는 다를 수 있다.

v2 비교 기준에는 main에 병합된 계정 상태 회귀 수정이 포함된다.
현재 테스트는 인증·사용자·지도방·장소 기본·리뷰·장소 이미지·마커 조회 API와 회원 상태·등록된 오류 사례를 검증한다. 이후 묶음은 별도로 이관한다.

보호 경로에서 탈퇴·없는 회원의 JWT를 차단한다.
v2 Guard처럼 **인증이 필요한 경로에서만** DB의 현재 회원 상태를 조회하고, 공개 경로에서는 조회하지 않는다.
JWT 필터에 모든 요청의 회원 조회를 추가하지 않는다. 새 v2는 존재하지 않는 회원도 거절하고 인증 정보의 email도 DB의 현재 값을 사용하므로 해당 동작을 반영했다.

현재 호환 범위와 후속 검증 항목은 다음과 같다.

- 이관한 인증 DTO는 v2 `forbidNonWhitelisted`를 적용한다. 정의되지 않은 필드는 400이며 `message` 배열에 `"property xxx should not exist"`가 포함된다.
- 예상하지 못한 오류의 500 응답은 v2와 같이 `{"statusCode":500,"message":"Internal server error"}`이며 `error` 키가 없다.
- 깨진 JSON 요청의 400 메시지는 케이스별 명시적 문구 예외로 비교한다. 상태·키·타입은 유지하며 실제 API의 구조 차이를 숨기지 않는다.

0번 묶음과 1번 묶음 설계는 확인 완료했다. 실제 이관 API 케이스를 묶음별로 추가한다.
2번 묶음 사용자 API는 [확정된 설계](USER_API_DESIGN.md)를 따른다. GET/PUT me, FCM, 탈퇴(롤백), 이미지 업로드를 구현했다. 변환기는 A로 결정했으나 의사소통 오류로 B로 구현, 결과 검토 후 유지한다. 현재 B는 libvips CLI 8.15.3이다.
3번 묶음 지도방 API의 [설계·SQL 기준](MAP_API_DESIGN.md)에 따라 14개를 이관했다. API-12 N+1 개선은 사용자 확인을 마쳤으며, API-11 초대 코드 재생성과 API-14 커버 업로드도 응답·DB·SQL·파일 변경을 비교한다. 커버와 프로필은 LocalImageStorage를 공유하고 목적별 폴더·크기만 지정한다.
4번 묶음 장소 기본 API는 [설계·v2 관측·결정](PLACE_API_DESIGN.md)에 따라 6개를 이관했다. 장소 쓰기에 따른 마커 셀 갱신과 삭제 시 이미지 정리를 포함한다.
5번 묶음 리뷰 API는 [설계·v2 관측·검증](REVIEW_API_DESIGN.md)에 따라 4개를 이관했다. 사용자별 리뷰 UPSERT와 본인 삭제, 장소 평점/개수 반영, 실제 동시 작성·커밋 경계를 비교한다. 새 라이브러리·스키마·결정은 추가하지 않았다.
6번 묶음 장소 이미지 API는 [확정 설계와 v2 경쟁 관측](IMAGE_API_DESIGN.md)에 따라 조건부 원자적 UPDATE·락 밖 변환으로 POST/DELETE를 구현했다. 정합성·SQL 수까지 이번 범위이며 비관적 락 대비 성능 비교는 2단계 실험 백로그다.

7번 묶음 마커 조회 API는 [설계·관측·SQL/EXPLAIN 기준](MARKER_API_DESIGN.md)에 따라 3개를 이관했다. places/집계 셀 전환과 기존 누락 좌표·저장 count를 유지한다. 2단계 PostGIS 실험용 계획을 기록했으며 성능 개선은 하지 않았다.

8-A 즐겨찾기·피드백·신고는 [설계·v2 관측·검증](FAVORITES_FEEDBACK_REPORTS_DESIGN.md)에 따라 이관했다. 비공개방 즐겨찾기 조회의 권한 누락, 동시 즐겨찾기 삭제 500, 신고 대상·중복 미검증은 v2 그대로이며 문서에 개선 후보로 남겼다. 기존 가짜 피드백 쓰기 비교는 실제 API로 전환했다.

8-B 카카오 장소 검색의 [확정 결정·설정·검증](KAKAO_SEARCH_DESIGN.md)을 기록했다. 카카오 전용 Apache HttpClient 5(Boot BOM)는 자동 재시도 없이 연결 2초·응답 소켓 무활동 5초를 적용한다. 실패는 고정 500 `카카오 검색 API 호출 실패`이며 오류 원문·키·Authorization을 기록하지 않는다. 정상 외부 응답의 느슨한 매핑은 v2대로 유지한다. 오류 메시지와 timeout 정책은 보고서의 알려진 차이 2개다.

`KAKAO_SEARCH_BASE_URL`, `KAKAO_CONNECT_TIMEOUT_MS`, `KAKAO_RESPONSE_TIMEOUT_MS`로 설정을 변경한다. 전체 수신 시간 제한은 없다. 외부 본문이 계속 조금씩 오면 서버 호출은 5초를 넘길 수 있으며, Flutter의 첫 응답 대기 약 10초가 서버 호출을 취소하거나 서버의 전체 시간 상한이 되지는 않는다. 전체 deadline·취소 전파는 이관 후 개선 후보로 남긴다.

### 이미지 쓰기 전환 주의사항

P2/E-22 이후 장소·마커 갱신은 지도방 UUID hash+정밀도 transaction advisory lock을 **별도 문장으로 먼저 획득**한 뒤 READ COMMITTED 집계를 실행한다(6SQL·3커밋). 모든 writer가 이 규약을 따라야 한다. v2·구버전 v3 writer나 직접 SQL을 같은 DB에 섞으면 교착 방지·최종 집계 정합성 보장이 깨진다. 기존 writer 중지 → 새 앱 V9 파생 셀 재생성 → 셀 검증 → 새 v3만 쓰기 재개 순서다. V9는 원본 places를 변경하지 않으며 실패 시 롤백된다. 해시 충돌은 관계없는 지도방의 과직렬화만 유발한다. 자세한 범위·실패 시 부분 커밋은 [E-22 계약](PERF_STAGE2_P2_DESIGN.md#12-e-22-b-변형-확정--별도-잠금-문장과-최신-집계)을 따른다.

P1 이후 장소·마커 쓰기도 한쪽 서버로 전환해야 한다. V7은 PG numeric ROUND·0 정규화 규칙으로 파생 셀을 재생성한다. v2/이전 v3 쓰기 중지 → 새 앱 Flyway V7 → 셀 검증 → 새 v3 쓰기 재개 순서다. V7은 테이블 쓰기 락을 잡으며 실패 시 재생성이 롤백된다. v2 writer를 다시 섞으면 옛 좌표 규칙이 돌아온다. [P1 계약과 검증](PERF_STAGE2_DESIGN.md#p1-좌표전환-계약)을 따른다. 이관 완료 당시11건에 E-5 좌표 규칙·E-12 overflow 동률 정렬 개선2건을 별도로 추가한다.

P1 정합성은 검증됐지만 성능 채택은 보류다.1% 조회 p95·p99 회귀10% 기준 미달,group 역조회 Seq Scan,기본 힙의 전체 viewport OOM을 [P1 실측 결과](../../perf/stage2/P1_RESULTS.md)에 기록했다.16req/s A5를 최대 지속 처리량으로 해석하지 않는다.

v2·v3가 같은 DB에 장소 이미지 쓰기를 섞으면 v3의 원자적 UPDATE만으로 전체 정합성을 보장할 수 없다. v2가 앞서 읽은 배열 전체를 뒤늦게 저장하면 v3 결과를 덮어쓸 수 있다. 전환 시 POST/DELETE 장소 이미지 쓰기는 담당 서버 하나로 보내고, 개발 공유 Compose에서도 같은 장소에 양쪽 쓰기를 섞지 않는다. 비교 테스트는 v2/v3 독립 DB를 사용한다. Flutter의 순차 업로드·중복 삭제 방지는 한 클라이언트의 동작일 뿐 서버 정합성의 근거로 삼지 않는다.
이후 선택지 답변에 **번호와 항목 이름이 함께 없으면**, 어느 결정에 대한 답인지 먼저 확인한다. 다른 주제의 답변을 연결하거나 문자 하나만으로 선택을 확정하지 않는다.
테스트 대역은 `/api/__test/harness/...`에만 둔다. 실제 API 이관 시 별도 `v3Path`를 제거하는 규칙과 이름 기반 빌더·연속 요청 예시는 [하네스 문서](COMPARISON_TESTS.md)를 따른다.
429는 기존 기반에서 필수 통과 케이스로 검증한다. 지도방 14개 API의 SQL 수는 인증 조회를 포함해 테스트로 고정한다. 지도방·장소 알림 전송은 9번 묶음에서 함께 연결했으며 기존 보류 차이는 필수 메시지 비교 검사로 전환했다.
작업 기록은 노션에서 관리한다.

공식 참고: [Spring Boot 3.5 실행 환경](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[DB 초기화](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html),
[Testcontainers 연동](https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html),
[Flyway baselineOnMigrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting),
[Prisma migrate diff](https://docs.prisma.io/docs/cli/migrate/diff).

Compose 참고: [기동 순서와 healthcheck](https://docs.docker.com/compose/how-tos/startup-order/),
[서비스 자원 제한](https://docs.docker.com/reference/compose-file/services/).

인증 참고: [Spring Method Security](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/method-security.html),
[JWT 검증](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html),
[MVC HandlerInterceptor](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/servlet/HandlerInterceptor.html).

GET/PUT me는 실제 경로로 비교하며 사용자용 가짜 컨트롤러를 제거했다. src/test/resources/users/profile-v2.json은 19가지 입력의 실제 v2 응답·DB 갱신 관측이다. 빈 객체는 갱신하지 않고 동일 값 명시는 updatedAt을 갱신한다. nickname:null·빈 배열은 v2처럼 500이다. DB 오류 시 전체 상태 롤백도 검증한다.

FCM은 실제 v2 관측 17종을 `src/test/resources/users/fcm-v2.json`에 고정했다. 누락/null은 해제, 같은 값도 갱신한다. 숫자·불리언·배열·빈 객체 값은 500이고 `{set: 문자열 또는 null}`은 Prisma와 같은 결과를 낸다. 미정의 필드는 무시한다.

DELETE me는 회원 탈퇴 표시·FCM 해제·리프레시 토큰 폐기·재설정 토큰 사용 처리를 한 트랜잭션으로 수행한다. 만료된 미처리 토큰도 처리하며, 이미 처리된 토큰과 다른 회원은 보존한다. 두 토큰 테이블에 각각 오류를 주입해 전체 DB 롤백을 비교 검증한다.

## 사용자 API와 이미지 저장

`UserController`는 보호된 5개 사용자 경로를 연결하고, `UserInputs`는 v2의 입력 차이를 처리하며, `UserResponses`는 API별 공개 필드만 반환한다.
`UserService`는 JPA 갱신과 탈퇴 트랜잭션을 담당한다. 시간은 기존 Clock·Auditing을 사용한다.

이미지 흐름은 `ProfileImageStorage`(검증·중앙 크롭·파일 저장), `VipsImageConverter`(네이티브 읽기·WebP 쓰기),
`ProfileImageService`(TransactionTemplate 커밋과 실패 보상 삭제), `UploadWebConfig`(공개 정적 제공) 순서로 읽으면 된다.
`/uploads/...` 상대 URL을 유지하며 API의 `/api` prefix와 별개다. 정적 다운로드는 회원 DB를 조회하지 않는다.

- 파일 필드 `file`, **10MiB 미만** 허용. 정확히 10MiB부터 v2처럼 413이다.
- `resolve-lazily`는 인증을 multipart 파싱보다 먼저 처리한다. `max-file-size`는 10MB, 전체 요청 크기는 별도로 제한하지 않는다.
- `max-swallow-size=-1`은 큰 파일 오류 응답을 전달하기 전에 연결이 끊기는 것을 방지한다.
- `UPLOAD_DIRECTORY`는 기본 `uploads`이며 빈 값도 같은 기본값으로 처리한다. `VIPS_EXECUTABLE`은 libvips 경로다.
- Windows 테스트와 `bootRun`은 `prepareVips`로 고정 바이너리를 준비한다. 수동 JAR 실행은 PATH 또는 `VIPS_EXECUTABLE`을 설정한다.
- 개발 Compose는 기존 `v2-uploads`를 두 앱에 연결하고 UID/GID를 1000:1000으로 통일한다. 기존 볼륨은 삭제하지 않는다.
- HEIC는 v2와 같은 500·기존 DB/파일 보존·새 0바이트 파일 잔여를 검증한다. AVIF와 애니메이션 WebP는 201로 일치한다.

관측 fixture는 `src/test/resources/users/`에 있다. `-PusersObserve=true`는 v2 관측 파일을 `build/reports/users/`에 출력하는 명시적 재관측 모드다.
일반 비교는 커밋된 fixture를 검증하며 자동으로 기대값을 교체하지 않는다.
양 플랫폼 재검증과 공유 볼륨 검증 명령은 [코덱 문서](CODEC_COMPATIBILITY.md)에 있다.
이번 묶음에서 공부할 핵심은 JPA 변경 감지·Auditing, nullable 입력과 필드 누락, 트랜잭션 롤백, 파일 보상 삭제,
TransactionTemplate의 커밋 경계, multipart 지연 파싱, 네이티브 프로세스 실행, Docker 볼륨의 UID/GID다.

테스트 시간 계측과 재시작 조건은 [하네스 문서의 실행 시간 계측](COMPARISON_TESTS.md#실행-시간-계측)을 따른다. 전체 비교 범위·필수 429·동시 요청 검증은 유지한다.
전체 회귀 시간은 [실측 전후 결과와 추가 단축 선택지](TEST_PERFORMANCE.md)를 참고한다. 케이스를 유지한 채 재시작 321→6회, 동일 계측 전체 1261.87→467.69초로 줄였다.

## 장소 기본 API (4번 묶음)

설계·결정·v2 관측 근거와 후속 개선 후보는 [장소 API 문서](PLACE_API_DESIGN.md)에 모은다.
`PlaceController` → `PlaceInputs`/`PlaceListing` → `PlaceQueryRepository` 또는 `PlaceMutationService` 순서로 읽는다.
`Place`는 지도방·작성자를 LAZY로 참조하며 cascade를 사용하지 않는다. 목록/리뷰 집계는 native projection이고, UUID 문자열·배열·timestamp(3) 매핑은 V1을 유지한다.

`MarkerCells`는 장소 쓰기와 별개로 SELECT 후 각 셀을 커밋한다. 키 생성(`CoordinateKeys`)과 PostgreSQL ROUND가 다른 기존 제약도 보존한다. `PrismaFloats`는 Prisma 5의 Float 입출력 변환을 맞추며 Node는 테스트 oracle에서만 사용한다.
입력/응답 fixture와 DB·부분 실패·알림 계약은 `src/test/java/com/loco/places`에 있다. `PlaceWriteSupport`는 UUID/시각 형식 검사와 비-ID 기준의 DB 행 대응을 공유한다.

## CMS 역할·메뉴 기반

관리자 보안 관리는 SYSTEM_ADMIN만, 업무 권한은 READ/MANAGE 합집합이다. 코드 템플릿을 복사해 역할을 만들며 기존 역할로 자동 전파하지 않는다. 메뉴 이동은 UUID를 유지하고 트리 메타 행 잠금+revision으로 동시 순환을 막는다. API·실제 DB 경합 수치·검증 명령은 [CMS 설계 16절](CMS_CORE_DESIGN.md#16-3단계-확정구현-기록)을 참고한다. CLI는 기존 스키마 검증만 하며 V4 적용은 배포 앱 기동 시 수행한다.

## CMS 파일 백엔드 (5-A)

로컬 저장소는 SeaweedFS S3 게이트웨이이며 SDK·이미지 변환은 호스트에만 있다. core는 저장/변환 포트와 JPA 상태·이력을 관리한다. [C-15~23 결정과 검증](CMS_CORE_DESIGN.md#20-c-23-c-확정--파일-선등록조건부-상태-전이)을 참고한다. 관리자 SPA의 개발·운영·별도 E2E 명령은 [apps/admin README](../admin/README.md), C-24~32와 화면 계약은 [ADMIN_UI_DESIGN.md](ADMIN_UI_DESIGN.md)에 있다.

저장소를 포함해 실행하려면 저장소 루트 `.env`에 기존 DB/JWT 설정과 **로컬용으로 새로 만든** `CMS_STORAGE_ACCESS_KEY`, `CMS_STORAGE_SECRET_KEY`를 지정한다. 실제 AWS 키를 사용하지 않는다. 예제 파일은 키 이름만 제공한다. 루트에서 `docker compose up -d --build --wait`를 실행하면 storage health 이후 v3가 V5를 적용하고 비공개 버킷을 준비한다. storage는 digest 고정·CPU1/1GiB, 전용 `cms-objects` 볼륨을 사용한다. 기존 DB·LOCO 업로드 볼륨은 유지한다. `docker compose down -v`로 기존 데이터를 지우지 않는다.

Compose는 endpoint `http://storage:8333`, region `us-east-1`, bucket `cms-files`를 기본 사용한다. 호스트에서 직접 앱을 실행할 때만 `CMS_STORAGE_ENABLED=true`, `CMS_STORAGE_ENDPOINT=http://localhost:8333`, region/bucket/접속 키를 설정한다. 초기 로컬 버킷 준비는 `CMS_STORAGE_CREATE_BUCKET=true`로 켠다. 이 플래그는 관리자 bootstrap CLI를 통한 DB migration과 무관하다. CLI는 여전히 스키마 검증만 한다. 운영 버킷은 별도 준비하고 기본 create=false를 유지한다.

파일 API prefix는 `/admin/api/core/files`다. GET 목록(`page=0&size=50`), GET `/{id}` 상세, GET `/{id}/content` 스트리밍 다운로드, POST multipart `file` 한 개, DELETE `/{id}`를 제공한다. 관리자 세션과 변경 요청 CSRF가 필요하다. `cms.files` READ는 모든 ACTIVE 파일 조회·다운로드, MANAGE는 업로드·삭제도 허용하며 SYSTEM_ADMIN은 모두 허용한다. 업로더 소유 제한은 없다. 다운로드는 attachment·no-store·nosniff이며 객체 URL이나 서명 URL은 노출하지 않는다.

업로드는 UPLOADING 선커밋→변환/PUT→조건부 ACTIVE+이력 커밋이다. 실패·오래된 업로드는 FAILED/ABANDONED, 삭제는 DELETE_PENDING을 거쳐 정리한다. 대조는10분마다 수행하며 생성10분 미만은 보존한다. ACTIVE 객체는 대조로 삭제하지 않는다. 응답 유실 후 재업로드는 중복 파일을 만들 수 있다(C-19 B). 파일은 각10MiB 이하, 이미지 JPEG/PNG/WebP/AVIF는 WebP만 저장하고 PDF/TXT/CSV는 원본을 저장한다. 일반 파일 검사는 악성코드 검사나 PDF의 안전성 보증이 아니다.

집중 재검증은 `./gradlew :cms-core:test :test --tests '*CmsFilesIntegrationTest' --tests '*StorageTransportTest' --tests '*CmsImageTransformerTest'`로 실행한다. 전체 범위는 `./gradlew allTests build`다(Windows는 `gradlew.bat`). 실제 AWS 접속 없이 로컬 SeaweedFS와 루프백 장애 대역으로 확인한다. 대조 비용 보고서는 `build/reports/cms-storage/reconciliation.txt`, 전체 결과는 Gradle HTML 보고서에서 확인한다.

5-A 당시 최종 전체 검증: **1,563건 중 성공1,562·실패0·HEIC 공통 제약 건너뜀1**, 알려진 차이11건 불변, **1,145.692초**(v2 이미지 캐시). core99·root312·비교1,152건, allTests/build/Spotless 통과. DB gate의 두 순서 모두 ACTIVE 객체 누락0이며 Linux Compose 이미지5종 흐름도 통과했다. 초기 SeaweedFS 준비 시점 문제와 수정 근거는 설계20절에 기록했다. 이후5-B 화면 결과는 ADMIN_UI_DESIGN, 6단계 결과는 아래 LOCO_ADMIN_DESIGN을 따른다.

## CMS LOCO 조회 (6단계)

회원·지도방·장소·신고의 관리자 목록/상세를 `/admin/api/loco/{users|maps|places|reports}` 및 `/{id}`로 제공한다. 관리자 세션과 해당 `loco.* READ`가 필요하며 SYSTEM_ADMIN은 모두 허용한다. 지도방·장소 READ는 **비공개 포함**, 역할 설명에 범위를 표시한다. 앱 JWT는 관리자 API 인증 수단이 아니다.

화면은 `/admin/loco/users`, `/admin/loco/maps`, `/admin/loco/places`, `/admin/loco/reports`다. [화면 실행 방법](../admin/README.md), [승인된 결정·구현·검증](LOCO_ADMIN_DESIGN.md), [1만/10만 건 실제 SQL·EXPLAIN](src/test/resources/cms-read/explain-baseline.json)을 참고한다. 이메일 서버 마스킹, size50/max100 cursor, 업무별 검색을 지원하며 토큰·초대 코드·이미지 URL을 제공하지 않는다. 대상 업무 READ가 없는 신고 조회에서는 대상 존재 여부도 숨긴다.

전용 JPA projection은 기존 public 스키마를 읽고 앱 서비스·마커 재집계를 호출하지 않는다. SYSTEM_ADMIN 기준 목록/상세 업무 SQL1·전체 SQL3을 고정했다. 최신순 목록은 현재 인덱스에서 scan/sort가 남으며 인덱스 추가는 후속 실험이다. public 데이터·변경 이력 불변, core의 LOCO 역참조 없음, CMS/앱 HTTP 선언 혼용 금지를 검사한다.

C-33/C-34의 6단계 당시 이연 기록과 설계2절은 보존한다. 후속 신고 처리에서 flat typed 호스트 이력 계약을 구현하고 업무 이력 열람을 분리했다(아래 참고). 리뷰·피드백 조회·회원 정지·CMS 파일 업무 연결은 이연 상태다. 최종 회귀는 `gradlew.bat allTests build`, 프런트는 고정 Node 준비 후 `npm.cmd --prefix ../admin run test:unit` 및 별도 `test:e2e`다.

6단계 최종 검증: 서버 총1,600건(성공1,599·실패0·기존 HEIC skip1), 알려진 차이11건(개선6·의도5·보류0) 불변. `allTests build`·Spotless1,001.813초(v2 이미지 캐시, 직전 실제 통과한 core101건 UP-TO-DATE 재사용), Vitest23건0.170초, Chromium E2E7건 준비/종료 포함34.789초. 상세 실행 범위와 근거는 [설계11절](LOCO_ADMIN_DESIGN.md#11-최종-검증완료-2026-11-08)에 있다.

## CMS 신고 처리 (마지막 묶음)

`PUT /admin/api/loco/reports/{id}/processing`은 관리자 세션·CSRF·`loco.reports MANAGE`가 필요하다. `expectedStatus`, `nextStatus`, `note`만 받으며 처리자와 시각은 서버가 정한다. 접수→검토중→완료/기각 단방향이고 종결 메모는 필수다. 종결 후 메모 편집·재개, 회원 정지·강제 비공개·앱 알림은 없다.

V6는 호스트 소유 `cms.loco_report_processing`만 추가한다. `public.reports`는 그대로이며 행이 없으면 접수로 조회한다. 첫 쓰기는 INSERT…ON CONFLICT 후 현재 상태 조건부 UPDATE, 성공 이력까지 같은 트랜잭션이다. 메모 원문은 현재 처리 행만, 이력은 status/hasNote/handledBy/handledAt만 저장한다. FK 없는 논리 참조로 원본이 삭제되어도 이력을 보존한다. migration은 앱 기동만 담당하고 bootstrap CLI는 계속 스키마 검증만 수행한다.

`GET /admin/api/core/history/resources`는 현재 허용된 리소스만 반환한다. 신고 READ는 `loco.report` 이력을 읽고 core 4종은 SYSTEM_ADMIN만 읽는다. 역할 MANAGE는 명시 배정하며 기존 역할에 자동 전파하지 않는다. 화면은 409 시 초안을 버리고 최신 상태를 다시 읽으며 자동 쓰기 재전송을 하지 않는다.

[승인 C-43~49·검증](REPORT_PROCESSING_DESIGN.md), [신고 SQL/EXPLAIN 기준](src/test/resources/cms-report/read-plans-baseline.json), [PR 본문 초안](CMS_PR_DRAFT.md)을 참고한다. LOCO 기존 앱 API와 v2 비교 fixture는 변경하지 않았다.

최종 서버1,642건: 성공1,641·실패0·기존 HEIC skip1, 알려진 차이11건(개선6·의도5·보류0). `allTests build`/Spotless1,054.991초(v2 이미지 캐시; core125는 직전 통과 후 재사용, root365·비교1,152 재실행). Vitest26건 runner0.206초, Chromium9건 준비·종료 포함38.724초. PR은 초안 파일만 작성했으며 생성·머지는 하지 않았다.
